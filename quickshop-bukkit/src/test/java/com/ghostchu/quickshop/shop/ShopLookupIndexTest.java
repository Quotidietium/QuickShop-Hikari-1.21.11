package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the secondary lookup indexes (id to shop, owner to shops):
 * registration indexes, removal un-indexes and ownership transfer moves the shop
 * between the owner buckets.
 */
class ShopLookupIndexTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private SimpleShopManager manager;
  private org.bukkit.World world;

  @BeforeEach
  void setUp() throws java.io.IOException {

    quickShopStatic = mockStatic(QuickShop.class);
    bukkitStatic = mockStatic(Bukkit.class);
    plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);

    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(bukkitPlugin.getResource(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
    lenient().when(plugin.getDataFolder())
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-lookup-test").toFile());
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
    lenient().when(config.getBoolean(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Boolean.class))).thenReturn(false);
    lenient().when(config.getString(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
    lenient().when(config.getString(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(String.class)))
            .thenAnswer(inv -> inv.getArgument(1, String.class));
    lenient().when(config.getStringList("shop-blocks")).thenReturn(java.util.List.of("CHEST"));
    lenient().when(plugin.getConfig()).thenReturn(config);
    lenient().when(plugin.getPasteManager())
            .thenReturn(mock(com.ghostchu.quickshop.util.paste.PasteManager.class));
    final var finder = mock(com.ghostchu.quickshop.api.shop.PlayerFinder.class);
    lenient().when(finder.name2Uuid(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> UUID.nameUUIDFromBytes(inv.getArgument(0, String.class).getBytes()));
    lenient().when(finder.uuid2NameFuture(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture("user"));
    lenient().when(plugin.getPlayerFinder()).thenReturn(finder);

    manager = new SimpleShopManager(plugin);
    lenient().when(plugin.getShopManager()).thenReturn(manager);
    // isValid() reaches Util.canBeShop, whose plugin reference is set by initialize()
    com.ghostchu.quickshop.util.Util.initialize();

    world = mock(org.bukkit.World.class);
    when(world.getName()).thenReturn("world");
    final org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
    when(block.getWorld()).thenReturn(world);
    when(block.getType()).thenReturn(org.bukkit.Material.CHEST);
    lenient().when(block.getState(false)).thenReturn(mock(org.bukkit.block.BlockState.class,
            org.mockito.Mockito.withSettings().extraInterfaces(org.bukkit.inventory.InventoryHolder.class)));
    lenient().when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
    lenient().when(world.getBlockAt(org.mockito.ArgumentMatchers.any(org.bukkit.Location.class))).thenReturn(block);
    // setOwner() refreshes sign text; an "unloaded" chunk lets that path return early
    // before it would reach the Folia scheduler, which is unavailable in unit tests
    lenient().when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
    lenient().when(plugin.getTextManager()).thenReturn(mock(com.ghostchu.quickshop.api.localization.text.TextManager.class));
  }

  @AfterEach
  void tearDown() {

    bukkitStatic.close();
    quickShopStatic.close();
  }

  private ContainerShop shop(final long id, final int x, final int z, final String ownerName) {

    final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes(ownerName.getBytes()), ownerName, true);
    final var item = mock(org.bukkit.inventory.ItemStack.class);
    lenient().when(item.getType()).thenReturn(org.bukkit.Material.DIAMOND);
    lenient().when(item.getAmount()).thenReturn(64);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    lenient().when(item.clone()).thenReturn(item);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    final ContainerShop shop = new ContainerShop(
            plugin, id, new org.bukkit.Location(world, x, 64, z), 10.0d, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), false, null,
            "Bukkit", "sym-" + ownerName + "-" + x + "-" + z, null,
            new HashMap<>(), benefit);
    return shop;
  }

  @Test
  void shopIdLookupResolvesRegisteredShops() {

    final ContainerShop first = shop(101, 10, 10, "alice");
    final ContainerShop second = shop(202, 20, 20, "bob");
    manager.registerShop(first, false).join();
    manager.registerShop(second, false).join();

    assertSame(first, manager.getShop(101L));
    assertSame(second, manager.getShop(202L));
    assertNull(manager.getShop(999L));
  }

  @Test
  void unregisteredPlaceholderIdsStayUnindexed() {

    final ContainerShop placeholder = shop(-1, 30, 30, "carol");
    manager.registerShop(placeholder, false).join();

    assertNull(manager.getShop(-1L));
    // the shop is still reachable through its owner bucket
    assertEquals(1, manager.getAllShops(placeholder.getOwner()).size());
  }

  @Test
  void ownerIndexTracksRegistrationAndRemoval() {

    final ContainerShop first = shop(301, 40, 40, "dave");
    final ContainerShop second = shop(302, 41, 41, "dave");
    manager.registerShop(first, false).join();
    manager.registerShop(second, false).join();

    final var dave = first.getOwner();
    final List<com.ghostchu.quickshop.api.shop.Shop> owned = manager.getAllShops(dave);
    assertEquals(2, owned.size());

    manager.unregisterShop(second, false).join();
    assertEquals(1, manager.getAllShops(dave).size());
    assertNull(manager.getShop(302L));
  }

  @Test
  void ownershipTransferMovesShopBetweenOwnerBuckets() {

    final ContainerShop shop = shop(401, 50, 50, "erin");
    manager.registerShop(shop, false).join();

    final var erin = shop.getOwner();
    final var frank = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("frank".getBytes()), "frank", true);

    assertEquals(1, manager.getAllShops(erin).size());
    assertEquals(0, manager.getAllShops(frank).size());

    shop.setOwner(frank);

    assertEquals(0, manager.getAllShops(erin).size());
    assertEquals(1, manager.getAllShops(frank).size());
    assertSame(shop, manager.getAllShops(frank).get(0));
  }

  @Test
  void runtimeUuidResolvesLoadedShopsOnly() {

    final ContainerShop loaded = shop(601, 80, 80, "iris");
    final ContainerShop unloaded = shop(602, 81, 81, "jack");
    manager.registerShop(loaded, false).join();
    manager.registerShop(unloaded, false).join();
    manager.getLoadedShops().add(loaded);
    // "jack" stays registered but unloaded

    assertSame(loaded, manager.getShopFromRuntimeRandomUniqueId(loaded.getRuntimeRandomUniqueId()));
    assertNull(manager.getShopFromRuntimeRandomUniqueId(unloaded.getRuntimeRandomUniqueId()),
            "unloaded shops must not resolve, mirroring the loaded-set scan");

    // removal must drop the index entry entirely
    manager.unregisterShop(loaded, false).join();
    assertNull(manager.getShopFromRuntimeRandomUniqueId(loaded.getRuntimeRandomUniqueId()));
  }

  @Test
  void locationLookupStillResolvesRegisteredCoordinates() {

    final ContainerShop shop = shop(501, 60, 60, "gina");
    manager.registerShop(shop, false).join();

    assertSame(shop, manager.getShop(new org.bukkit.Location(world, 60, 64, 60), true));
    assertNull(manager.getShop(new org.bukkit.Location(world, 61, 64, 60), true));
  }

  @Test
  void nonIntegralLegacyLocationsResolveByBlockCoordinates() {

    final ContainerShop shop = newShopAt(70.5d, 70, "hank");
    // legacy data may store non-integral coordinates; registration keeps them verbatim
    manager.registerShop(shop, false).join();
    // the coordinate scan matches by block coordinates, so the lookup succeeds where a
    // normalized-key hash lookup would have missed
    assertSame(shop, manager.getShop(new org.bukkit.Location(world, 70, 64, 70), true));
  }

  private ContainerShop newShopAt(final double x, final int z, final String ownerName) {

    final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes(ownerName.getBytes()), ownerName, true);
    final var item = mock(org.bukkit.inventory.ItemStack.class);
    lenient().when(item.getType()).thenReturn(org.bukkit.Material.DIAMOND);
    lenient().when(item.getAmount()).thenReturn(64);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    lenient().when(item.clone()).thenReturn(item);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    return new ContainerShop(
            plugin, -1L, new org.bukkit.Location(world, x, 64, z), 10.0d, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), false, null,
            "Bukkit", "sym-" + ownerName + "-" + x + "-" + z, null,
            new HashMap<>(), benefit);
  }
}
