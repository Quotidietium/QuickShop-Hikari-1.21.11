package com.ghostchu.quickshop.watcher;

import com.ghostchu.quickshop.QuickShop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * R55: when the log file never opened (read-only FS, disk full), printWriter stays null
 * forever — the old code kept queueing every log()/logLazy() entry while run() returned
 * without consuming anything, so a high-traffic server slowly walked into an OOM. The
 * fix drops entries at enqueue time whenever the writer is dead.
 */
class LogWatcherDeadWriterTest {

  @TempDir
  File tempDir;

  private LogWatcher watcher() throws Exception {

    final QuickShop plugin = mock(QuickShop.class);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder()).thenReturn(tempDir);
    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getDouble(anyString(), anyDouble())).thenReturn(100.0d);
    lenient().when(plugin.getConfig()).thenReturn(config);
    final LogWatcher watcher = new LogWatcher(plugin, new File(tempDir, "qs-dead-writer.log"));
    // simulate "the file never opened": same state the constructor leaves behind on
    // IOException; close the real writer first or its handle pins the temp file on Windows
    final Field writerField = LogWatcher.class.getDeclaredField("printWriter");
    writerField.setAccessible(true);
    final java.io.PrintWriter real = (java.io.PrintWriter)writerField.get(watcher);
    if(real != null) {
      real.close();
    }
    writerField.set(watcher, null);
    return watcher;
  }

  @SuppressWarnings("unchecked")
  private int queueSize(final LogWatcher watcher) throws Exception {

    final Field logsField = LogWatcher.class.getDeclaredField("logs");
    logsField.setAccessible(true);
    return ((Queue<Object>)logsField.get(watcher)).size();
  }

  @Test
  void deadWriterDropsEntriesInsteadOfQueueing() throws Exception {

    try(final LogWatcher watcher = watcher()) {
      for(int i = 0; i < 1_000; i++) {
        final int idx = i;
        watcher.log("entry-" + idx);
        watcher.logLazy(()->"lazy-" + idx);
      }
      assertEquals(0, queueSize(watcher),
                   "no entry may be queued when nothing can ever drain it (unbounded growth = OOM)");
    }
  }

  @Test
  void healthyWriterStillQueues() throws Exception {

    final QuickShop plugin = mock(QuickShop.class);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder()).thenReturn(tempDir);
    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getDouble(anyString(), anyDouble())).thenReturn(100.0d);
    lenient().when(plugin.getConfig()).thenReturn(config);
    try(final LogWatcher watcher = new LogWatcher(plugin, new File(tempDir, "qs-alive.log"))) {
      watcher.log("queued");
      assertTrue(queueSize(watcher) == 1, "a healthy writer must keep queueing entries");
      watcher.run();
      assertTrue(queueSize(watcher) == 0, "run() drains the queue of a healthy writer");
    }
  }

  /** close()/run() must not blow up on a dead writer either (they re-check printWriter). */
  @Test
  void closeToleratesDeadWriter() throws Exception {

    try(final LogWatcher watcher = watcher()) {
      watcher.run(); // no-op on a dead writer, must not throw
    }
  }
}
