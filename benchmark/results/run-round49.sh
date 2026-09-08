#!/usr/bin/env bash
# R49 A/B benchmark driver: baseline (7810e0ec1, R48 completion point) vs candidate
# (listener click-path residuals: ExpiringSet deadline-map backing + dead
# InventoryInteractEvent handler removal + teleport move-event synthesis removal),
# 3 forks per side, strictly alternating. Full suite with the allocation axis
# (-Dbenchmark.alloc=*). The benchmark module change (listener/rateLimitGate case) is
# part of the candidate; the module is synced into the baseline worktree before the
# baseline build so both sides run identical benchmark sources (R40 precedent).
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=7810e0ec1
WT=/f/Github/repo/QuickShop-Hikari-1.21.11-baseline
if [ ! -d "$WT" ]; then
  git worktree add "$WT" "$BASELINE"
fi

for f in 1 2 3; do
  echo "=== fork $f: baseline install + run ==="
  # discard the previous round's synced benchmark files (tracked-modified) and any
  # untracked stragglers before switching baseline commits
  (cd "$WT" && git clean -qfd benchmark && git checkout -q -- benchmark/     && git checkout -q "$BASELINE")
  rm -rf "$WT/benchmark/src"
  cp -r benchmark/src "$WT/benchmark/src"
  cp benchmark/pom.xml "$WT/benchmark/pom.xml"
  (cd "$WT" && mvn -q install -pl quickshop-bukkit -am -DskipTests)
  (cd "$WT/benchmark" && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt && \
    CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round49-baseline "R48 baseline $BASELINE (pre listener click-path residuals)")
  # copy the fork's results out immediately: the next fork's git clean deletes them
  # from the worktree (this bit fork 1/2 of the recorded run; their B/op medians were
  # reconstructed from the run log — see the report's methodology note)
  cp "$WT"/benchmark/results/round49-baseline-fork$f.json benchmark/results/

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt && \
    CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round49 "R49 candidate (listener click-path residuals)")
done
echo "ALL FORKS DONE"
