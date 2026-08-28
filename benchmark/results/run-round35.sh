#!/usr/bin/env bash
# R34 A/B benchmark driver: baseline (af395aacb, tooling-only) vs candidate (c5efc353e,
# journaled inventory operations), 4 forks per side, strictly alternating to cancel
# machine-state drift.
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=84e88d04a
WT=/f/Github/repo/QuickShop-Hikari-1.21.11-baseline
if [ ! -d "$WT" ]; then
  git worktree add "$WT" "$BASELINE"
fi

for f in 1 2 3 4; do
  echo "=== fork $f: baseline install + run ==="
  (cd "$WT" && mvn -q install -pl quickshop-bukkit -am -DskipTests)
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.fork=$f com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round35-baseline "R35 baseline $BASELINE")

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.fork=$f com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round35 "R35 candidate dc60e1bc5 (scan pre-gate + hoisted counters)")
done
echo "ALL FORKS DONE"
