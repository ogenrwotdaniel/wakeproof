"""Tests for the same-bottle check.

Same caveat as test_detect.py: these are regression tests over synthetic
scenes, where "a different bottle" means a wholly different background seed and
tint. Real photographs put two bottles in the same room under the same lamp, so
the gap measured here (positives >= 0.99, negatives <= 0.63) will narrow. The
threshold is set permissively for that reason, not because the synthetic
separation demands it.
"""

from __future__ import annotations

import numpy as np
import pytest

from waterline.detect import (
    SAME_CONTAINER_FLOOR,
    SIGNATURE_LENGTH,
    colour_signature,
    compare,
    detect,
    profile_correlation,
    same_container,
    shared_bands,
    signature_similarity,
    wall_profile,
)
from waterline.synth import render


def _score(before, after) -> float:
    first, second = detect(before.rgb), detect(after.rgb)
    return same_container(before.rgb, after.rgb, first.line, second.line)[1]


# --- the pieces --------------------------------------------------------------

def test_signature_of_identical_pixels_intersects_fully():
    block = np.full((20, 10, 3), 128, dtype=np.uint8)
    signature = colour_signature(block)
    assert signature.sum() == pytest.approx(1.0)
    assert signature_similarity(signature, signature) == pytest.approx(1.0)


def test_signatures_of_different_hues_do_not_intersect():
    red = colour_signature(np.tile(np.array([220, 40, 40], np.uint8), (20, 10, 1)))
    blue = colour_signature(np.tile(np.array([40, 40, 220], np.uint8), (20, 10, 1)))
    assert signature_similarity(red, blue) == pytest.approx(0.0)


def test_the_signature_ignores_illumination_entirely():
    # The point of chromaticity: scaling every channel is what a lamp does, and
    # it must leave the signature alone. Raw RGB bins failed exactly here.
    base = np.full((20, 10, 3), 200, dtype=np.uint8)
    dimmed = (base.astype(np.float32) * 0.45).astype(np.uint8)
    assert signature_similarity(colour_signature(base), colour_signature(dimmed)) == 1.0


def test_near_black_pixels_are_binned_apart_from_real_colours():
    # Their hue is sensor noise; scattering them across the plane would make two
    # dark photographs look like two different bottles.
    black = colour_signature(np.zeros((20, 10, 3), dtype=np.uint8))
    assert black[-1] == pytest.approx(1.0)


def test_empty_block_yields_an_empty_signature():
    signature = colour_signature(np.zeros((0, 10, 3), dtype=np.uint8))
    assert signature.shape == (SIGNATURE_LENGTH,)
    assert signature.sum() == pytest.approx(0.0)


def test_profile_correlation_ignores_a_brightness_change():
    profile = np.array([0.2, 0.9, 0.4, 0.8, 0.3], dtype=np.float32)
    assert profile_correlation(profile, profile * 0.5 + 0.1) == pytest.approx(1.0, abs=1e-5)


def test_profile_correlation_of_a_flat_profile_is_no_evidence():
    flat = np.full(8, 0.5, dtype=np.float32)
    assert profile_correlation(flat, flat) == 0.0


def test_profile_correlation_needs_matching_widths():
    assert profile_correlation(np.zeros(4), np.zeros(6)) == 0.0


def test_wall_profile_finds_the_bright_bottle_edges():
    scene = render(0.6, seed=1)
    profile = wall_profile(scene.rgb)
    # The renderer adds specular highlights down both walls, so the edge columns
    # must read brighter than the middle -- this is the cue being compared.
    assert profile[0] > profile[len(profile) // 2]
    assert profile[-1] > profile[len(profile) // 2]


# --- the shared bands --------------------------------------------------------

def test_shared_bands_are_air_above_and_water_below():
    air, water = shared_bands(before_line=0.30, after_line=0.60, height=100)
    # Air: above the higher line (the fuller bottle's). Water: below the lower.
    assert air.start == 0 and air.stop < 30
    assert water.start > 60 and water.stop == 100


def test_shared_bands_exclude_everything_that_changed():
    air, water = shared_bands(before_line=0.25, after_line=0.75, height=200)
    changed = range(int(0.25 * 199), int(0.75 * 199) + 1)
    assert all(row not in changed for row in range(air.start, air.stop))
    assert all(row not in changed for row in range(water.start, water.stop))


def test_a_brim_full_bottle_has_no_air_band():
    air, _ = shared_bands(before_line=0.0, after_line=0.4, height=100)
    assert air.stop - air.start <= 0


# --- the verdict -------------------------------------------------------------

def test_the_same_bottle_drunk_from_is_still_the_same_bottle():
    # The case the whole shared-band trick exists for: a genuine drink converts
    # a band of the image from water to air, and comparing the crops wholesale
    # would score that as two different bottles.
    kwargs = dict(seed=11, body_tint=(0.72, 1.0, 0.78))
    assert _score(render(0.90, **kwargs), render(0.40, **kwargs)) >= SAME_CONTAINER_FLOOR


def test_a_different_bottle_is_rejected():
    before = render(0.85, seed=21, body_tint=(1.0, 1.0, 1.0))
    after = render(0.55, seed=88, body_tint=(0.55, 0.75, 1.0), with_label=True)
    assert _score(before, after) < SAME_CONTAINER_FLOOR


def test_a_dimmer_second_photo_of_the_same_bottle_survives():
    # Turning a lamp on between the two shots must not read as a swap.
    assert _score(render(0.80, seed=31), render(0.50, seed=31, brightness=0.6)) >= \
        SAME_CONTAINER_FLOOR


def test_mismatched_image_sizes_are_a_different_container():
    before = render(0.8, seed=41)
    after = render(0.5, seed=41, height=200, width=100)
    assert same_container(before.rgb, after.rgb, 0.2, 0.5) == (False, 0.0)


def test_the_separation_holds_across_a_spread_of_pairs():
    positives, negatives = [], []
    rng = np.random.default_rng(5)
    for i in range(8):
        full = float(rng.uniform(0.55, 0.95))
        drunk = full - 0.25
        kwargs = dict(seed=300 + i, brightness=1.0 if i % 2 else 0.6)
        positives.append(_score(render(full, **kwargs), render(drunk, **kwargs)))
        negatives.append(
            _score(
                render(full, seed=400 + i),
                render(drunk, seed=700 + i, body_tint=(0.55, 0.75, 1.0)),
            )
        )
    assert min(positives) >= SAME_CONTAINER_FLOOR
    assert max(negatives) < SAME_CONTAINER_FLOOR
    # The gap is what matters; a threshold sitting on top of either class is a
    # threshold that will not survive real photographs.
    assert min(positives) - max(negatives) > 0.25


def test_compare_reports_the_container_verdict():
    kwargs = dict(seed=51)
    result = compare(render(0.90, **kwargs).rgb, render(0.55, **kwargs).rgb)
    assert result["same_container"] is True
    assert result["container_score"] >= SAME_CONTAINER_FLOOR
    assert result["refilled"] is False


def test_compare_flags_a_swapped_bottle():
    result = compare(
        render(0.90, seed=61).rgb,
        render(0.55, seed=99, body_tint=(0.55, 0.75, 1.0), with_label=True).rgb,
    )
    assert result["same_container"] is False
