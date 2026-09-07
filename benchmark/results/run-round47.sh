#!/usr/bin/env bash
# R47 A/B benchmark driver: baseline (d929f30f2, R46 completion point) vs candidate
# (single-pass getShop location lookup + listShops finder hoist), 3 forks per side,
# strictly alternating. Full suite with the allocation axis (-Dbenchmark.alloc=*). No
# benchmark-module changes this round: the lookup suite measures getShop directly and
# the db suite measures listShops.
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=d929f30f2
WT=/f/Github/repo/QuickShop-Hikari-1.21.11-baseline
if [ ! -d "$WT" ]; then
  git worktree add "$WT" "$BASELINE"
fi

for f in 1 2 3; do
  echo "=== fork $f: baseline install + run ==="
  (cd "$WT" && git checkout -q "$BASELINE" && mvn -q install -pl quickshop-bukkit -am -DskipTests)
  (cd "$WT/benchmark" && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt && \
    CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round47-baseline "R46 baseline $BASELINE (pre lookup-chain tail sweep)")

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round47 "R47 candidate (lookup-chain tail sweep)")
done
echo "ALL FORKS DONE"
