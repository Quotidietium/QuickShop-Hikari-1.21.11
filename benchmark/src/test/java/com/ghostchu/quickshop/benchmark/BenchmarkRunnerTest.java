package com.ghostchu.quickshop.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surefire-based launcher for the benchmark suites.
 * <p>
 * The forked exec:java JVM cannot retransform QuickShop with Mockito's inline mock maker
 * (the Maven JVM has byte-buddy appended to the bootstrap classpath), while Surefire's
 * known-good fork setup works — so the suites run inside a singleJUnit test.
 * <p>
 * Usage: {@code mvn test -Dtest=BenchmarkRunnerTest -Dbenchmark.label=baseline}
 */
class BenchmarkRunnerTest {

  @Test
  void runBenchmarkSuites() throws Exception {

    final String label = System.getProperty("benchmark.label", "run");
    final String note = System.getProperty("benchmark.note", "");
    final boolean ok = BenchmarkMain.run(new String[]{label, note});
    assertTrue(ok, "benchmark suites must not fail");
  }
}
