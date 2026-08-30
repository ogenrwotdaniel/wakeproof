#!/usr/bin/env python
"""Expand a shooting *sequence* into every labelled pair it contains.

Why this exists
---------------
docs/OFFLINE_WATER.md asks for 300+ pairs and assumes each one is a separate
before/after event, labelled by the cloud model. Both halves of that are more
expensive than they need to be.

A sequence is one bottle, photographed repeatedly as measured amounts are poured
out of it:

    shot 0   full            cumulative poured   0 ml
    shot 1   after a pour                      100 ml
    shot 2   after a pour                      220 ml
    ...

EVERY ordered pair (i, j) with i < j inside that sequence is a valid labelled
pair, and its truth is just ``cumulative[j] - cumulative[i]``. So n shots yield
n(n-1)/2 pairs, not n/2. Eight shots give 28 pairs. Five bottles x three
lightings x eight shots is 120 photographs and up to 420 pairs -- the whole
Phase 0 target in about two hours, instead of a fortnight of waiting on
opt-in telemetry.

It is also better data. The doc proposes using the cloud verdict as a weak label
and warns to treat its fill fractions as +/-5%. A measuring jug costs nothing
and gives EXACT truth, so the corpus stops inheriting the error of the model it
is meant to replace. You cannot measure whether you are within 5% of the truth
using a label that is itself only within 5% of the truth.

Usage
-----
    python sequences.py shots.csv > corpus/manifest.csv

``shots.csv`` is what you fill in while shooting -- one row per photograph:

    sequence,bottle,capacity_ml,lighting,filename,poured_total_ml[,crop,unreadable]
    s1,clear-750,750,daylight,IMG_2001.jpg,0
    s1,clear-750,750,daylight,IMG_2002.jpg,100
    s1,clear-750,750,daylight,IMG_2003.jpg,220

Order within a sequence is taken from ``poured_total_ml``, not from row order,
so a mis-ordered file list still expands correctly.
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import defaultdict
from pathlib import Path

#: Pairs whose true drop is below the app's MIN_CONSUMED_ML are not useful as
#: accuracy data -- the app would reject them regardless of what we measure --
#: but a few are worth keeping to check we do not INVENT a drop. See the app's
#: KimiRepository.MIN_CONSUMED_ML.
MIN_USEFUL_ML = 30.0


def expand(rows: list[dict[str, str]], max_per_sequence: int | None = None) -> list[dict[str, str]]:
    sequences: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        if not row.get("filename", "").strip():
            continue
        sequences[row.get("sequence", "s0").strip()].append(row)

    out: list[dict[str, str]] = []
    for name, shots in sorted(sequences.items()):
        shots.sort(key=lambda r: float(r["poured_total_ml"]))
        if len(shots) < 2:
            print(f"warning: sequence {name} has {len(shots)} shot(s), skipped",
                  file=sys.stderr)
            continue

        made = 0
        for i in range(len(shots)):
            for j in range(i + 1, len(shots)):
                before, after = shots[i], shots[j]
                poured = float(after["poured_total_ml"]) - float(before["poured_total_ml"])
                # Keep the sub-threshold pairs only as no-drop controls: a
                # detector that hallucinates 40 ml from a 5 ml pour is broken in
                # a way the accuracy metric alone would never show.
                if poured < MIN_USEFUL_ML and poured > 0:
                    continue
                out.append({
                    "pair_id": f"{name}_{i:02d}_{j:02d}",
                    "before": before["filename"].strip(),
                    "after": after["filename"].strip(),
                    "poured_ml": f"{poured:g}",
                    "capacity_ml": before.get("capacity_ml", "750").strip() or "750",
                    "bottle": before.get("bottle", "?").strip() or "?",
                    "lighting": before.get("lighting", "?").strip() or "?",
                    "crop": before.get("crop", "").strip(),
                    "unreadable": before.get("unreadable", "").strip(),
                    "notes": f"seq {name} shots {i}->{j}",
                })
                made += 1
                if max_per_sequence and made >= max_per_sequence:
                    break
            if max_per_sequence and made >= max_per_sequence:
                break
        print(f"{name}: {len(shots)} shots -> {made} pairs", file=sys.stderr)
    return out


FIELDS = ["pair_id", "before", "after", "poured_ml", "capacity_ml",
          "bottle", "lighting", "crop", "unreadable", "notes"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("shots", type=Path, help="the CSV you filled in while shooting")
    parser.add_argument("--max-per-sequence", type=int, default=None,
                        help="cap pairs per sequence, to keep the corpus balanced "
                             "across bottles rather than dominated by the longest run")
    parser.add_argument("-o", "--out", type=Path, help="write here instead of stdout")
    args = parser.parse_args()

    with args.shots.open(newline="") as handle:
        rows = [r for r in csv.DictReader(handle)
                if r.get("sequence", "").strip()
                and not r["sequence"].lstrip().startswith("#")]
    if not rows:
        raise SystemExit(f"{args.shots} held no shot rows")

    pairs = expand(rows, args.max_per_sequence)
    stream = args.out.open("w", newline="") if args.out else sys.stdout
    try:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(pairs)
    finally:
        if args.out:
            stream.close()
    print(f"\n{len(pairs)} pairs from {len(rows)} photographs", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
