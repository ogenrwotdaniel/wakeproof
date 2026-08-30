#!/usr/bin/env python
"""Measure the offline water-line detector against a corpus of real photo pairs.

This is the script that decides whether Phase 1 ships. The gate is set in
docs/OFFLINE_WATER.md and is repeated by `--gate`:

  * fill fraction within +/-5% of truth on >= 80% of pairs, and
  * `readable = False` on the pairs a human agrees are unreadable.

Recall on the reject class matters more than the regression error. A wrong
*number* only miscredits some millilitres against a target that has a 50 ml
tolerance and keeps asking for more photos; a wrong *readable* silently accepts
noise as a drink, which is the app's central promise failing quietly.

Usage
-----
    python evaluate.py --synthetic              # smoke run, no corpus needed
    python evaluate.py --corpus corpus/         # the real thing
    python evaluate.py --corpus corpus/ --overlays out/   # write annotated JPEGs
    python evaluate.py --corpus corpus/ --sweep           # fit the thresholds

Corpus layout
-------------
    corpus/
      manifest.csv
      images/001_before.jpg, 001_after.jpg, ...

`manifest.csv` columns (see README for the shooting protocol):

    pair_id      any unique string
    before,after filenames under images/
    poured_ml    MEASURED millilitres removed between the two shots. This is the
                 ground truth and it is why a jug beats a guess -- see README.
    capacity_ml  the bottle's full volume, i.e. the app's bottleCapacityMl
    bottle       an id for the physical bottle, for per-bottle breakdowns
    lighting     daylight | lamp | dim | mixed
    crop         optional "x,y,w,h" if the shot is not already bottle-framed
    unreadable   optional 1 when a human says no line is visible (reject class)
    notes        free text
"""

from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))

from waterline.detect import (  # noqa: E402
    CONFIDENCE_FLOOR,
    compare,
    detect,
    same_container,
)

GATE_FILL_TOLERANCE = 0.05
GATE_PASS_RATE = 0.80

#: Bottle name given to pairs manufactured by --swap-negatives. The same-bottle
#: check has no negative class otherwise: every pair in a depth corpus is, by
#: construction, the same bottle twice.
SWAP_PREFIX = "swapped:"


@dataclass
class Pair:
    pair_id: str
    before: np.ndarray
    after: np.ndarray
    poured_ml: float
    capacity_ml: float
    bottle: str
    lighting: str
    crop: tuple[int, int, int, int] | None
    unreadable: bool
    notes: str = ""

    @property
    def truth_fill_drop(self) -> float:
        """Expected drop in fill fraction, from a measured pour."""
        return self.poured_ml / max(self.capacity_ml, 1.0)


def _load_image(path: Path) -> np.ndarray:
    from PIL import Image, ImageOps

    with Image.open(path) as handle:
        # EXIF transpose matters: a phone shot is usually stored rotated, and a
        # sideways bottle makes the water line vertical, which defeats the whole
        # method. Silently getting this wrong would read as an algorithm failure.
        return np.asarray(ImageOps.exif_transpose(handle).convert("RGB"), dtype=np.uint8)


def load_corpus(root: Path) -> list[Pair]:
    manifest = root / "manifest.csv"
    if not manifest.exists():
        raise SystemExit(f"no manifest at {manifest} -- see the README's shooting protocol")

    images = root / "images"
    pairs: list[Pair] = []
    with manifest.open(newline="") as handle:
        for row in csv.DictReader(handle):
            if not row.get("pair_id", "").strip() or row["pair_id"].lstrip().startswith("#"):
                continue
            crop = None
            if row.get("crop", "").strip():
                x, y, w, h = (int(v) for v in row["crop"].split(","))
                crop = (x, y, w, h)
            pairs.append(
                Pair(
                    pair_id=row["pair_id"].strip(),
                    before=_load_image(images / row["before"].strip()),
                    after=_load_image(images / row["after"].strip()),
                    poured_ml=float(row.get("poured_ml") or 0),
                    capacity_ml=float(row.get("capacity_ml") or 750),
                    bottle=row.get("bottle", "").strip() or "?",
                    lighting=row.get("lighting", "").strip() or "?",
                    crop=crop,
                    unreadable=str(row.get("unreadable", "")).strip() in {"1", "true", "yes"},
                    notes=row.get("notes", "").strip(),
                )
            )
    if not pairs:
        raise SystemExit(f"{manifest} parsed but held no rows")
    return pairs


