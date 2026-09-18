package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.economy.benefit.BenefitProvider;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermissionGroup;
import com.ghostchu.quickshop.obj.QUserImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Pins two lifecycle hardenings on ContainerShop:
 *
 * <ul>
 *   <li>{@code isDeleted} used to be a {@code private final boolean = false} constant —
 *       every deleted-flag consumer (isValid, display status, async persistence chains)
 *       was reading a provably-false placeholder. It is now a real flag set by
 *       {@code markDeleted()} on the delete path.</li>
 *   <li>{@code playerGroup} was a plain HashMap mutated from async profile-IO callbacks
 *       while region threads copy/serialize it — it must be a concurrent map now, and a
 *       corrupted DB row (null entries) must be filtered, not make the shop
 *       un-loadable.</li>
 * </ul>
 */
class ContainerShopDeletedFlagTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private World world;

  @BeforeEach
  void setUp() throws java.io.IOException {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    final var pluginManager = mock(org.bukkit.plugin.PluginManager.class);
    bukkitStatic.when(Bukkit::isPrimaryThread).thenReturn(true);
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    HandlerList.unregisterAll();

    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);

    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder())
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-deleted-test").toFile());
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getBoolean(anyString(), anyBoolean())).thenReturn(false);
    lenient().when(config.getInt(anyString(), anyInt())).thenReturn(-1);
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.platform()).thenReturn(mock(com.ghostchu.quickshop.platform.Platform.class));
    final var permissionManager = mock(SimpleShopPermissionManager.class);
    lenient().when(permissionManager.hasGroup(anyString())).thenReturn(true);
    lenient().when(plugin.getShopPermissionManager()).thenReturn(permissionManager);

    world = mock(World.class);
    when(world.getName()).thenReturn("world");
  }

  @AfterEach
  void tearDown() {

    HandlerList.unregisterAll();
    bukkitStatic.close();
    quickShopStatic.close();
  }

  private ContainerShop shop(final Map<UUID, String> groups) {

    final var owner = QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("deleted-owner".getBytes()), "deleted-owner", true);
    final var benefit = mock(BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.getType()).thenReturn(Material.DIRT);
    lenient().when(item.getAmount()).thenReturn(1);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    lenient().when(item.clone()).thenReturn(item);
    return new ContainerShop(
            plugin, -1L, new Location(world, 1, 64, 1), 10.0d, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new YamlConfiguration(), false, null,
            "Bukkit", "sym-deleted", null,
            groups, benefit);
  }

  @Test
  void freshShopIsNotDeleted() {

    assertFalse(shop(new HashMap<>()).isDeleted());
  }

  @Test
  void markDeletedFlipsFlagAndInvalidatesShop() {

    final ContainerShop shop = shop(new HashMap<>());

    shop.markDeleted();

    assertTrue(shop.isDeleted());
    // isValid answers false straight from the flag — before the block check, so a
    // deleted shop is unusable even while its chest block is still standing
    assertFalse(shop.isValid());
    // idempotent
    shop.markDeleted();
    assertTrue(shop.isDeleted());
  }

  @Test
  void playerGroupIsBackedByConcurrentMap() throws ReflectiveOperationException {

    final ContainerShop shop = shop(new HashMap<>());

    // the field itself must be concurrent: async staff commands mutate it while region
    // threads iterate/copy for trades, notifications and save flushes
    final var field = ContainerShop.class.getDeclaredField("playerGroup");
    field.setAccessible(true);
    assertInstanceOf(ConcurrentHashMap.class, field.get(shop));
    field.setAccessible(false);

    // structural proof through the public surface: assignment survives copies
    final UUID staff = UUID.randomUUID();
    shop.setPlayerGroup(staff, BuiltInShopPermissionGroup.STAFF);
    assertDoesNotThrow(()->{
      for(int i = 0; i < 200; i++) {
        final Map<UUID, String> snapshot = shop.getPermissionAudiences();
        if(!snapshot.containsKey(staff)) {
          fail("staff assignment lost in copy: " + snapshot);
        }
      }
    });
  }

  private static void fail(final String message) {

    org.junit.jupiter.api.Assertions.fail(message);
  }

  @Test
  void corruptedNullEntriesAreFilteredNotFatal() {

    final UUID valid = UUID.randomUUID();
    final UUID nullValued = UUID.randomUUID();
    final Map<UUID, String> corrupted = new HashMap<>();
    corrupted.put(valid, BuiltInShopPermissionGroup.STAFF.getNamespacedNode());
    corrupted.put(nullValued, null);

    final ContainerShop shop = assertDoesNotThrow(()->shop(corrupted));

    assertEquals(BuiltInShopPermissionGroup.STAFF.getNamespacedNode(),
                 shop.getPermissionAudiences().get(valid));
    assertFalse(shop.getPermissionAudiences().containsKey(nullValued));
  }
}
