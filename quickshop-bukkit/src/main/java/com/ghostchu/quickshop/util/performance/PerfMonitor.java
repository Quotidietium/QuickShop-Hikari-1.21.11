package com.ghostchu.quickshop.util.performance;

import com.ghostchu.quickshop.util.logger.Log;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.logging.Level;

public class PerfMonitor implements AutoCloseable {

  private final String name;
  // monotonic start; the wall-clock Instant this replaced allocated per monitored block
  // and made the elapsed measurement sensitive to wall-clock adjustments
  private final long startNanos = System.nanoTime();
  @Nullable
  private final Duration exceptedDuration;
  private final Log.Caller caller;
  @Nullable
  private String context;

  public PerfMonitor(@NotNull final String name) {

    this.caller = Log.Caller.create();
    this.name = name;
    this.exceptedDuration = null;
  }

  public PerfMonitor(@NotNull final String name, @NotNull final Duration exceptedDuration) {

    this.caller = Log.Caller.create();
    this.name = name;
    this.exceptedDuration = exceptedDuration;
  }

  @Nullable
  public Duration getExceptedDuration() {

    return exceptedDuration;
  }

  @NotNull
  public String getName() {

    return name;
  }

  public void setContext(@Nullable final String context) {

    this.context = context;
  }


  @Override
  public void close() {

    // hot-path free: the level check only consults the clock when a limit exists,
    // and the message (clock read + builder) is deferred to the log record's lazy
    // supplier, evaluated solely when the performance buffer is actually read
    final Level level = (exceptedDuration != null && isReachedLimit())? Level.WARNING : Level.INFO;
    Log.performance(level, () -> {
      final String passed = getTimePassed().toMillis() + "ms";
      final StringBuilder messageBuilder = new StringBuilder(name.length() + 48);
      messageBuilder.append("The task [").append(name).append("] ");
      if(context != null) {
        messageBuilder.append("(").append(context).append(") ");
      }
      messageBuilder.append("has finished in ").append(passed).append(".");
      if(level == Level.WARNING) {
        messageBuilder.append(" OVER LIMIT! The excepted time cost should less than ").append(exceptedDuration.toMillis()).append("ms.");
      }
      return messageBuilder.toString();
    }, caller);
  }

  @NotNull
  public Duration getTimePassed() {

    return Duration.ofNanos(System.nanoTime() - startNanos);
  }

  public boolean isReachedLimit() {

    if(exceptedDuration == null) {
      return false;
    }
    return getTimePassed().compareTo(exceptedDuration) > 0;
  }
}
