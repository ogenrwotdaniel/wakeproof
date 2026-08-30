#!/usr/bin/env python
"""Derive water-line labels from a COCO segmentation export.

Several public datasets segment the **liquid region** inside a transparent
container. The top edge of that mask IS the water line, so a dataset built for a
different task hands us exactly the label this detector needs, already drawn, for
free. That is the cheapest labelled breadth available anywhere.

Known usable sources (all need a free Roboflow account for the API key):

  liquidfy/liquicontain    semantic segmentation, classes incl. `liquid` --
                           the one worth pulling first
  bottle-fill-level        ~290 images

Export from Roboflow as **COCO Segmentation**, unzip, then:

    python import_masks.py path/to/_annotations.coco.json \
        --images path/to/images --out corpus/breadth/lines.csv

What it does NOT do
-------------------
It does not invent labels. An image with no liquid annotation is SKIPPED rather
than guessed at as empty, because "annotator did not mark the liquid" and "the
bottle was empty" are indistinguishable in a COCO file and confusing them would
poison the corpus with false empties. Pass ``--empty-as-full-height`` only if you
know the dataset annotates every container including the empty ones.

Every skip is counted and printed. A silent drop would make a partial import read
as full coverage, which is the same class of bug as a silently truncated search.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from waterline import lines  # noqa: E402

#: Category names treated as the liquid itself. Matched case-insensitively as a
#: substring, since dataset authors name things `liquid`, `water`, `fluid-1`...
LIQUID_NAMES = ("liquid", "water", "fluid", "juice", "wine", "milk", "beverage")

#: Category names treated as the containing vessel, used as the crop.
CONTAINER_NAMES = ("bottle", "glass", "container", "cup", "jar", "vessel", "flask", "tumbler")

#: A liquid mask must fill at least this fraction of the container's width to be
#: a believable water line. A narrow sliver is usually a reflection, a droplet,
#: or a mis-traced polygon -- and it would put the "line" in the wrong place.
MIN_WIDTH_RATIO = 0.45

#: Reject a liquid mask taller than this share of the container: it has probably
#: been traced around the whole bottle rather than the liquid inside it.
MAX_HEIGHT_RATIO = 1.02


def _polygon_bounds(segmentation: object, fallback: list[float]) -> tuple[float, float, float, float]:
    """Return (x0, y0, x1, y1) for a COCO polygon list, else the bbox."""
    if isinstance(segmentation, list) and segmentation and isinstance(segmentation[0], list):
        xs: list[float] = []
        ys: list[float] = []
        for polygon in segmentation:
            coordinates = [float(v) for v in polygon]
            xs.extend(coordinates[0::2])
            ys.extend(coordinates[1::2])
        if xs and ys:
            return min(xs), min(ys), max(xs), max(ys)
    x, y, w, h = (float(v) for v in fallback)
    return x, y, x + w, y + h


def _overlap(inner: tuple[float, ...], outer: tuple[float, ...]) -> float:
    """Fraction of `inner`'s area that falls inside `outer`."""
    ix0 = max(inner[0], outer[0])
    iy0 = max(inner[1], outer[1])
    ix1 = min(inner[2], outer[2])
    iy1 = min(inner[3], outer[3])
    if ix1 <= ix0 or iy1 <= iy0:
        return 0.0
    intersection = (ix1 - ix0) * (iy1 - iy0)
    area = max((inner[2] - inner[0]) * (inner[3] - inner[1]), 1e-6)
    return intersection / area