def synthetic_pairs(count: int = 24, seed: int = 3) -> list[Pair]:
    """Smoke corpus so `--synthetic` runs with no photographs.

    These are NOT evidence about real accuracy -- see the warning in synth.py.
    """
    from waterline.synth import render

    rng = np.random.default_rng(seed)
    pairs: list[Pair] = []
    for i in range(count):
        capacity = float(rng.choice([500, 750, 1000]))
        before_fill = float(rng.uniform(0.45, 0.95))
        poured = float(rng.integers(4, 22) * 10)
        after_fill = max(0.05, before_fill - poured / capacity)
        opaque = i % 8 == 7
        kwargs = {"opacity": 1.0} if opaque else {}
        pairs.append(
            Pair(
                pair_id=f"syn{i:03d}",
                before=render(before_fill, seed=i, **kwargs).rgb,
                after=render(after_fill, seed=i, **kwargs).rgb,
                poured_ml=round((before_fill - after_fill) * capacity),
                capacity_ml=capacity,
                # Four nominal bottles rather than one, so --swap-negatives has
                # something to cross. Each renders from its own seed, so they
                # really are different scenes.
                bottle=f"synthetic-{i % 4}",
                lighting="synthetic",
                crop=None,
                unreadable=opaque,
            )
        )
    return pairs


def swap_negatives(pairs: list[Pair], limit: int | None = None) -> list[Pair]:
    """Manufacture "different bottle" pairs by crossing the corpus over itself.

    Free negatives. A depth corpus holds several bottles, so the BEFORE of one
    bottle against the AFTER of another is exactly the case the same-bottle
    check exists to catch — with no extra shooting at all. Without these the
    check can only ever be measured on false rejections, which flatters any
    threshold low enough to accept everything.

    Only crosses pairs whose bottle names differ, so two shots of the same
    bottle in different light are never mislabelled as a swap.
    """
    out: list[Pair] = []
    for i, first in enumerate(pairs):
        for second in pairs[i + 1:]:
            if first.bottle == second.bottle or first.before.shape != second.after.shape:
                continue
            out.append(
                Pair(
                    pair_id=f"swap_{first.pair_id}_{second.pair_id}",
                    before=first.before,
                    after=second.after,
                    # Truth is meaningless for a swap: the pair exists only to
                    # be rejected, so it is marked unreadable to keep it out of
                    # the fill-accuracy numbers.
                    poured_ml=0.0,
                    capacity_ml=first.capacity_ml,
                    bottle=f"{SWAP_PREFIX}{first.bottle}/{second.bottle}",
                    lighting=first.lighting,
                    crop=first.crop,
                    unreadable=False,
                    notes="manufactured swap",
                )
            )
            if limit and len(out) >= limit:
                return out
    return out


def _crop(image: np.ndarray, box: tuple[int, int, int, int] | None) -> np.ndarray:
    if box is None:
        return image
    x, y, w, h = box
    return image[y: y + h, x: x + w]


@dataclass
class Result:
    pair: Pair
    estimated_drop: float
    estimated_ml: float
    readable: bool
    confidence: float
    refilled: bool
    #: Verdict of the same-bottle check, and its 0..1 score.
    same_container: bool = True
    container_score: float = 1.0

    @property
    def fill_error(self) -> float:
        return abs(self.estimated_drop - self.pair.truth_fill_drop)

    @property
    def ml_error(self) -> float:
        return abs(self.estimated_ml - self.pair.poured_ml)


