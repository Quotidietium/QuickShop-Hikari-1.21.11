#!/usr/bin/env bash
# R51 A/B benchmark driver: baseline (214f9795f, R50 completion point) vs candidate
# (click-entry measurement collapse: info-panel single snapshot + single scan,
# listener-gated sign-render dedup against onClick, out-of-space/stock gate sharing
# its measurement with the cap calculation, and the transaction/operation item
# adoption path), 3 forks per side, strictly alternating. Full suite with the
# allocation axis (-Dbenchmark.alloc=*). The benchmark module change (the new
# trade/clickTradeEntry case) is part of the candidate; the module is synced into the
# baseline worktree before the baseline build so both sides run identical benchmark
# sources (R40 precedent).
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=214f9795f
WT=/f/Github/repo/QuickShop-Hikari-1.21.11-baseline
if [ ! -d "$WT" ]; then
  git worktree add "$WT" "$BASELINE"
fi

for f in 1 2 3; do
  echo "=== fork $f: baseline install + run ==="
  # discard the previous round's synced benchmark files (tracked-modified) and any
  # untracked stragglers before switching baseline commits (R49 methodology)
  (cd "$WT" && git clean -qfd benchmark && git checkout -q -- benchmark/ && git checkout -q "$BASELINE")
  rm -rf "$WT/benchmark/src"
  cp -r benchmark/src "$WT/benchmark/src"
  cp benchmark/pom.xml "$WT/benchmark/pom.xml"
  (cd "$WT" && mvn -q install -pl quickshop-bukkit -am -DskipTests)
  (cd "$WT/benchmark" && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt && \
    CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round51-baseline "R51 baseline $BASELINE (pre click-entry measurement collapse)")
  # copy the fork's results out immediately: the next fork's git clean deletes them
  # from the worktree (R48 methodology)
  cp "$WT"/benchmark/results/round51-baseline-fork$f.json benchmark/results/

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt && \
    CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round51 "R51 candidate (click-entry measurement collapse)")
done
echo "ALL FORKS DONE"
