package com.ghostchu.quickshop.watcher;

import com.ghostchu.quickshop.api.shop.Shop;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression test for the sign-update queue fix: the queue is written from region threads
 * (hopper events, chunk loads, trades) and drained by the async timer thread, so it must
 * be a concurrent implementation - a plain LinkedList corrupts under that access pattern.
 */
class SignUpdateWatcherTest {

  @Test
  void queueUsesConcurrentImplementation() throws Exception {

    final SignUpdateWatcher watcher = new SignUpdateWatcher();
    final Field queueField = SignUpdateWatcher.class.getDeclaredField("signUpdateQueue");
    queueField.setAccessible(true);
    final Object queue = queueField.get(watcher);
    assertInstanceOf(ConcurrentLinkedQueue.class, queue, "the sign queue must be a concurrent queue");
  }

  @Test
  void duplicateSchedulesAreDeduplicated() throws Exception {

    final SignUpdateWatcher watcher = new SignUpdateWatcher();
    final Queue<?> queue = queueOf(watcher);

    final Shop shop = mock(Shop.class);
    watcher.scheduleSignUpdate(shop);
    watcher.scheduleSignUpdate(shop);
    watcher.scheduleSignUpdate(shop, null);

    assertEquals(1, queue.size(), "deduplication must leave exactly one entry");
    assertTrue(queue.poll() != null);
    assertTrue(queue.isEmpty());
  }

  private static Queue<?> queueOf(final SignUpdateWatcher watcher) throws Exception {

    final Field queueField = SignUpdateWatcher.class.getDeclaredField("signUpdateQueue");
    queueField.setAccessible(true);
    return (Queue<?>)queueField.get(watcher);
  }
}
