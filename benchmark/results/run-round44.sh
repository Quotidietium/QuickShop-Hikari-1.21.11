#!/usr/bin/env bash
# R44 A/B benchmark driver: baseline (8e78643b3, R43 completion point) vs candidate
# (economy provider-resolution sweep), 3 forks per side, strictly alternating to cancel
# machine-state drift. Full suite with the allocation axis (-Dbenchmark.alloc=*), so
# every case reports ns/op AND B/op. No benchmark-module changes this round: the
# existing economy and trade suites already exercise the transaction-commit path whose
# operations used to re-resolve the economy provider through the services chain.
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=8e78643b3
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
    round44-baseline "R43 baseline $BASELINE (pre economy provider sweep)")

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round44 "R44 candidate (economy provider-resolution sweep)")
done
echo "ALL FORKS DONE"
