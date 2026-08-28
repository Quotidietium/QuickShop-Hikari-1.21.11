package com.ghostchu.quickshop.common.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class QuickExecutor {

  private static ExecutorService HIKARICP_EXECUTOR;
  private static ExecutorService SHOP_HISTORY_QUERY_EXECUTOR;
  private static ExecutorService SHOP_SAVE_EXECUTOR = Executors.newWorkStealingPool(2);
  private static ExecutorService COMMON_EXECUTOR = Executors.newCachedThreadPool();
  private static ExecutorService PRIMARY_PROFILE_IO_EXECUTOR = Executors.newWorkStealingPool(16);
  private static ExecutorService SECONDARY_PROFILE_IO_EXECUTOR = Executors.newWorkStealingPool(2);

  static {
    HIKARICP_EXECUTOR = provideHikariCPExecutor();
    SHOP_HISTORY_QUERY_EXECUTOR = provideShopHistoryQueryExecutor();
  }

  private QuickExecutor() {


  }

  public static ExecutorService provideShopHistoryQueryExecutor() {

    return new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
  }

  public static ExecutorService provideHikariCPExecutor() {

    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("QuickShop-Database-Worker-", 0).factory());
  }

  public static ExecutorService getCommonExecutor() {

    return COMMON_EXECUTOR;
  }

  public static void setCommonExecutor(final ExecutorService commonExecutor) {

    COMMON_EXECUTOR = commonExecutor;
  }

  public static ExecutorService getHikaricpExecutor() {

    return HIKARICP_EXECUTOR;
  }

  public static void setHikaricpExecutor(final ExecutorService hikaricpExecutor) {

    HIKARICP_EXECUTOR = hikaricpExecutor;
  }

  public static ExecutorService getShopSaveExecutor() {

    return SHOP_SAVE_EXECUTOR;
  }

  public static void setShopSaveExecutor(final ExecutorService shopSaveExecutor) {

    SHOP_SAVE_EXECUTOR = shopSaveExecutor;
  }

  public static ExecutorService getPrimaryProfileIoExecutor() {

    return PRIMARY_PROFILE_IO_EXECUTOR;
  }

  public static void setPrimaryProfileIoExecutor(final ExecutorService primaryProfileIoExecutor) {

    PRIMARY_PROFILE_IO_EXECUTOR = primaryProfileIoExecutor;
  }

  public static ExecutorService getSecondaryProfileIoExecutor() {

    return SECONDARY_PROFILE_IO_EXECUTOR;
  }

  public static void setSecondaryProfileIoExecutor(final ExecutorService secondaryProfileIoExecutor) {

    SECONDARY_PROFILE_IO_EXECUTOR = secondaryProfileIoExecutor;
  }

  public static ExecutorService getShopHistoryQueryExecutor() {

    return SHOP_HISTORY_QUERY_EXECUTOR;
  }

  public static void setShopHistoryQueryExecutor(final ExecutorService shopHistoryQueryExecutor) {

    SHOP_HISTORY_QUERY_EXECUTOR = shopHistoryQueryExecutor;
  }

  /**
   * Shuts every static pool down (plugin disable) and immediately re-arms fresh pools:
   * without this, surviving worker threads pin the plugin classloader through hot reloads
   * (PlugMan-style disable/enable), and re-arming keeps a same-classloader re-enable
   * working. Call only after EasySQL has shut down - the SQL layer runs on
   * HIKARICP_EXECUTOR.
   */
  public static void shutdownAll() {

    shutdownQuietly(HIKARICP_EXECUTOR);
    shutdownQuietly(SHOP_HISTORY_QUERY_EXECUTOR);
    shutdownQuietly(SHOP_SAVE_EXECUTOR);
    shutdownQuietly(COMMON_EXECUTOR);
    shutdownQuietly(PRIMARY_PROFILE_IO_EXECUTOR);
    shutdownQuietly(SECONDARY_PROFILE_IO_EXECUTOR);
    HIKARICP_EXECUTOR = provideHikariCPExecutor();
    SHOP_HISTORY_QUERY_EXECUTOR = provideShopHistoryQueryExecutor();
    SHOP_SAVE_EXECUTOR = Executors.newWorkStealingPool(2);
    COMMON_EXECUTOR = Executors.newCachedThreadPool();
    PRIMARY_PROFILE_IO_EXECUTOR = Executors.newWorkStealingPool(16);
    SECONDARY_PROFILE_IO_EXECUTOR = Executors.newWorkStealingPool(2);
  }

  private static void shutdownQuietly(final ExecutorService executor) {

    if(executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if(!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }
}
