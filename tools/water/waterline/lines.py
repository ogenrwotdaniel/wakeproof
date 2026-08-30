"""Single-image, line-labelled corpora — the shape internet data comes in.

`evaluate.py` is built around before/after PAIRS with a measured pour, because
that is what the app actually does and the only way to check the millilitre
arithmetic end to end. No public dataset has that and none ever will: it needs
someone standing at a sink with a measuring jug.

But pairs are the wrong instrument for the question "does the line get found on
a bottle shape I have never seen". That question needs only ONE image and ONE
number: the y of the water line. Which means it can be answered with any pile of
bottle photographs, from anywhere, plus a label we supply ourselves.

So the corpus is deliberately split in two:

    breadth  -- many bottles, line-position labels, unpaired.  THIS MODULE.
                Sources: public datasets, web images, your own snaps.
                Answers: does perception generalise across shapes and lighting.

    depth    -- few bottles, measured pours, paired.  `evaluate.py --corpus`.
                Source: only you, with a jug.
                Answers: does a 120 ml pour read as 120 ml.

The metric here is deliberately **line position within the given crop**, not
fill fraction. `detect()` converts line to fill by assuming the crop IS the
water column (top = brim, bottom = base) -- which the app guarantees with its
framing guide and a random internet photograph does not. Scoring line position
measures the perception without contaminating it with a framing assumption that
was never true for this data.
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path

import numpy as np


@dataclass
class LineSample:
    """One image with a hand- or mask-derived water line."""

    sample_id: str
    image: np.ndarray
    #: Ground-truth line, normalised 0..1 down the CROP (0 = crop top).
    line: float
    #: Crop applied before detection, as (x, y, w, h) in the original image.
    crop: tuple[int, int, int, int] | None
    bottle: str = "?"
    lighting: str = "?"
    source: str = "?"
    #: True when a human says no line is visible: the reject class.
    unreadable: bool = False


FIELDS = ["sample_id", "image", "line", "crop", "bottle", "lighting", "source", "unreadable"]


def _parse_crop(text: str) -> tuple[int, int, int, int] | None:
    text = (text or "").strip()
    if not text:
        return None
    x, y, w, h = (int(round(float(v))) for v in text.split(","))
    return x, y, w, h


def load(manifest: Path, images_root: Path | None = None) -> list[LineSample]:
    """Read a `lines.csv` manifest. Columns are :data:`FIELDS`.

    ``line`` is normalised against the crop, so a manifest stays correct if the
    image is later resized -- which matters because web images arrive at every
    resolution imaginable.
    """
    from PIL import Image, ImageOps

    root = images_root or manifest.parent / "images"
    samples: list[LineSample] = []
    with manifest.open(newline="") as handle:
        for row in csv.DictReader(handle):
            ident = (row.get("sample_id") or "").strip()
            if not ident or ident.startswith("#"):
                continue
            path = root / row["image"].strip()
            with Image.open(path) as handle_image:
                # EXIF transpose is not optional: a sideways bottle makes the
                # water line vertical and the whole method is built on it being
                # horizontal. Getting this wrong reads as an algorithm failure.
                array = np.asarray(
                    ImageOps.exif_transpose(handle_image).convert("RGB"), dtype=np.uint8
                )
            samples.append(
                LineSample(
                    sample_id=ident,
                    image=array,
                    line=float(row["line"]),
                    crop=_parse_crop(row.get("crop", "")),
                    bottle=(row.get("bottle") or "?").strip() or "?",
                    lighting=(row.get("lighting") or "?").strip() or "?",
                    source=(row.get("source") or "?").strip() or "?",
                    unreadable=str(row.get("unreadable", "")).strip() in {"1", "true", "yes"},
                )
            )
    if not samples:
        raise SystemExit(f"{manifest} parsed but held no rows")
    return samples


def save(samples: list[dict[str, object]], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        for row in samples:
            writer.writerow({key: row.get(key, "") for key in FIELDS})


def crop_of(sample: LineSample) -> np.ndarray:
    if sample.crop is None:
        return sample.image
    x, y, w, h = sample.crop
    return sample.image[y: y + h, x: x + w]


@dataclass
class LineResult:
    sample: LineSample
    detected: float
    confidence: float
    readable: bool

    @property
    def error(self) -> float:
        """Absolute line-position error, normalised to crop height.

        Directly comparable to the fill tolerance: fill = 1 - line, so an error
        of 0.05 in line position is an error of 0.05 in fill fraction.
        """
        return abs(self.detected - self.sample.line)


def evaluate(samples: list[LineSample], floor: float | None = None) -> list[LineResult]:
    from .detect import CONFIDENCE_FLOOR, detect

    results: list[LineResult] = []
    for sample in samples:
        observation = detect(
            crop_of(sample),
            confidence_floor=CONFIDENCE_FLOOR if floor is None else floor,
        )
        results.append(
            LineResult(
                sample=sample,
                detected=observation.line,
                confidence=observation.confidence,
                readable=observation.readable,
            )
        )
    return results


def report(results: list[LineResult], tolerance: float = 0.05, verbose: bool = False) -> bool:
    """Print breadth metrics and return whether the tolerance gate is met."""
    measurable = [r for r in results if not r.sample.unreadable]
    rejects = [r for r in results if r.sample.unreadable]

    print(f"\n{'=' * 74}")
    print(f"{len(results)} line-labelled images: {len(measurable)} measurable, "
          f"{len(rejects)} marked unreadable")
    print("=" * 74)

    within = 0.0
    if measurable:
        errors = np.array([r.error for r in measurable])
        within = float((errors <= tolerance).mean())
        print(f"\nline-position error   MAE {errors.mean():.4f}   "
              f"median {np.median(errors):.4f}   p90 {np.percentile(errors, 90):.4f}")
        print(f"within +/-{tolerance:.0%}          {within * 100:.1f}%")
        print(f"reported readable     {sum(r.readable for r in measurable)}/{len(measurable)}")

        for key in ("source", "bottle", "lighting"):
            groups: dict[str, list[LineResult]] = {}
            for r in measurable:
                groups.setdefault(getattr(r.sample, key), []).append(r)
            if len(groups) > 1:
                print(f"\n  by {key}:")
                for name, rows in sorted(groups.items(),
                                         key=lambda kv: -len(kv[1]))[:12]:
                    e = np.array([r.error for r in rows])
                    print(f"    {name:20s} n={len(rows):4d}  MAE={e.mean():.4f}  "
                          f"<={tolerance:.0%}={(e <= tolerance).mean() * 100:5.1f}%  "
                          f"readable={sum(r.readable for r in rows)}/{len(rows)}")

    reject_recall = 1.0
    if rejects:
        caught = sum(1 for r in rejects if not r.readable)
        reject_recall = caught / len(rejects)
        print(f"\nreject class          {caught}/{len(rejects)} called unreadable "
              f"({reject_recall * 100:.0f}%)")

    if verbose and measurable:
        print(f"\n{'sample':24s} {'truth':>7} {'got':>7} {'err':>7} {'conf':>8}  rd  source")
        for r in sorted(measurable, key=lambda r: -r.error)[:40]:
            print(f"{r.sample.sample_id:24s} {r.sample.line:7.3f} {r.detected:7.3f} "
                  f"{r.error:7.3f} {r.confidence:8.1f}  {'Y' if r.readable else 'n'}   "
                  f"{r.sample.source}")

    passed = within >= 0.80
    print(f"\nBREADTH {'PASSED' if passed else 'NOT MET'} "
          f"-- {within * 100:.1f}% within +/-{tolerance:.0%} (want >= 80%)")
    print("Breadth measures perception across bottle shapes. It does NOT measure\n"
          "millilitre accuracy -- that needs measured pours (evaluate.py --corpus).")
    return passed
