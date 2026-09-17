package com.ghostchu.quickshop.menu.browse;
/*
 * QuickShop-Hikari
 * Copyright (C) 2024 Daniel "creatorfromhell" Vidmar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.ItemMatcher;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.cache.ShopInventoryCountCache;
import com.ghostchu.quickshop.common.util.CommonUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MarketUtils - Utility class for market/browse operations Handles grouping shops by item,
 * filtering, sorting, and searching
 *
 * @author creatorfromhell
 * @since 6.2.0.8
 */
public final class MarketUtils {

  private MarketUtils() {
    // Utility class
  }

  /**
   * How long a preloaded inventory-cache row stays fresh for menu renders. Reads inside
   * the window are instant map lookups; the database is only re-queried (asynchronously,
   * after a pending-write flush) once a row goes stale.
   */
  private static final long INVENTORY_SNAPSHOT_TTL_MS = 3_000L;
  /**
   * Bounded wait for the very first load of a shop's row (e.g. the first menu open after
   * boot): renders may block this long once, never longer — a degraded database degrades
   * to a partial snapshot instead of stalling the render thread.
   */
  private static final long INVENTORY_SNAPSHOT_COLD_WAIT_MS = 250L;

  private static final java.util.concurrent.ConcurrentHashMap<Long, SnapshotEntry> INVENTORY_SNAPSHOTS = new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ConcurrentHashMap<Long, CompletableFuture<Void>> INVENTORY_INFLIGHT = new java.util.concurrent.ConcurrentHashMap<>();

  private static final class SnapshotEntry {

    private final @Nullable ShopInventoryCountCache cache;
    private final long fetchedAt;

    private SnapshotEntry(@Nullable final ShopInventoryCountCache cache, final long fetchedAt) {

      this.cache = cache;
      this.fetchedAt = fetchedAt;
    }
  }

