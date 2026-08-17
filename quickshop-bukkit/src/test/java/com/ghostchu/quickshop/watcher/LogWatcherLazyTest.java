package com.ghostchu.quickshop.watcher;

import com.ghostchu.quickshop.QuickShop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Regression tests for the lazy log queue: eager and lazy entries keep one global
 * order, a throwing supplier is isolated without dropping the rest of the queue, and
 * close() drains everything pending.
 */
class LogWatcherLazyTest {

  @TempDir
  File tempDir;

  private LogWatcher watcher() {

    final QuickShop plugin = mock(QuickShop.class);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder()).thenReturn(tempDir);
    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getDouble(anyString(), anyDouble())).thenReturn(100.0d);
    lenient().when(plugin.getConfig()).thenReturn(config);
    return new LogWatcher(plugin, new File(tempDir, "qs-test.log"));
  }

  private List<String> lines(final File log) throws Exception {

    return Files.readAllLines(log.toPath());
  }

  @Test
  void lazyAndEagerEntriesKeepOrder() throws Exception {

    final File log = new File(tempDir, "qs-test.log");
    try(final LogWatcher watcher = watcher()) {
      watcher.log("eager-one");
      watcher.logLazy(()->"lazy-two");
      watcher.log("eager-three");
      watcher.run();
      final List<String> lines = lines(log);
      assertEquals(3, lines.size());
      assertTrue(lines.get(0).endsWith("eager-one"));
      assertTrue(lines.get(1).endsWith("lazy-two"));
      assertTrue(lines.get(2).endsWith("eager-three"));
    }
  }

  @Test
  void failingSupplierIsIsolated() throws Exception {

    final File log = new File(tempDir, "qs-test.log");
    try(final LogWatcher watcher = watcher()) {
      watcher.log("before");
      watcher.logLazy(()->{
        throw new IllegalStateException("boom");
      });
      watcher.log("after");
      watcher.run();
      final List<String> lines = lines(log);
      assertEquals(2, lines.size());
      assertTrue(lines.get(0).endsWith("before"));
      assertTrue(lines.get(1).endsWith("after"));
    }
  }

  @Test
  void closeDrainsPendingEntries() throws Exception {

    final File log = new File(tempDir, "qs-test.log");
    try(final LogWatcher watcher = watcher()) {
      watcher.logLazy(()->"flushed-on-close");
      watcher.run();
    }
    assertTrue(lines(log).get(0).endsWith("flushed-on-close"));
  }

  @Test
  void supplierRunsOnlyAtFlushNotAtQueueTime() throws Exception {

    final File log = new File(tempDir, "qs-test.log");
    final int[] evaluated = {0};
    try(final LogWatcher watcher = watcher()) {
      watcher.logLazy(()->{
        evaluated[0]++;
        return "evaluated";
      });
      assertEquals(0, evaluated[0]);
      watcher.run();
      assertEquals(1, evaluated[0]);
    }
    assertTrue(lines(log).get(0).endsWith("evaluated"));
  }
}
