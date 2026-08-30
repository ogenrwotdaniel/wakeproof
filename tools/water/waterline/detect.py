"""Offline water-line detection: the Phase-1 classic-CV baseline.

This is the prototype of what becomes `OnDeviceWaterObserver` in the app. It
produces the same observations the cloud model is asked for, so everything
downstream (the ml arithmetic, the running tally, the strike count) is untouched
-- see docs/OFFLINE_WATER.md.

PORTABILITY IS A HARD CONSTRAINT. Every operation here has a trivial pure-Kotlin
equivalent: means, variances, prefix sums, and separable box blurs. No scipy, no
OpenCV, no FFT, no matrix decompositions. If you are tempted to reach for
`scipy.ndimage`, put it in `evaluate.py` instead -- OpenCV's Android AAR is
~40 MB and the release AAB is already 38 MB.

The method
----------
The plan in docs/OFFLINE_WATER.md proposed collapsing the bottle to a row
profile and taking the largest step. That was measured and it is NOT good
enough, for a reason worth writing down: averaging across the bottle's width
destroys the one cue that identifies a water line. A blotchy background seen
through a clear bottle produces steps in the row profile that are *larger* than
the water line's own (measured: 0.13 vs 0.10 luminance on a synthetic case the
detector then got confidently wrong). Real bathrooms are full of these -- a
tiled wall meeting paint, a countertop edge, a shadow gradient.

What separates them is geometry, not photometry: **a water line is horizontal
across the whole bottle, because gravity makes it so.** A background blotch
boundary crossing the bottle is not. So the primary detector measures, for each
row, an across-column t-statistic that a *coherent* horizontal edge exists
there -- the vertical gradient agreeing in sign across every column. On the
synthetic corpus this cut fill error 11x against the row-profile method (MAE
0.043 -> 0.0039).

Three cues are then combined, each covering the others' failure:

1. ``coherence_curve``  -- is there a horizontal edge here (the gravity cue).
2. ``suppress_bands``   -- an opaque label is a *band*: two coherent edges of
   opposite sign a short distance apart. Water is a *half-space* boundary, with
   one edge and water continuing to the bottle's bottom. Edges that have an
   opposite-signed partner above or below are damped. This is what stops the
   detector locking onto a label's bottom edge, its single worst failure.
3. ``welch_t_curve``    -- the global two-region split, kept as a multiplicative
   gate. On its own it is fooled by background structure; as a gate it vetoes
   coherent edges that do not also separate the column into air-above and
   water-below.

Confidence is the coherence t-statistic at the chosen row, which makes a weak
peak mean "I cannot see the line" -- exactly the answer `levelReadable = False`
is supposed to carry. That is the honest failure mode for an opaque steel flask,
where no line exists at any price.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

# --- Tunables -----------------------------------------------------------------
# Values marked (fitted) were chosen by sweeping the SYNTHETIC corpus, which
# proves only that they are not absurd. `evaluate.py` against real photographs
# is what turns them into measurements. See the README's shooting protocol.

#: Fraction of the bottle's width kept when profiling. The curved walls throw
#: specular highlights that swamp the interior signal, so the outer edges are
#: discarded rather than averaged in.
WIDTH_KEEP = 0.70

#: A candidate row must leave at least this fraction of the column on each side.
#: This is what bounds the detectable fill range: a line inside this margin of
#: the crop's top or bottom cannot be found, so keep it small. (fitted: 0.02
#: through 0.06 scored identically, so the smallest useful value wins.)
MIN_SIDE_FRACTION = 0.03

#: Vertical smoothing (in rows) applied to the gradient field before measuring
#: coherence. Matches the meniscus scale and suppresses sensor noise. (fitted:
#: 1-3 all reached 100% within +/-2%; 3 is the most noise-tolerant of those.)
GRADIENT_SMOOTH_ROWS = 3

#: Vertical box-blur radius applied to each row profile for the global gate.
PROFILE_SMOOTH_RADIUS = 2

#: Band suppression: an opposite-signed edge between these distances (as a
#: fraction of crop height) marks a label band rather than a water line.
BAND_MIN_SPAN = 0.015
BAND_MAX_SPAN = 0.30

#: How strong the opposite-signed partner must be, relative to the candidate,
#: to count as the other side of a band.
BAND_PARTNER_FRACTION = 0.55

#: What surviving a band verdict costs. Damped rather than zeroed: a water line
#: that genuinely sits near a label edge should lose the argmax only if
#: something better exists, not be deleted outright.
BAND_DAMPING = 0.25

#: Coherence t below this means no visible line. Calibrated so opaque bodies
#: fall below it and real lines clear it -- recall on the reject class matters
#: more than regression error, since a wrong *number* only miscredits some ml
#: while a wrong *readable* silently accepts noise.
CONFIDENCE_FLOOR = 8.0

#: Bins per chromaticity axis for the colour signature. 12 -> 144 bins plus one
#: for near-black pixels, whose hue is meaningless.
SIGNATURE_BINS = 12

#: Channel sum below which a pixel is too dark for its chromaticity to mean
#: anything, out of a possible 765.
SIGNATURE_DARK_SUM = 24.0

#: Rows at each end of a shared band that are dropped before comparing. The
#: meniscus and the detected line both carry a few rows of slop, and including
#: them would compare water against air.
BAND_MARGIN_FRACTION = 0.04

#: Weight of the colour term against the wall-profile term in the same-container
#: score. Even, because they fail differently: colour is defeated by a change of
#: lighting, the profile by a bottle photographed from a different distance.
SIGNATURE_WEIGHT = 0.5

#: Same-container score below this means a different bottle. (fitted) Deliberately
#: permissive: a false "different bottle" is a rejection the user sees at 06:00,
#: while a missed one only means someone swapped bottles mid-drink, which earns
#: them nothing -- only *drops* are ever credited.
SAME_CONTAINER_FLOOR = 0.55


@dataclass
class Observation:
    """One bottle in one photo. Mirrors the app's per-image half of `WaterVerdict`."""

    #: Row of the water line, normalised 0..1 down the analysed bottle column.
    line: float
    #: (bottom - line) / (bottom - top). 0.0 = empty, 1.0 = full to the brim.
    fill: float
    #: Coherence t-statistic at the chosen row. Drives `readable`.
    confidence: float
    #: confidence >= CONFIDENCE_FLOOR. Becomes `levelReadable`.
    readable: bool
    #: Signed gradient at the line: negative when the water side is darker.
    #: Not used for the verdict; kept because it is a cheap same-bottle cue.
    polarity: float = 0.0
    #: The pixel column range profiled, for overlay rendering in evaluate.py.
    columns: tuple[int, int] = (0, 0)
    #: Intermediate curves, for debugging. Never used by the verdict.
    debug: dict[str, np.ndarray] = field(default_factory=dict)