  /**
   * Inventory-count snapshot for menu renders. Menu pages draw on the main thread, so the
   * old flush+SELECT round-trip was a main-thread join on every open/flip/sort; rows are
   * now served from a short-TTL memory snapshot, refreshed asynchronously (with the same
   * pending-write flush first). A cold row (first render after boot) is awaited once for
   * at most {@link #INVENTORY_SNAPSHOT_COLD_WAIT_MS} so the first numbers are real.
   *
   * @param shops the shops whose cache rows are needed
   *
   * @return shopId -&gt; cache; shops without a cache row are absent (reads fall back to 0)
   */
  @NotNull
  public static Map<Long, ShopInventoryCountCache> loadInventoryCaches(@NotNull final Collection<Shop> shops) {

    if(shops.isEmpty()) {
      return Map.of();
    }
    try {
      final long now = System.currentTimeMillis();
      final List<Long> ids = shops.stream().map(Shop::getShopId).distinct().toList();
      final List<Long> stale = new ArrayList<>();
      for(final Long id : ids) {
        final SnapshotEntry entry = INVENTORY_SNAPSHOTS.get(id);
        if((entry == null || now - entry.fetchedAt > INVENTORY_SNAPSHOT_TTL_MS)
           && !INVENTORY_INFLIGHT.containsKey(id)) {
          stale.add(id);
        }
      }
      if(!stale.isEmpty()) {
        startSnapshotLoad(stale);
      }
      final boolean coldMiss = ids.stream().anyMatch(id->INVENTORY_SNAPSHOTS.get(id) == null);
      if(coldMiss) {
        try {
          CompletableFuture.allOf(ids.stream()
                                        .map(INVENTORY_INFLIGHT::get)
                                        .filter(java.util.Objects::nonNull)
                                        .toArray(CompletableFuture[]::new))
                  .get(INVENTORY_SNAPSHOT_COLD_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch(final Exception ignored) {
          // timeout or failed load: a partial snapshot beats stalling the render thread
        }
      }
      return collectSnapshot(ids);
    } catch(final Exception e) {
      // same fallback semantics as the per-shop readers: no data beats broken menus
      return Map.of();
    }
  }

  private static void startSnapshotLoad(@NotNull final List<Long> ids) {

    final CompletableFuture<Void> mark = new CompletableFuture<>();
    for(final Long id : ids) {
      INVENTORY_INFLIGHT.putIfAbsent(id, mark);
    }
    final var plugin = QuickShop.getInstance();
    final var batcher = plugin.getDbWriteBatcher();
    final CompletableFuture<Map<Long, ShopInventoryCountCache>> load = (batcher != null)
            ? batcher.flushInventoryCacheAsync()
                    .thenCompose(v->plugin.getDatabaseHelper().queryInventoryCaches(ids))
            : plugin.getDatabaseHelper().queryInventoryCaches(ids);
    load.whenComplete((rows, err)->{
      final long fetchedAt = System.currentTimeMillis();
      for(final Long id : ids) {
        if(err == null) {
          INVENTORY_SNAPSHOTS.put(id, new SnapshotEntry(rows.get(id), fetchedAt));
        } else {
          // negative-cache briefly (retry ~1s instead of a full TTL) so a broken
          // database doesn't turn every render into another query
          INVENTORY_SNAPSHOTS.put(id, new SnapshotEntry(null,
                                                        fetchedAt - INVENTORY_SNAPSHOT_TTL_MS + 1_000L));
        }
        INVENTORY_INFLIGHT.remove(id);
      }
    });
  }

  @NotNull
  private static Map<Long, ShopInventoryCountCache> collectSnapshot(@NotNull final List<Long> ids) {

    final Map<Long, ShopInventoryCountCache> result = new HashMap<>(ids.size());
    for(final Long id : ids) {
      final SnapshotEntry entry = INVENTORY_SNAPSHOTS.get(id);
      if(entry != null && entry.cache != null) {
        result.put(id, entry.cache);
      }
    }
    return result;
  }

  /**
   * Stock for a shop from a preloaded snapshot. Unlimited shops read -1; shops without a
   * cache row read 0 (identical to the per-shop path's uninitialized/error fallback).
   */
  public static int stockOf(@NotNull final Shop shop, @NotNull final Map<Long, ShopInventoryCountCache> snapshot) {

    if(shop.isUnlimited()) {
      return -1;
    }
    final ShopInventoryCountCache cache = snapshot.get(shop.getShopId());
    if(cache == null) {
      return 0;
    }
    final int stock = cache.getStock();
    return stock >= 0? stock : 0;
  }

  /**
   * Space for a shop from a preloaded snapshot; same fallback semantics as
   * {@link #stockOf(Shop, Map)}.
   */
  public static int spaceOf(@NotNull final Shop shop, @NotNull final Map<Long, ShopInventoryCountCache> snapshot) {

    if(shop.isUnlimited()) {
      return -1;
    }
    final ShopInventoryCountCache cache = snapshot.get(shop.getShopId());
    if(cache == null) {
      return 0;
    }
    final int space = cache.getSpace();
    return space >= 0? space : 0;
  }

  /**
   * Group shops by item type using the ItemMatcher
   *
   * @param shops List of shops to group
   *
   * @return List of MarketItemGroups
   */
  @NotNull
  public static List<MarketItemGroup> groupShopsByItem(@NotNull final List<Shop> shops) {

    final List<MarketItemGroup> groups = new ArrayList<>();
    final Map<Material, List<MarketItemGroup>> groupsByMat = new EnumMap<>(Material.class);
    final ItemMatcher matcher = QuickShop.getInstance().getItemMatcher();
    // the builtin matcher treats both arguments as read-only (the same trust
    // full-inventory scans and ContainerShop#matches pass live stacks under), so its
    // container-shop probes read the live prototype; third-party matchers and
    // third-party shop implementations keep the historical per-shop defensive copy
    final boolean liveProbes = matcher instanceof com.ghostchu.quickshop.util.matcher.item.QuickShopItemMatcherImpl;

    for(final Shop shop : shops) {
      final ItemStack shopItem = liveProbes && shop instanceof final com.ghostchu.quickshop.shop.ContainerShop containerShop
              ? containerShop.getItemDirect() : shop.getItem();
      MarketItemGroup matchingGroup = null;
      List<MarketItemGroup> matGroups = groupsByMat.computeIfAbsent(shopItem.getType(), k->new ArrayList<>());
      // Find existing group that matches this shop's item; the package-private
      // representative read skips the defensive clone (matches() treats its arguments
      // as read-only — full-inventory scans already pass live stacks)
      for(final MarketItemGroup group : matGroups) {
        if(matcher.matches(group.getRepresentativeItemUncloned(), shopItem)) {
          matchingGroup = group;
          break;
        }
      }

      // Create new group if no match found; the constructor clones whatever it receives
      // into its own private representative, so handing it the live prototype costs the
      // one ownership copy per new group instead of one per shop plus one per group
      if(matchingGroup == null) {
        matchingGroup = new MarketItemGroup(shopItem);
        matGroups.add(matchingGroup);
        groups.add(matchingGroup);
      }

      matchingGroup.addShop(shop);
    }
    // grouping only: statistics need an inventory snapshot, computed by the snapshot-aware
    // caller — the legacy per-shop statistics path did one blocking (and main-thread
    // illegal) database query per shop
    return groups;
  }

  /**
   * Snapshot-aware variant of {@link #groupShopsByItem(List)}: group statistics are
   * computed from the preloaded cache map.
   */
  @NotNull
  public static List<MarketItemGroup> groupShopsByItem(@NotNull final List<Shop> shops,
                                                       @NotNull final Map<Long, ShopInventoryCountCache> snapshot) {

    final List<MarketItemGroup> groups = groupShopsByItem(shops);
    for(final MarketItemGroup group : groups) {
      group.calculateStatistics(snapshot);
    }
    return groups;
  }

  /**
   * Filter shops based on filter mode
   *
   * @param shops      List of shops to filter
   * @param filterMode The filter mode to apply
   *
   * @return Filtered list of shops
   */
  @NotNull
  public static List<Shop> filterShops(@NotNull final List<Shop> shops,
                                       @NotNull final BrowseFilterMode filterMode) {

    return switch(filterMode) {
      case ALL -> new ArrayList<>(shops);
      case BUYING -> shops.stream()
              .filter(Shop::isBuying)
              .toList();
      case SELLING -> shops.stream()
              .filter(Shop::isSelling)
              .toList();
    };
  }

  /**
   * Snapshot-aware variant of {@link #filterByStock(List, boolean)}: all reads come from
   * the preloaded map, no database access.
   */
  @NotNull
  public static List<Shop> filterByStock(@NotNull final List<Shop> shops, final boolean stockOnly,
                                         @NotNull final Map<Long, ShopInventoryCountCache> snapshot) {

    if(!stockOnly) {
      return new ArrayList<>(shops);
    }
    return shops.stream()
            .filter(shop->{
              if(shop.isUnlimited()) return true;
              if(shop.isSelling()) {
                return stockOf(shop, snapshot) > 0;
              } else {
                return spaceOf(shop, snapshot) > 0;
              }
            })
            .toList();
  }

  /**
   * Filter item groups based on filter mode
   *
   * @param groups     List of groups to filter
   * @param filterMode The filter mode to apply
   *
   * @return Filtered list of item groups
   */
  @NotNull
  public static List<MarketItemGroup> filterGroups(@NotNull final List<MarketItemGroup> groups,
                                                   @NotNull final BrowseFilterMode filterMode) {

    return switch(filterMode) {
      case ALL -> new ArrayList<>(groups);
      case BUYING -> groups.stream()
              .filter(MarketItemGroup::hasBuyingShops)
              .toList();
      case SELLING -> groups.stream()
              .filter(MarketItemGroup::hasSellingShops)
              .toList();
    };
  }

  /**
   * Snapshot-aware variant of {@link #filterGroupsByStock(List, boolean)}.
   */
  @NotNull
  public static List<MarketItemGroup> filterGroupsByStock(@NotNull final List<MarketItemGroup> groups,
                                                          final boolean stockOnly,
                                                          @NotNull final Map<Long, ShopInventoryCountCache> snapshot) {

    if(!stockOnly) {
      return new ArrayList<>(groups);
    }
    return groups.stream()
            .filter(group->group.getShops().stream().anyMatch(shop->{
              if(shop.isUnlimited()) return true;
              if(shop.isSelling()) {
                return stockOf(shop, snapshot) > 0;
              } else {
                return spaceOf(shop, snapshot) > 0;
              }
            }))
            .toList();
  }

  /**
   * Prettified material names, memoized over the finite {@link Material} domain: the
   * NAME sort and menu headers ask for the same strings on every render.
   */
  private static final Map<Material, String> PRETTIFIED_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

  @NotNull
  private static String prettifiedName(@NotNull final Material material) {

    return PRETTIFIED_NAMES.computeIfAbsent(material, mat->CommonUtil.prettifyText(mat.name()));
  }

  /**
   * Decorate-sort-undecorate for the NAME mode, mirroring {@link #sortShopsByStockDesc}:
   * the previous comparator rebuilt its key (a full defensive stack clone plus the
   * prettify string) on every comparison — TimSort evaluates it ~2·n·log n times. Keys
   * are read exactly once per shop here, through the clone-free {@code getMaterial()}.
   * Ordering is identical: the comparator only distinguishes keys and {@code List.sort}
   * is stable, so equal keys keep encounter order exactly like before.
   */
  private static void sortShopsByName(@NotNull final List<Shop> shops) {

    record NameKey(@NotNull Shop shop, @NotNull String name) {

    }
    final List<NameKey> decorated = new ArrayList<>(shops.size());
    for(final Shop shop : shops) {
      decorated.add(new NameKey(shop, prettifiedName(shop.getMaterial())));
    }
    decorated.sort(Comparator.comparing(NameKey::name));
    for(int i = 0; i < decorated.size(); i++) {
      shops.set(i, decorated.get(i).shop());
    }
  }

  /**
   * Decorate-sort-undecorate for the STOCK mode. The previous comparator re-read the
   * stock key on every comparison ({@code comparingInt(...).reversed()}), so TimSort
   * evaluated it ~2·n·log n times — each read going through {@code isUnlimited()} /
   * {@code getShopId()} and, on the legacy path, a blocking cache query per comparison.
   * Keys are read exactly once per shop here. Ordering is identical: the comparator only
   * distinguishes keys and {@code List.sort} is stable, so equal keys keep encounter
   * order exactly like the previous stable reversed comparison.
   */
  private static void sortShopsByStockDesc(@NotNull final List<Shop> shops,
                                           @NotNull final java.util.function.ToIntFunction<Shop> keyReader) {

    record StockKey(@NotNull Shop shop, int stock) {

    }
    final List<StockKey> decorated = new ArrayList<>(shops.size());
    for(final Shop shop : shops) {
      decorated.add(new StockKey(shop, keyReader.applyAsInt(shop)));
    }
    decorated.sort((a, b)->Integer.compare(b.stock(), a.stock()));
    for(int i = 0; i < decorated.size(); i++) {
      shops.set(i, decorated.get(i).shop());
    }
  }

  /**
   * Snapshot-aware variant of {@link #sortShops(List, BrowseSortMode)}: the STOCK mode
   * sorts on map lookups instead of a blocking cache query per comparison.
   */
  @NotNull
  public static List<Shop> sortShops(@NotNull final List<Shop> shops,
                                     @NotNull final BrowseSortMode sortMode,
                                     @NotNull final Map<Long, ShopInventoryCountCache> snapshot) {

    final List<Shop> sorted = new ArrayList<>(shops);

    switch(sortMode) {
      case PRICE_ASC -> sorted.sort((a, b) -> a.comparePrice(b.price(), false));
      case PRICE_DESC -> sorted.sort((a, b) -> a.comparePrice(b.price(), true));
      case STOCK -> sortShopsByStockDesc(sorted, shop->stockOf(shop, snapshot));
      case NAME -> sortShopsByName(sorted);
    }

    return sorted;
  }

  /**
   * Sort item groups based on sort mode
   *
   * @param groups   List of groups to sort
   * @param sortMode The sort mode to apply
   *
   * @return Sorted list of groups
   */
  @NotNull
  public static List<MarketItemGroup> sortGroups(@NotNull final List<MarketItemGroup> groups,
                                                 @NotNull final BrowseSortMode sortMode) {

    final List<MarketItemGroup> sorted = new ArrayList<>(groups);

    switch(sortMode) {
      case PRICE_ASC -> sorted.sort(Comparator.comparingDouble(group->{
        // Use selling price if available, otherwise buying price
        if(group.hasSellingShops()) {
          return group.getSellingMinPrice();
        }
        return group.getBuyingMinPrice();
      }));
      case PRICE_DESC -> sorted.sort(Comparator.comparingDouble((final MarketItemGroup group)->{
        if(group.hasSellingShops()) {
          return group.getSellingMaxPrice();
        }
        return group.getBuyingMaxPrice();
      }).reversed());
      case STOCK ->
              sorted.sort(Comparator.comparingInt(MarketItemGroup::getSellingTotalStock).reversed());
      case NAME -> sorted.sort(Comparator.comparing(MarketItemGroup::getItemDisplayName));
    }

    return sorted;
  }

  /**
   * Search shops by item name
   *
   * @param shops       List of shops to search
   * @param searchQuery The search query (item name)
   *
   * @return List of shops matching the search query
   */
  @NotNull
  public static List<Shop> searchShops(@NotNull final List<Shop> shops,
                                       @Nullable final String searchQuery) {

    if(searchQuery == null || searchQuery.trim().isEmpty()) {
      return new ArrayList<>(shops);
    }

    final String query = searchQuery.toLowerCase(Locale.ROOT).trim();

    // matchesSearch only reads (type, hasItemMeta, and getItemMeta() — which hands out
    // its own copy per the Bukkit contract), so container shops probe the live
    // prototype; third-party shop implementations keep their defensive getItem()
    return shops.stream()
            .filter(shop->matchesSearch(shop instanceof final com.ghostchu.quickshop.shop.ContainerShop containerShop
                                                ? containerShop.getItemDirect() : shop.getItem(), query))
            .toList();
  }

  /**
   * Search item groups by item name
   *
   * @param groups      List of groups to search
   * @param searchQuery The search query (item name)
   *
   * @return List of groups matching the search query
   */
  @NotNull
  public static List<MarketItemGroup> searchGroups(@NotNull final List<MarketItemGroup> groups,
                                                   @Nullable final String searchQuery) {

    if(searchQuery == null || searchQuery.trim().isEmpty()) {
      return new ArrayList<>(groups);
    }

    final String query = searchQuery.toLowerCase(Locale.ROOT).trim();

    // read-only probe: the uncloned representative feeds matchesSearch directly
    // (same-package read contract as groupShopsByItem's probes)
    return groups.stream()
            .filter(group->matchesSearch(group.getRepresentativeItemUncloned(), query))
            .toList();
  }

  /**
   * Check if an item matches a search query
   *
   * @param item  The item to check
   * @param query The search query (lowercase)
   *
   * @return true if the item matches
   */
  private static boolean matchesSearch(@NotNull final ItemStack item, @NotNull final String query) {
    // Check material name
    final String materialName = item.getType().name().toLowerCase(Locale.ROOT).replace("_", " ");
    if(materialName.contains(query)) {
      return true;
    }

    // Check prettified name
    final String prettyName = CommonUtil.prettifyText(item.getType().name()).toLowerCase(Locale.ROOT);
    if(prettyName.contains(query)) {
      return true;
    }

    // Check custom display name if present; one meta read for both the flag and the
    // name (getItemMeta hands out a fresh copy per call in production); a null meta
    // here would only mean "no custom name", same as hasItemMeta() being false
    if(item.hasItemMeta()) {
      final org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
      if(meta != null && meta.hasDisplayName()) {
        return meta.getDisplayName().toLowerCase(Locale.ROOT).contains(query);
      }
    }

    return false;
  }

  /**
   * Apply all filters, search, and sorting to shops. Every stock/space read comes from
   * the preloaded map, zero database access.
   */
  @NotNull
  public static List<Shop> processShops(@NotNull final List<Shop> shops,
                                        @NotNull final BrowseFilterMode filterMode,
                                        @NotNull final BrowseSortMode sortMode,
                                        @Nullable final String searchQuery,
                                        final boolean stockOnly,
                                        @NotNull final Map<Long, ShopInventoryCountCache> snapshot) {

    List<Shop> result = filterShops(shops, filterMode);
    result = filterByStock(result, stockOnly, snapshot);
    result = searchShops(result, searchQuery);
    result = sortShops(result, sortMode, snapshot);
    return result;
  }

  /**
   * Apply all filters, search, and sorting to item groups; group statistics come from the
   * preloaded inventory snapshot.
   */
  @NotNull
  public static List<MarketItemGroup> processGroups(@NotNull final List<Shop> shops,
                                                    @NotNull final BrowseFilterMode filterMode,
                                                    @NotNull final BrowseSortMode sortMode,
                                                    @Nullable final String searchQuery,
                                                    final boolean stockOnly,
                                                    @NotNull final Map<Long, ShopInventoryCountCache> snapshot) {
    List<Shop> filteredShops = filterShops(shops, filterMode);
    filteredShops = filterByStock(filteredShops, stockOnly, snapshot);
    filteredShops = searchShops(filteredShops, searchQuery);

    List<MarketItemGroup> groups = groupShopsByItem(filteredShops, snapshot);
    groups = sortGroups(groups, sortMode);

    return groups;
  }
}
