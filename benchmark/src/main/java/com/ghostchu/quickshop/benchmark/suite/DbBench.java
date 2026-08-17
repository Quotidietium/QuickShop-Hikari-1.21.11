package com.ghostchu.quickshop.benchmark.suite;

import cc.carm.lib.easysql.manager.SQLManagerImpl;
import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.PlayerFinder;
import com.ghostchu.quickshop.benchmark.Env;
import com.ghostchu.quickshop.common.util.QuickExecutor;
import com.ghostchu.quickshop.database.SimpleDatabaseHelperV2;
import com.ghostchu.quickshop.shop.ContainerShop;
import org.h2.jdbcx.JdbcDataSource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static com.ghostchu.quickshop.benchmark.BenchHarness.consume;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Measures real SQL throughput against an in-memory H2 (MODE=MYSQL) database through the
 * production EasySQL layer: shop creation chain, dirty-shop update (including the
 * data-record dedup SELECT) and the startup full-table read.
 */
public final class DbBench {

  private static final int SHOP_COUNT = 2_000;

  private DbBench() {

  }

  public static void run(final com.ghostchu.quickshop.benchmark.BenchHarness harness) throws Exception {

    final QuickShop plugin = Env.plugin();

    final PlayerFinder finder = mock(PlayerFinder.class);
    when(finder.uuid2NameFuture(any(UUID.class), anyBoolean(), any()))
            .thenAnswer(inv -> CompletableFuture.completedFuture("user"));
    when(finder.name2Uuid(anyString(), anyBoolean(), any()))
            .thenAnswer(inv -> UUID.nameUUIDFromBytes(inv.getArgument(0, String.class).getBytes(StandardCharsets.UTF_8)));
    when(plugin.getPlayerFinder()).thenReturn(finder);

    final JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:qsbenchmark;MODE=MYSQL;DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    h2.setPassword("");
    final SQLManagerImpl sqlManager = new SQLManagerImpl(h2, "benchmark-sql-manager");
    sqlManager.executeSQL("SET MODE=MYSQL");
    sqlManager.setExecutorPool(QuickExecutor.getHikaricpExecutor());
    final SimpleDatabaseHelperV2 helper = new SimpleDatabaseHelperV2(plugin, sqlManager, "qs_");
    when(plugin.getDatabaseHelper()).thenReturn(helper);

    // populate: create the full three-table chain for every shop (mirrors registerShop)
    final ContainerShop[] shops = DataRecordBench.setup();
    final long[] ids = new long[Math.min(SHOP_COUNT, shops.length)];
    for(int i = 0; i < ids.length; i++) {
      final long dataId = helper.createData(shops[i]).join();
      final long shopId = helper.createShop(dataId).join();
      helper.createShopMap(shopId, shops[i].bukkitLocation()).join();
      shops[i].setShopId(shopId);
      ids[i] = shopId;
    }

    final int[] permutation = ThreadLocalRandom.current().ints(0, ids.length).limit(ids.length).toArray();

    // one-shot re-insert cases operate on fresh shops so the dedup SELECT misses
    final ContainerShop[] freshShops = new ContainerShop[64];
    for(int i = 0; i < freshShops.length; i++) {
      freshShops[i] = createVariantShop(shops[i], i);
    }

    harness.bench("db/insertShopChain", ctx -> {
      final ContainerShop shop = freshShops[(int)(ctx.index++ % freshShops.length)];
      final long dataId = helper.createData(shop).join();
      final long shopId = helper.createShop(dataId).join();
      consume(helper.createShopMap(shopId, shop.bukkitLocation()).join());
    });

    harness.bench("db/updateShop-unchangedData", ctx -> {
      final ContainerShop shop = shops[permutation[(int)(ctx.index++ % permutation.length)]];
      consume(helper.updateShop(shop).join());
    });

    harness.bench("db/locateShopDataId", ctx -> {
      final long id = ids[permutation[(int)(ctx.index++ % permutation.length)]];
      consume(helper.locateShopDataId(id).join());
    });

    harness.bench("db/listShops", ctx -> {
      ctx.index++;
      consume(helper.listShops(null, false).size());
    });

    // per-trade metric insert cost: one qs_log_purchase row per purchase op
    harness.bench("db/metricInsertSingle", ctx -> {
      final long shopId = ids[permutation[(int)(ctx.index++ % permutation.length)]];
      consume(helper.insertMetricRecord(metricRecord(shopId, ctx.index)).join());
    });

    // batched metric flush (candidate builds only — probed at runtime so baseline jars
    // simply skip the case); one op = one JDBC batch of 100 records
    try {
      SimpleDatabaseHelperV2.class.getMethod("insertMetricRecords", java.util.List.class);
      final int batchSize = 100;
      harness.bench("db/metricInsertBatch100", ctx -> {
        ctx.index++;
        final java.util.List<com.ghostchu.quickshop.api.database.ShopMetricRecord> batch = new java.util.ArrayList<>(batchSize);
        for(int i = 0; i < batchSize; i++) {
          batch.add(metricRecord(ids[permutation[(int)((ctx.index + i) % permutation.length)]], ctx.index + i));
        }
        consume(helper.insertMetricRecords(batch).join());
      });
    } catch(final NoSuchMethodException absentInBaseline) {
      // baseline jar: insertMetricRecords not present, case intentionally unregistered
    }
  }

  private static com.ghostchu.quickshop.api.database.ShopMetricRecord metricRecord(final long shopId, final long seq) {

    return new com.ghostchu.quickshop.api.database.ShopMetricRecord(
            System.currentTimeMillis(), shopId,
            com.ghostchu.quickshop.api.database.ShopOperationEnum.PURCHASE_SELLING_SHOP,
            10.0d, 0.5d, (int)(seq % 64),
            com.ghostchu.quickshop.obj.QUserImpl.createFullFilled(
                    java.util.UUID.nameUUIDFromBytes("metric-bench".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "metric-bench", true));
  }

  /**
   * Creates a shop whose data record differs from the source (unique symbol link), so the
   * dedup SELECT on insert actually misses and the INSERT path is measured.
   */
  private static ContainerShop createVariantShop(final ContainerShop source, final int salt) {

    final var plugin = Env.plugin();
    return new ContainerShop(
            plugin, -1L, source.bukkitLocation().clone(), source.getPrice() + 0.01d * salt,
            source.getItem(), source.getOwner(), source.isUnlimited(),
            com.ghostchu.quickshop.shop.SimpleShopManager.SELLING_TYPE,
            com.ghostchu.quickshop.shop.SimpleShopManager.ACTIVE_STATE,
            new org.bukkit.configuration.file.YamlConfiguration(), null,
            false, null, "Bukkit",
            "sym-fresh-" + salt + "-" + System.identityHashCode(source), source.getShopName(),
            new java.util.HashMap<>(source.getPermissionAudiences()), source.getShopBenefit());
  }
}
