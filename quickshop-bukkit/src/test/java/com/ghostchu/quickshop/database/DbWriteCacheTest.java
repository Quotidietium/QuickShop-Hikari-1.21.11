package com.ghostchu.quickshop.database;

import cc.carm.lib.easysql.manager.SQLManagerImpl;
import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.QuickShopProvider;
import com.ghostchu.quickshop.common.util.QuickExecutor;
import com.ghostchu.quickshop.shop.ContainerShop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the write-path caches in SimpleDatabaseHelperV2: unchanged
 * saves are skipped, dedup keeps sharing data rows through the cache, deletes
 * invalidate cached data ids, and locateShopDataId agrees with the persisted pointer.
 */
class DbWriteCacheTest {

  private static QuickShop plugin;
  private static SimpleDatabaseHelperV2 helper;
  private static World world;

  @BeforeAll
  static void boot() throws Exception {

    plugin = mock(QuickShop.class);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString(), org.mockito.ArgumentMatchers.any(Boolean.class))).thenReturn(true);
    lenient().when(config.getBoolean(anyString())).thenReturn(true);
    lenient().when(plugin.getConfig()).thenReturn(config);
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(plugin.getShopManager()).thenReturn(mock(com.ghostchu.quickshop.shop.SimpleShopManager.class));
    final var finder = mock(com.ghostchu.quickshop.api.shop.PlayerFinder.class);
    lenient().when(finder.name2Uuid(anyString(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> UUID.nameUUIDFromBytes(inv.getArgument(0, String.class).getBytes()));
    lenient().when(finder.uuid2NameFuture(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture("user"));
    lenient().when(plugin.getPlayerFinder()).thenReturn(finder);
    final var platform = mock(com.ghostchu.quickshop.platform.Platform.class);
    lenient().when(platform.encodeStack(org.mockito.ArgumentMatchers.any(ItemStack.class))).thenReturn("aGVsbG8=");
    final ItemStack decoded = itemMock();
    lenient().when(platform.decodeStack(anyString())).thenReturn(decoded);
    lenient().when(plugin.platform()).thenReturn(platform);

    final org.bukkit.Server server = mock(org.bukkit.Server.class);
    when(server.isPrimaryThread()).thenReturn(true);
    when(server.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
    final Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, server);

    final QuickShopProvider provider = mock(QuickShopProvider.class);
    when(provider.getApiInstance()).thenReturn(plugin);
    final org.bukkit.plugin.RegisteredServiceProvider<QuickShopProvider> rsp =
            mock(org.bukkit.plugin.RegisteredServiceProvider.class);
    when(rsp.getProvider()).thenReturn(provider);
    final org.bukkit.plugin.Plugin rspPlugin = mock(org.bukkit.plugin.Plugin.class);
    lenient().when(rspPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(rsp.getPlugin()).thenReturn(rspPlugin);
    final org.bukkit.plugin.ServicesManager servicesManager = mock(org.bukkit.plugin.ServicesManager.class);
    lenient().when(servicesManager.getRegistration(QuickShopProvider.class)).thenReturn(rsp);
    when(server.getServicesManager()).thenReturn(servicesManager);

    final Field instanceField = QuickShop.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, plugin);

    final JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:qswritecache;MODE=MYSQL;DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    h2.setPassword("");
    final SQLManagerImpl sqlManager = new SQLManagerImpl(h2, "writecache-sql-manager");
    sqlManager.executeSQL("SET MODE=MYSQL");
    sqlManager.setExecutorPool(QuickExecutor.getHikaricpExecutor());
    helper = new SimpleDatabaseHelperV2(plugin, sqlManager, "qs_");
    lenient().when(plugin.getDatabaseHelper()).thenReturn(helper);

    world = mock(World.class);
    lenient().when(world.getName()).thenReturn("world");
  }

  @AfterAll
  static void shutdown() throws Exception {

    final Field instanceField = QuickShop.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }

  @BeforeEach
  void resetCaches() {

    helper.invalidateCaches();
  }

  private static ItemStack itemMock() {

    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.getType()).thenReturn(Material.DIAMOND);
    lenient().when(item.getAmount()).thenReturn(64);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    lenient().when(item.clone()).thenReturn(item);
    return item;
  }

  private ContainerShop shop(final String symbolLink, final double price, final int slot) {

    // unique coordinates per test: shop_map rows REPLACE by position, so tests sharing
    // one database must not share one position
    return newShopAt(new Location(world, 1000 + slot, 64, 1000 + slot), symbolLink, price);
  }

  private ContainerShop newShopAt(final Location location, final String symbolLink, final double price) {

    final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
            UUID.nameUUIDFromBytes("dbcache-owner".getBytes()), "dbcache-owner", true);
    final ItemStack item = mock(ItemStack.class);
    lenient().when(item.getType()).thenReturn(Material.DIAMOND);
    lenient().when(item.getAmount()).thenReturn(64);
    lenient().when(item.hasItemMeta()).thenReturn(false);
    lenient().when(item.clone()).thenReturn(item);
    final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
    lenient().when(benefit.serialize()).thenReturn("{}");
    return new ContainerShop(
            plugin, -1L, location, price, item, owner, false,
            SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
            new YamlConfiguration(), false, null,
            "Bukkit", symbolLink, null, new HashMap<>(), benefit);
  }

  private long insertShop(final ContainerShop shop) {

    final long dataId = helper.createData(shop).join();
    final long shopId = helper.createShop(dataId).join();
    helper.createShopMap(shopId, shop.bukkitLocation()).join();
    shop.setShopId(shopId);
    return dataId;
  }

  @Test
  void createDataDedupsIdenticalRecordsThroughCache() {

    final ContainerShop first = shop("dedup", 25.0d, 1);
    final ContainerShop second = shop("dedup", 25.0d, 1);
    // same owner/price/item/symbol-link/extra: identical record content
    final Long firstId = helper.createData(first).join();
    final Long secondId = helper.createData(second).join();
    assertNotNull(firstId);
    assertEquals(firstId, secondId, "identical records must share one data row");
  }

  @Test
  void unchangedUpdateShopSkipsSqlAndChangedUpdatePersists() {

    final ContainerShop shop = shop("update-skip", 30.0d, 2);
    insertShop(shop);

    // first save: pointer written, caches primed
    helper.updateShop(shop).join();
    // second save with identical content must complete without SQL (skipped path)
    helper.updateShop(shop).join();

    // change the price: the record differs, so the save must take the full path
    shop.setPrice(42.0d);
    helper.updateShop(shop).join();

    final var records = helper.listShops(null, false);
    final var match = records.stream()
            .filter(r->r.getInfoRecord().getWorld().equals("world") && r.getInfoRecord().getX() == 1002 && r.getInfoRecord().getZ() == 1002)
            .findFirst().orElseThrow();
    assertEquals(42.0d, match.getDataRecord().getPrice(), 0.0001d, "changed price must persist");
  }

  @Test
  void removeDataInvalidatesTheCachedId() {

    final ContainerShop shop = shop("invalidate", 35.0d, 3);
    final Long dataId = helper.createData(shop).join();
    assertNotNull(dataId);

    // prime the dedup cache
    assertEquals(dataId, helper.createData(shop).join());

    helper.removeData(dataId).join();
    // without invalidation this would return the deleted id from the cache
    final Long recreated = helper.createData(shop).join();
    assertNotNull(recreated);
    assertNotEquals(dataId, recreated, "deleted data rows must not be handed out again");
  }

  @Test
  void locateShopDataIdAgreesWithPersistedPointer() {

    final ContainerShop shop = shop("locate", 40.0d, 4);
    final long dataId = insertShop(shop);
    helper.updateShop(shop).join();

    final Long located = helper.locateShopDataId(shop.getShopId()).join();
    assertNotNull(located);
    assertEquals(dataId, located, "metric path must resolve the data row the shop points at");
    // second resolution goes through the cache and must stay consistent
    assertEquals(located, helper.locateShopDataId(shop.getShopId()).join());
  }
}
