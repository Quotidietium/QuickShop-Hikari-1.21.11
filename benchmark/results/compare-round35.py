#!/usr/bin/env python3
# R35 A/B comparison: round35-baseline (84e88d04a, pre-R35 scan layer)
# vs round35 (dc60e1bc5, scan pre-gate and hoisted counters).
# Median ns/op across all forks.
import json, pathlib, statistics

base = pathlib.Path(__file__).parent

def load(label):
    cases = {}
    for f in sorted(base.glob(f"{label}-fork*.json")):
        if f.parent != base:
            continue
        d = json.load(open(f, encoding="utf-8"))
        for case in d["cases"]:
            cases.setdefault(case["name"], []).append(case["medianNsPerOp"])
    return cases

def dist(values):
    return "[" + ", ".join(f"{v:,.0f}" for v in sorted(values)) + "]"

a, b = load("round35-baseline"), load("round35")
common = sorted(set(a) & set(b))
only_a, only_b = sorted(set(a) - set(b)), sorted(set(b) - set(a))

overlaps = []
print(f"{'case':40s} {'baseline ns/op':>15s} {'R35 ns/op':>15s} {'delta':>8s}   fork values (base | cand)")
for name in common:
    ma, mb = statistics.median(a[name]), statistics.median(b[name])
    sa, sb = sorted(a[name]), sorted(b[name])
    overlap = max(sa) > min(sb) and max(sb) > min(sa)
    if overlap:
        overlaps.append(name)
    print(f"{name:40s} {ma:15,.0f} {mb:15,.0f} {100*(mb-ma)/ma:+7.1f}%   {dist(a[name])} | {dist(b[name])}")
for name in only_a:
    print(f"{name:40s} {statistics.median(a[name]):15,.0f} {'(absent)':>15s}")
for name in only_b:
    print(f"{name:40s} {'(absent)':>15s} {statistics.median(b[name]):15,.0f}  (candidate-only)")

print(f"\nfork ranges overlap (no clean separation) on {len(overlaps)}/{len(common)} shared cases:")
for name in overlaps:
    print(f"  - {name}")
