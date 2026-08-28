package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.common.util.CommonUtil;
import com.ghostchu.quickshop.database.DatabaseIOUtil;
import com.ghostchu.quickshop.database.SimpleDatabaseHelperV2;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.util.logger.Log;
import com.ghostchu.quickshop.util.performance.BatchBukkitExecutor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


public class ShopPurger {

  private final QuickShop plugin;
  // atomic guard: the old check-then-act on a volatile boolean let two purge() calls both
  // pass the check, and any early exit before the final whenComplete left it stuck true
  // (purge dead until restart)
  private final AtomicBoolean executing = new AtomicBoolean(false);

  public ShopPurger(final QuickShop plugin) {

    this.plugin = plugin;
  }

  public void purge() {

    if(!plugin.getConfig().getBoolean("purge.enabled")) {
      plugin.logger().info("[Shop Purger] Purge not enabled!");
      return;
    }
    if(!executing.compareAndSet(false, true)) {
      plugin.logger().info("[Shop Purger] Another purge task still running!");
      return;
    }
    try {
      Util.asyncThreadRun(this::run);
    } catch(final Throwable t) {
      executing.set(false);
      throw t;
    }
  }

  private void run() {

    Util.ensureThread(true);
    try {
      final DatabaseIOUtil ioUtil = new DatabaseIOUtil((SimpleDatabaseHelperV2)plugin.getDatabaseHelper());
      if(!ioUtil.performBackup("shops-auto-purge")) {
        plugin.logger().warn("[Shop Purger] Purge progress declined due backup failure");
        return;
      }
      plugin.logger().info("[Shop Purger] Scanning and removing shops....");
      final List<Shop> pendingRemovalShops = new ArrayList<>();
      final int days = plugin.getConfig().getInt("purge.days", 360);
      final boolean deleteBanned = plugin.getConfig().getBoolean("purge.banned");
      final boolean skipOp = plugin.getConfig().getBoolean("purge.skip-op");
      for(final Shop shop : plugin.getShopManager().getAllShops()) {
        try {
          final OfflinePlayer player = shop.getOwner().getUniqueIdIfRealPlayer().map(Bukkit::getOfflinePlayer).orElse(null);
          if(player == null) {
            // virtual/server-owned shop: skip it, do not abort the whole purge run
            continue;
          }
          if(!player.hasPlayedBefore()) {
            Log.debug("Shop " + shop + " detection skipped: Owner never played before.");
            continue;
          }
          final long lastPlayed = player.getLastPlayed();
          if(lastPlayed == 0) {
            continue;
          }
          if(player.isOnline()) {
            continue;
          }
          if(player.isOp() && skipOp) {
            continue;
          }
          boolean markDeletion = player.isBanned() && deleteBanned;
          final long noOfDaysBetween = ChronoUnit.DAYS.between(CommonUtil.getDateTimeFromTimestamp(lastPlayed), CommonUtil.getDateTimeFromTimestamp(System.currentTimeMillis()));
          if(noOfDaysBetween > days) {
            markDeletion = true;
          }
          if(!markDeletion) {
            continue;
          }
          pendingRemovalShops.add(shop);
        } catch(final Exception e) {
          plugin.logger().warn("Failed to purge shop " + shop.getShopId(), e);
        }
      }

      final AtomicInteger purgedCount = new AtomicInteger(0);
      final List<CompletableFuture<Void>> deletionFutures = new CopyOnWriteArrayList<>();

      for(final Shop shop : pendingRemovalShops) {
        final CompletableFuture<Void> future = QuickShop.folia().getScheduler().runAtLocation(shop.bukkitLocation(), (loc) -> {
          try {
            plugin.getShopManager().deleteShop(shop);
            purgedCount.incrementAndGet();
          } catch(final Exception e) {
            plugin.logger().warn("Failed to delete shop " + shop.getShopId(), e);
          }
        });
        deletionFutures.add(future);
      }

      final long startTime = Instant.now().toEpochMilli();
      CompletableFuture.allOf(deletionFutures.toArray(new CompletableFuture[0]))
              .whenComplete((unused, throwable) -> {
                final long usedTime = Instant.now().toEpochMilli() - startTime;
                plugin.logger().info("[Shop Purger] Total shop {} has been purged, used {}ms",
                                     purgedCount.get(),
                                     usedTime);
                executing.set(false);
              });
    } catch(final Throwable t) {
      // any failure before the whenComplete must release the guard or purge dies until restart
      executing.set(false);
      plugin.logger().warn("[Shop Purger] Purge run failed.", t);
    }
  }
}
