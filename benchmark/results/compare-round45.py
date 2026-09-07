#!/usr/bin/env python3
# R45 A/B comparison: round45-baseline (8f9693e1b, R44 done) vs round45
# (text render pipeline convergence). Median ns/op and median B/op across all
# forks; the B/op axis is near-deterministic, so it is the primary acceptance axis —
# ns/op still reports for drift detection but is expected noisy on a loaded machine.
import json, pathlib, statistics

base = pathlib.Path(__file__).parent

def load(label, field):
    cases = {}
    for f in sorted(base.glob(f"{label}-fork*.json")):
        if f.parent != base:
            continue
        d = json.load(open(f, encoding="utf-8"))
        for case in d["cases"]:
            v = case.get(field)
            if v is not None:
                cases.setdefault(case["name"], []).append(v)
    return cases

def dist(values):
    return "[" + ", ".join(f"{v:,.0f}" for v in sorted(values)) + "]"

a_ns, b_ns = load("round45-baseline", "medianNsPerOp"), load("round45", "medianNsPerOp")
a_b, b_b = load("round45-baseline", "medianBytesPerOp"), load("round45", "medianBytesPerOp")

common = sorted(set(a_ns) & set(b_ns))
print(f"shared cases: {len(common)}\n")

print("== B/op axis (primary; near-deterministic) ==")
print(f"{'case':40s} {'baseline B/op':>15s} {'R45 B/op':>15s} {'delta':>8s}   fork values (base | cand)")
for name in common:
    if name not in a_b or name not in b_b:
        continue
    ma, mb = statistics.median(a_b[name]), statistics.median(b_b[name])
    delta = f"{100*(mb-ma)/ma:+7.1f}%" if ma != 0 else f"{mb-ma:+9,.0f}B"
    print(f"{name:40s} {ma:15,.0f} {mb:15,.0f} {delta}   {dist(a_b[name])} | {dist(b_b[name])}")

print("\n== ns/op axis (reference only on this machine) ==")
for name in common:
    ma, mb = statistics.median(a_ns[name]), statistics.median(b_ns[name])
    print(f"{name:40s} {ma:15,.0f} {mb:15,.0f} {100*(mb-ma)/ma:+7.1f}%")
