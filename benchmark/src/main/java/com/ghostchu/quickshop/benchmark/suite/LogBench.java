package com.ghostchu.quickshop.benchmark.suite;

import com.ghostchu.quickshop.benchmark.BenchHarness;
import com.ghostchu.quickshop.util.logger.Log;

/**
 * Measures the in-memory log pipeline that backs every trade, inventory and debug
 * call: record append into the shared ring (string form is used so both sides of an
 * A/B run compile against the same API; the hot-path lazy-supplier form is exercised
 * indirectly by the trade/economy suites).
 */
public final class LogBench {

  private LogBench() {

  }

  public static void run(final BenchHarness harness) throws Exception {

    harness.bench("log/appendTransaction", ctx -> {
      ctx.index++;
      Log.transaction("bench txn op #" + ctx.index + " amount 12.34 to owner account");
    });

    harness.bench("log/appendDebug", ctx -> {
      ctx.index++;
      Log.debug("bench debug op #" + ctx.index + " remains 32 stackSize 32 target STONE");
    });
  }
}
