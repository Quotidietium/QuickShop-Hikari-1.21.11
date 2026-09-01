package com.ghostchu.quickshop.database;

import cc.carm.lib.easysql.manager.SQLManagerImpl;
import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.QuickShopProvider;
import com.ghostchu.quickshop.api.database.ShopMetricRecord;
import com.ghostchu.quickshop.api.database.ShopOperationEnum;
import com.ghostchu.quickshop.common.util.QuickExecutor;
import com.ghostchu.quickshop.metric.MetricBatcher;
import com.ghostchu.quickshop.shop.ContainerShop;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for batched metric inserts: MetricBatcher coalesces records and
 * SimpleDatabaseHelperV2.insertMetricRecords lands every row of a mixed-shop batch
 * exactly once, the empty batch is a no-op, and single inserts still work.
 */
class MetricBatchTest {

  private static QuickShop plugin;
  private static SimpleDatabaseHelperV2 helper;
  private static World world;
  private static final List<Long> SHOP_IDS = new ArrayList<>();

  @BeforeAll
  static void boot() throws Exception {

    plugin = mock(QuickShop.class);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    final dev.dejvokep.boostedyaml.YamlDocument config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getBoolean(anyString(), any(Boolean.class))).thenReturn(true);
    lenient().when(config.getBoolean(anyString())).thenReturn(true);
    lenient().when(plugin.getConfig()).thenReturn(config);
    final var bukkitPlugin = mock(com.ghostchu.quickshop.QuickShopBukkit.class);
    lenient().when(bukkitPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(plugin.getJavaPlugin()).thenReturn(bukkitPlugin);
    lenient().when(plugin.getShopManager()).thenReturn(mock(SimpleShopManager.class));
    final var finder = mock(com.ghostchu.quickshop.api.shop.PlayerFinder.class);
    lenient().when(finder.name2Uuid(anyString(), any(Boolean.class), any()))
            .thenAnswer(inv -> UUID.nameUUIDFromBytes(inv.getArgument(0, String.class).getBytes()));
    lenient().when(finder.uuid2NameFuture(any(UUID.class), any(Boolean.class), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture("user"));
    lenient().when(plugin.getPlayerFinder()).thenReturn(finder);
    final var platform = mock(com.ghostchu.quickshop.platform.Platform.class);
    lenient().when(platform.encodeStack(any(ItemStack.class))).thenReturn("aGVsbG8=");
    final ItemStack decoded = itemMock();
    lenient().when(platform.decodeStack(anyString())).thenReturn(decoded);
    lenient().when(plugin.platform()).thenReturn(platform);

    final Server server = mock(Server.class);
    when(server.isPrimaryThread()).thenReturn(true);
    when(server.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
    final Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, server);

    final QuickShopProvider provider = mock(QuickShopProvider.class);
    when(provider.getApiInstance()).thenReturn(plugin);
    final var rsp = mock(org.bukkit.plugin.RegisteredServiceProvider.class);
    when(rsp.getProvider()).thenReturn(provider);
    final var rspPlugin = mock(org.bukkit.plugin.Plugin.class);
    lenient().when(rspPlugin.getName()).thenReturn("quickshophikari");
    lenient().when(rsp.getPlugin()).thenReturn(rspPlugin);
    final var servicesManager = mock(org.bukkit.plugin.ServicesManager.class);
    lenient().when(servicesManager.getRegistration(QuickShopProvider.class)).thenReturn(rsp);
    when(server.getServicesManager()).thenReturn(servicesManager);

    final Field instanceField = QuickShop.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, plugin);

    final JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:qsmetricbatch;MODE=MYSQL;DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    h2.setPassword("");
    final SQLManagerImpl sqlManager = new SQLManagerImpl(h2, "metricbatch-sql-manager");
    sqlManager.executeSQL("SET MODE=MYSQL");
    sqlManager.setExecutorPool(QuickExecutor.getHikaricpExecutor());
    helper = new SimpleDatabaseHelperV2(plugin, sqlManager, "qs_");
    lenient().when(plugin.getDatabaseHelper()).thenReturn(helper);

    world = mock(World.class);
    lenient().when(world.getName()).thenReturn("world");

    // three real shops so locateShopDataId resolves for every batch member
    for(int i = 0; i < 3; i++) {
      final var owner = com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
              UUID.nameUUIDFromBytes(("metric-owner-" + i).getBytes()), "metric-owner-" + i, true);
      final var benefit = mock(com.ghostchu.quickshop.api.economy.benefit.BenefitProvider.class);
      lenient().when(benefit.serialize()).thenReturn("{}");
      final ContainerShop shop = new ContainerShop(
              plugin, -1L, new Location(world, 2000 + i, 64, 2000 + i), 10.0d, itemMock(), owner, false,
              SimpleShopManager.SELLING_TYPE, SimpleShopManager.ACTIVE_STATE,
              new YamlConfiguration(), false, null,
              "Bukkit", "metric-sym-" + i, null, new HashMap<>(), benefit);
      final long dataId = helper.createData(shop).join();
      final long shopId = helper.createShop(dataId).join();
      helper.createShopMap(shopId, shop.bukkitLocation()).join();
      SHOP_IDS.add(shopId);
    }
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

  private long purchaseRows() {

    return helper.getManager().createQuery()
            .withPreparedSQL("SELECT COUNT(*) AS c FROM qs_log_purchase")
            .setParams(java.util.Collections.emptyList())
            .executeFuture(q->{
              try(q) {
                q.getResultSet().next();
                return q.getResultSet().getLong("c");
              }
            }).join();
  }

  private ShopMetricRecord record(final int i) {

    return new ShopMetricRecord(
            System.currentTimeMillis(), SHOP_IDS.get(i % SHOP_IDS.size()),
            ShopOperationEnum.PURCHASE_SELLING_SHOP, 10.0d, 0.5d, i + 1,
            com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
                    UUID.nameUUIDFromBytes("metric-buyer".getBytes()), "metric-buyer", true));
  }

  @Test
  void batchedInsertLandsEveryRowOnce() {

    final long before = purchaseRows();
    final List<ShopMetricRecord> batch = new ArrayList<>();
    for(int i = 0; i < 60; i++) {
      batch.add(record(i));
    }
    final Integer lines = helper.insertMetricRecords(batch).join();

    assertEquals(60, lines);
    assertEquals(before + 60, purchaseRows());
  }

  @Test
  void batcherFlushesPendingRecords() {

    final long before = purchaseRows();
    final MetricBatcher batcher = new MetricBatcher(plugin);
    for(int i = 0; i < 25; i++) {
      batcher.offer(record(i));
    }
    batcher.flushSync(10);

    assertEquals(before + 25, purchaseRows());
    // double flush is a no-op
    assertEquals(0, batcher.flushAsync().join());
    assertEquals(before + 25, purchaseRows());
  }

  @Test
  void emptyBatchIsNoOp() {

    assertEquals(0, helper.insertMetricRecords(List.of()).join());
    assertEquals(0, new MetricBatcher(plugin).flushAsync().join());
  }

  @Test
  void singleInsertStillWorks() {

    final long before = purchaseRows();
    helper.insertMetricRecord(record(0)).join();
    assertEquals(before + 1, purchaseRows());
  }

  @Test
  void batchAfterSinglesSharesTable() {

    final long before = purchaseRows();
    helper.insertMetricRecord(record(0)).join();
    final List<ShopMetricRecord> batch = List.of(record(1), record(2));
    helper.insertMetricRecords(batch).join();

    assertEquals(before + 3, purchaseRows());
  }
}