def run(pairs: list[Pair], floor: float = CONFIDENCE_FLOOR) -> list[Result]:
    results: list[Result] = []
    for pair in pairs:
        before = _crop(pair.before, pair.crop)
        after = _crop(pair.after, pair.crop)
        first = detect(before, confidence_floor=floor)
        second = detect(after, confidence_floor=floor)
        drop = first.fill - second.fill
        same, container_score = same_container(before, after, first.line, second.line)
        results.append(
            Result(
                pair=pair,
                estimated_drop=drop,
                estimated_ml=drop * pair.capacity_ml,
                readable=first.readable and second.readable,
                confidence=min(first.confidence, second.confidence),
                refilled=second.fill > first.fill + 0.02,
                same_container=same,
                container_score=container_score,
            )
        )
    return results


def report(results: list[Result], verbose: bool = False) -> bool:
    """Print the numbers and return whether the docs/OFFLINE_WATER.md gate passes."""
    swapped = [r for r in results if r.pair.bottle.startswith(SWAP_PREFIX)]
    scored = [r for r in results if r not in swapped]
    measurable = [r for r in scored if not r.pair.unreadable]
    rejects = [r for r in scored if r.pair.unreadable]

    print(f"\n{'=' * 74}\n{len(results)} pairs: "
          f"{len(measurable)} measurable, {len(rejects)} human-marked unreadable\n{'=' * 74}")

    if measurable:
        fill_errors = np.array([r.fill_error for r in measurable])
        ml_errors = np.array([r.ml_error for r in measurable])
        within = float((fill_errors <= GATE_FILL_TOLERANCE).mean())
        kept = [r for r in measurable if r.readable]

        print(f"\nfill-drop error   MAE {fill_errors.mean():.4f}   "
              f"median {np.median(fill_errors):.4f}   p90 {np.percentile(fill_errors, 90):.4f}")
        print(f"ml error          MAE {ml_errors.mean():6.1f} ml   "
              f"median {np.median(ml_errors):5.1f} ml   p90 {np.percentile(ml_errors, 90):5.1f} ml")
        print(f"within +/-5% fill {within * 100:.1f}%  (gate: >= {GATE_PASS_RATE * 100:.0f}%)")
        print(f"reported readable {len(kept)}/{len(measurable)}")
        # A pair the app would wrongly reject costs the user a retry; a pair it
        # wrongly accepts costs the app its promise. Both are worth seeing.
        false_refills = [r for r in measurable if r.refilled and r.pair.poured_ml > 0]
        if false_refills:
            print(f"!! {len(false_refills)} measured pours read as REFILLS: "
                  + ", ".join(r.pair.pair_id for r in false_refills[:8]))

        for key in ("lighting", "bottle"):
            groups: dict[str, list[Result]] = {}
            for r in measurable:
                groups.setdefault(getattr(r.pair, key), []).append(r)
            if len(groups) > 1:
                print(f"\n  by {key}:")
                for name, rows in sorted(groups.items()):
                    e = np.array([r.fill_error for r in rows])
                    print(f"    {name:14s} n={len(rows):3d}  MAE={e.mean():.4f}  "
                          f"<=5%={(e <= GATE_FILL_TOLERANCE).mean() * 100:5.1f}%  "
                          f"readable={sum(r.readable for r in rows)}/{len(rows)}")
    else:
        within = 0.0

    # Same-bottle check. Every pair in a depth corpus IS the same bottle, so
    # this half only measures false rejections -- the ones the user sees. The
    # negative class comes from --swap-negatives, which pairs shots across
    # bottles for free.
    genuine = scored
    if genuine:
        accepted = sum(1 for r in genuine if r.same_container)
        print(f"\nsame bottle       {accepted}/{len(genuine)} accepted "
              f"(min score {min(r.container_score for r in genuine):.2f})")
        for r in genuine:
            if not r.same_container:
                print(f"    FALSE SWAP {r.pair.pair_id}: score {r.container_score:.2f} "
                      f"-- lower SAME_CONTAINER_FLOOR below this")
    if swapped:
        caught = sum(1 for r in swapped if not r.same_container)
        print(f"swapped bottle    {caught}/{len(swapped)} correctly rejected "
              f"(max score {max(r.container_score for r in swapped):.2f})")

    reject_recall = 1.0
    if rejects:
        caught = sum(1 for r in rejects if not r.readable)
        reject_recall = caught / len(rejects)
        print(f"\nreject class      {caught}/{len(rejects)} correctly called unreadable "
              f"({reject_recall * 100:.0f}%)")
        for r in rejects:
            if r.readable:
                print(f"    LEAKED {r.pair.pair_id}: confidence {r.confidence:.1f} "
                      f"-- raise CONFIDENCE_FLOOR above this")

    if verbose and measurable:
        print(f"\n{'pair':10s} {'truth':>8} {'est':>8} {'err':>7} {'ml err':>8} "
              f"{'conf':>7}  rd  bottle/light")
        for r in sorted(measurable, key=lambda r: -r.fill_error):
            print(f"{r.pair.pair_id:10s} {r.pair.truth_fill_drop:8.3f} "
                  f"{r.estimated_drop:8.3f} {r.fill_error:7.3f} {r.ml_error:8.1f} "
                  f"{r.confidence:7.1f}  {'Y' if r.readable else 'n'}   "
                  f"{r.pair.bottle}/{r.pair.lighting}")

    passed = within >= GATE_PASS_RATE and reject_recall >= 1.0 - 1e-9
    print(f"\n{'GATE PASSED' if passed else 'GATE NOT MET'} "
          f"-- fill {within * 100:.1f}%/{GATE_PASS_RATE * 100:.0f}%, "
          f"reject recall {reject_recall * 100:.0f}%/100%")
    if not results or all(r.pair.bottle.startswith("synthetic") for r in results):
        print("NOTE: synthetic corpus. This says nothing about real accuracy.")
    return passed