def to_features(rgb: np.ndarray) -> dict[str, np.ndarray]:
    """Three per-pixel feature planes, each float32 in roughly 0..1.

    Chosen because they fail in *different* ways:

    ``lum``    Mean luminance. Water attenuates what is behind it. The strongest
               cue on a clear bottle; destroyed by a dark or coloured body.
    ``sat``    Saturation. Survives the brightness changes that break ``lum``;
               confused by a coloured label.
    ``detail`` Absolute horizontal gradient. A genuine physical asymmetry rather
               than a photometric accident: the water column refracts, so
               background detail seen *through* water is smeared while detail
               through the air half stays sharp. Independent of whether water
               reads darker or lighter; defeated by printed text.
    """
    img = rgb.astype(np.float32) / 255.0
    r, g, b = img[..., 0], img[..., 1], img[..., 2]

    lum = 0.299 * r + 0.587 * g + 0.114 * b
    sat = img.max(axis=2) - img.min(axis=2)

    detail = np.zeros_like(lum)
    detail[:, 1:-1] = np.abs(lum[:, 2:] - lum[:, :-2]) * 0.5
    if lum.shape[1] > 1:
        detail[:, 0] = np.abs(lum[:, 1] - lum[:, 0])
        detail[:, -1] = np.abs(lum[:, -1] - lum[:, -2])

    return {"lum": lum, "sat": sat, "detail": detail}