def convert(
    coco_path: Path,
    source: str,
    empty_as_full_height: bool = False,
) -> tuple[list[dict[str, object]], Counter]:
    coco = json.loads(coco_path.read_text())
    categories = {c["id"]: str(c["name"]).lower() for c in coco.get("categories", [])}
    images = {img["id"]: img for img in coco.get("images", [])}

    def kind(category_id: int) -> str:
        name = categories.get(category_id, "")
        if any(token in name for token in LIQUID_NAMES):
            return "liquid"
        if any(token in name for token in CONTAINER_NAMES):
            return "container"
        return "other"

    per_image: dict[int, dict[str, list[dict]]] = {}
    for annotation in coco.get("annotations", []):
        bucket = per_image.setdefault(annotation["image_id"], {"liquid": [], "container": []})
        role = kind(annotation.get("category_id", -1))
        if role in bucket:
            bucket[role].append(annotation)

    rows: list[dict[str, object]] = []
    skipped: Counter = Counter()

    for image_id, image in sorted(images.items(), key=lambda kv: kv[0]):
        buckets = per_image.get(image_id, {"liquid": [], "container": []})
        containers = buckets["container"]
        liquids = buckets["liquid"]

        if not containers:
            skipped["no container annotated"] += 1
            continue

        for index, container in enumerate(containers):
            outer = _polygon_bounds(container.get("segmentation"), container["bbox"])
            cx0, cy0, cx1, cy1 = outer
            width, height = cx1 - cx0, cy1 - cy0
            if width < 8 or height < 24:
                skipped["container too small"] += 1
                continue

            # Pick the liquid mask that actually sits inside THIS container --
            # datasets often hold several bottles per photograph.
            inside = [
                bounds for bounds in
                (_polygon_bounds(a.get("segmentation"), a["bbox"]) for a in liquids)
                if _overlap(bounds, outer) >= 0.60
            ]
            if not inside:
                if empty_as_full_height:
                    rows.append(_row(image, index, outer, 1.0, source, height))
                else:
                    skipped["no liquid mask inside container"] += 1
                continue

            # Tallest qualifying mask: the liquid body, not a droplet or a
            # reflection higher up the bottle.
            best = max(inside, key=lambda b: b[3] - b[1])
            lx0, ly0, lx1, ly1 = best
            if (lx1 - lx0) < MIN_WIDTH_RATIO * width:
                skipped["liquid mask too narrow"] += 1
                continue
            if (ly1 - ly0) > MAX_HEIGHT_RATIO * height:
                skipped["liquid mask taller than container"] += 1
                continue

            line = (ly0 - cy0) / height
            if not 0.0 <= line <= 1.0:
                skipped["liquid top outside container"] += 1
                continue
            rows.append(_row(image, index, outer, line, source, height))

    return rows, skipped


def _row(
    image: dict,
    index: int,
    outer: tuple[float, float, float, float],
    line: float,
    source: str,
    height: float,
) -> dict[str, object]:
    x0, y0, x1, y1 = (int(round(v)) for v in outer)
    stem = Path(str(image["file_name"])).stem
    return {
        "sample_id": f"{stem}__{index}",
        "image": image["file_name"],
        "line": f"{line:.4f}",
        "crop": f"{max(0, x0)},{max(0, y0)},{x1 - x0},{y1 - y0}",
        "bottle": "?",
        "lighting": "?",
        "source": source,
        "unreadable": "",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("coco", type=Path, help="_annotations.coco.json from a COCO Segmentation export")
    parser.add_argument("--out", type=Path, required=True, help="lines.csv to write")
    parser.add_argument("--source", default=None, help="tag for the by-source breakdown")
    parser.add_argument("--empty-as-full-height", action="store_true",
                        help="treat a container with no liquid mask as EMPTY (line=1.0). "
                             "Only if the dataset annotates every container.")
    args = parser.parse_args()

    rows, skipped = convert(
        args.coco,
        source=args.source or args.coco.parent.name,
        empty_as_full_height=args.empty_as_full_height,
    )
    if not rows:
        raise SystemExit("no usable labels derived -- check the category names against "
                         f"LIQUID_NAMES/CONTAINER_NAMES; skips were: {dict(skipped)}")

    lines.save(rows, args.out)
    print(f"wrote {len(rows)} labels -> {args.out}")
    if skipped:
        total = sum(skipped.values())
        print(f"\nskipped {total} candidates (never silently -- here is why):")
        for reason, count in skipped.most_common():
            print(f"  {count:6d}  {reason}")
        print("\nThe skips are the honest part. A mask-derived label is only as good as\n"
              "the annotator's polygon, so spot-check with:\n"
              f"  python evaluate.py --lines {args.out} --overlays /tmp/ov -v")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
