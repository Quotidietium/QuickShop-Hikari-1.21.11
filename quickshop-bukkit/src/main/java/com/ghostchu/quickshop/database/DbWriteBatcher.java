package com.ghostchu.quickshop.database;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.util.BatchingQueue;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Coalesces the remaining per-trade database writes: qs_external_cache row updates
 * (InternalListener's inventory-calc listener, one per value change) and qs_messages
 * offline-message inserts (owner-offline trade notifications). Same batching contract
 * as {@link com.ghostchu.quickshop.metric.MetricBatcher}: items land within one flush
 * window; readers chain on the flush future for exact freshness; shutdown drains
 * synchronously. Pending cache updates for the same shop collapse to the latest one.
 */
public class DbWriteBatcher {

  /** Inventory cache row write (last-write-wins per shop). */
  record ShopCacheWrite(long shopId, int space, int stock) {

  }

  /** Offline message append. */
  record OfflineMessageWrite(UUID receiver, String message, long time) {

  }

  /** Flush cadence in ticks (10s), matching MetricBatcher. */
  public static final long FLUSH_INTERVAL_TICKS = 20L * 10L;
  /** Immediate flush threshold per queue. */
  public static final int FLUSH_THRESHOLD = 256;

  private final BatchingQueue<ShopCacheWrite> cacheQueue;
  private final BatchingQueue<OfflineMessageWrite> messageQueue;

  public DbWriteBatcher(@NotNull final QuickShop plugin, @NotNull final SimpleDatabaseHelperV2 helper) {

    this.cacheQueue = new BatchingQueue<>(plugin, "external-inventory-cache", FLUSH_THRESHOLD,
            batch->helper.updateExternalInventoryProfileCaches(toCacheRows(batch)),
            write->write.shopId());
    this.messageQueue = new BatchingQueue<>(plugin, "offline-messages", FLUSH_THRESHOLD,
            batch->helper.saveOfflineTransactionMessages(toMessageRows(batch)),
            null);
  }

  private static List<SimpleDatabaseHelperV2.ShopCacheRow> toCacheRows(final List<ShopCacheWrite> writes) {

    return writes.stream().map(w->new SimpleDatabaseHelperV2.ShopCacheRow(w.shopId(), w.space(), w.stock())).toList();
  }

  private static List<SimpleDatabaseHelperV2.OfflineMessageRow> toMessageRows(final List<OfflineMessageWrite> writes) {

    return writes.stream().map(w->new SimpleDatabaseHelperV2.OfflineMessageRow(w.receiver(), w.message(), w.time())).toList();
  }

  public void offerInventoryCache(final long shopId, final int space, final int stock) {

    cacheQueue.offer(new ShopCacheWrite(shopId, space, stock));
  }

  public void offerOfflineMessage(@NotNull final UUID receiver, @NotNull final String message, final long time) {

    messageQueue.offer(new OfflineMessageWrite(receiver, message, time));
  }

  /** Inventory-cache readers chain here for exactly-fresh rows. */
  public @NotNull CompletableFuture<Void> flushInventoryCacheAsync() {

    return cacheQueue.flushAsync();
  }

  /** Offline-message readers chain here for exactly-fresh rows. */
  public @NotNull CompletableFuture<Void> flushMessagesAsync() {

    return messageQueue.flushAsync();
  }

  public void start() {

    cacheQueue.start(FLUSH_INTERVAL_TICKS);
    messageQueue.start(FLUSH_INTERVAL_TICKS);
  }

  /** Shutdown drain for both queues. */
  public void flushSync(final int timeoutSeconds) {

    cacheQueue.flushSync(timeoutSeconds);
    messageQueue.flushSync(timeoutSeconds);
  }
}