def _box_blur_rows(plane: np.ndarray, radius: int) -> np.ndarray:
    """Vertical moving average with replicated edges, over a 1-D or 2-D array."""
    if radius <= 0:
        return plane
    window = 2 * radius + 1
    padded = np.pad(
        plane, ((radius, radius),) + ((0, 0),) * (plane.ndim - 1), mode="edge"
    )
    zero_shape = (1,) + plane.shape[1:]
    cumulative = np.concatenate(
        [np.zeros(zero_shape, dtype=np.float64), np.cumsum(padded, axis=0, dtype=np.float64)]
    )
    return ((cumulative[window:] - cumulative[:-window]) / window).astype(np.float32)


def _normalise(profile: np.ndarray) -> np.ndarray:
    """Rescale to 0..1 by observed range, so features are comparable."""
    lo, hi = float(profile.min()), float(profile.max())
    if hi - lo < 1e-6:
        return np.zeros_like(profile)
    return (profile - lo) / (hi - lo)


def coherence_curve(
    lum: np.ndarray, min_side: int, smooth_rows: int = GRADIENT_SMOOTH_ROWS
) -> np.ndarray:
    """Signed across-column t-statistic for a coherent horizontal edge per row.

    This is the primary detector and the reason the method works. For each row
    it takes the vertical gradient across every column and asks whether the
    columns *agree*::

        t[y] = mean_x(g[y]) * sqrt(W) / std_x(g[y])

    A water line is horizontal by gravity, so every column darkens (or
    brightens) together: the mean is large and the spread small, giving a large
    t. Arbitrary background structure crossing the bottle disagrees between
    columns, so the mean cancels against a large spread and t stays small.

    The sign is preserved -- it says which side is darker, which
    :func:`suppress_bands` needs and which is a free same-bottle cue.
    """
    height, width = lum.shape
    gradient = np.zeros_like(lum)
    gradient[1:-1] = lum[2:] - lum[:-2]
    gradient = _box_blur_rows(gradient, smooth_rows)

    mean = gradient.mean(axis=1)
    spread = gradient.std(axis=1)
    t = mean * np.sqrt(width) / (spread + 1e-9)

    # Rows too close to either end cannot host a line: no room for a region.
    # The valid band is [min_side, height - min_side] INCLUSIVE at both ends,
    # matching welch_t_curve exactly -- an edge at either boundary still leaves
    # min_side rows on its short side. The two must agree: `detect` multiplies
    # them together, so a mismatch would silently make one of them the real
    # bound and mask a bug in the other.
    t[:min_side] = 0.0
    t[height - min_side + 1:] = 0.0
    return t.astype(np.float32)


def welch_t_curve(profile: np.ndarray, min_side: int) -> np.ndarray:
    """Welch t for splitting ``profile`` above/below each row -- the global gate.

    Element ``k`` compares rows ``[0, k)`` against ``[k, n)``. O(n) via prefix
    sums. The ``E[x^2] - E[x]^2`` variance shortcut is only safe because
    features are normalised to ~0..1 before they get here; keep that invariant
    when porting or the subtraction will lose precision.
    """
    n = profile.shape[0]
    out = np.zeros(n, dtype=np.float32)
    if n < 2 * min_side or min_side < 1:
        return out

    x = profile.astype(np.float64)
    cs = np.concatenate([[0.0], np.cumsum(x)])
    cq = np.concatenate([[0.0], np.cumsum(x * x)])

    k = np.arange(min_side, n - min_side + 1)
    n1 = k.astype(np.float64)
    n2 = (n - k).astype(np.float64)

    m1, m2 = cs[k] / n1, (cs[n] - cs[k]) / n2
    # Clamped at 0: the shortcut can go a hair negative on a constant run.
    v1 = np.maximum(cq[k] / n1 - m1 * m1, 0.0)
    v2 = np.maximum((cq[n] - cq[k]) / n2 - m2 * m2, 0.0)

    out[k] = (np.abs(m1 - m2) / np.sqrt(v1 / n1 + v2 / n2 + 1e-12)).astype(np.float32)
    return out


