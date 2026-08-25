package com.ghostchu.quickshop.database;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.cache.ShopInventoryCountCache;
import com.ghostchu.quickshop.common.util.QuickExecutor;
import cc.carm.lib.easysql.manager.SQLManagerImpl;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.bukkit.Bukkit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R23 batched inventory-cache lookup: one IN(...) round-trip
 * must return every stored row with the same column semantics as the single-id query,
 * unknown ids stay absent, and empty input completes immediately.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseHelperBatchCacheTest {

  private SimpleDatabaseHelperV2 helper;
  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;

  @BeforeAll
  void setUp() throws Exception {

    final QuickShop plugin = mock(QuickShop.class);
    when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    final YamlDocument config = mock(YamlDocument.class);
    when(plugin.getConfig()).thenReturn(config);
    when(config.getBoolean(anyString(), anyBoolean())).thenReturn(true);
    when(config.getBoolean(anyString())).thenReturn(true);

    bukkitStatic = mockStatic(Bukkit.class);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    quickShopStatic = mockStatic(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);

    final JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:qstestbatchcache;MODE=MYSQL;DB_CLOSE_DELAY=-1");
    h2.setUser("sa");
    h2.setPassword("");
    final SQLManagerImpl sqlManager = new SQLManagerImpl(h2, "test-sql-manager");
    sqlManager.executeSQL("SET MODE=MYSQL");
    sqlManager.setExecutorPool(QuickExecutor.getHikaricpExecutor());

    helper = new SimpleDatabaseHelperV2(plugin, sqlManager, "qs_");
  }

  @AfterAll
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private static void await(final CompletableFuture<?> future) throws Exception {

    future.get(10, TimeUnit.SECONDS);
  }

  @Test
  void batchedLookupReturnsAllStoredRowsAndSkipsUnknownIds() throws Exception {

    await(helper.updateExternalInventoryProfileCache(101, 27, 640));
    await(helper.updateExternalInventoryProfileCache(102, 0, 5));
    await(helper.updateExternalInventoryProfileCache(103, 9, 70));

    final Map<Long, ShopInventoryCountCache> result = helper
            .queryInventoryCaches(List.of(101L, 102L, 103L, 999L))
            .get(10, TimeUnit.SECONDS);

    assertEquals(3, result.size());
    assertFalse(result.containsKey(999L));

    final ShopInventoryCountCache first = result.get(101L);
    assertTrue(first.initialized());
    assertEquals(640, first.getStock());
    assertEquals(27, first.getSpace());

    assertEquals(5, result.get(102L).getStock());
    assertEquals(70, result.get(103L).getStock());

    // single-id and batched readers must agree on column semantics
    final ShopInventoryCountCache single = helper.queryInventoryCache(102L).get(10, TimeUnit.SECONDS);
    assertEquals(single.getStock(), result.get(102L).getStock());
    assertEquals(single.getSpace(), result.get(102L).getSpace());
  }

  @Test
  void emptyInputCompletesToEmptyMap() throws Exception {

    final Map<Long, ShopInventoryCountCache> result = helper.queryInventoryCaches(List.of())
            .get(10, TimeUnit.SECONDS);
    assertTrue(result.isEmpty());
  }
}
