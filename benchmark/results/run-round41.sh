#!/usr/bin/env bash
# R41 A/B benchmark driver: baseline (b056e6c56, R40 completion point; worktree carries
# only the benchmark-module MenuBench case addition so both sides measure the same
# 51-case roster) vs candidate (a59e5293a, browse-menu item access costs), 3 forks per
# side, strictly alternating to cancel machine-state drift. Full suite with the
# allocation axis (-Dbenchmark.alloc=*), so every case reports ns/op AND B/op.
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=b056e6c56
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
    round41-baseline "R41 baseline $BASELINE (pre menu item access)")

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round41 "R41 candidate a59e5293a (menu item access costs)")
done
echo "ALL FORKS DONE"
