#!/usr/bin/env python
"""Click the water line. One image, one click, one label.

The point of this script is that **breadth costs almost nothing**. Perception
("is the line found on a bottle shape I have never seen?") needs exactly one
number per image: the y of the water line. A click gives it in about two
seconds, so any pile of bottle photographs -- a public dataset with no liquid
masks, web images, your own camera roll -- becomes usable validation data.

That is the half of the problem internet data genuinely solves. The other half,
millilitre accuracy, needs measured pours and cannot come from anywhere but a
sink and a jug -- but it needs only a few bottles, because it is checking
arithmetic and tolerance rather than perception across shapes.

Usage
-----
    python label.py path/to/images/ --out corpus/breadth/lines.csv

Keys, shown on screen while it runs:

    click        set the water line at the clicked height
    d / right    keep and go to the next image
    a / left     back one image
    u            mark UNREADABLE (opaque bottle, no line visible) -- the reject
                 class needs real examples, so this is as valuable as a line
    s            skip: not a bottle, or too ambiguous to label honestly
    q            save and quit

Progress is written after every image, so quitting or crashing never loses work,
and re-running skips whatever is already labelled. Label a hundred, run
`evaluate.py --lines`, and stop when the numbers stop moving.

Honesty note: a clicked label is a human eyeball, good to a few pixels. Do not
read a measured MAE below ~0.01 as real -- that is inside the label noise.
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def existing(out: Path) -> dict[str, dict[str, str]]:
    if not out.exists():
        return {}
    with out.open(newline="") as handle:
        return {row["image"]: row for row in csv.DictReader(handle) if row.get("image")}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("images", type=Path, help="directory of images to label")
    parser.add_argument("--out", type=Path, required=True, help="lines.csv to append to")
    parser.add_argument("--source", default=None, help="tag for the by-source breakdown")
    parser.add_argument("--bottle", default="?", help="bottle id, if this batch is one bottle")
    parser.add_argument("--lighting", default="?", help="daylight | lamp | dim | mixed")
    parser.add_argument("--relabel", action="store_true", help="revisit already-labelled images")
    args = parser.parse_args()

    import matplotlib
    import matplotlib.pyplot as plt
    from PIL import Image, ImageOps

    if not args.images.is_dir():
        raise SystemExit(f"{args.images} is not a directory")
    paths = sorted(p for p in args.images.rglob("*") if p.suffix.lower() in IMAGE_SUFFIXES)
    if not paths:
        raise SystemExit(f"no images under {args.images}")

    done = existing(args.out)
    source = args.source or args.images.name
    queue = [p for p in paths
             if args.relabel or str(p.relative_to(args.images)) not in done]
    if not queue:
        print(f"all {len(paths)} images already in {args.out} -- pass --relabel to revisit")
        return 0

    print(f"{len(queue)} to label ({len(done)} already done). "
          f"click=line  d=next  a=back  u=unreadable  s=skip  q=save+quit")
    if matplotlib.get_backend().lower() == "agg":
        raise SystemExit(
            "matplotlib has no interactive backend here (headless). This script needs a\n"
            "display. Either run it from a desktop session, or derive labels from an\n"
            "existing dataset's liquid masks with import_masks.py instead."
        )

    state: dict[str, object] = {"index": 0, "line": None, "quit": False}

    def flush() -> None:
        rows = list(done.values())
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with args.out.open("w", newline="") as handle:
            writer = csv.DictWriter(
                handle,
                fieldnames=["sample_id", "image", "line", "crop",
                            "bottle", "lighting", "source", "unreadable"],
            )
            writer.writeheader()
            writer.writerows(rows)

    def record(relative: str, line: float | None, unreadable: bool) -> None:
        done[relative] = {
            "sample_id": relative.replace("/", "_"),
            "image": relative,
            # An unreadable image has no line; 0 is a placeholder the evaluator
            # never reads, because it filters on `unreadable` first.
            "line": f"{line:.4f}" if line is not None else "0",
            "crop": "",
            "bottle": args.bottle,
            "lighting": args.lighting,
            "source": source,
            "unreadable": "1" if unreadable else "",
        }
        flush()

    figure, axes = plt.subplots(figsize=(6, 9))

    def draw() -> None:
        axes.clear()
        path = queue[state["index"]]
        with Image.open(path) as handle:
            image = ImageOps.exif_transpose(handle).convert("RGB")
        axes.imshow(image)
        relative = str(path.relative_to(args.images))
        previous = done.get(relative)
        title = f"[{state['index'] + 1}/{len(queue)}] {path.name}"
        if state["line"] is not None:
            axes.axhline(state["line"] * image.height, color="#28dc5a", lw=2)
            title += f"   line={state['line']:.3f}  (d to keep)"
        elif previous:
            title += "   already: " + ("UNREADABLE" if previous["unreadable"]
                                       else f"line={float(previous['line']):.3f}")
        else:
            title += "   click the water line"
        axes.set_title(title, fontsize=9)
        axes.set_xticks([]); axes.set_yticks([])
        figure.canvas.draw_idle()

    def advance(step: int) -> None:
        state["line"] = None
        state["index"] += step
        if state["index"] >= len(queue) or state["index"] < 0:
            state["index"] = max(0, min(len(queue) - 1, state["index"]))
            if step > 0:
                print("end of queue -- q to quit")
        draw()

    def on_click(event) -> None:
        if event.inaxes is not axes or event.ydata is None:
            return
        height = axes.images[0].get_array().shape[0]
        state["line"] = float(min(max(event.ydata / max(height - 1, 1), 0.0), 1.0))
        draw()

    def on_key(event) -> None:
        relative = str(queue[state["index"]].relative_to(args.images))
        if event.key in ("d", "right", " "):
            if state["line"] is not None:
                record(relative, float(state["line"]), unreadable=False)
            advance(1)
        elif event.key in ("a", "left"):
            advance(-1)
        elif event.key == "u":
            record(relative, None, unreadable=True)
            advance(1)
        elif event.key == "s":
            advance(1)
        elif event.key == "q":
            state["quit"] = True
            plt.close(figure)

    figure.canvas.mpl_connect("button_press_event", on_click)
    figure.canvas.mpl_connect("key_press_event", on_key)
    draw()
    plt.show()

    flush()
    labelled = sum(1 for r in done.values() if not r["unreadable"])
    rejects = len(done) - labelled
    print(f"\n{args.out}: {labelled} lines + {rejects} unreadable = {len(done)} rows")
    print(f"measure with:  python evaluate.py --lines {args.out} "
          f"--images {args.images} -v")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