def write_overlays(results: list[Result], out: Path) -> None:
    """Annotate each photo with the detected line, for eyeballing failures.

    Looking at the picture is how you find out that the bottle was sideways, the
    label was in the way, or the framing guide was ignored -- none of which show
    up in a MAE.
    """
    from PIL import Image, ImageDraw

    out.mkdir(parents=True, exist_ok=True)
    for r in results:
        for tag, image in (("before", r.pair.before), ("after", r.pair.after)):
            cropped = _crop(image, r.pair.crop)
            observation = detect(cropped)
            canvas = Image.fromarray(cropped).convert("RGB")
            draw = ImageDraw.Draw(canvas)
            y = int(observation.line * (canvas.height - 1))
            left, right = observation.columns
            colour = (60, 220, 90) if observation.readable else (240, 70, 70)
            draw.line([(0, y), (canvas.width, y)], fill=colour, width=3)
            draw.rectangle([left, 0, right, canvas.height - 1], outline=(80, 160, 255), width=2)
            draw.text((6, 6), f"{tag} fill={observation.fill:.3f} "
                              f"t={observation.confidence:.1f} "
                              f"{'readable' if observation.readable else 'UNREADABLE'}",
                      fill=colour)
            canvas.save(out / f"{r.pair.pair_id}_{tag}.jpg", quality=88)
    print(f"\noverlays -> {out}  (blue box = profiled columns, line = detected level)")


def sweep(pairs: list[Pair]) -> None:
    """Fit CONFIDENCE_FLOOR against the corpus.

    Only the floor is swept here. The geometric tunables (band span, smoothing)
    were chosen on synthetic data and should be re-fitted the same way ONLY once
    the corpus is big enough to hold out a slice -- fitting them on all of it
    would report its own training error as accuracy.
    """
    print("\nCONFIDENCE_FLOOR sweep")
    print(f"{'floor':>8} {'kept':>12} {'rejects caught':>15} {'MAE(kept)':>11}")
    for floor in (2, 4, 6, 8, 12, 16, 24, 32, 48, 64):
        results = run(pairs, floor=float(floor))
        measurable = [r for r in results if not r.pair.unreadable]
        rejects = [r for r in results if r.pair.unreadable]
        kept = [r for r in measurable if r.readable]
        caught = sum(1 for r in rejects if not r.readable)
        mae = np.mean([r.fill_error for r in kept]) if kept else float("nan")
        print(f"{floor:8d} {len(kept):5d}/{len(measurable):<6d} "
              f"{caught:7d}/{len(rejects):<7d} {mae:11.4f}")
    print("\nPick the highest floor that still keeps the pairs you want kept:\n"
          "  a lost readable pair costs the user one retry, a leaked unreadable\n"
          "  pair costs the app its promise.")


