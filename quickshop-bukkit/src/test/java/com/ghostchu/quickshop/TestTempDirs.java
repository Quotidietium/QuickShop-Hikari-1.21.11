package com.ghostchu.quickshop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Per-test plugin data folders under the system temp dir, deleted recursively when the
 * (Surefire-forked) JVM exits. Bare {@code Files.createTempDirectory} in test setup leaks
 * a handful of directories per test class into %TEMP% on every run — thousands accumulate
 * across audit rounds.
 */
public final class TestTempDirs {

  private static final List<Path> CREATED = new ArrayList<>();

  static {

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      for (int i = CREATED.size() - 1; i >= 0; i--) {
        try (var walk = Files.walk(CREATED.get(i))) {
          walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final IOException ignored) {
          // best effort: a locked dir must not mask the others
        }
      }
    }, "quickshop-test-tempdir-cleanup"));
  }

  private TestTempDirs() {

  }

  public static synchronized File newFolder(final String prefix) {

    try {
      final Path dir = Files.createTempDirectory(prefix);
      CREATED.add(dir);
      return dir.toFile();
    } catch (final IOException e) {
      throw new IllegalStateException("cannot create test data folder", e);
    }
  }
}
