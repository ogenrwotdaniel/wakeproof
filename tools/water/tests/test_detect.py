"""Tests for the offline water-line detector.

These are REGRESSION tests over synthetic scenes. They answer "did I break the
algorithm", not "does the algorithm work" -- see the warning at the top of
synth.py. Real accuracy is `evaluate.py` against photographs, and until that has
been run against a real corpus none of these numbers licence shipping anything.

The accuracy thresholds below are deliberately looser than the measured values,
so an honest refactor does not turn red for a rounding change, but a real
regression does.
"""

from __future__ import annotations

import numpy as np
import pytest

from waterline.detect import (
    CONFIDENCE_FLOOR,
    coherence_curve,
    compare,
    detect,
    find_bottle_columns,
    suppress_bands,
    to_features,
    welch_t_curve,
)
from waterline.synth import corpus, render


# --- the statistics, in isolation --------------------------------------------

def test_welch_t_finds_a_clean_step():
    profile = np.concatenate([np.zeros(50), np.ones(50)]).astype(np.float32)
    curve = welch_t_curve(profile, min_side=5)
    assert abs(int(curve.argmax()) - 50) <= 1
    assert curve.max() > 100  # a noiseless step is arbitrarily significant


def test_welch_t_is_flat_on_constant_input():
    curve = welch_t_curve(np.full(100, 0.4, dtype=np.float32), min_side=5)
    assert float(curve.max()) == pytest.approx(0.0, abs=1e-3)


def test_welch_t_respects_min_side():
    """No split may be proposed inside the margin, or a 2-row sliver wins.

    The valid band is [min_side, n - min_side] inclusive: a split at either
    boundary still leaves exactly min_side rows on its short side.
    """
    profile = np.concatenate([np.zeros(3), np.ones(97)]).astype(np.float32)
    curve = welch_t_curve(profile, min_side=10)
    assert float(curve[:10].max()) == 0.0
    assert float(curve[91:].max()) == 0.0
    assert float(curve[90]) > 0.0, "the boundary split itself is legal"


def test_coherence_and_welch_agree_on_the_valid_band():
    """`detect` multiplies these two curves, so a mismatched valid range would
    silently make one of them the real bound and hide a bug in the other."""
    height, width, min_side = 100, 40, 10
    plane = np.zeros((height, width), dtype=np.float32)
    plane[50:] = 0.5

    coherence = np.abs(coherence_curve(plane, min_side=min_side, smooth_rows=1))
    welch = welch_t_curve(plane.mean(axis=1), min_side=min_side)
    assert (coherence > 0).nonzero()[0].min() >= min_side
    assert (coherence > 0).nonzero()[0].max() <= height - min_side
    assert (welch > 0).nonzero()[0].max() <= height - min_side


def test_welch_t_degenerate_lengths_do_not_raise():
    assert welch_t_curve(np.zeros(4, dtype=np.float32), min_side=5).max() == 0.0
    assert welch_t_curve(np.zeros(0, dtype=np.float32), min_side=1).shape == (0,)


