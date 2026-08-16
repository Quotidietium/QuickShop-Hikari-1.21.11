package com.ghostchu.quickshop.watcher;

import com.ghostchu.quickshop.api.shop.Shop;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression test for the sign-update queue fix: the queue is written from region threads
 * (hopper events, chunk loads) and drained by the async timer thread, so it must be a
 * concurrent implementation - a plain LinkedList corrupts under that access pattern.
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
  void duplicateSchedulesAreDeduplicatedAndDrained() throws Exception {

    final SignUpdateWatcher watcher = new SignUpdateWatcher();
    final Field queueField = SignUpdateWatcher.class.getDeclaredField("signUpdateQueue");
    queueField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final Queue<Shop> queue = (Queue<Shop>)queueField.get(watcher);

    final Shop shop = mock(Shop.class);
    watcher.scheduleSignUpdate(shop);
    watcher.scheduleSignUpdate(shop);
    assertSame(shop, queue.poll());
    assertTrue(queue.isEmpty(), "deduplication must leave exactly one entry");
  }
}
