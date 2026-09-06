#!/usr/bin/env bash
# R43 A/B benchmark driver: baseline (c2f2cfff6, R42 completion point; worktree carries
# only the benchmark-module ListenerBench case additions so both sides measure the same
# 53-case roster) vs candidate (e24a83b32, block-state snapshot gate sweep), 3 forks per
# side, strictly alternating to cancel machine-state drift. Full suite with the
# allocation axis (-Dbenchmark.alloc=*), so every case reports ns/op AND B/op.
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=c2f2cfff6
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
    round43-baseline "R42 baseline $BASELINE (pre snapshot gate sweep)")

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round43 "R43 candidate e24a83b32 (block-state snapshot gate sweep)")
done
echo "ALL FORKS DONE"
