package com.ghostchu.quickshop.util.logger;

import com.ghostchu.quickshop.util.performance.PerfMonitor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the R33 log pipeline: lock-free ring semantics (order,
 * eviction window, type filters, concurrent appends) and lazy message records
 * (supplier not evaluated until first read, memoized afterwards, eager String
 * path byte-identical to the historical behavior).
 */
class LogRingAndLazyMessageTest {

  private static final String MARKER = LogRingAndLazyMessageTest.class.getSimpleName();

  private List<Log.Record> tail(final int n) {

    final List<Log.Record> all = Log.fetchLogs();
    return all.subList(Math.max(0, all.size() - n), all.size());
  }

  @Test
  void testSnapshotOrderAndEvictionWindow() {

    for(int i = 0; i < 5; i++) {
      Log.debug(MARKER + "-order-" + i);
    }
    final List<Log.Record> afterSmall = tail(5);
    for(int i = 0; i < 5; i++) {
      assertEquals(MARKER + "-order-" + i, afterSmall.get(i).getMessage(), "records must surface in write order");
    }

    // overflow the window: BUFFER_SIZE (2000 x types) + 10 fresh writes evict everything older
    for(int i = 0; i < 2000 * Log.Type.values().length + 10; i++) {
      Log.debug(MARKER + "-flood");
    }
    final List<Log.Record> flooded = Log.fetchLogs();
    assertTrue(flooded.size() <= 2000 * Log.Type.values().length, "ring must cap at the historical buffer size");
    assertTrue(flooded.stream().allMatch(r -> r.getMessage().equals(MARKER + "-flood")),
               "records older than the window must be evicted");
  }

  @Test
  void testTypeFiltersAndExclude() {

    Log.debug(MARKER + "-typed-debug");
    Log.transaction(MARKER + "-typed-txn");

    assertTrue(Log.fetchLogs(Log.Type.TRANSACTION).stream()
                   .anyMatch(r -> r.getMessage().equals(MARKER + "-typed-txn")),
               "type filter must find the transaction record");
    assertFalse(Log.fetchLogs(Log.Type.TRANSACTION).stream()
                    .anyMatch(r -> r.getMessage().equals(MARKER + "-typed-debug")),
                "type filter must not leak debug records");
    assertFalse(Log.fetchLogsExclude(Log.Type.DEBUG).stream()
                    .anyMatch(r -> r.getMessage().equals(MARKER + "-typed-debug")),
                "exclude filter must drop debug records");
    assertTrue(Log.fetchLogsExclude(Log.Type.DEBUG).stream()
                   .anyMatch(r -> r.getMessage().equals(MARKER + "-typed-txn")),
               "exclude filter must keep transaction records");
  }

  @Test
  void testLazySupplierEvaluatedOnceAndMemoized() {

    final AtomicInteger evaluations = new AtomicInteger();
    final Supplier<String> lazy = () -> {
      evaluations.incrementAndGet();
      return MARKER + "-lazy-value";
    };
    Log.transaction(lazy);
    assertEquals(0, evaluations.get(), "recording must not evaluate the message supplier");

    final List<Log.Record> records = Log.fetchLogs(Log.Type.TRANSACTION);
    final Log.Record record = records.get(records.size() - 1);
    assertEquals(MARKER + "-lazy-value", record.getMessage(), "first read resolves the supplier");
    assertEquals(1, evaluations.get());
    assertEquals(MARKER + "-lazy-value", record.getMessage(), "second read returns the memoized string");
    assertEquals(1, evaluations.get(), "memoized read must not re-evaluate");
  }

  @Test
  void testEagerStringPathUnchanged() {

    Log.transaction(MARKER + "-eager");
    final List<Log.Record> records = Log.fetchLogs(Log.Type.TRANSACTION);
    assertEquals(MARKER + "-eager", records.get(records.size() - 1).getMessage(),
                 "string messages must be readable without any supplier involvement");
  }

  @Test
  void testConcurrentAppendsAreLosslessWithinWindow() throws Exception {

    final int threads = 4;
    final int perThread = 3000;
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    for(int t = 0; t < threads; t++) {
      final int id = t;
      final Thread worker = new Thread(() -> {
        try {
          start.await();
          for(int i = 0; i < perThread; i++) {
            Log.transaction(MARKER + "-conc-" + id);
          }
        } catch(final InterruptedException ignored) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      }, MARKER + "-writer-" + t);
      worker.setDaemon(true);
      worker.start();
    }
    start.countDown();
    done.await();

    final List<Log.Record> records = Log.fetchLogs();
    assertTrue(records.size() <= 2000 * Log.Type.values().length);
    // 4 x 3000 = 12000 concurrent writes fit the window together, so every
    // writer's marker must be fully retained despite the unsynchronized race
    for(int t = 0; t < threads; t++) {
      final String msg = MARKER + "-conc-" + t;
      assertEquals(perThread, records.stream().filter(r -> msg.equals(r.getMessage())).count(),
                   "every concurrent write must survive the race (writer " + t + ")");
    }
  }

  @Test
  void testPerfMonitorCloseRecordsLazyPerformanceLog() throws Exception {

    try(PerfMonitor monitor = new PerfMonitor(MARKER + "-task")) {
      monitor.setContext("ctx");
    }
    final List<Log.Record> records = Log.fetchLogs(Log.Type.PERFORMANCE);
    final Log.Record record = records.get(records.size() - 1);
    assertEquals(java.util.logging.Level.INFO, record.getLevel(), "no duration limit -> INFO");
    final String message = record.getMessage();
    assertTrue(message.contains("The task [" + MARKER + "-task]"), "message must keep the historical layout");
    assertTrue(message.contains("(ctx) "));
    assertTrue(message.contains("has finished in "));

    try(PerfMonitor over = new PerfMonitor(MARKER + "-slow", Duration.ofMillis(-1))) {
      // Duration already exceeded by construction -> must flag WARNING + OVER LIMIT
    }
    final List<Log.Record> after = Log.fetchLogs(Log.Type.PERFORMANCE);
    final Log.Record flagged = after.get(after.size() - 1);
    assertEquals(java.util.logging.Level.WARNING, flagged.getLevel());
    assertTrue(flagged.getMessage().contains("OVER LIMIT!"));
  }
}
