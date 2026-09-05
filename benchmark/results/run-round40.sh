#!/usr/bin/env bash
# R40 A/B benchmark driver: baseline (f97083504, R39 completion point; worktree carries
# only the benchmark-module case addition so both sides measure the same 50-case roster)
# vs candidate (94d3b4e08, locate-chain cost stripping), 3 forks per side, strictly
# alternating to cancel machine-state drift. Full suite with the allocation axis
# (-Dbenchmark.alloc=*), so every case reports ns/op AND B/op.
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=f97083504
WT=/f/Github/repo/QuickShop-Hikari-1.21.11-baseline
if [ ! -d "$WT" ]; then
  git worktree add "$WT" "$BASELINE"
fi

for f in 1 2 3; do
  echo "=== fork $f: baseline install + run ==="
  (cd "$WT" && mvn -q install -pl quickshop-bukkit -am -DskipTests)
  (cd "$WT/benchmark" && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt && \
    CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round40-baseline "R40 baseline $BASELINE (pre locate-chain strip)")

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round40 "R40 candidate 94d3b4e08 (locate-chain cost stripping)")
done
echo "ALL FORKS DONE"
