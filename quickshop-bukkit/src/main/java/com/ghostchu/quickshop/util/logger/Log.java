package com.ghostchu.quickshop.util.logger;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.common.util.CommonUtil;
import com.ghostchu.quickshop.common.util.QuickExecutor;
import com.ghostchu.quickshop.common.util.Timer;
import com.ghostchu.quickshop.util.Util;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;
import java.util.logging.Level;

public class Log {

  /**
   * Records kept readable per snapshot; mirrors the historical EvictingQueue capacity
   * (2000 per {@link Type}). The backing ring is the next power of two so slot mapping
   * is a mask; entries older than the window are excluded by sequence check.
   */
  private static final int BUFFER_SIZE = 2000 * Type.values().length;
  private static final int RING_SIZE = Integer.highestOneBit(BUFFER_SIZE - 1) << 1;
  private static final int RING_MASK = RING_SIZE - 1;
  private static final AtomicReferenceArray<Slot> RING = new AtomicReferenceArray<>(RING_SIZE);
  private static final AtomicLong CURSOR = new AtomicLong();
  private static final boolean DISABLE_LOCATION_RECORDING;
  private static final StackWalker STACK_WALKER = StackWalker.getInstance();

  static {
    // Cannot replace with Util since it depend on this class
    DISABLE_LOCATION_RECORDING = Boolean.parseBoolean(System.getProperty("com.ghostchu.quickshop.util.logger."));
  }

  /**
   * Appends a record to the lock-free ring. Writers race only on the cursor CAS; a
   * snapshot taken concurrently may miss the very last writes (weakly consistent, the
   * buffer only feeds the dev paste/viewer) but never sees torn records.
   */
  private static void append(@NotNull final Record record) {

    final long seq = CURSOR.getAndIncrement();
    RING.set((int)(seq & RING_MASK), new Slot(seq, record));
  }

  /**
   * Collects the newest {@link #BUFFER_SIZE} records in write order. Slots overwritten
   * by newer writes or not yet published fail the sequence check and are skipped.
   */
  @NotNull
  private static List<Record> snapshot() {

    final long cursor = CURSOR.get();
    final long from = Math.max(0, cursor - BUFFER_SIZE);
    final List<Record> records = new ArrayList<>((int)Math.min(cursor, BUFFER_SIZE));
    for(long seq = from; seq < cursor; seq++) {
      final Slot slot = RING.get((int)(seq & RING_MASK));
      if(slot != null && slot.seq == seq) {
        records.add(slot.record);
      }
    }
    return records;
  }

  public static void cron(@NotNull final String message) {

    cron(Level.INFO, message, Caller.create());
  }

  @ApiStatus.Internal
  public static void cron(@NotNull final Level level, @NotNull final String message, @Nullable final Caller caller) {

    final Record recordEntry;
    if(DISABLE_LOCATION_RECORDING) {
      recordEntry = new Record(level, Type.CRON, message, null);
    } else {
      recordEntry = new Record(level, Type.CRON, message, caller);
    }
    append(recordEntry);
    debugStdOutputs(recordEntry);
  }

  private static void debugStdOutputs(final Record recordEntry) {

    if (Util.isDevMode()) {
      recordEntry
          .generate()
          .thenAccept(log -> QuickShop.getInstance().logger().info("[DEBUG] " + log));
      }
  }

  public static void cron(@NotNull final Level level, @NotNull final String message) {

    cron(level, message, Caller.create());
  }

  public static void debug(@NotNull final String message) {

    debug(Level.INFO, message, Caller.create());
  }

  @ApiStatus.Internal
  public static void debug(@NotNull final Level level, @NotNull final String message, @Nullable final Caller caller) {

    final Record recordEntry;
    if(DISABLE_LOCATION_RECORDING) {
      recordEntry = new Record(level, Type.DEBUG, message, null);
    } else {
      recordEntry = new Record(level, Type.DEBUG, message, caller);
    }
    append(recordEntry);
    debugStdOutputs(recordEntry);
  }

  public static void debug(@NotNull final Level level, @NotNull final String message) {

    debug(level, message, Caller.create());
  }

  /**
   * Debug log with a lazily built message: the supplier only runs when the record is
   * actually read (dev paste/viewer), keeping hot call sites free of string assembly.
   * Suppliers must be pure reads over call-time-stable state.
   */
  public static void debug(@NotNull final Supplier<String> message) {

    debug(Level.INFO, message);
  }

  public static void debug(@NotNull final Level level, @NotNull final Supplier<String> message) {

    final Record recordEntry;
    if(DISABLE_LOCATION_RECORDING) {
      recordEntry = new Record(level, Type.DEBUG, message, (Caller)null);
    } else {
      recordEntry = new Record(level, Type.DEBUG, message, Caller.create());
    }
    append(recordEntry);
    debugStdOutputs(recordEntry);
  }


  public static void privacy(@NotNull final String message) {

    privacy(Level.INFO, message, Caller.create());
  }

