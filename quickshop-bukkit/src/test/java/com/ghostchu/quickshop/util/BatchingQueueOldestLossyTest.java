package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.QuickShop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Pins the oldest-lossy contract of {@link BatchingQueue}'s re-queue ceiling: when a
 * failed flush is re-queued at the MAX_PENDING ceiling, the OLDEST pending items are
 * the ones dropped. The previous implementation dropped the tail of the drained batch —
 * the newest values — which for last-write-wins cache rows meant the database would
 * settle on a stale value after the outage ended.
 *
 * The ceiling is 10_000 and a flush drains the ENTIRE pending queue, so the ceiling is
 * only reachable inside reoffer when a single drained batch exceeds it; the test drives
 * exactly that shape (10_001 items, failing flusher, then a capturing flush).
 */
class BatchingQueueOldestLossyTest {

  private static final int CEILING = 10_000;
  private static final int TOTAL = CEILING + 1;

  private MockedStatic<QuickShop> quickShopStatic;
  private QuickShop plugin;

  @BeforeEach
  void setUp() {

    final org.slf4j.Logger logger = mock(org.slf4j.Logger.class);
    quickShopStatic = mockStatic(QuickShop.class);
    plugin = mock(QuickShop.class);
    lenient().when(plugin.logger()).thenReturn(logger);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
  }

  @Test
  void testCeilingDropsOldestKeepsNewest() throws Exception {

    final Function<List<Integer>, CompletableFuture<?>> failingFlusher =
            batch->CompletableFuture.failedFuture(new RuntimeException("db down"));
    final BatchingQueue<Integer> queue = new BatchingQueue<>(plugin, "test-queue", Integer.MAX_VALUE,
                                                             failingFlusher, null);

    for(int i = 0; i < TOTAL; i++) {
      queue.offer(i);
    }

    // failed flush: the whole batch comes back; at the ceiling the oldest item (0)
    // must be evicted so the queue settles on the newest 10_000 values. The future
    // itself completes exceptionally (the flush failed) - that is expected.
    try {
      queue.flushAsync().get();
      throw new AssertionError("the failing flusher must propagate its failure");
    } catch(final java.util.concurrent.ExecutionException expected) {
      // reoffer ran inside whenComplete before the exception surfaced
    }

    // second flush with a capturing (successful) flusher reveals what survived
    final List<List<Integer>> captured = new java.util.ArrayList<>();
    final Function<List<Integer>, CompletableFuture<?>> capturingFlusher = batch->{
      captured.add(batch);
      return CompletableFuture.completedFuture(null);
    };
    setFlusher(queue, capturingFlusher);
    queue.flushAsync().get();

    assertEquals(1, captured.size(), "exactly one capturing flush expected");
    final List<Integer> survived = captured.get(0);
    assertEquals(CEILING, survived.size(), "queue must settle exactly at the ceiling");
    assertEquals(1, survived.get(0), "the OLDEST item (0) must be the one dropped");
    assertEquals(TOTAL - 1, survived.get(survived.size() - 1), "the NEWEST item must survive");
    // ordering is preserved: the survivors are exactly items 1..TOTAL-1
    for(int i = 0; i < survived.size(); i++) {
      assertEquals(i + 1, survived.get(i), "survivor order/value mismatch at index " + i);
    }
  }

  @Test
  void testNormalRequeueKeepsEverything() throws Exception {

    final Function<List<Integer>, CompletableFuture<?>> failingFlusher =
            batch->CompletableFuture.failedFuture(new RuntimeException("db down"));
    final BatchingQueue<Integer> queue = new BatchingQueue<>(plugin, "test-queue", Integer.MAX_VALUE,
                                                             failingFlusher, null);
    for(int i = 0; i < 100; i++) {
      queue.offer(i);
    }
    try {
      queue.flushAsync().get();
    } catch(final java.util.concurrent.ExecutionException expected) {
      // expected: flusher failed
    }

    final List<List<Integer>> captured = new java.util.ArrayList<>();
    setFlusher(queue, batch->{
      captured.add(batch);
      return CompletableFuture.completedFuture(null);
    });
    queue.flushAsync().get();

    assertEquals(1, captured.size());
    assertEquals(100, captured.get(0).size(), "below the ceiling nothing may be dropped");
  }

  /** The flusher is a final field; tests swap it reflectively (same trick the ceiling probe needs). */
  private static void setFlusher(final BatchingQueue<Integer> queue, final Function<List<Integer>, CompletableFuture<?>> flusher) {

    try {
      final java.lang.reflect.Field field = BatchingQueue.class.getDeclaredField("flusher");
      field.setAccessible(true);
      field.set(queue, flusher);
    } catch(final ReflectiveOperationException e) {
      throw new AssertionError("cannot swap flusher", e);
    }
  }
}
