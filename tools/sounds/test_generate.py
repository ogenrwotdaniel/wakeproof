"""What has to stay true about the bundled study loops.

These are cheap structural checks, not a taste test — no test can tell you
whether rain sounds like rain. What they can do is catch the three ways this
generator has a history of going quietly wrong: a loop that clicks every time
round, a set whose members are levelled differently, and an encoder setting
that turns noise to soup.

Run with a short duration so the suite stays fast; nothing under test depends
on the loop actually being 75 s.
"""

from __future__ import annotations

import numpy as np
import pytest
import soundfile as sf

from tools.sounds import generate, loops, synth

SECONDS = 4


@pytest.fixture(scope="module")
def rendered() -> dict[str, np.ndarray]:
    return {name: generate.render(name, SECONDS) for name in loops.LOOPS}


def test_every_loop_id_has_a_generator() -> None:
    for name, (fn, seed) in loops.LOOPS.items():
        assert callable(fn)
        assert isinstance(seed, int)


def test_loops_are_stereo_and_finite(rendered: dict[str, np.ndarray]) -> None:
    for name, audio in rendered.items():
        assert audio.shape == (SECONDS * synth.SAMPLE_RATE, 2), name
        assert np.isfinite(audio).all(), name
        assert np.abs(audio).max() <= 1.0, name


def test_render_is_deterministic() -> None:
    first = generate.render("night_rain", SECONDS)
    second = generate.render("night_rain", SECONDS)
    assert np.array_equal(first, second)


def test_the_seam_is_not_a_click(rendered: dict[str, np.ndarray]) -> None:
    """The join must be as ordinary as any other pair of adjacent samples.

    This is the property the whole spectral/wrapped construction exists for. A
    loop built by crossfading, or one with a time-domain filter's start-up
    transient in it, fails here: the step from the last sample back to the
    first is far larger than a typical step, and that is what a listener hears
    as a tick every time round.
    """
    for name, audio in rendered.items():
        seam = np.abs(audio[0] - audio[-1]).max()
        typical = np.percentile(np.abs(np.diff(audio[:, 0])), 99)
        assert seam <= typical, f"{name}: seam {seam:.4f} vs p99 step {typical:.4f}"


def test_the_set_is_loudness_matched(rendered: dict[str, np.ndarray]) -> None:
    """Switching sound must not mean reaching for the volume keys.

    The tolerance is one-sided and generous below target because a loop with
    real swells (surf) legitimately measures quieter than steady noise once its
    crests are kept under the ceiling.
    """
    for name, audio in rendered.items():
        measured = synth.k_weighted_lufs(audio)
        assert generate.TARGET_LUFS - 3.0 <= measured <= generate.TARGET_LUFS + 0.5, (
            f"{name}: {measured:.2f} LUFS"
        )


def test_nothing_is_levelled_by_a_single_transient(rendered: dict[str, np.ndarray]) -> None:
    """Crest factor stays in a range that survives the peak ceiling."""
    for name, audio in rendered.items():
        crest = 20 * np.log10(np.abs(audio).max() / synth.rms(audio))
        assert crest < 22.0, f"{name}: crest {crest:.1f} dB"


def test_encoder_setting_does_not_make_noise_swirl(tmp_path) -> None:
    """The decoded file must fluctuate no more, band by band, than the source.

    Vorbis codes noise by shaping a quantisation floor, and pushed too far that
    floor pumps: the rain stops being a steady sheet and starts washing in and
    out, band by band. It is audible long before any spectral average moves,
    which is why the measurement here is of per-band energy *variance* over
    time rather than of the spectrum.
    """
    audio = generate.render("night_rain", SECONDS)
    path = tmp_path / "rain.ogg"
    generate.write_ogg(path, audio)
    decoded, rate = sf.read(path, always_2d=True)

    assert rate == synth.SAMPLE_RATE
    # Sample-exact length is what lets the player loop the file without a gap.
    assert len(decoded) == len(audio)

    excess = _band_modulation(decoded[:, 0]) - _band_modulation(audio[:, 0])
    assert excess.max() < 0.5, f"excess band modulation {excess}"


def _band_modulation(mono: np.ndarray) -> np.ndarray:
    """Per-octave-band energy spread over time, in dB."""
    hop = 1024
    frames = len(mono) // hop
    windowed = mono[: frames * hop].reshape(frames, hop) * np.hanning(hop)
    power = np.abs(np.fft.rfft(windowed, axis=1)) ** 2
    freqs = np.fft.rfftfreq(hop, 1.0 / synth.SAMPLE_RATE)
    spread = []
    for low in (250, 500, 1_000, 2_000, 4_000, 8_000):
        band = (freqs >= low) & (freqs < low * 2)
        energy = 10 * np.log10(power[:, band].mean(axis=1) + 1e-20)
        spread.append(energy.std())
    return np.array(spread)