  @ApiStatus.Internal
  public static void privacy(@NotNull final Level level, @NotNull final String message, @Nullable final Caller caller) {

    final Record recordEntry;
    if(DISABLE_LOCATION_RECORDING) {
      recordEntry = new Record(level, Type.PRIVACY, message, null);
    } else {
      recordEntry = new Record(level, Type.PRIVACY, message, caller);
    }
    append(recordEntry);
    debugStdOutputs(recordEntry);
  }

  public static void privacy(@NotNull final Level level, @NotNull final String message) {

    privacy(level, message, Caller.create());
  }


  public static void performance(@NotNull final Level level, @NotNull final String message, @NotNull final Caller caller) {

    final Record recordEntry = new Record(level, Type.PERFORMANCE, message, caller);
    append(recordEntry);
    debugStdOutputs(recordEntry);
  }

  @ApiStatus.Internal
  public static void performance(@NotNull final Level level, @NotNull final Supplier<String> message, @NotNull final Caller caller) {

    final Record recordEntry = new Record(level, Type.PERFORMANCE, message, caller);
    append(recordEntry);
    debugStdOutputs(recordEntry);
  }

  @NotNull
  public static List<Record> fetchLogs() {

    return snapshot();
  }

  @NotNull
  public static List<Record> fetchLogs(@NotNull final Type type) {

    return snapshot().stream().filter(recordEntry->recordEntry.getType() == type).toList();
  }

  @NotNull
  public static List<Record> fetchLogsExclude(@NotNull final Type... excludes) {

    final List<Record> records = new ArrayList<>();
    for(final Record recordEntry : snapshot()) {
      if(CommonUtil.arrayContains(excludes, recordEntry.getType())) {
        continue;
      }
      records.add(recordEntry);
    }
    return records;
  }

  @NotNull
  public static List<Record> fetchLogsLevel(@NotNull final Type type, @NotNull final Level level) {

    return snapshot().stream().filter(recordEntry->recordEntry.getType() == type && recordEntry.getLevel() == level).toList();
  }

  public static void permission(@NotNull final String message) {

    permission(Level.INFO, message, Caller.create(3, false));
  }

  @ApiStatus.Internal
  public static void permission(@NotNull final Level level, @NotNull final String message, @Nullable final Caller caller) {

    final Record recordEntry;
    if(DISABLE_LOCATION_RECORDING) {
      recordEntry = new Record(level, Type.PERMISSION, message, null);
    } else {
      recordEntry = new Record(level, Type.PERMISSION, message, caller);
    }
    append(recordEntry);
    debugStdOutputs(recordEntry);
  }

  public static void permission(@NotNull final Level level, @NotNull final String message) {

    permission(level, message, Caller.create(3, false));
  }

  public static void timing(@NotNull final String operation, @NotNull final Timer timer) {

    timing(Level.INFO, operation, timer, Caller.create());
  }

  @ApiStatus.Internal
  public static void timing(@NotNull final Level level, @NotNull final String operation, @NotNull final Timer timer, @Nullable final Caller caller) {

    final Record recordEntry;
    if(DISABLE_LOCATION_RECORDING) {
      recordEntry = new Record(level, Type.TIMING, operation + " (cost " + timer.getPassedTime() + " ms)", null);
    } else {
      recordEntry = new Record(level, Type.TIMING, operation + " (cost " + timer.getPassedTime() + " ms)", caller);
    }
    append(recordEntry);
    debugStdOutputs(recordEntry);
  }

  public static void transaction(@NotNull final String message) {

    transaction(Level.INFO, message, Caller.create());
  }

  @ApiStatus.Internal
  public static void transaction(@NotNull final Level level, @NotNull final String message, @Nullable final Caller caller) {

    final Record recordEntry;
    if(DISABLE_LOCATION_RECORDING) {
      recordEntry = new Record(level, Type.TRANSACTION, message, null);
    } else {
      recordEntry = new Record(level, Type.TRANSACTION, message, caller);
    }
    append(recordEntry);
    debugStdOutputs(recordEntry);
  }

  public static void transaction(@NotNull final Level level, @NotNull final String message) {

    transaction(level, message, Caller.create());
  }

  /**
   * Transaction log with a lazily built message, see {@link #debug(Supplier)}. The
   * supplier must only read values snapshotted at the call site so the recorded
   * content stays identical to the eager form.
   */
  public static void transaction(@NotNull final Supplier<String> message) {

    transaction(Level.INFO, message);
  }

  public static void transaction(@NotNull final Level level, @NotNull final Supplier<String> message) {

    final Record recordEntry;
    if(DISABLE_LOCATION_RECORDING) {
      recordEntry = new Record(level, Type.TRANSACTION, message, (Caller)null);
    } else {
      recordEntry = new Record(level, Type.TRANSACTION, message, Caller.create());
    }
    append(recordEntry);
    debugStdOutputs(recordEntry);
  }