def test_coherence_prefers_a_horizontal_edge_over_a_diagonal_one():
    """The whole method rests on this: gravity makes water lines horizontal.

    A diagonal boundary of the SAME contrast must score lower, or background
    structure crossing the bottle would be mistaken for a water line.
    """
    height, width = 120, 80
    horizontal = np.zeros((height, width), dtype=np.float32)
    horizontal[60:] = 0.4

    diagonal = np.zeros((height, width), dtype=np.float32)
    for x in range(width):
        diagonal[40 + (x * 40) // width:, x] = 0.4

    h_peak = float(np.abs(coherence_curve(horizontal, min_side=6)).max())
    d_peak = float(np.abs(coherence_curve(diagonal, min_side=6)).max())
    assert h_peak > 5 * d_peak, f"horizontal {h_peak:.1f} vs diagonal {d_peak:.1f}"


def test_coherence_sign_follows_which_side_is_darker():
    height, width = 120, 60
    darker_below = np.full((height, width), 0.8, dtype=np.float32)
    darker_below[60:] = 0.2
    curve = coherence_curve(darker_below, min_side=6)
    assert curve[int(np.abs(curve).argmax())] < 0

    curve = coherence_curve(1.0 - darker_below, min_side=6)
    assert curve[int(np.abs(curve).argmax())] > 0


def test_suppress_bands_damps_a_band_but_keeps_a_lone_edge():
    height = 400  # BAND_MAX_SPAN is 0.30h, so the lone edge needs > 120 rows' clearance
    signed = np.zeros(height, dtype=np.float32)
    signed[50] = 10.0    # step onto a label
    signed[70] = -10.0   # and off it again
    signed[250] = 10.0   # a half-space boundary: no partner within reach

    out = suppress_bands(signed, height)
    assert out[50] < out[250], "band edge should lose to a lone edge"
    assert out[70] < out[250]
    assert out[250] == pytest.approx(10.0), "a lone edge must not be damped at all"


def test_suppress_bands_damps_any_edge_near_an_opposite_one():
    """A KNOWN LIMITATION, pinned here so it cannot regress unnoticed.

    Band suppression cannot tell "label edge" from "real water line that happens
    to sit within BAND_MAX_SPAN of a label edge" -- both have an opposite-signed
    neighbour. A genuine line near a label is therefore damped too.

    This is mitigated, not solved: BAND_DAMPING is 0.25 rather than 0, so a
    strong line still beats a weak label edge on the argmax. It bought
    80% -> 90% on the synthetic label class, but it is exactly the behaviour to
    re-examine first if labelled bottles underperform on the real corpus.
    """
    height = 400
    signed = np.zeros(height, dtype=np.float32)
    signed[100] = 10.0   # label edge
    signed[160] = -10.0  # a real water line, 60 rows away (< 0.30 * 400)

    out = suppress_bands(signed, height)
    assert out[160] < abs(signed[160]), "documents the false-positive case"
    assert out[160] > 0, "damped, never deleted -- it can still win the argmax"


def test_suppress_bands_searches_upward_too():
    """Looking only downward missed a label's BOTTOM edge -- the failure that
    cost 2 of 10 synthetic label scenes."""
    height = 200
    signed = np.zeros(height, dtype=np.float32)
    signed[40] = 10.0    # label top
    signed[80] = -10.0   # label bottom: its partner is ABOVE it
    out = suppress_bands(signed, height)
    assert out[80] < abs(signed[80]), "bottom edge of a band must be damped"


# --- feature planes ----------------------------------------------------------

def test_features_are_bounded_and_finite():
    rgb = render(0.5, seed=1).rgb
    for name, plane in to_features(rgb).items():
        assert np.isfinite(plane).all(), name
        assert plane.min() >= -1e-6 and plane.max() <= 1.0 + 1e-6, name


def test_detail_is_higher_through_air_than_through_water():
    """The refraction asymmetry the `detail` feature exists to measure."""
    scene = render(0.5, seed=3, smear_radius=5)
    left, right = find_bottle_columns(scene.rgb)
    detail = to_features(scene.rgb[:, left:right])["detail"].mean(axis=1)
    split = len(detail) // 2
    assert detail[:split].mean() > detail[split:].mean()


# --- the detector end to end -------------------------------------------------

def test_detect_rejects_a_non_rgb_array():
    with pytest.raises(ValueError):
        detect(np.zeros((10, 10), dtype=np.uint8))


def test_detect_survives_a_tiny_image():
    observation = detect(np.zeros((4, 4, 3), dtype=np.uint8))
    assert not observation.readable and observation.confidence == 0.0


def test_detect_survives_a_uniform_image():
    observation = detect(np.full((200, 100, 3), 128, dtype=np.uint8))
    assert not observation.readable


@pytest.mark.parametrize("fill", [0.15, 0.30, 0.50, 0.70, 0.88])
def test_detect_recovers_a_known_fill(fill):
    scene = render(fill, seed=11)
    observation = detect(scene.rgb)
    assert observation.readable
    assert abs(observation.fill - fill) <= 0.05, f"got {observation.fill:.3f}"


def test_opaque_flask_is_reported_unreadable_not_guessed():
    """A steel flask has no line at any price. Saying so is the correct answer;
    inventing a number would silently accept noise."""
    for seed in range(6):
        observation = detect(render(0.5, seed=seed, opacity=1.0).rgb)
        assert not observation.readable, f"seed {seed} conf={observation.confidence:.1f}"


def test_confidence_separates_readable_from_unreadable():
    readable = [detect(render(f, seed=i).rgb).confidence
                for i, f in enumerate([0.2, 0.4, 0.6, 0.8])]
    opaque = [detect(render(0.5, seed=i, opacity=1.0).rgb).confidence for i in range(4)]
    assert min(readable) > CONFIDENCE_FLOOR > max(opaque)


# --- corpus-level gates ------------------------------------------------------
# These mirror the acceptance gate in docs/OFFLINE_WATER.md, so the harness that
# will judge the real corpus is exercised now. Passing here is NOT the gate.

def test_synthetic_corpus_meets_the_fill_accuracy_gate():
    scenes = [s for s in corpus(60) if not s.unreadable]
    errors = np.array([abs(detect(s.rgb).fill - s.fill) for s in scenes])
    within = (errors <= 0.05).mean()
    assert within >= 0.80, f"only {within*100:.0f}% within +/-5% (MAE {errors.mean():.4f})"


def test_synthetic_corpus_rejects_every_opaque_body():
    opaque = [s for s in corpus(60) if s.unreadable]
    assert opaque, "corpus should contain the unreadable class"
    assert not any(detect(s.rgb).readable for s in opaque)


def test_clean_scenes_are_much_better_than_the_gate():
    """Guards the coherence cue specifically: the row-profile method this
    replaced scored MAE 0.043 here, so a regression to it would trip this."""
    scenes = [s for s in corpus(60) if not s.unreadable and "+label" not in s.label]
    errors = np.array([abs(detect(s.rgb).fill - s.fill) for s in scenes])
    assert errors.mean() < 0.02, f"MAE {errors.mean():.4f} -- lost the coherence cue?"


# --- the pair-level contract the app consumes --------------------------------

def test_compare_reports_a_drop_as_a_drop():
    before = render(0.80, seed=21).rgb
    after = render(0.55, seed=21).rgb
    result = compare(before, after)
    assert result["level_readable"]
    assert not result["refilled"]
    assert result["before_fill"] > result["after_fill"]
    assert abs((result["before_fill"] - result["after_fill"]) - 0.25) <= 0.05


def test_compare_flags_a_refill():
    """The level going up is the definition of a refill: arithmetic, not
    perception, so it must be exact rather than merely usually right."""
    result = compare(render(0.40, seed=22).rgb, render(0.85, seed=22).rgb)
    assert result["refilled"]


def test_compare_is_not_fooled_by_an_identical_pair():
    """Photographing the same untouched bottle twice must not credit any drop."""
    same = render(0.6, seed=23).rgb
    result = compare(same, same)
    assert not result["refilled"]
    assert abs(result["before_fill"] - result["after_fill"]) < 1e-6


def test_compare_marks_a_pair_unreadable_if_either_photo_is():
    result = compare(render(0.6, seed=24).rgb,
                     render(0.4, seed=24, opacity=1.0).rgb)
    assert not result["level_readable"]


def test_compare_never_reports_a_verdict():
    """`consumedMl` and `accepted` are the app's to compute, not the
    observer's. Leaking them here would duplicate the policy in two places."""
    result = compare(render(0.7, seed=25).rgb, render(0.5, seed=25).rgb)
    assert "consumed_ml" not in result and "accepted" not in result