def suppress_bands(
    signed: np.ndarray,
    height: int,
    min_span: float = BAND_MIN_SPAN,
    max_span: float = BAND_MAX_SPAN,
    partner_fraction: float = BAND_PARTNER_FRACTION,
    damping: float = BAND_DAMPING,
) -> np.ndarray:
    """Damp coherent edges that are one side of a band, and return magnitudes.

    An opaque label is a band: a step onto it and an opposite step off it, a
    short distance apart. A water line is a half-space boundary -- one step,
    with water continuing to the bottle's bottom. So an edge with a comparable
    opposite-signed partner within a label's thickness is probably a label.

    Searching BOTH directions is load-bearing. Looking only downward misses the
    case that actually bit: locking onto a label's *bottom* edge, whose partner
    is above it. Fixing that took the synthetic label pass-rate from 80% to 90%
    with no cost to clean scenes.
    """
    n = signed.shape[0]
    out = np.abs(signed).copy()
    near = max(1, int(height * min_span))
    far = max(near + 1, int(height * max_span))

    for y in range(n):
        if out[y] <= 0.0:
            continue
        wanted = partner_fraction * abs(signed[y])
        opposite = -np.sign(signed[y])
        below = signed[y + near: min(n, y + far)]
        above = signed[max(0, y - far): max(0, y - near)]
        for segment in (below, above):
            if segment.size and float((opposite * segment).max()) >= wanted:
                out[y] *= damping
                break
    return out