  public enum Type {
    DEBUG,
    CRON,
    TRANSACTION,
    TIMING,
    PERFORMANCE,
    PRIVACY,
    PERMISSION
  }

  @Getter
  @EqualsAndHashCode
  public static class Record {

    private final long timestamp = System.currentTimeMillis();
    @NotNull
    private final Level level;
    @NotNull
    private final Type type;
    // String, or an unevaluated Supplier<String> resolved on first read (lazy hot-path logs)
    @NotNull
    private Object message;
    @Nullable
    private final Caller caller;

    public Record(@NotNull final Level level, @NotNull final Type type, @NotNull final String message, @Nullable final Caller caller) {

      this.level = level;
      this.type = type;
      this.message = message;
      this.caller = caller;
    }

    public Record(@NotNull final Level level, @NotNull final Type type, @NotNull final Supplier<String> message, @Nullable final Caller caller) {

      this.level = level;
      this.type = type;
      this.message = message;
      this.caller = caller;
    }

    /**
     * Resolves the message, evaluating and memoizing a lazy supplier on first access.
     * Benign race: concurrent first reads may evaluate twice, both yielding the same
     * pure-read result.
     */
    @NotNull
    public String getMessage() {

      final Object pending = this.message;
      if(pending instanceof final String resolved) {
        return resolved;
      }
      final String resolved = ((Supplier<String>)pending).get();
      this.message = resolved;
      return resolved;
    }

    public CompletableFuture<String> generate() {

      return CompletableFuture.supplyAsync(()->{
        final StringBuilder sb = new StringBuilder();
        final Log.Caller caller;
        caller = Objects.requireNonNullElseGet(this.caller, ()->new Caller("<NO RECORDING>", "<NO RECORDING>", "<NO RECORDING>", -1));
        final String simpleClassName = caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1);
        sb.append("[");
        sb.append(caller.getThreadName());
        sb.append("/");
        sb.append(this.getLevel().getName());
        sb.append("]");
        sb.append(" ");
        sb.append("(");
        sb.append(simpleClassName).append("#").append(caller.getMethodName()).append(":").append(caller.getLineNumber());
        sb.append(")");
        sb.append(" ");
        sb.append(this.getMessage());
        return sb.toString();
      }, QuickExecutor.getCommonExecutor());
    }

    @Override
    public String toString() {

      return generate().join();
    }
  }

  /** Ring slot pairing a record with its write sequence for snapshot ordering. */
  private static final class Slot {

    private final long seq;
    @NotNull
    private final Record record;

    private Slot(final long seq, @NotNull final Record record) {

      this.seq = seq;
      this.record = record;
    }
  }

  @Data
  public final static class Caller {

    private static final ThreadLocal<CallerCache> CALLER_CACHE = ThreadLocal.withInitial(CallerCache::new);
    // Read once like DISABLE_LOCATION_RECORDING: the property is a launch-time -D flag
    private static final boolean DISABLED = "true".equalsIgnoreCase(System.getProperty("quickshop-hikari-disable-debug-logger"));

    @NotNull
    private final String threadName;
    @NotNull
    private final String className;
    @NotNull
    private final String methodName;
    private final int lineNumber;

    public Caller(@NotNull final String threadName, @NotNull final String className, @NotNull final String methodName, final int lineNumber) {

      this.threadName = threadName;
      this.className = className;
      this.methodName = methodName;
      this.lineNumber = lineNumber;
    }

    @NotNull
    public static Caller create() {

      return create(3, false);
    }

    @NotNull
    public static Caller createSync() {

      return create(3, false);
    }

    @NotNull
    public static Caller createSync(final boolean force) {

      return create(3, force);
    }

    @NotNull
    public static Caller create(final int steps, final boolean force) {
      if(!force) {
        if(DISABLED) {
          return new Caller("<DISABLED>", "<DISABLED>", "<DISABLED>", -1);
        }
      }

      final CallerCache cache = CALLER_CACHE.get();
      if(!force && cache.steps == steps && cache.caller != null) {
        return cache.caller;
      }

      final Caller caller = STACK_WALKER.walk(stream->stream.skip(steps).findFirst()
              .map(frame->{
                final String threadName = Thread.currentThread().getName();
                final String className = frame.getClassName();
                final String methodName = frame.getMethodName();
                final int codeLine = frame.getLineNumber();
                return new Caller(threadName, className, methodName, codeLine);
              })
              .orElseGet(()->new Caller("<INVALID>", "<INVALID>", "<INVALID>", -1)));

      cache.steps = steps;
      cache.caller = caller;
      return caller;
    }

    /**
     * Cleans up the ThreadLocal cache for the current thread.
     * Should be called during plugin shutdown or when threads are being terminated.
     */
    public static void cleanupThreadLocal() {
      CALLER_CACHE.remove();
    }

    private static class CallerCache {
      int steps = -1;
      Caller caller = null;
    }
  }

}
