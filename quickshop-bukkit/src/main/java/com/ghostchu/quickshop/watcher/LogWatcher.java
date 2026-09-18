package com.ghostchu.quickshop.watcher;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.util.logger.Log;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LogWatcher implements AutoCloseable, Runnable {

  private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
  private static final DateTimeFormatter LOG_FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

  /** One queue for both eager strings and lazy suppliers, so global ordering is kept. */
  private final Queue<java.util.function.Supplier<String>> logs = new ConcurrentLinkedQueue<>();

  private final QuickShop plugin;
  private final File log;
  private final double maxSizeMb;

  private WrappedTask task = null;

  private PrintWriter printWriter = null;

  public LogWatcher(final QuickShop plugin, final File log) {

    this.plugin = plugin;
    this.log = log;
    this.maxSizeMb = plugin.getConfig().getDouble("logging.file-size", 10.0d);

    try {
      if(!log.exists()) {
        //noinspection ResultOfMethodCallIgnored
        log.getParentFile().mkdirs();
        //noinspection ResultOfMethodCallIgnored
        log.createNewFile();
      }
      boolean deleteFailed = false;
      if(isOverSize()) {
        deleteFailed = rotateArchive();
      }
      final FileWriter logFileWriter;
      if(deleteFailed) {
        //If could not delete, just override it
        logFileWriter = new FileWriter(log, false);
      } else {
        //Otherwise append
        logFileWriter = new FileWriter(log, true);
      }
      printWriter = new PrintWriter(logFileWriter);
    } catch(final FileNotFoundException e) {
      plugin.logger().error("Log file was not found!", e);
    } catch(final IOException e) {
      plugin.logger().error("Could not create the log file!", e);
    }
  }

  public void start(final int i, final int i2) {

    // idempotent: config reloads can call start() on an already-running watcher
    stop();
    task = QuickShop.folia().getScheduler().runTimerAsync(this, i, i2);
  }

  public void stop() {

    try {
      if(task != null && !task.isCancelled()) {
        task.cancel();
      }
    } catch(final IllegalStateException ex) {
      Log.debug("Task already cancelled " + ex.getMessage());
    }
  }

  @Override
  public void close() {

    if(printWriter != null) {
      run();
      printWriter.flush();
      printWriter.close();
    }
  }

  public void log(@NotNull final String log) {

    if(printWriter == null) {
      return; // the file never opened (read-only FS / disk full): dropping beats OOM
    }
    logs.add(()->"[" + DATETIME_FORMATTER.format(Instant.now()) + "] " + log);
  }

  /**
   * Queues a log line whose rendering (and any heavy serialization inside it) runs on
   * the watcher's async tick instead of the calling thread. Ordering with {@link #log(String)}
   * entries is preserved. A throwing supplier is isolated: the failure is reported and
   * the remaining queue keeps flowing.
   */
  public void logLazy(@NotNull final java.util.function.Supplier<String> line) {

    if(printWriter == null) {
      return; // see log(String): never queue when nothing can ever drain it
    }
    logs.add(()->"[" + DATETIME_FORMATTER.format(Instant.now()) + "] " + line.get());
  }

  @Override
  public void run() {

    if(printWriter == null) {
      //Waiting for init
      return;
    }
    final Iterator<java.util.function.Supplier<String>> iterator = logs.iterator();
    while(iterator.hasNext()) {
      final java.util.function.Supplier<String> entry = iterator.next();
      try {
        printWriter.println(entry.get());
      } catch(final Throwable t) {
        Log.debug("Skipped a log entry whose rendering failed: " + t.getMessage());
      }
      iterator.remove();
    }
    printWriter.flush();
    // rotation is also checked while running: the constructor-only check let qs.log grow
    // without bound on servers that run for months between restarts
    if(isOverSize()) {
      rotateAndReopen();
    }
  }

  private boolean isOverSize() {

    return (log.length() / 1024f / 1024f) > maxSizeMb;
  }

  /**
   * Archives the current log file into a dated gzip and recreates it. Returns whether the
   * original file could not be deleted (caller falls back to truncation).
   */
  private boolean rotateArchive() {

    try {
      final Path logPath = plugin.getDataFolder().toPath().resolve("logs");
      Files.createDirectories(logPath);
      //Find a available name
      Path targetPath;
      int i = 1;
      do {
        targetPath = logPath.resolve(ZonedDateTime.now().format(LOG_FILE_FORMATTER) + "-" + i + ".log.gz");
        i++;
      } while(Files.exists(targetPath));
      Files.createFile(targetPath);
      final GzipParameters gzipParameters = new GzipParameters();
      gzipParameters.setFilename(log.getName());
      try(final GzipCompressorOutputStream archiveOutputStream = new GzipCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(targetPath.toFile())), gzipParameters)) {
        Files.copy(log.toPath(), archiveOutputStream);
        archiveOutputStream.finish();
        if(log.delete()) {
          //noinspection ResultOfMethodCallIgnored
          log.createNewFile();
          return false;
        }
        return true;
      }
    } catch(final IOException e) {
      plugin.logger().error("Could not rotate the log file!", e);
      return true;
    }
  }

  private void rotateAndReopen() {

    printWriter.flush();
    printWriter.close();
    final boolean deleteFailed = rotateArchive();
    try {
      printWriter = new PrintWriter(new FileWriter(log, !deleteFailed));
    } catch(final IOException e) {
      // from here on log()/logLazy() drop entries: a dead writer beats an unbounded queue
      printWriter = null;
      plugin.logger().error("Could not reopen the log file after rotation!", e);
    }
  }

}