def find_bottle_columns(rgb: np.ndarray, keep: float = WIDTH_KEEP) -> tuple[int, int]:
    """Horizontal extent to profile.

    Deliberately dumb: the capture UI draws a bottle-shaped framing guide, so
    the subject is already centred and roughly scaled. Constraining the input
    beats solving the general case -- docs/OFFLINE_WATER.md makes the same call.
    """
    width = rgb.shape[1]
    margin = int(round(width * (1.0 - keep) / 2.0))
    left = min(margin, max(0, width // 2 - 1))
    right = max(width - margin, left + 1)
    return left, right


def detect(
    rgb: np.ndarray,
    columns: tuple[int, int] | None = None,
    confidence_floor: float = CONFIDENCE_FLOOR,
    keep_debug: bool = False,
) -> Observation:
    """Locate the water line in one bottle photo.

    ``rgb`` is HxWx3 uint8, cropped to the bottle's water column (the framing
    guide's job). ``line`` and ``fill`` are normalised against that crop, so the
    caller never handles pixel coordinates.
    """
    if rgb.ndim != 3 or rgb.shape[2] != 3:
        raise ValueError(f"expected HxWx3 RGB, got shape {rgb.shape}")

    left, right = columns if columns is not None else find_bottle_columns(rgb)
    crop = rgb[:, left:right]
    if crop.shape[0] < 8 or crop.shape[1] < 2:
        return Observation(0.0, 0.0, 0.0, False, columns=(left, right))

    planes = to_features(crop)
    height = crop.shape[0]
    min_side = max(1, int(round(height * MIN_SIDE_FRACTION)))

    # 1. Primary: coherent horizontal edges (the gravity cue).
    signed = coherence_curve(planes["lum"], min_side)
    # 2. Damp label bands.
    score = suppress_bands(signed, height)
    # 3. Gate on the global two-region split, pooled over all three features.
    #    Raw t values are summed, NOT each normalised to its own peak: the t
    #    statistic is already scale-free, so its magnitude IS the evidence
    #    weight. Normalising first gives an uninformative feature an equal vote
    #    and measurably wrecked the label cases (MAE 0.30 vs 0.078).
    gate = np.zeros(height, dtype=np.float32)
    for plane in planes.values():
        profile = _box_blur_rows(plane.mean(axis=1), PROFILE_SMOOTH_RADIUS)
        gate += welch_t_curve(_normalise(profile), min_side)
    score = score * _normalise(gate)

    line_row = int(score.argmax())
    line = line_row / max(1, height - 1)
    # Confidence is the coherence t itself, not the gated product, so it stays
    # an interpretable statistic rather than an arbitrary score.
    confidence = float(abs(signed[line_row]))

    observation = Observation(
        line=line,
        # The crop IS the water column, so top = 0 and bottom = 1 by definition.
        fill=float(np.clip(1.0 - line, 0.0, 1.0)),
        confidence=confidence,
        readable=confidence >= confidence_floor,
        polarity=float(signed[line_row]),
        columns=(left, right),
    )
    if keep_debug:
        observation.debug = {"signed": signed, "score": score, "gate": gate}
    return observation


#: Histogram length: the chromaticity plane plus the near-black bin.
SIGNATURE_LENGTH = SIGNATURE_BINS * SIGNATURE_BINS + 1


def colour_signature(rows: np.ndarray) -> np.ndarray:
    """Normalised CHROMATICITY histogram of a block of pixels.

    Chromaticity -- ``r/(r+g+b)``, ``g/(r+g+b)`` -- rather than raw RGB, because
    the raw version failed the case that matters. Scaling every channel by the
    same factor is exactly what a lamp being switched on does, and it leaves
    chromaticity untouched while moving raw RGB into different bins. Measured on
    synthetic scenes: a 40% brightness drop between the two shots scored the same
    bottle at 0.53 against a 0.55 floor -- a rejection, at 06:00, for turning a
    light off. Chromaticity is invariant to it by construction.

    Blue carries no extra information once the other two are normalised (the
    three sum to one), so the histogram is 2-D. Near-black pixels have no
    meaningful chromaticity at all and go in their own bin instead of being
    scattered by sensor noise.

    Portable by construction -- a divide, an integer bin index and a count.
    """
    if rows.size == 0:
        return np.zeros(SIGNATURE_LENGTH, dtype=np.float64)
    flat = rows.reshape(-1, 3).astype(np.float64)
    total = flat.sum(axis=1)
    safe = np.maximum(total, 1e-6)
    red = np.clip((flat[:, 0] / safe * SIGNATURE_BINS).astype(np.int32), 0, SIGNATURE_BINS - 1)
    green = np.clip((flat[:, 1] / safe * SIGNATURE_BINS).astype(np.int32), 0, SIGNATURE_BINS - 1)
    packed = red * SIGNATURE_BINS + green
    packed = np.where(total < SIGNATURE_DARK_SUM, SIGNATURE_BINS * SIGNATURE_BINS, packed)
    hist = np.bincount(packed, minlength=SIGNATURE_LENGTH).astype(np.float64)
    total_count = hist.sum()
    return hist / total_count if total_count > 0 else hist


def signature_similarity(a: np.ndarray, b: np.ndarray) -> float:
    """Histogram intersection, 0..1. Identical distributions give 1.0."""
    return float(np.minimum(a, b).sum())


def wall_profile(rows: np.ndarray) -> np.ndarray:
    """Per-column mean luminance across a block -- the bottle's wall signature.

    The curved walls throw specular highlights down both edges, so this profile
    is a cheap stand-in for the silhouette: it says how wide the bottle is and
    where its edges fall, without segmenting anything.
    """
    if rows.size == 0:
        return np.zeros(0, dtype=np.float32)
    img = rows.astype(np.float32) / 255.0
    lum = 0.299 * img[..., 0] + 0.587 * img[..., 1] + 0.114 * img[..., 2]
    return lum.mean(axis=0)


def profile_correlation(a: np.ndarray, b: np.ndarray) -> float:
    """Pearson correlation of two wall profiles, clipped to 0..1.

    Correlation rather than a distance because it is invariant to the overall
    brightness change between two photographs of the same bottle, which is
    exactly the nuisance variable here.
    """
    if a.size < 2 or a.size != b.size:
        return 0.0
    x, y = a - a.mean(), b - b.mean()
    denominator = float(np.sqrt((x * x).sum() * (y * y).sum()))
    if denominator < 1e-9:
        # Two featureless profiles agree trivially; that is not evidence either
        # way, so it neither convicts nor acquits.
        return 0.0
    return float(np.clip((x * y).sum() / denominator, 0.0, 1.0))


def shared_bands(
    before_line: float, after_line: float, height: int
) -> tuple[slice, slice]:
    """The row ranges showing the same thing in both photos.

    This is the whole trick. Comparing the two crops wholesale is defeated by
    the very thing being measured: drinking converts a band of the image from
    water to air, so an honest before/after pair of the SAME bottle scores as
    two different ones. But the rows above the higher line are air in both, and
    the rows below the lower line are water in both -- those two bands are
    directly comparable, and everything that changed lies between them.
    """
    margin = max(1, int(round(height * BAND_MARGIN_FRACTION)))
    top = int(round(min(before_line, after_line) * (height - 1)))
    bottom = int(round(max(before_line, after_line) * (height - 1)))
    air = slice(0, max(0, top - margin))
    water = slice(min(height, bottom + margin), height)
    return air, water


def same_container(
    before: np.ndarray,
    after: np.ndarray,
    before_line: float,
    after_line: float,
    columns: tuple[int, int] | None = None,
) -> tuple[bool, float]:
    """Is this the same bottle in both photos? Returns (verdict, score 0..1).

    Two cues over the bands that did not change (:func:`shared_bands`):
    a coarse colour histogram and the wall-highlight profile. Neither is clever,
    and docs/OFFLINE_WATER.md is explicit that neither should be -- the job is
    rejecting "a different bottle" while tolerating "same bottle, moved 10 cm".

    A band that comes out empty (a nearly full or nearly empty bottle leaves no
    air or no water band) simply drops out of the average rather than scoring
    zero, which would convict every brim-full bottle of being a different one.
    """
    left, right = columns if columns is not None else find_bottle_columns(before)
    if before.shape != after.shape:
        return False, 0.0

    height = before.shape[0]
    air, water = shared_bands(before_line, after_line, height)

    scores: list[float] = []
    for band in (air, water):
        first, second = before[band, left:right], after[band, left:right]
        if first.shape[0] < 2:
            continue
        colour = signature_similarity(colour_signature(first), colour_signature(second))
        profile = profile_correlation(wall_profile(first), wall_profile(second))
        scores.append(SIGNATURE_WEIGHT * colour + (1.0 - SIGNATURE_WEIGHT) * profile)

    if not scores:
        # Nothing comparable at all: the two lines sit on top of each other and
        # the bottle fills the frame. Say yes -- there is no evidence of a swap,
        # and the "no measurable drop" rule below will reject the pair anyway.
        return True, 1.0
    score = float(np.mean(scores))
    return score >= SAME_CONTAINER_FLOOR, score


def compare(
    before: np.ndarray,
    after: np.ndarray,
    columns: tuple[int, int] | None = None,
) -> dict[str, object]:
    """Observe a before/after pair, in the shape the app's `WaterVerdict` wants.

    Deliberately reports OBSERVATIONS ONLY. `consumedMl` and `accepted` are the
    verdict and stay in Kotlin, recomputed locally exactly as they are today --
    that separation is what makes this a drop-in replacement for the cloud call
    rather than a rewrite. See docs/OFFLINE_WATER.md.

    ``refilled`` is arithmetic, not perception: the level going *up* is the
    definition of a refill. It never needed a model and does not get one.

    ``same_container`` is the cheap classic check docs/OFFLINE_WATER.md asks
    for -- see :func:`same_container`. It is compared over the bands the drink
    did not change, so measuring a real gulp cannot itself look like a swapped
    bottle.
    """
    first = detect(before, columns)
    second = detect(after, columns)
    same, container_score = same_container(
        before, after, first.line, second.line, columns
    )
    return {
        "same_container": same,
        "container_score": container_score,
        "level_readable": first.readable and second.readable,
        "before_fill": first.fill,
        "after_fill": second.fill,
        "refilled": second.fill > first.fill + 0.02,
        "confidence": min(first.confidence, second.confidence),
        # Printed ml marks are ML Kit Text Recognition's job: on-device, free,
        # and Android-only, so there is nothing to prototype here. The app falls
        # back to the fill fraction when marks are not legible, which is what
        # this reports unconditionally.
        "scale_ml_readable": False,
        "_before": first,
        "_after": second,
    }
