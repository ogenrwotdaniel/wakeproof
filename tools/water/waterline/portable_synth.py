"""A bottle renderer with NO library randomness, so Kotlin can reproduce it exactly.

Why this exists, when `synth.py` already renders bottles
--------------------------------------------------------
`synth.py` is the better renderer and stays the one the accuracy tests use. But
it draws its noise from ``numpy.random.Generator``, which no Kotlin port can
reproduce, so it cannot answer the one question that matters once `detect.py`
has been ported: **does the Kotlin code compute the same numbers?**

Property tests on each side ("fill lands within 5% of truth") do not answer it
either. A porting slip -- a population variance turned into a sample variance, a
band search that lost its upward half, an off-by-one at the min-side boundary --
can leave every property intact on easy scenes and quietly cost accuracy on hard
ones. The only test that catches those is running *the same pixels* through both
implementations and comparing.

So this renderer uses an explicit 31-bit LCG and integer arithmetic, both of
which port verbatim. Run ``python -m waterline.portable_synth`` to print the
reference numbers that `WaterLineDetectorTest` asserts, and re-run it whenever
`detect.py` changes: those constants are a snapshot of Python's answer, and a
deliberate tuning change makes them stale rather than wrong.

See `app/src/test/java/com/ogenrwotdaniel/wakeproof/verify/water/PortableScene.kt`
for the Kotlin twin. The two must stay byte-identical in behaviour; there is a
test on each side that fails if they do not.
"""

from __future__ import annotations

import numpy as np

#: Numerical Recipes' LCG constants, in 31 bits. Chosen because "multiply, add,
#: mask" is the same in every language, which is the whole point.
LCG_MULTIPLIER = 1103515245
LCG_INCREMENT = 12345
LCG_MASK = 0x7FFFFFFF


class Lcg:
    """A linear congruential generator, reproducible in any language."""

    def __init__(self, seed: int) -> None:
        self.state = seed & LCG_MASK

    def next_int(self) -> int:
        self.state = (self.state * LCG_MULTIPLIER + LCG_INCREMENT) & LCG_MASK
        return self.state

    def unit(self) -> float:
        """A float in [0, 1), from the top bits -- the low bits of an LCG are weak."""
        return (self.next_int() >> 7) / float(1 << 24)


def render(
    fill: float,
    height: int = 360,
    width: int = 180,
    seed: int = 1,
    *,
    with_label: bool = False,
    opaque: bool = False,
    brightness: float = 1.0,
    tint: tuple[float, float, float] = (1.0, 1.0, 1.0),
) -> np.ndarray:
    """Render one bottle as an HxWx3 uint8 array. Deterministic given ``seed``.

    Models only the cues the detector actually leans on, deliberately: a
    structured background (so refraction has something to smear), water that
    darkens and blurs what is behind it, a meniscus band at the line, specular
    highlights down both walls, and optionally an opaque label or an opaque body.
    """
    rng = Lcg(seed)
    line_row = int(round((1.0 - min(max(fill, 0.0), 1.0)) * (height - 1)))

    # --- blotchy background, upsampled from a coarse grid -------------------
    coarse_h, coarse_w = max(2, height // 24), max(2, width // 24)
    coarse = [[rng.unit() for _ in range(coarse_w)] for _ in range(coarse_h)]
    scene = np.zeros((height, width, 3), dtype=np.float64)
    for y in range(height):
        cy = min(coarse_h - 1, y * coarse_h // height)
        for x in range(width):
            cx = min(coarse_w - 1, x * coarse_w // width)
            value = 0.45 + 0.40 * coarse[cy][cx]
            scene[y, x, :] = value

    # --- water column: darker, and horizontally smeared ---------------------
    if line_row < height - 1:
        for y in range(line_row, height):
            row = scene[y].copy()
            for x in range(width):
                lo, hi = max(0, x - 3), min(width - 1, x + 3)
                scene[y, x, :] = row[lo:hi + 1].mean(axis=0) * 0.72

    # --- meniscus: a thin high-contrast band exactly at the line ------------
    band = max(1, height // 110)
    if 0 < line_row < height - 1:
        for y in range(max(0, line_row - band), line_row):
            scene[y] *= 1.35
        for y in range(line_row, min(height, line_row + band + 1)):
            scene[y] *= 0.755

    # --- bottle walls: specular highlights down both curved edges -----------
    edge = max(2, int(width * 0.10))
    for x in range(edge):
        ramp = 0.30 * (1.0 - x / float(edge))
        scene[:, x, :] += ramp
        scene[:, width - 1 - x, :] += ramp

    scene *= np.asarray(tint, dtype=np.float64)[None, None, :]

    # --- opaque label: the adversarial case ---------------------------------
    if with_label:
        top, bottom = int(0.30 * height), int(0.46 * height)
        for y in range(top, bottom):
            for x in range(width):
                scene[y, x, :] = 0.90
        for y in range(top + 2, bottom - 2, 5):
            for x in range(width):
                if rng.unit() < 0.42:
                    scene[y, x, :] = 0.10
                    scene[y + 1, x, :] = 0.10

    # --- opaque body: no line exists at any price ---------------------------
    if opaque:
        for y in range(height):
            for x in range(width):
                scene[y, x, :] = 0.42
        for x in range(edge):
            ramp = 0.30 * (1.0 - x / float(edge))
            scene[:, x, :] += ramp
            scene[:, width - 1 - x, :] += ramp

    scene *= brightness
    for y in range(height):
        for x in range(width):
            noise = (rng.unit() - 0.5) * 0.024
            scene[y, x, :] += noise

    return np.clip(scene * 255.0 + 0.5, 0.0, 255.0).astype(np.uint8)


#: The scenes the cross-language test pins. Name -> render kwargs.
REFERENCE_SCENES: dict[str, dict] = {
    "clear_070": dict(fill=0.70, seed=1),
    "clear_035": dict(fill=0.35, seed=1),
    "dim_055": dict(fill=0.55, seed=2, brightness=0.55),
    "labelled_080": dict(fill=0.80, seed=3, with_label=True),
    "opaque_060": dict(fill=0.60, seed=4, opaque=True),
    "tinted_045": dict(fill=0.45, seed=5, tint=(0.72, 1.0, 0.78)),
}


def main() -> int:
    from waterline.detect import detect, same_container

    print("// Generated by `python -m waterline.portable_synth` -- see that file.")
    for name, kwargs in REFERENCE_SCENES.items():
        observation = detect(render(**kwargs))
        print(f'    "{name}" to Expected('
              f'line = {observation.line:.6f}, '
              f'fill = {observation.fill:.6f}, '
              f'confidence = {observation.confidence:.4f}, '
              f'readable = {str(observation.readable).lower()}),')

    print("\n// same-container scores")
    pairs = [
        ("clear_070", "clear_035"),
        ("clear_070", "tinted_045"),
        ("clear_070", "dim_055"),
    ]
    for first_name, second_name in pairs:
        first_rgb = render(**REFERENCE_SCENES[first_name])
        second_rgb = render(**REFERENCE_SCENES[second_name])
        first, second = detect(first_rgb), detect(second_rgb)
        _, score = same_container(first_rgb, second_rgb, first.line, second.line)
        print(f'    Triple("{first_name}", "{second_name}", {score:.6f}),')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
