package com.ghostchu.quickshop.watcher;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.Shop;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Regression test for the sign-update queue fix: the queue is written from region threads
 * (hopper events, chunk loads, trades) and drained by the async timer thread, so it must
 * be a concurrent implementation - a plain LinkedList corrupts under that access pattern.
 */
class SignUpdateWatcherTest {

  private org.mockito.MockedStatic<Bukkit> bukkitStatic;
  private org.mockito.MockedStatic<QuickShop> quickShopStatic;

  @BeforeEach
  void setUp() {

    // instrumenting the Shop interface triggers its class initialization, which reads
    // Bukkit.server; standalone runs need the standard fake environment installed
    bukkitStatic = mockStatic(Bukkit.class);
    quickShopStatic = mockStatic(QuickShop.class);
    final QuickShop plugin = mock(QuickShop.class);
    quickShopStatic.when(QuickShop::getInstance).thenReturn(plugin);
    com.ghostchu.quickshop.MockBukkit.install(bukkitStatic, plugin);
  }

  @AfterEach
  void tearDown() {

    quickShopStatic.close();
    bukkitStatic.close();
  }

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

  @Test
  void dedupHoldsAcrossABusyQueueWithoutRescanning() throws Exception {

    final SignUpdateWatcher watcher = new SignUpdateWatcher();
    final Queue<?> queue = queueOf(watcher);

    // a full drain window of pending shops, then the hopper-fed shape: the SAME
    // shop scheduled again on every item move
    for(int i = 0; i < 500; i++) {
      watcher.scheduleSignUpdate(mock(Shop.class));
    }
    final Shop fed = mock(Shop.class);
    watcher.scheduleSignUpdate(fed);
    final int sizeBefore = queue.size();
    for(int i = 0; i < 1000; i++) {
      watcher.scheduleSignUpdate(fed);
    }
    assertEquals(sizeBefore, queue.size(), "re-schedules of a pending shop must not enqueue");
  }

  @Test
  void drainedShopBecomesSchedulableAgain() throws Exception {

    final SignUpdateWatcher watcher = new SignUpdateWatcher();
    final Queue<?> queue = queueOf(watcher);

    // entries with a locale never touch QuickShop.getInstance(), so run() is testable
    final var locale = mock(com.ghostchu.quickshop.api.localization.text.ProxiedLocale.class);
    final Shop shop = mock(Shop.class);
    watcher.scheduleSignUpdate(shop, locale);
    watcher.scheduleSignUpdate(shop, locale);
    assertEquals(1, queue.size());

    watcher.run();

    assertTrue(queue.isEmpty(), "run() must drain the pending entry");
    watcher.scheduleSignUpdate(shop, locale);
    assertEquals(1, queue.size(), "a drained shop must be schedulable again");
  }

  private static Queue<?> queueOf(final SignUpdateWatcher watcher) throws Exception {

    final Field queueField = SignUpdateWatcher.class.getDeclaredField("signUpdateQueue");
    queueField.setAccessible(true);
    return (Queue<?>)queueField.get(watcher);
  }
}