def write_line_overlays(results: list, out: Path) -> None:
    """Annotate line-labelled singles with BOTH the truth and the detection.

    Seeing the two lines together is the only way to tell a detector error from a
    bad label, and mask-derived labels are only as good as the annotator's
    polygon.
    """
    from PIL import Image, ImageDraw

    from waterline.lines import crop_of

    out.mkdir(parents=True, exist_ok=True)
    for r in results:
        cropped = crop_of(r.sample)
        canvas = Image.fromarray(cropped).convert("RGB")
        draw = ImageDraw.Draw(canvas)
        truth_y = int(r.sample.line * (canvas.height - 1))
        got_y = int(r.detected * (canvas.height - 1))
        draw.line([(0, truth_y), (canvas.width, truth_y)], fill=(255, 210, 40), width=3)
        colour = (60, 220, 90) if r.error <= 0.05 else (240, 70, 70)
        draw.line([(0, got_y), (canvas.width, got_y)], fill=colour, width=2)
        draw.text((6, 6), f"truth={r.sample.line:.3f} got={r.detected:.3f} "
                          f"err={r.error:.3f} t={r.confidence:.1f}", fill=colour)
        canvas.save(out / f"{r.sample.sample_id.replace('/', '_')}.jpg", quality=88)
    print(f"\noverlays -> {out}  (yellow = label, green/red = detection)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--corpus", type=Path,
                        help="DEPTH: directory holding manifest.csv + images/ (measured pours)")
    source.add_argument("--lines", type=Path,
                        help="BREADTH: lines.csv of single images with water-line labels")
    source.add_argument("--synthetic", action="store_true", help="smoke run, no photos needed")
    parser.add_argument("--images", type=Path, help="images root for --lines (default: alongside it)")
    parser.add_argument("--overlays", type=Path, help="write annotated JPEGs here")
    parser.add_argument("--sweep", action="store_true", help="fit CONFIDENCE_FLOOR")
    parser.add_argument("--swap-negatives", type=int, nargs="?", const=60, default=0,
                        metavar="N",
                        help="add up to N manufactured different-bottle pairs, to "
                             "measure the same-bottle check against a negative class")
    parser.add_argument("-v", "--verbose", action="store_true", help="per-item table")
    parser.add_argument("--gate", action="store_true", help="exit non-zero unless the gate passes")
    args = parser.parse_args()

    # BREADTH: unpaired, line-labelled. Measures perception across bottle
    # shapes; says nothing about millilitre accuracy. See waterline/lines.py.
    if args.lines:
        from waterline import lines as lines_module

        samples = lines_module.load(args.lines, args.images)
        results = lines_module.evaluate(samples)
        passed = lines_module.report(results, verbose=args.verbose)
        if args.overlays:
            write_line_overlays(results, args.overlays)
        return 0 if (passed or not args.gate) else 1

    pairs = synthetic_pairs() if args.synthetic else load_corpus(args.corpus)
    if args.swap_negatives:
        swaps = swap_negatives(pairs, args.swap_negatives)
        print(f"+{len(swaps)} manufactured swapped-bottle pairs", file=sys.stderr)
        pairs = pairs + swaps
    results = run(pairs)
    passed = report(results, verbose=args.verbose)

    if args.sweep:
        sweep(pairs)
    if args.overlays:
        write_overlays(results, args.overlays)
    return 0 if (passed or not args.gate) else 1


if __name__ == "__main__":
    raise SystemExit(main())
