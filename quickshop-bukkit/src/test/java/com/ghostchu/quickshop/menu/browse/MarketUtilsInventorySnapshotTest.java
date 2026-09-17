package com.ghostchu.quickshop.menu.browse;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.cache.ShopInventoryCountCache;
import com.ghostchu.quickshop.shop.cache.SimpleShopInventoryCountCache;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R56 menu render snapshot: {@code loadInventoryCaches} serves
 * renders from a short-TTL memory map (menu pages draw on the main thread) instead of a
 * flush+SELECT+join every render, and the cold path waits at most the bounded budget.
 */
class MarketUtilsInventorySnapshotTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;
  private com.ghostchu.quickshop.database.SimpleDatabaseHelperV2 dbHelper;
  private final AtomicInteger queries = new AtomicInteger();

  @BeforeEach
  void setUp() {

    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    final QuickShop plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
    dbHelper = mock(com.ghostchu.quickshop.database.SimpleDatabaseHelperV2.class);
    lenient().when(plugin.getDatabaseHelper()).thenReturn(dbHelper);
    lenient().when(plugin.getDbWriteBatcher()).thenReturn(null);
    queries.set(0);
    when(dbHelper.queryInventoryCaches(anyList())).thenAnswer(inv->{
      queries.incrementAndGet();
      final List<Long> ids = inv.getArgument(0);
      final Map<Long, ShopInventoryCountCache> rows = new HashMap<>();
      for(final Long id : ids) {
        rows.put(id, new SimpleShopInventoryCountCache(11, 22, true));
      }
      return CompletableFuture.completedFuture(rows);
    });
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private static Shop shop(final long id) {

    final Shop shop = mock(Shop.class);
    when(shop.getShopId()).thenReturn(id);
    return shop;
  }

  @Test
  void coldLoadWaitsWithinBudgetAndReturnsRealRows() {

    final long start = System.currentTimeMillis();
    final Map<Long, ShopInventoryCountCache> snapshot = MarketUtils.loadInventoryCaches(List.of(shop(1L), shop(2L)));
    final long elapsed = System.currentTimeMillis() - start;

    assertTrue(elapsed < 5_000, "cold load must resolve within the bounded budget (took " + elapsed + "ms)");
    assertEquals(11, MarketUtils.stockOf(shop(1L), snapshot), "cold path must serve the queried rows");
    assertEquals(22, MarketUtils.spaceOf(shop(2L), snapshot));
  }

  @Test
  void freshWindowServesWithoutAnotherQuery() {

    MarketUtils.loadInventoryCaches(List.of(shop(5L)));
    final int firstCount = queries.get();
    assertTrue(firstCount >= 1, "the first load must have queried");

    final Map<Long, ShopInventoryCountCache> again = MarketUtils.loadInventoryCaches(List.of(shop(5L)));
    assertEquals(11, MarketUtils.stockOf(shop(5L), again));
    verify(dbHelper, times(firstCount)).queryInventoryCaches(anyList());
  }

  @Test
  void emptyShopListShortCircuits() {

    assertTrue(MarketUtils.loadInventoryCaches(List.of()).isEmpty());
    assertEquals(0, queries.get());
  }

  @Test
  void failedLoadDegradesToPartialSnapshotWithoutThrowing() {

    when(dbHelper.queryInventoryCaches(anyList()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("db down")));

    final Map<Long, ShopInventoryCountCache> snapshot = MarketUtils.loadInventoryCaches(List.of(shop(9L)));
    // bounded wait absorbs the failure; missing rows read 0 (per-shop fallback semantics)
    assertEquals(0, MarketUtils.stockOf(shop(9L), snapshot));
  }
}
