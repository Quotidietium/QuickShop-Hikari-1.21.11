#!/usr/bin/env python3
# R23 A/B comparison: round23-baseline (R22-closed HEAD 24522ac68) vs round23
# (browse-menu inventory-cache snapshot batching + pagination fix). Median ns/op, all forks.
import json, pathlib, statistics, sys

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

a, b = load("round23-baseline"), load("round23")
common = sorted(set(a) & set(b))
only_a, only_b = sorted(set(a) - set(b)), sorted(set(b) - set(a))

print(f"{'case':40s} {'baseline ns/op':>15s} {'r22 ns/op':>15s} {'delta':>8s}")
for name in common:
    ma, mb = statistics.median(a[name]), statistics.median(b[name])
    print(f"{name:40s} {ma:15,.0f} {mb:15,.0f} {100*(mb-ma)/ma:+7.1f}%")
for name in only_a:
    print(f"{name:40s} {statistics.median(a[name]):15,.0f} {'(absent)':>15s}")
for name in only_b:
    print(f"{name:40s} {'(absent)':>15s} {statistics.median(b[name]):15,.0f}  (candidate-only)")
