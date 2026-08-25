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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the R23 inventory-cache snapshot reads: the browse menus must
 * read stock/space from one preloaded map with exactly the fallback semantics of the
 * per-shop database path (unlimited -&gt; -1, missing/uninitialized row -&gt; 0).
 */
class MarketUtilsSnapshotTest {

  private MockedStatic<Bukkit> bukkitStatic;
  private MockedStatic<QuickShop> quickShopStatic;

  @BeforeEach
  void setUp() {

    // Shop's supertypes initialize NamespacedKeys through the services manager
    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    final QuickShop plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

  private static Shop shop(final long id, final boolean selling, final boolean unlimited) {

    final Shop shop = mock(Shop.class);
    when(shop.getShopId()).thenReturn(id);
    when(shop.isSelling()).thenReturn(selling);
    when(shop.isBuying()).thenReturn(!selling);
    when(shop.isUnlimited()).thenReturn(unlimited);
    return shop;
  }

  private static Map<Long, ShopInventoryCountCache> snapshot(final Object... idStockSpacePairs) {

    final Map<Long, ShopInventoryCountCache> snapshot = new HashMap<>();
    for(int i = 0; i < idStockSpacePairs.length; i += 3) {
      snapshot.put((Long)idStockSpacePairs[i],
                   new SimpleShopInventoryCountCache((Integer)idStockSpacePairs[i + 2],
                                                     (Integer)idStockSpacePairs[i + 1], true));
    }
    return snapshot;
  }

  @Test
  void stockReadsMatchPerShopFallbackSemantics() {

    final Map<Long, ShopInventoryCountCache> snapshot = snapshot(1L, 10, 64, 2L, 0, -5);
    assertEquals(64, MarketUtils.stockOf(shop(1, true, false), snapshot));
    // negative/uninitialized cache rows clamp to 0, identical to the per-shop reader
    assertEquals(0, MarketUtils.stockOf(shop(2, true, false), snapshot));
    // shop without a cache row reads 0
    assertEquals(0, MarketUtils.stockOf(shop(3, true, false), snapshot));
    // unlimited shops never consult the snapshot
    assertEquals(-1, MarketUtils.stockOf(shop(4, true, true), Map.of()));
    assertEquals(-1, MarketUtils.spaceOf(shop(4, true, true), Map.of()));
  }

  @Test
  void spaceReadsUseTheSpaceColumn() {

    final Map<Long, ShopInventoryCountCache> snapshot = snapshot(7L, 27, 1);
    assertEquals(27, MarketUtils.spaceOf(shop(7, false, false), snapshot));
    assertEquals(0, MarketUtils.spaceOf(shop(8, false, false), snapshot));
  }

  @Test
  void stockOnlyFilterKeepsStockedSellingAndSpacedBuyingShops() {

    final Shop stocked = shop(1, true, false);
    final Shop emptyStock = shop(2, true, false);
    final Shop spacedBuying = shop(3, false, false);
    final Shop fullBuying = shop(4, false, false);
    final Shop unlimited = shop(5, true, true);

    final Map<Long, ShopInventoryCountCache> snapshot = snapshot(
            1L, 0, 30,   // selling, stock 30
            2L, 0, 0,    // selling, stock 0
            3L, 12, 0,   // buying, space 12
            4L, 0, 0     // buying, space 0
    );

    final List<Shop> kept = MarketUtils.filterByStock(
            new java.util.ArrayList<>(List.of(stocked, emptyStock, spacedBuying, fullBuying, unlimited)),
            true, snapshot);

    assertTrue(kept.contains(stocked));
    assertFalse(kept.contains(emptyStock));
    assertTrue(kept.contains(spacedBuying));
    assertFalse(kept.contains(fullBuying));
    assertTrue(kept.contains(unlimited));
  }

  @Test
  void stockSortOrdersBySnapshotWithoutTouchingTheDatabase() {

    final Shop low = shop(1, true, false);
    final Shop high = shop(2, true, false);
    final Shop absent = shop(3, true, false);

    final Map<Long, ShopInventoryCountCache> snapshot = snapshot(
            1L, 0, 5,
            2L, 0, 500
    );

    final List<Shop> sorted = MarketUtils.sortShops(new java.util.ArrayList<>(List.of(low, absent, high)),
                                                    BrowseSortMode.STOCK, snapshot);
    assertEquals(high, sorted.get(0));
    assertEquals(low, sorted.get(1));
    // absent cache rows sort as 0, below any positive stock
    assertEquals(absent, sorted.get(2));
  }
}
