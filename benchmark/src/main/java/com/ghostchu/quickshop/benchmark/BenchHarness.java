package com.ghostchu.quickshop.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
  private final List<String> order = new ArrayList<>();

  public void bench(final String name, final Consumer<OpContext> op) {

    System.out.printf(">> %-46s ", name);
    final String profile = System.getProperty("benchmark.profile");
    if(profile != null && (profile.equals("*") || List.of(profile.split(",")).contains(name))) {
      benchProfiled(name, op);
      return;
    }
    final List<Double> samples = new ArrayList<>(WARMUP_SAMPLES + SAMPLES);
    for(int i = 0; i < WARMUP_SAMPLES + SAMPLES; i++) {
      samples.add(sampleNsPerOp(op));
      System.out.print('.');
    }
    reportSamples(name, samples);
  }

  /** Reports and stores already-collected samples for a case. */
  private void reportSamples(final String name, final List<Double> samples) {

    final List<Double> measured = samples.subList(WARMUP_SAMPLES, samples.size());
    final double median = median(measured);
    results.put(name, measured);
    order.add(name);
    System.out.printf(" %,12.1f ns/op  (%,10.0f ops/s)%n", median, 1_000_000_000.0 / median);
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
      final long singleStart = System.nanoTime();
      op.accept(context);
      return (System.nanoTime() - singleStart) * 1.0d;
    }
    return (now - start) / (double)ops;
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
