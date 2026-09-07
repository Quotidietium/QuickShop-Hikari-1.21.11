#!/usr/bin/env bash
# R46 A/B benchmark driver: baseline (ea77593a7, R45 completion point) vs candidate
# (permission-node memoization + RETRIEVE settings-event construction gates), 3 forks
# per side, strictly alternating to cancel machine-state drift. Full suite with the
# allocation axis (-Dbenchmark.alloc=*). No benchmark-module changes this round: the
# serialize suite (createDataRecord), db suite (updateShop/insertShopChain) and listener
# signRender already exercise the gated getters and permission-node resolution.
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=ea77593a7
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
    round46-baseline "R45 baseline $BASELINE (pre permission node and retrieve-event sweep)")

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round46 "R46 candidate (permission node and retrieve-event sweep)")
done
echo "ALL FORKS DONE"
