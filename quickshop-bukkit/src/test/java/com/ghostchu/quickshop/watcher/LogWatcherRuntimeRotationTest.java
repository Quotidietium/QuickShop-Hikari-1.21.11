package com.ghostchu.quickshop.watcher;

import com.ghostchu.quickshop.QuickShop;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * R59: rotation used to be checked in the constructor only — a server running for months
 * between restarts grew qs.log without bound. run() must now rotate an over-threshold
 * file in place and keep writing into the fresh file (a failed reopen would silently
 * drop every future entry, see the dead-writer contract).
 */
class LogWatcherRuntimeRotationTest {

  @TempDir
  File tempDir;

  private LogWatcher watcher(final double sizeMb) {

    final QuickShop plugin = mock(QuickShop.class);
    lenient().when(plugin.logger()).thenReturn(mock(org.slf4j.Logger.class));
    lenient().when(plugin.getDataFolder()).thenReturn(tempDir);
    final var config = mock(dev.dejvokep.boostedyaml.YamlDocument.class);
    lenient().when(config.getDouble(anyString(), anyDouble())).thenReturn(sizeMb);
    lenient().when(plugin.getConfig()).thenReturn(config);
    return new LogWatcher(plugin, new File(tempDir, "qs-rotate.log"));
  }

  private List<File> archives() {

    final File logsDir = new File(tempDir, "logs");
    final File[] archives = logsDir.listFiles((dir, name)->name.endsWith(".log.gz"));
    final List<File> list = archives == null? new ArrayList<>() : new ArrayList<>(Arrays.asList(archives));
    list.sort(Comparator.comparing(File::getName)); // "-1" before "-2": creation order
    return list;
  }

  private String gunzip(final File archive) throws Exception {

    try(InputStream in = Files.newInputStream(archive.toPath());
        final GzipCompressorInputStream gz = new GzipCompressorInputStream(in)) {
      return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void runRotatesOverSizeFileAndKeepsWriting() throws Exception {

    try(final LogWatcher watcher = watcher(0.000001d)) { // ~1 byte: any line exceeds it
      watcher.log("before-rotation");
      watcher.run();
      assertEquals(1, archives().size(),
                   "an over-threshold file must be archived on the watcher tick");
      assertTrue(gunzip(archives().getFirst()).contains("before-rotation"),
                 "the pre-rotation line must live in the archive");

      // writing continues into the recreated file; the next tick rotates it again
      watcher.log("after-rotation");
      watcher.run();
      assertEquals(2, archives().size(), "the watcher keeps rotating while over threshold");
      final String second = gunzip(archives().get(1));
      assertTrue(second.contains("after-rotation"),
                 "entries logged after an in-place rotation must be written, not dropped: " + second);
    }
  }

  @Test
  void smallFileIsNotRotated() throws Exception {

    final File log = new File(tempDir, "qs-rotate.log");
    try(final LogWatcher watcher = watcher(100.0d)) {
      watcher.log("normal");
      watcher.run();
    }
    assertEquals(0, archives().size(), "a file under the threshold must not be rotated");
    assertTrue(Files.readAllLines(log.toPath()).stream().anyMatch(l->l.endsWith("normal")));
  }
}
