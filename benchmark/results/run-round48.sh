#!/usr/bin/env bash
# R48 A/B benchmark driver: baseline (86f7ebcf1, R47 completion point) vs candidate
# (click → quick-create gate chain: createShop gate reorder + kill-switch snapshot +
# interaction dispatch OrNull resolution), 3 forks per side, strictly alternating.
# Full suite with the allocation axis (-Dbenchmark.alloc=*). The benchmark module
# changes (3 listener cases + RegistryAccess provider) are part of the candidate; the
# module is synced into the baseline worktree before the baseline build so both sides
# run identical benchmark sources against their own jars (R40 baseline-sync precedent).
set -euo pipefail
cd /f/Github/repo/QuickShop-Hikari-1.21.11

BASELINE=86f7ebcf1
WT=/f/Github/repo/QuickShop-Hikari-1.21.11-baseline
if [ ! -d "$WT" ]; then
  git worktree add "$WT" "$BASELINE"
fi

for f in 1 2 3; do
  echo "=== fork $f: baseline install + run ==="
  (cd "$WT" && git clean -qfd benchmark && git checkout -q "$BASELINE")
  rm -rf "$WT/benchmark/src"
  cp -r benchmark/src "$WT/benchmark/src"
  cp benchmark/pom.xml "$WT/benchmark/pom.xml"
  (cd "$WT" && mvn -q install -pl quickshop-bukkit -am -DskipTests)
  (cd "$WT/benchmark" && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt && \
    CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round48-baseline "R47 baseline $BASELINE (pre click-create gate sweep)")
  # copy the fork's results out immediately: the next fork's git clean deletes them
  # from the worktree (this bit fork 1/2 of the recorded run; their B/op medians were
  # reconstructed from the run log — see the report's methodology note)
  cp "$WT"/benchmark/results/round48-baseline-fork$f.json benchmark/results/

  echo "=== fork $f: candidate install + run ==="
  mvn -q install -pl quickshop-bukkit -am -DskipTests
  (cd benchmark && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt && \
    CP="target/classes;$(cat target/cp.txt)" && \
    java -cp "$CP" -Dbenchmark.alloc="*" -Dbenchmark.fork=$f \
    com.ghostchu.quickshop.benchmark.BenchmarkMain \
    round48 "R48 candidate (click-create gate sweep)")
done
echo "ALL FORKS DONE"
