package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.QuickShop;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Coalesces per-trade database writes into periodic batch flushes. Producers offer items
 * (a concurrent-queue add); a timer — or the size threshold — drains the queue into the
 * supplied flusher. An optional dedupe key collapses pending items for the same entity to
 * the latest one ("last write wins"), so N updates to one shop within a window become a
 * single row write. Flushes only ever split the queue into several batches; items are
 * never duplicated or dropped by the queue itself.
 */
public class BatchingQueue<T> {

  /**
   * Re-queue ceiling after a failed flush. Without it a long DB outage turns the queue
   * into unbounded memory growth; at the ceiling the oldest-lossy option is taken —
   * drop with a loud error log — instead of an OOM that takes the whole server down.
   */
  private static final int MAX_PENDING = 10_000;

  private final QuickShop plugin;
  private final String name;
  private final int threshold;
  private final Function<List<T>, CompletableFuture<?>> flusher;
  @Nullable
  private final Function<T, Object> dedupeKey;

  private final ConcurrentLinkedQueue<T> pending = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();

  public BatchingQueue(@NotNull final QuickShop plugin, @NotNull final String name, final int threshold,
                       @NotNull final Function<List<T>, CompletableFuture<?>> flusher,
                       @Nullable final Function<T, Object> dedupeKey) {

    this.plugin = plugin;
    this.name = name;
    this.threshold = threshold;
    this.flusher = flusher;
    this.dedupeKey = dedupeKey;
  }

  public void offer(@NotNull final T item) {

    this.pending.add(item);
    if(this.size.incrementAndGet() >= threshold) {
      flushAsync();
    }
  }

  /**
   * @return future completing when the drained batch has been written (completed future
   *         when nothing was pending) — readers can chain on it for exact freshness
   */
  public @NotNull CompletableFuture<Void> flushAsync() {

    if(pending.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    final List<T> drained = drain();
    if(drained.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    size.addAndGet(-drained.size());
    final CompletableFuture<?> flushed;
    try {
      flushed = flusher.apply(drained);
    } catch(final Exception syncError) {
      // the flusher may also throw synchronously (e.g. statement build failures)
      reoffer(drained);
      return CompletableFuture.failedFuture(syncError);
    }
    return flushed
            .thenApply(v->(Void)null)
            .whenComplete((v, err)->{
              if(err != null) {
                reoffer(drained);
              }
            });
  }

  /**
   * Puts a failed batch back for the next timer cycle without re-triggering the size
   * threshold (that would spin retry loops while the database is down).
   */
  private void reoffer(final List<T> drained) {

    int requeued = 0;
    for(final T item : drained) {
      if(size.get() >= MAX_PENDING) {
        final int dropped = drained.size() - requeued;
        plugin.logger().error("Batching queue '" + name + "' flush failed and the re-queue ceiling (" + MAX_PENDING
                                      + ") is reached; dropping " + dropped + " items to protect server memory.");
        return;
      }
      pending.add(item);
      size.incrementAndGet();
      requeued++;
    }
    plugin.logger().warn("Batching queue '" + name + "' flush failed; re-queued " + requeued
                                 + " items for the next flush cycle.");
  }

  private List<T> drain() {

    final List<T> raw = new ArrayList<>();
    T item;
    while((item = pending.poll()) != null) {
      raw.add(item);
    }
    if(dedupeKey == null || raw.size() <= 1) {
      return raw;
    }
    final LinkedHashMap<Object, T> collapsed = new LinkedHashMap<>();
    for(final T t : raw) {
      collapsed.put(dedupeKey.apply(t), t);
    }
    return List.copyOf(collapsed.values());
  }

  /** Starts the periodic flush timer (ticks). */
  public void start(final long flushTicks) {

    QuickShop.folia().getScheduler().runTimerAsync(this::flushAsync, flushTicks, flushTicks);
  }

  /** Shutdown drain: blocks until the pending batch lands or times out. */
  public void flushSync(final int timeoutSeconds) {

    try {
      flushAsync().get(timeoutSeconds, TimeUnit.SECONDS);
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch(final Exception e) {
      plugin.logger().warn("Batching queue '" + name + "' shutdown flush failed: " + e.getMessage());
    }
  }
}
