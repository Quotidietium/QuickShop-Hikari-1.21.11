package com.ghostchu.quickshop.papi;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.common.util.JsonUtil;
import com.ghostchu.quickshop.util.performance.PerfMonitor;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.Reloadable;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

public class PAPICache implements Reloadable {

  /** Guard for the soft-cache map: placeholder keys are server-defined, this is anti-leak only. */
  private static final int SOFT_CACHE_MAX_ENTRIES = 4096;

  private QuickShop plugin;
  private long expiredTime;
  private Cache<String, Optional<String>> performCaches;
  private final ConcurrentHashMap<String, SoftEntry> softCaches = new ConcurrentHashMap<>();

  private static final class SoftEntry {

    private volatile String value;
    private volatile long loadedAt;
    private final AtomicBoolean refreshing = new AtomicBoolean();
  }

  public PAPICache() {

    init();
    QuickShop.getInstance().getReloadManager().register(this);
  }

  private void init() {

    this.plugin = QuickShop.getInstance();
    this.expiredTime = plugin.getConfig().getLong("plugin.PlaceHolderAPI.cache", 900000L);
    this.performCaches = CacheBuilder.newBuilder()
            .expireAfterWrite(expiredTime, java.util.concurrent.TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
    this.softCaches.clear();
  }

  /**
   * Stale-while-revalidate variant of {@link #getCached(UUID, String, BiFunction)} for
   * handlers whose loader performs database queries: the call never runs the loader on the
   * calling (usually main) thread. A fresh value returns immediately; a stale value returns
   * immediately while a refresh runs on the common executor; a miss schedules the load and
   * resolves as empty until it lands (PlaceholderAPI re-asks on every render, so the value
   * appears seconds later without ever blocking a tick).
   */
  @NotNull
  public Optional<String> getCachedSoft(@NotNull final UUID player, @NotNull final String args, @NotNull final BiFunction<UUID, String, String> loader) {

    if(softCaches.size() > SOFT_CACHE_MAX_ENTRIES) {
      softCaches.clear();
    }
    final SoftEntry entry = softCaches.computeIfAbsent(compileUniqueKey(player, args), k->new SoftEntry());
    if(entry.value != null && System.currentTimeMillis() - entry.loadedAt < expiredTime) {
      return Optional.ofNullable(entry.value);
    }
    if(entry.refreshing.compareAndSet(false, true)) {
      CompletableFuture.runAsync(()->{
        String loaded = null;
        try {
          loaded = loader.apply(player, args);
        } catch(final Throwable t) {
          plugin.logger().warn("Failed to refresh PAPI value for " + player + " " + args, t);
        } finally {
          entry.value = loaded;
          entry.loadedAt = System.currentTimeMillis();
          entry.refreshing.set(false);
        }
      }, com.ghostchu.quickshop.common.util.QuickExecutor.getCommonExecutor());
    }
    return Optional.ofNullable(entry.value);
  }

  @NotNull
  public Optional<String> getCached(@NotNull final UUID player, @NotNull final String args, @NotNull final BiFunction<UUID, String, String> loader) {

    try(final PerfMonitor ignored = new PerfMonitor("PlaceHolder API Handling")) {
      return performCaches.get(compileUniqueKey(player, args), ()->Optional.ofNullable(loader.apply(player, args)));
    } catch(final ExecutionException ex) {
      plugin.logger().warn("Failed to get cache for " + player + " " + args, ex);
      return Optional.empty();
    }
  }

  @NotNull
  private String compileUniqueKey(@NotNull final UUID player, @NotNull final String queryString) {

    return JsonUtil.standard().toJson(new CompiledUniqueKey(player, queryString));
  }

  private long getShopsInWorld(@NotNull final String world, final boolean loadedOnly) {

    return plugin.getShopManager().getAllShops().stream()
            .filter(shop->shop.bukkitLocation().getWorld() != null)
            .filter(shop->shop.bukkitLocation().getWorld().getName().equals(world))
            .filter(shop->!loadedOnly || shop.isLoaded())
            .count();
  }

  private long getLoadedPlayerShops(@NotNull final UUID uuid) {

    return plugin.getShopManager().getLoadedShops().stream().filter(shop->{
      final UUID souid = shop.getOwner().getUniqueId();
      if(souid == null) {
        return false;
      }
      return souid.equals(uuid);
    }).count();
  }

  private long getLoadedPlayerShops(@NotNull final String name) {

    return plugin.getShopManager().getLoadedShops().stream().filter(shop->{
      final String sousrname = shop.getOwner().getUsername();
      if(sousrname == null) {
        return false;
      }
      return name.equals(sousrname);
    }).count();
  }

  private long getPlayerShopsInventoryUnavailable(@NotNull final UUID uuid) {

    return plugin.getShopManager().getAllShops(uuid).stream()
            .filter(Shop::inventoryAvailable)
            .count();
  }

  public long getExpiredTime() {

    return expiredTime;
  }

  public @NotNull CacheStats getStats() {

    return performCaches.stats();
  }

  @Nullable
  public String readCache(@NotNull final UUID player, @NotNull final String queryString) {

    final Optional<String> cache = performCaches.getIfPresent(compileUniqueKey(player, queryString));
    //noinspection OptionalAssignedToNull
    if(cache == null || cache.isEmpty()) {
      return null;
    }
    return cache.orElse(null);
  }

  @Override
  public ReloadResult reloadModule() throws Exception {

    init();
    return Reloadable.super.reloadModule();
  }

  public void writeCache(@NotNull final UUID player, @NotNull final String queryString, @NotNull final String queryValue) {

    performCaches.put(compileUniqueKey(player, queryString), Optional.of(queryValue));
  }

  @Data
  static class CompiledUniqueKey {

    private UUID player;
    private String queryString;

    public CompiledUniqueKey(final UUID player, final String queryString) {

      this.player = player;
      this.queryString = queryString;
    }
  }

}
