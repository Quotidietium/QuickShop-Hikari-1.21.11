package com.ghostchu.quickshop.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Minimal hand-rolled benchmark harness (JMH-style warmup/measure rounds without the
 * annotation processor, so it runs against arbitrary installed artifact versions).
 * <p>
 * Each case runs calibration-free batched loops: ops are executed until the batch exceeds
 * {@link #MIN_BATCH_MILLIS}, giving one sample (ns/op). After warmup samples are discarded,
 * {@link #SAMPLES} measured samples are collected and the median is reported. Ops must
 * funnel results through {@link #consume(Object)} to defeat dead-code elimination.
 */
public final class BenchHarness {

  /** Discarded warmup samples per case. */
  private static final int WARMUP_SAMPLES = 4;
  /** Measured samples per case; the median is reported. */
  private static final int SAMPLES = 7;
  /** Minimum wall time per sample; longer samples average out scheduler noise. */
  private static final long MIN_BATCH_MILLIS = 250;

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  /** Global dead-code-elimination guard; hashed op results land here. */
  private static final java.util.concurrent.atomic.AtomicLong BLACKHOLE =
          new java.util.concurrent.atomic.AtomicLong();

  private final Map<String, List<Double>> results = new LinkedHashMap<>();
  /**
   * Allocation per op (B/op) samples, filled only in allocation mode
   * ({@code -Dbenchmark.alloc}); keys parallel {@link #results}.
   */
  private final Map<String, List<Double>> allocResults = new LinkedHashMap<>();
  private final List<String> order = new ArrayList<>();

  /** HotSpot thread allocation counter; null when the JVM does not expose it. */
  private static final com.sun.management.ThreadMXBean ALLOC_THREAD_BEAN =
          ManagementFactory.getThreadMXBean() instanceof final com.sun.management.ThreadMXBean bean
                  && bean.isThreadAllocatedMemorySupported()? bean : null;

  public void bench(final String name, final Consumer<OpContext> op) {

    System.out.printf(">> %-46s ", name);
    final String profile = System.getProperty("benchmark.profile");
    if(profile != null && (profile.equals("*") || List.of(profile.split(",")).contains(name))) {
      benchProfiled(name, op);
      return;
    }
    final String alloc = System.getProperty("benchmark.alloc");
    if(alloc != null && (alloc.equals("*") || List.of(alloc.split(",")).contains(name))) {
      benchWithAlloc(name, op);
      return;
    }
    final List<Double> samples = new ArrayList<>(WARMUP_SAMPLES + SAMPLES);
    for(int i = 0; i < WARMUP_SAMPLES + SAMPLES; i++) {
      samples.add(sampleNsPerOp(op));
      System.out.print('.');
    }
    reportSamples(name, samples);
  }

  /**
   * Allocation-measuring variant: identical timing loop, plus a
   * {@code getThreadAllocatedBytes} snapshot at each sample boundary so each sample yields
   * both ns/op and B/op. The snapshot calls sit outside the timed window, so ns/op is
   * unaffected; B/op is nearly deterministic, which makes it a stable A/B axis where
   * mock-stack timing noise would drown small wins.
   */
  private void benchWithAlloc(final String name, final Consumer<OpContext> op) {

    if(ALLOC_THREAD_BEAN == null) {
      System.out.print(" [alloc unsupported, timing only]");
      final List<Double> samples = new ArrayList<>(WARMUP_SAMPLES + SAMPLES);
      for(int i = 0; i < WARMUP_SAMPLES + SAMPLES; i++) {
        samples.add(sampleNsPerOp(op));
        System.out.print('.');
      }
      reportSamples(name, samples);
      return;
    }
    final long threadId = Thread.currentThread().getId();
    final List<Double> samples = new ArrayList<>(WARMUP_SAMPLES + SAMPLES);
    final List<Double> bytesPerOp = new ArrayList<>(WARMUP_SAMPLES + SAMPLES);
    for(int i = 0; i < WARMUP_SAMPLES + SAMPLES; i++) {
      final OpContext context = new OpContext();
      long ops = 0;
      final long allocBefore = ALLOC_THREAD_BEAN.getThreadAllocatedBytes(threadId);
      final long start = System.nanoTime();
      final long deadline = start + MIN_BATCH_MILLIS * 1_000_000L;
      long now = start;
      while(now < deadline) {
        op.accept(context);
        ops++;
        now = System.nanoTime();
      }
      final long allocAfter = ALLOC_THREAD_BEAN.getThreadAllocatedBytes(threadId);
      final double ns = ops == 0? singleShotNs(op) : (now - start) / (double)ops;
      samples.add(ns);
      bytesPerOp.add(ops == 0? Double.NaN : Math.max(0, allocAfter - allocBefore) / (double)ops);
      System.out.print('.');
    }
    reportSamples(name, samples, bytesPerOp);
  }

  /** Reports and stores already-collected samples for a case. */
  private void reportSamples(final String name, final List<Double> samples) {

    reportSamples(name, samples, null);
  }

  /** Reports and stores already-collected samples, optionally with B/op samples. */
  private void reportSamples(final String name, final List<Double> samples, final List<Double> bytesPerOp) {

    final List<Double> measured = samples.subList(WARMUP_SAMPLES, samples.size());
    final double median = median(measured);
    results.put(name, measured);
    if(bytesPerOp != null) {
      allocResults.put(name, new ArrayList<>(bytesPerOp.subList(WARMUP_SAMPLES, bytesPerOp.size())));
    }
    order.add(name);
    if(bytesPerOp != null) {
      final List<Double> allocMeasured = bytesPerOp.subList(WARMUP_SAMPLES, bytesPerOp.size());
      final List<Double> finite = allocMeasured.stream().filter(d -> !d.isNaN()).toList();
      if(!finite.isEmpty()) {
        System.out.printf(" %,12.1f ns/op  (%,10.0f ops/s)  %,10.1f B/op%n", median, 1_000_000_000.0 / median, median(finite));
      } else {
        System.out.printf(" %,12.1f ns/op  (%,10.0f ops/s)  n/a B/op%n", median, 1_000_000_000.0 / median);
      }
    } else {
      System.out.printf(" %,12.1f ns/op  (%,10.0f ops/s)%n", median, 1_000_000_000.0 / median);
    }
    System.gc();
    try {
      Thread.sleep(50);
    } catch(final InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Poor-man's sampling profiler mode (off unless {@code -Dbenchmark.profile} selects case
   * names or {@code *}): while the measured samples run, a daemon thread samples the
   * benchmark thread's stack ~every 500 µs and prints the hottest leaf frames plus the
   * hottest top-most quickshop frames. Measurement is identical to the normal path; this
   * only adds a concurrent reader thread (used for exploration, not for A/B reports).
   */
  private void benchProfiled(final String name, final Consumer<OpContext> op) {

    System.out.print("[profiled] ");
    final Map<String, Integer> leafCounts = new java.util.HashMap<>();
    final Map<String, Integer> qsCounts = new java.util.HashMap<>();
    final Thread mainThread = Thread.currentThread();
    final AtomicBoolean running = new AtomicBoolean(true);
    final Thread sampler = new Thread(() -> {
      while(running.get()) {
        final StackTraceElement[] stack = mainThread.getStackTrace();
        if(stack.length > 0) {
          final StackTraceElement leaf = stack[0];
          leafCounts.merge(leaf.getClassName() + '.' + leaf.getMethodName() + ':' + leaf.getLineNumber(), 1, Integer::sum);
          for(final StackTraceElement frame : stack) {
            final String cls = frame.getClassName();
            if(cls.startsWith("com.ghostchu.") || cls.startsWith("org.maxgamer.")) {
              qsCounts.merge(cls + '.' + frame.getMethodName(), 1, Integer::sum);
              break;
            }
          }
        }
        try {
          Thread.sleep(0, 500_000);
        } catch(final InterruptedException ignored) {
          return;
        }
      }
    });
    sampler.setDaemon(true);
    sampler.start();
    final List<Double> samples = new ArrayList<>(WARMUP_SAMPLES + SAMPLES);
    for(int i = 0; i < WARMUP_SAMPLES + SAMPLES; i++) {
      samples.add(sampleNsPerOp(op));
      System.out.print('.');
    }
    running.set(false);
    reportSamples(name, samples);
    System.out.println("   -- profile: top leaf frames --");
    printTop(leafCounts, 20);
    System.out.println("   -- profile: top quickshop frames (top-most in stack) --");
    printTop(qsCounts, 25);
  }

  private void printTop(final Map<String, Integer> counts, final int limit) {

    counts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .forEach(e -> System.out.printf("   %6d  %s%n", e.getValue(), e.getKey()));
  }

  private double sampleNsPerOp(final Consumer<OpContext> op) {

    final OpContext context = new OpContext();
    long ops = 0;
    final long start = System.nanoTime();
    final long deadline = start + MIN_BATCH_MILLIS * 1_000_000L;
    long now = start;
    while(now < deadline) {
      op.accept(context);
      ops++;
      now = System.nanoTime();
    }
    if(ops == 0) {
      // op is slower than the batch window: run it once and time it precisely
      return singleShotNs(op);
    }
    return (now - start) / (double)ops;
  }

  /** Times a single op precisely (for ops slower than the batch window). */
  private double singleShotNs(final Consumer<OpContext> op) {

    final long singleStart = System.nanoTime();
    op.accept(new OpContext());
    return (System.nanoTime() - singleStart) * 1.0d;
  }

  public static double median(final List<Double> values) {

    final List<Double> sorted = new ArrayList<>(values);
    sorted.sort(Double::compareTo);
    final int mid = sorted.size() / 2;
    if(sorted.size() % 2 == 1) {
      return sorted.get(mid);
    }
    return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0d;
  }

  public static void consume(final Object value) {

    BLACKHOLE.addAndGet(System.identityHashCode(value));
  }

  public long blackholeValue() {

    return BLACKHOLE.get();
  }

  public Map<String, Object> toReport(final String label, final String commit, final String note) {

    final Map<String, Object> report = new LinkedHashMap<>();
    report.put("label", label);
    report.put("commit", commit);
    report.put("note", note);
    report.put("javaVersion", System.getProperty("java.version"));
    report.put("jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
    report.put("createdAt", java.time.Instant.now().toString());
    final List<Map<String, Object>> cases = new ArrayList<>();
    for(final String name : order) {
      final List<Double> measured = results.get(name);
      final Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", name);
      entry.put("medianNsPerOp", median(measured));
      final List<Double> all = new ArrayList<>(measured);
      all.sort(Double::compareTo);
      entry.put("minNsPerOp", all.get(0));
      entry.put("maxNsPerOp", all.get(all.size() - 1));
      entry.put("samples", measured);
      final List<Double> bytes = allocResults.get(name);
      if(bytes != null) {
        final List<Double> finite = bytes.stream().filter(d -> !d.isNaN()).toList();
        if(!finite.isEmpty()) {
          entry.put("medianBytesPerOp", median(finite));
        }
        entry.put("samplesBytesPerOp", bytes);
      }
      cases.add(entry);
    }
    report.put("cases", cases);
    return report;
  }

  public String toJson(final String label, final String commit, final String note) {

    return GSON.toJson(toReport(label, commit, note));
  }

  /** Per-invocation context; op implementations may use it for batch-style ops. */
  public static final class OpContext {

    public int index;
  }
}
