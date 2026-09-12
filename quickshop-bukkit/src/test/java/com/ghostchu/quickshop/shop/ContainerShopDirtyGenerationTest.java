package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.database.DatabaseHelper;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * R53 lost-update guard for the dirty flag: updateShop snapshots the save record at call
 * time while the SQL completes later — the old completion callback cleared the dirty flag
 * unconditionally, so any setDirty between snapshot and completion (a price change, an
 * item swap) was silently never persisted. The dirty flag may only clear when no change
 * landed after the save's snapshot.
 */
class ContainerShopDirtyGenerationTest {

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
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder())
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-dirty-test").toFile());
    lenient().when(plugin.getReloadManager()).thenReturn(mock(com.ghostchu.simplereloadlib.ReloadManager.class));
    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString())).thenReturn(false);
    lenient().when(config.getInt(anyString())).thenReturn(0);
    lenient().when(plugin.getConfig()).thenReturn(config);

    final var manager = mock(SimpleShopManager.class);
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

  private ContainerShop shopWithPendingSave(final CompletableFuture<Void> pending) {

    final DatabaseHelper helper = mock(DatabaseHelper.class);
    when(helper.updateShop(any(com.ghostchu.quickshop.api.shop.Shop.class))).thenReturn(pending);
    lenient().when(plugin.getDatabaseHelper()).thenReturn(helper);

    final Location location = new Location(world, 3, 64, 3);
    final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("dirty-owner".getBytes()), "dirty-owner", true);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.clone()).thenReturn(item);
    lenient().when(item.getType()).thenReturn(Material.DIAMOND);
    lenient().when(item.getAmount()).thenReturn(64);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    return new ContainerShop(
            plugin, 7777L, location, 10.0d, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), false, null,
            "Bukkit", "3;3;64;3;world", null,
            new HashMap<>(), benefit);
  }

  @Test
  void changeRacingTheSqlRoundTripKeepsTheDirtyFlag() {

    final CompletableFuture<Void> pending = new CompletableFuture<>();
    final ContainerShop shop = shopWithPendingSave(pending);

    shop.setDirty();
    shop.update();                       // snapshot taken, SQL "in flight"
    shop.setDirty();                     // a change lands after the snapshot
    pending.complete(null);              // the raced write finishes successfully

    assertTrue(shop.isDirty(), "a post-snapshot change must stay dirty for the next flush");
  }

  @Test
  void unchangedSaveClearsTheDirtyFlag() {

    final CompletableFuture<Void> pending = new CompletableFuture<>();
    final ContainerShop shop = shopWithPendingSave(pending);

    shop.setDirty();
    shop.update();
    pending.complete(null);

    assertFalse(shop.isDirty(), "a save of the latest state clears the flag");
  }

  @Test
  void failedSaveKeepsTheDirtyFlag() {

    final CompletableFuture<Void> pending = new CompletableFuture<>();
    final ContainerShop shop = shopWithPendingSave(pending);

    shop.setDirty();
    shop.update();
    pending.completeExceptionally(new RuntimeException("db down"));

    assertTrue(shop.isDirty(), "a failed write must keep the flag");
  }
}
