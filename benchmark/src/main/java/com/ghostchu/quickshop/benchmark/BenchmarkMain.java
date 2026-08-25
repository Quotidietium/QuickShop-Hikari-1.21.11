package com.ghostchu.quickshop.benchmark;

import com.ghostchu.quickshop.benchmark.suite.DataRecordBench;
import com.ghostchu.quickshop.benchmark.suite.DbBench;
import com.ghostchu.quickshop.benchmark.suite.EconomyBench;
import com.ghostchu.quickshop.benchmark.suite.ListenerBench;
import com.ghostchu.quickshop.benchmark.suite.MenuBench;
import com.ghostchu.quickshop.benchmark.suite.ShopLookupBench;
import com.ghostchu.quickshop.benchmark.suite.TextBench;
import com.ghostchu.quickshop.benchmark.suite.TradeBench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point: runs every benchmark suite and writes a JSON report to
 * {@code benchmark/results/<label>-<fork>.json}.
 * <p>
 * Usage: {@code mvn exec:java -Dexec.args="<label> <note>"} — label defaults to
 * "run", note defaults to empty. The current git commit is captured automatically.
 */
public final class BenchmarkMain {

  private BenchmarkMain() {

  }

  public static void main(final String[] args) throws Exception {

    final boolean ok = run(args);
    if(!ok) {
      System.exit(2);
    }
  }

  /**
   * Runs every suite and writes the JSON report; returns false when any suite failed.
   * Split from {@link #main(String[])} so JUnit-based launchers can call it without
   * triggering {@code System.exit}.
   */
  public static boolean run(final String[] args) throws Exception {

    final String label = args.length > 0? args[0] : "run";
    final String note = args.length > 1? args[1] : "";
    final String commit = currentCommit();

    System.out.println("QuickShop-Hikari benchmark harness");
    System.out.println("  label : " + label);
    System.out.println("  commit: " + commit);
    System.out.println("  jvm   : " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
    System.out.println();

    // touch Env first so QuickShop.instance exists before any QuickShop class initializes
    Env.plugin();

    final BenchHarness harness = new BenchHarness();
    final List<String> failures = new ArrayList<>();

    runSuite(harness, failures, "shop-lookup", ShopLookupBench::run);
    runSuite(harness, failures, "trade", TradeBench::run);
    runSuite(harness, failures, "listener", ListenerBench::run);
    runSuite(harness, failures, "menu", MenuBench::run);
    runSuite(harness, failures, "data-record", DataRecordBench::run);
    runSuite(harness, failures, "economy", EconomyBench::run);
    runSuite(harness, failures, "text", TextBench::run);
    runSuite(harness, failures, "database", DbBench::run);

    final Path resultsDir = Path.of("results");
    Files.createDirectories(resultsDir);
    final int fork = Integer.parseInt(System.getProperty("benchmark.fork", "1"));
    final Path outFile = resultsDir.resolve(label + "-fork" + fork + ".json");
    Files.writeString(outFile, harness.toJson(label, commit, note), StandardCharsets.UTF_8);
    System.out.println();
    System.out.println("results written to " + outFile.toAbsolutePath());
    System.out.println("blackhole: " + harness.blackholeValue());

    if(!failures.isEmpty()) {
      System.out.println();
      System.out.println("FAILED SUITES:");
      failures.forEach(f -> System.out.println("  - " + f));
      return false;
    }
    return true;
  }

  @FunctionalInterface
  private interface SuiteRunner {

    void run(BenchHarness harness) throws Exception;
  }

  private static void runSuite(final BenchHarness harness, final List<String> failures,
                               final String name, final SuiteRunner runner) {

    System.out.println("== suite: " + name + " ==");
    final long start = System.currentTimeMillis();
    try {
      runner.run(harness);
      System.out.println("   suite ok (" + (System.currentTimeMillis() - start) + " ms)");
    } catch(final Throwable t) {
      failures.add(name + ": " + t);
      System.out.println("   suite FAILED: " + t);
      t.printStackTrace(System.out);
    }
    System.out.println();
  }

  private static String currentCommit() {

    try {
      final Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
              .redirectErrorStream(true)
              .start();
      final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      process.waitFor();
      return output.isEmpty()? "unknown" : output;
    } catch(final Exception e) {
      return "unknown";
    }
  }
}
