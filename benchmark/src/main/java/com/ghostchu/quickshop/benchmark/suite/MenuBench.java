package com.ghostchu.quickshop.benchmark.suite;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.cache.ShopInventoryCountCache;
import com.ghostchu.quickshop.menu.browse.BrowseFilterMode;
import com.ghostchu.quickshop.menu.browse.BrowseSortMode;
import com.ghostchu.quickshop.menu.browse.MarketUtils;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import com.ghostchu.quickshop.shop.cache.SimpleShopInventoryCountCache;
import com.ghostchu.quickshop.benchmark.Env;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Measures the browse-menu stock pipeline: what one page render pays to filter
 * (stock-only) and sort (by stock) the market's shop list.
 * <p>
 * The candidate (R23) routes every stock/space read through one preloaded snapshot map
 * (one batched database query per render); the baseline reads per shop — and the STOCK
 * sort's comparator re-reads per comparison. The per-shop database round-trip is
 * represented here by a mock manager returning completed futures, so the measured
 * delta is the dispatch overhead — the real win (n SELECTs, each behind a batch
 * flush, collapsing to one IN(...) query) is strictly larger.
 */
public final class MenuBench {

  private MenuBench() {

  }

  @SuppressWarnings("unchecked")
  public static void run(final com.ghostchu.quickshop.benchmark.BenchHarness harness) throws Exception {

    final QuickShop plugin = Env.plugin();

    // the legacy per-shop reader resolves through the shop manager; the mock hands out
    // completed futures so the op measures dispatch + join, not a real round-trip
    final SimpleShopManager shopManager = Env.pin(mock(SimpleShopManager.class,
                                                       withSettings().stubOnly()));
    lenient().when(plugin.getShopManager()).thenReturn(shopManager);

    final int shopCount = 200;
    final List<Shop> shops = new ArrayList<>(shopCount);
    final Map<Long, ShopInventoryCountCache> snapshot = new HashMap<>();
    for(int i = 0; i < shopCount; i++) {
      final long id = i + 1;
      final Shop shop = Env.pin(Env.hotMock(Shop.class));
      when(shop.getShopId()).thenReturn(id);
      when(shop.isSelling()).thenReturn(i % 2 == 0);
      when(shop.isBuying()).thenReturn(i % 2 != 0);
      when(shop.isUnlimited()).thenReturn(false);
      // stock derived from the id: mix of zero-stock and stocked shops so both the
      // filter and the comparator have real work
      final int stock = (i % 3 == 0)? 0 : (int)(id * 7 % 5000);
      snapshot.put(id, new SimpleShopInventoryCountCache(stock, stock, true));
      lenient().when(shopManager.queryShopInventoryCacheInDatabase(any(Shop.class)))
              .thenAnswer(inv->CompletableFuture.completedFuture(
                      new SimpleShopInventoryCountCache(stock, stock, true)));
      shops.add(shop);
    }
    // candidate-only 6-arg snapshot overload; baseline falls back to the legacy 5-arg
    // pipeline below (compile-safe: the legacy signature exists on both jars)
    MethodHandle snapshotPipeline = null;
    try {
      final Method method = MarketUtils.class.getMethod("processShops",
                                                        List.class, BrowseFilterMode.class,
                                                        BrowseSortMode.class, String.class,
                                                        boolean.class, Map.class);
      snapshotPipeline = java.lang.invoke.MethodHandles.publicLookup().unreflect(method);
    } catch(final NoSuchMethodException absentInBaseline) {
      // baseline jar: snapshot pipeline not present yet
    }
    final MethodHandle candidate = snapshotPipeline;

    harness.bench("menu/browseStockPipeline", ctx -> {
      ctx.index++;
      if(candidate != null) {
        try {
          // cast shapes invokeExact's expected return type (erased List)
          final List<?> result = (List<?>)candidate.invokeExact((List<Shop>)shops, BrowseFilterMode.ALL,
                                                                BrowseSortMode.STOCK, (String)null,
                                                                true, (Map<Long, ShopInventoryCountCache>)snapshot);
          com.ghostchu.quickshop.benchmark.BenchHarness.consume(result);
        } catch(final Throwable t) {
          throw new IllegalStateException("snapshot pipeline failed", t);
        }
      } else {
        // the historic path: filterShops -> per-shop cache join -> sort with a
        // comparator that re-queries per comparison
        final Object result = MarketUtils.processShops(shops, BrowseFilterMode.ALL,
                                                       BrowseSortMode.STOCK, null, true);
        com.ghostchu.quickshop.benchmark.BenchHarness.consume(result);
      }
    });

    // grouping + NAME-sort pipeline (browse main page): groupShopsByItem runs every
    // loaded shop through the real matcher, and NAME sort orders by prettified
    // material name. Shop mocks bridge the candidate's clone-free getMaterial() to
    // getItem().getType() via the default answer, so one bench source measures both
    // sides' real pipelines (baseline sort keeps deriving the key per comparison).
    final var matcherConfig = Env.pin(mock(dev.dejvokep.boostedyaml.block.implementation.Section.class));
    lenient().when(plugin.getConfig().getSection("matcher.item")).thenReturn(matcherConfig);
    final var platform = Env.pin(Env.hotMock(com.ghostchu.quickshop.platform.Platform.class));
    lenient().when(platform.getItemShopId(any(org.bukkit.inventory.ItemStack.class))).thenReturn(null);
    lenient().when(plugin.platform()).thenReturn(platform);
    // construct before the stubbing opens: the matcher's constructor re-reads the plugin
    // config, a mock call that must not nest inside when(...) (same hazard TradeBench
    // documents for the layout provider)
    final var realMatcher = new com.ghostchu.quickshop.util.matcher.item.QuickShopItemMatcherImpl(plugin);
    lenient().when(plugin.getItemMatcher()).thenReturn(realMatcher);

    final org.bukkit.Material[] groupMaterials = {
            org.bukkit.Material.DIAMOND, org.bukkit.Material.IRON_INGOT, org.bukkit.Material.GOLD_INGOT,
            org.bukkit.Material.EMERALD, org.bukkit.Material.REDSTONE, org.bukkit.Material.LAPIS_LAZULI,
            org.bukkit.Material.COAL, org.bukkit.Material.STONE};
    final org.mockito.stubbing.Answer<Object> materialBridge = inv -> {
      if(inv.getMethod().getName().equals("getMaterial")) {
        return ((Shop)inv.getMock()).getItem().getType();
      }
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(inv);
    };
    final int groupCount = 120;
    final List<Shop> groupShops = new ArrayList<>(groupCount);
    for(int i = 0; i < groupCount; i++) {
      final long id = 10_000 + i;
      final org.bukkit.Material material = groupMaterials[i % groupMaterials.length];
      final Shop shop = Env.pin(mock(Shop.class,
                                     withSettings().stubOnly().defaultAnswer(materialBridge)));
      final var stack = Env.pin(Env.hotMock(org.bukkit.inventory.ItemStack.class));
      when(stack.getType()).thenReturn(material);
      when(stack.hasItemMeta()).thenReturn(false);
      when(stack.clone()).thenReturn(stack);
      when(stack.isSimilar(any(org.bukkit.inventory.ItemStack.class))).thenAnswer(inv -> {
        final var other = inv.getArgument(0, org.bukkit.inventory.ItemStack.class);
        return other != null && other.getType() == material && !other.hasItemMeta();
      });
      when(shop.getShopId()).thenReturn(id);
      when(shop.isUnlimited()).thenReturn(false);
      when(shop.isSelling()).thenReturn(true);
      when(shop.isBuying()).thenReturn(false);
      lenient().when(shop.getPrice()).thenReturn(10.0d);
      when(shop.getItem()).thenReturn(stack);
      lenient().when(shopManager.queryShopInventoryCacheInDatabase(any(Shop.class)))
              .thenAnswer(inv->CompletableFuture.completedFuture(
                      new SimpleShopInventoryCountCache(64, 64, true)));
      groupShops.add(shop);
    }

    harness.bench("menu/groupAndNameSort", ctx -> {
      ctx.index++;
      consume(MarketUtils.groupShopsByItem(groupShops));
      consume(MarketUtils.sortShops(groupShops, BrowseSortMode.NAME));
    });
  }

  private static void consume(final Object value) {

    com.ghostchu.quickshop.benchmark.BenchHarness.consume(value);
  }
}
