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
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R55 CAS recovery: updateShop() snapshots the save record synchronously on the calling
 * thread BEFORE any future exists. When that snapshot throws (item serialization etc.),
 * the updatingAtomic flag was already flipped and no whenComplete could ever reset it —
 * the shop became permanently unsaveable while every later update() quietly returned a
 * null-completed future pretending success. The fix resets the flag on the synchronous
 * throw and surfaces the failure on the returned future.
 */
class ContainerShopUpdateCasRecoveryTest {

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
            .thenReturn(java.nio.file.Files.createTempDirectory("qs-cas-test").toFile());
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

  private ContainerShop shopWithHelper(final DatabaseHelper helper) {

    lenient().when(plugin.getDatabaseHelper()).thenReturn(helper);
    final Location location = new Location(world, 3, 64, 3);
    final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("cas-owner".getBytes()), "cas-owner", true);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.clone()).thenReturn(item);
    lenient().when(item.getType()).thenReturn(Material.DIAMOND);
    lenient().when(item.getAmount()).thenReturn(64);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    return new ContainerShop(
            plugin, 8888L, location, 10.0d, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), false, null,
            "Bukkit", "3;3;64;3;world", null,
            new HashMap<>(), benefit);
  }

  @Test
  void syncThrowSurfacesOnFutureAndFreesTheCas() throws Exception {

    // first updateShop call explodes during the synchronous record snapshot
    final DatabaseHelper failingHelper = mock(DatabaseHelper.class);
    when(failingHelper.updateShop(any(com.ghostchu.quickshop.api.shop.Shop.class)))
            .thenThrow(new RuntimeException("record serialization blew up"));
    final ContainerShop shop = shopWithHelper(failingHelper);

    shop.setDirty();
    final CompletableFuture<Void> failed = shop.update();
    assertNotNull(failed, "the failing update must still return a future");

    // the failure must be observable on the returned future (no silent null completion)
    assertThrows(ExecutionException.class, failed::get);

    // second update with a healthy helper must be allowed to proceed: pre-fix, the
    // stranded updatingAtomic=true short-circuited this into a fake completed future
    final DatabaseHelper okHelper = mock(DatabaseHelper.class);
    when(okHelper.updateShop(any(com.ghostchu.quickshop.api.shop.Shop.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
    when(plugin.getDatabaseHelper()).thenReturn(okHelper);

    final CompletableFuture<Void> second = shop.update();
    assertNotNull(second);
    second.get(); // must actually complete through the write path, not the CAS bail-out
    assertFalse(second.isCompletedExceptionally(), "the retried save must succeed");
    // the CAS bail-out path never touches the helper: this proves the retry really
    // re-entered the write path after the flag reset
    verify(okHelper, org.mockito.Mockito.times(1)).updateShop(shop);
  }

  @Test
  void dirtyFlagSurvivesTheFailedSave() {

    final DatabaseHelper failingHelper = mock(DatabaseHelper.class);
    when(failingHelper.updateShop(any(com.ghostchu.quickshop.api.shop.Shop.class)))
            .thenThrow(new RuntimeException("record serialization blew up"));
    final ContainerShop shop = shopWithHelper(failingHelper);

    shop.setDirty();
    shop.update();

    assertTrue(shop.isDirty(), "a failed save must leave the shop dirty for the next flush");
  }
}
