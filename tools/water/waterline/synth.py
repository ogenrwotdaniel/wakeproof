"""Synthetic bottle photos with exact ground truth.

This exists so the detector can be developed and regression-tested TODAY, with
no corpus and no camera. It is scaffolding, not evidence.

READ THIS BEFORE TRUSTING A NUMBER FROM IT. A synthetic image contains exactly
the cues the person who wrote the generator thought to put in, which makes it
worthless as a measure of real-world accuracy -- it is a test of "did I break
the algorithm", not "does the algorithm work". The gate in
docs/OFFLINE_WATER.md (fill within +/-5% on 80% of pairs) must be met on real
photographs. Passing it here means nothing.

What it does model, because these are the things that actually break the method:
  * a structured background, so there is detail for refraction to smear
  * water that darkens, blurs and slightly compresses what is behind it
  * a meniscus band at the line, the strongest single feature on a real bottle
  * specular highlights down the curved bottle walls
  * an opaque printed label -- the known worst case, since it is a stronger
    horizontal step than the water line it hides
  * a body tint, for coloured and dark bottles
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass
class Scene:
    """A rendered bottle plus the truth the renderer used."""

    rgb: np.ndarray
    #: Normalised row of the water line, 0.0 at the crop top, 1.0 at the bottom.
    line: float
    #: Ground-truth fill fraction, i.e. 1 - line.
    fill: float
    #: True when the scene was built to be genuinely unreadable (opaque body).
    unreadable: bool = False
    label: str = ""


def _background(height: int, width: int, rng: np.random.Generator) -> np.ndarray:
    """Blotchy structured background, upsampled from coarse noise.

    Structure matters: against a flat wall the `detail` feature has nothing to
    measure, which would quietly make the smearing cue untestable.
    """
    coarse = rng.random((max(2, height // 24), max(2, width // 24), 3)).astype(np.float32)
    ys = np.linspace(0, coarse.shape[0] - 1, height)
    xs = np.linspace(0, coarse.shape[1] - 1, width)
    # Nearest-neighbour upsample, then a light blur: keeps it numpy-only.
    field = coarse[np.round(ys).astype(int)][:, np.round(xs).astype(int)]
    field = 0.45 + 0.4 * field
    for _ in range(2):
        field[1:-1] = (field[:-2] + field[1:-1] + field[2:]) / 3.0
        field[:, 1:-1] = (field[:, :-2] + field[:, 1:-1] + field[:, 2:]) / 3.0
    return field


def _blur_x(plane: np.ndarray, radius: int) -> np.ndarray:
    """Horizontal box blur -- stands in for refractive smearing."""
    if radius <= 0:
        return plane
    out = plane.copy()
    for _ in range(2):
        padded = np.pad(out, ((0, 0), (radius, radius), (0, 0)), mode="edge")
        window = 2 * radius + 1
        cumulative = np.cumsum(padded, axis=1, dtype=np.float64)
        cumulative = np.concatenate(
            [np.zeros((plane.shape[0], 1, plane.shape[2])), cumulative], axis=1
        )
        out = ((cumulative[:, window:] - cumulative[:, :-window]) / window).astype(np.float32)
    return out


def render(
    fill: float,
    height: int = 320,
    width: int = 160,
    seed: int = 0,
    *,
    with_label: bool = False,
    label_span: tuple[float, float] = (0.30, 0.46),
    body_tint: tuple[float, float, float] = (1.0, 1.0, 1.0),
    opacity: float = 0.0,
    brightness: float = 1.0,
    noise: float = 0.012,
    water_darkening: float = 0.72,
    smear_radius: int = 3,
    meniscus_strength: float = 0.35,
) -> Scene:
    """Render one bottle at a given ``fill`` (0.0 empty .. 1.0 brim-full).

    ``opacity`` at 1.0 renders an opaque body with no line at all, which is the
    steel-flask case: the honest answer there is `readable = False` forever, and
    that needs a test too.
    """
    rng = np.random.default_rng(seed)
    fill = float(np.clip(fill, 0.0, 1.0))
    line_row = int(round((1.0 - fill) * (height - 1)))

    scene = _background(height, width, rng)

    # --- water column: darker, smeared, slightly vertically compressed -------
    if line_row < height - 1:
        below = scene[line_row:]
        smeared = _blur_x(below, smear_radius)
        # Refraction through a cylinder compresses the view vertically as well;
        # resample the region onto itself with a mild squeeze.
        rows = np.clip(
            (np.arange(below.shape[0]) * 0.94 + below.shape[0] * 0.03).astype(int),
            0, below.shape[0] - 1,
        )
        scene[line_row:] = smeared[rows] * water_darkening

    # --- meniscus: a thin high-contrast band exactly at the line ------------
    if 0 < line_row < height - 1 and meniscus_strength > 0:
        band = max(1, height // 110)
        lo, hi = max(0, line_row - band), min(height, line_row + band + 1)
        scene[lo:line_row] *= 1.0 + meniscus_strength
        scene[line_row:hi] *= max(0.0, 1.0 - meniscus_strength * 0.7)

    # --- bottle walls: specular highlights down both curved edges ------------
    edge = max(2, int(width * 0.10))
    ramp = np.linspace(1.0, 0.0, edge, dtype=np.float32)[None, :, None]
    scene[:, :edge] += 0.30 * ramp
    scene[:, width - edge:] += 0.30 * ramp[:, ::-1]

    scene *= np.asarray(body_tint, dtype=np.float32)[None, None, :]

    # --- opaque label: the adversarial case ---------------------------------
    if with_label:
        top = int(label_span[0] * height)
        bottom = int(label_span[1] * height)
        patch = np.full((max(0, bottom - top), width, 3), 0.90, dtype=np.float32)
        # Printed text: high horizontal detail, which is what fools `detail`.
        for row in range(2, patch.shape[0] - 2, 5):
            marks = rng.random(width) < 0.42
            patch[row:row + 2, marks] = 0.10
        scene[top:bottom] = patch

    # --- opaque body: no line exists at any price ---------------------------
    if opacity > 0:
        flat = np.full_like(scene, 0.42)
        flat[:, :edge] += 0.30 * ramp
        flat[:, width - edge:] += 0.30 * ramp[:, ::-1]
        scene = scene * (1.0 - opacity) + flat * opacity

    scene *= brightness
    scene += rng.normal(0.0, noise, scene.shape).astype(np.float32)

    rgb = (np.clip(scene, 0.0, 1.0) * 255.0).round().astype(np.uint8)
    return Scene(
        rgb=rgb,
        line=line_row / max(1, height - 1),
        fill=fill,
        unreadable=opacity >= 0.85,
        label=f"fill={fill:.2f} seed={seed}"
        + (" +label" if with_label else "")
        + (" +opaque" if opacity >= 0.85 else ""),
    )


def corpus(count: int = 60, seed: int = 7) -> list[Scene]:
    """A spread of scenes: clear bottles, dim light, tints, labels, one flask.

    Used by the test suite and by `evaluate.py --synthetic` as a smoke corpus.
    Composition is deliberately harsher than a real morning would be.
    """
    rng = np.random.default_rng(seed)
    scenes: list[Scene] = []
    for i in range(count):
        fill = float(rng.uniform(0.12, 0.95))
        kind = i % 6
        if kind == 0:  # bright, clear, easy
            scenes.append(render(fill, seed=i, brightness=1.0))
        elif kind == 1:  # dim indoor lamp, the 06:00 case
            scenes.append(render(fill, seed=i, brightness=0.55, noise=0.02))
        elif kind == 2:  # tinted green bottle
            scenes.append(render(fill, seed=i, body_tint=(0.72, 1.0, 0.78)))
        elif kind == 3:  # weak contrast: barely-darkening water
            scenes.append(render(fill, seed=i, water_darkening=0.88,
                                 meniscus_strength=0.16, smear_radius=2))
        elif kind == 4:  # printed label, placed clear of the line
            span = (0.06, 0.20) if fill < 0.70 else (0.72, 0.86)
            scenes.append(render(fill, seed=i, with_label=True, label_span=span))
        else:  # opaque flask: must be reported unreadable, not guessed
            scenes.append(render(fill, seed=i, opacity=1.0))
    return scenes
