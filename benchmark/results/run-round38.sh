#!/usr/bin/env bash
# R38 A/B benchmark driver: baseline (cabc1f55f, alloc harness only) vs candidate
# (4539f4c38, allocation-reduction batch 1), 3 forks per side, strictly alternating
# to cancel machine-state drift. Full suite with the allocation axis enabled
# (-Dbenchmark.alloc=*), so every case reports ns/op AND B/op.
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=cabc1f55f
WT=/f/Github/repo/QuickShop-Hikari-1.21.11-baseline
if [ ! -d "$WT" ]; then
  git worktree add "$WT" "$BASELINE"
fi

for f in 1 2 3; do
  echo "=== fork $f: baseline install + run ==="
  (cd "$WT" && mvn -q install -pl quickshop-bukkit -am -DskipTests)
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round38-baseline "R38 baseline $BASELINE (pre alloc-reduction)")

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round38 "R38 candidate 4539f4c38 (alloc reduction batch 1)")
done
echo "ALL FORKS DONE"
