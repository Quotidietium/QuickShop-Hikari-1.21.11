#!/usr/bin/env bash
# R45 A/B benchmark driver: baseline (8f9693e1b, R44 completion point) vs candidate
# (text render pipeline convergence), 3 forks per side, strictly alternating to cancel
# machine-state drift. Full suite with the allocation axis (-Dbenchmark.alloc=*), so
# every case reports ns/op AND B/op. No benchmark-module changes this round: the
# existing text suite (forLocaleWithArgs walks the pre-parsed render plus the
# FillerProcessor post-process pass) already exercises the changed path.
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=8f9693e1b
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
    round45-baseline "R44 baseline $BASELINE (pre text render pipeline convergence)")

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round45 "R45 candidate (text render pipeline convergence)")
done
echo "ALL FORKS DONE"
