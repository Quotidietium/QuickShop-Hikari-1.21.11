package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.platform.Platform;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the memoized save-path item encodings: createDataRecord and
 * saveToInfoStorage re-ran a full NBT encode per call (per dirty-save flush, per
 * purchase-log snapshot) for stacks that only change through setItem's fresh snapshots
 * or the allow-stack amount flip. The tests pin the call-count contract (one encode per
 * stack state) and the invalidation on setItem.
 */
class ShopItemEncodeCacheTest {

  private MockedStatic<QuickShop> quickShopStatic;
  private MockedStatic<Bukkit> bukkitStatic;
  private QuickShop plugin;
  private Platform platform;
  private World world;
  private final AtomicInteger encodeCalls = new AtomicInteger();

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
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder())
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-encode-test").toFile());
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(any(String.class))).thenReturn(false);
    lenient().when(config.getInt(any(String.class))).thenReturn(0);
    lenient().when(plugin.getConfig()).thenReturn(config);

    platform = mock(Platform.class);
    when(platform.encodeStack(any(ItemStack.class))).thenAnswer(
            inv -> "enc-" + encodeCalls.incrementAndGet());
    lenient().when(plugin.platform()).thenReturn(platform);

    // getTaxAccount() casts the manager; the null cache account keeps it on the plain path
    final SimpleShopManager manager = mock(SimpleShopManager.class);
    lenient().when(manager.getCacheTaxAccount()).thenReturn(null);
    lenient().when(plugin.getShopManager()).thenReturn(manager);

    world = mock(World.class);
    when(world.getName()).thenReturn("world");
  }

  @AfterEach
  void tearDown() {

    HandlerList.unregisterAll();
    bukkitStatic.close();
    quickShopStatic.close();
  }

  private ContainerShop shop() {

    final Location location = new Location(world, 1, 64, 1);
    final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("encode-owner".getBytes()), "encode-owner", true);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.clone()).thenReturn(item);
    lenient().when(item.getType()).thenReturn(Material.DIAMOND);
    lenient().when(item.getAmount()).thenReturn(64);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    return new ContainerShop(
            plugin, -1L, location, 10.0d, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), false, null,
            "Bukkit", "2;1;64;1;world", null,
            new HashMap<>(), benefit);
  }

  @Test
  void testRepeatedSavesEncodeOncePerStackState() {

    final ContainerShop shop = shop();
    final var first = shop.createDataRecord();
    final var second = shop.createDataRecord();
    assertEquals(first.getItem(), second.getItem(), "cached encoding must be value-stable");
    verify(platform, times(1)).encodeStack(any(ItemStack.class));
    shop.saveToInfoStorage();
    assertEquals(2, encodeCalls.get(), "saveToInfoStorage encodes the original item slot once, then caches");
    shop.saveToInfoStorage();
    assertEquals(2, encodeCalls.get(), "second saveToInfoStorage must hit the memo");
  }

  @Test
  void testSetItemInvalidatesTheMemo() {

    final ContainerShop shop = shop();
    shop.createDataRecord();
    verify(platform, times(1)).encodeStack(any(ItemStack.class));

    final ItemStack replacement = mock(ItemStack.class);
    lenient().when(replacement.clone()).thenReturn(replacement);
    lenient().when(replacement.getType()).thenReturn(Material.EMERALD);
    lenient().when(replacement.getAmount()).thenReturn(16);
    lenient().when(replacement.hasItemMeta()).thenReturn(false);
    shop.setItem(replacement);

    final var after = shop.createDataRecord();
    verify(platform, times(2)).encodeStack(any(ItemStack.class));
    assertEquals("enc-2", after.getItem(), "the new stack state must re-encode");
    shop.createDataRecord();
    verify(platform, times(2)).encodeStack(any(ItemStack.class));
  }
}
