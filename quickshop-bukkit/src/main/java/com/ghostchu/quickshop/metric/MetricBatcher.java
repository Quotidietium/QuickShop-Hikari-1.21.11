package com.ghostchu.quickshop.metric;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.database.ShopMetricRecord;
import com.ghostchu.quickshop.util.logger.Log;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coalesces per-trade metric records into periodic JDBC batch inserts. Every successful
 * purchase currently costs one qs_log_purchase INSERT round-trip on a virtual thread;
 * busy servers pay hundreds of single-row statements per second for what are diagnostic
 * logs. Records queue here and flush as one batched statement every {@link #FLUSH_INTERVAL_TICKS}
 * (or immediately once {@link #FLUSH_THRESHOLD} records pile up), mirroring how LogWatcher
 * already batches qs.log writes. Readers that need up-to-date counts (metric queries)
 * trigger {@link #flushAsync()} first; shutdown drains the queue synchronously before the
 * connection pool closes.
 */
public class MetricBatcher {

  /** Flush cadence in ticks (10s) — matches LogWatcher's batching granularity class. */
  public static final long FLUSH_INTERVAL_TICKS = 20L * 10L;
  /** Immediate flush threshold — bounds queue memory and worst-case staleness under bursts. */
  public static final int FLUSH_THRESHOLD = 256;

  private final QuickShop plugin;
  private final ConcurrentLinkedQueue<ShopMetricRecord> pending = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();
  private volatile boolean started;

  public MetricBatcher(@NotNull final QuickShop plugin) {

    this.plugin = plugin;
  }

  public void offer(@NotNull final ShopMetricRecord record) {

    this.pending.add(record);
    if(this.size.incrementAndGet() >= FLUSH_THRESHOLD) {
      flushAsync();
    }
  }

  /** Schedules the periodic flush timer; safe to call once at enable. */
  public void start() {

    if(started) {
      return;
    }
    started = true;
    QuickShop.folia().getScheduler().runTimerAsync(this::flushAsync, FLUSH_INTERVAL_TICKS, FLUSH_INTERVAL_TICKS);
  }

  /**
   * Drains the queue into one batched insert. Multiple concurrent flushes merely split
   * the queue into several batches — never duplicate or lose records.
   *
   * @return future completing with the number of rows written
   */
  public @NotNull CompletableFuture<Integer> flushAsync() {

    if(pending.isEmpty()) {
      return CompletableFuture.completedFuture(0);
    }
    final List<ShopMetricRecord> drained = new ArrayList<>();
    ShopMetricRecord record;
    while((record = pending.poll()) != null) {
      drained.add(record);
    }
    if(drained.isEmpty()) {
      return CompletableFuture.completedFuture(0);
    }
    size.addAndGet(-drained.size());
    Log.debug("Flushing " + drained.size() + " metric records into database.");
    return plugin.getDatabaseHelper().insertMetricRecords(drained)
            .whenComplete((lines, err)->{
              if(err != null) {
                plugin.logger().warn("Failed to flush " + drained.size() + " metric records: " + err.getMessage());
              }
            });
  }

  /** Shutdown drain: blocks until the pending batch lands or times out. */
  public void flushSync(final int timeoutSeconds) {

    try {
      flushAsync().get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch(final Exception e) {
      plugin.logger().warn("Metric batch shutdown flush failed: " + e.getMessage());
    }
  }
}
