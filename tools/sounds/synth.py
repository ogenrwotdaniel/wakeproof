"""Building blocks for the bundled study loops.

Everything here is *circular*: a signal of ``n`` samples that is periodic with
period exactly ``n``, so concatenating copies of it is indistinguishable from
one continuous signal. That is the whole trick behind the loops sounding
seamless without a crossfade.

Three rules keep a layer circular, and every generator in
:mod:`tools.sounds.loops` obeys them:

* **Noise** is built in the frequency domain (:func:`spectral_noise`). An
  inverse real FFT of ``n//2 + 1`` bins is periodic in ``n`` by construction,
  which a time-domain filter applied to time-domain noise is not.
* **Modulation** uses :func:`lfo`, whose rate is an integer number of cycles
  per loop, so the envelope meets itself at the seam.
* **Events** (raindrops, fire crackles) are written with :func:`add_wrapped`,
  so an event starting near the end continues over the beginning — the same
  drop the listener hears at the seam either way.

The previous generation of these assets crossfaded the tail into the head
instead. That works, but it means the last few seconds are two takes summed at
reduced level, which reads as a dip in density every time round.
"""

from __future__ import annotations

import numpy as np

SAMPLE_RATE = 44_100

# --------------------------------------------------------------------------
# Spectral shaping. Each helper returns a magnitude multiplier for an array of
# frequencies in Hz, so shapes compose by multiplication.
# --------------------------------------------------------------------------


def tilt(freqs: np.ndarray, slope: float, ref: float = 1_000.0) -> np.ndarray:
    """``(f / ref) ** -slope``: 0 = white, 0.5 = pink, 1 = brown."""
    out = np.zeros_like(freqs)
    nz = freqs > 0
    out[nz] = (freqs[nz] / ref) ** (-slope)
    return out


def lowpass(freqs: np.ndarray, cutoff: float, order: float = 2.0) -> np.ndarray:
    """Butterworth-shaped magnitude roll-off above [cutoff]."""
    return 1.0 / np.sqrt(1.0 + (freqs / cutoff) ** (2 * order))


def highpass(freqs: np.ndarray, cutoff: float, order: float = 2.0) -> np.ndarray:
    """Butterworth-shaped magnitude roll-off below [cutoff]."""
    ratio = np.divide(freqs, cutoff, out=np.zeros_like(freqs), where=freqs > 0)
    return ratio**order / np.sqrt(1.0 + ratio ** (2 * order))


def bandpass(
    freqs: np.ndarray, low: float, high: float, order: float = 2.0
) -> np.ndarray:
    return highpass(freqs, low, order) * lowpass(freqs, high, order)


def bell(
    freqs: np.ndarray, centre: float, gain_db: float, q: float = 1.0
) -> np.ndarray:
    """A broad peak/dip at [centre], for nudging a shape rather than carving it."""
    width = np.log2(np.maximum(freqs, 1e-6) / centre) * q
    return 10.0 ** ((gain_db / 20.0) * np.exp(-(width**2)))


# --------------------------------------------------------------------------
# Circular primitives
# --------------------------------------------------------------------------


def spectral_noise(
    n: int,
    rng: np.random.Generator,
    shape,
    coherence=None,
) -> np.ndarray:
    """Stereo noise of ``n`` frames whose magnitude spectrum follows [shape].

    Periodic in ``n``, because it is an inverse FFT of exactly ``n`` bins.

    [coherence] gives, per frequency, how much of the two channels is the same
    signal (1 = mono, 0 = independent). Real diffuse fields are coherent at low
    frequencies and not at high ones, and following that matters twice over:
    independent bass in headphones is a muddy, unstable rumble, while identical
    treble collapses the image to a point between the ears. The default fades
    from mono under ~150 Hz to nearly independent above ~2 kHz.
    """
    freqs = np.fft.rfftfreq(n, 1.0 / SAMPLE_RATE)
    magnitude = np.asarray(shape(freqs), dtype=np.float64)
    magnitude[0] = 0.0  # no DC: it is inaudible and only eats headroom

    if coherence is None:
        coh = 1.0 / np.sqrt(1.0 + (freqs / 150.0) ** 2)
        coh = np.clip(coh, 0.08, 1.0)
    else:
        coh = np.clip(np.asarray(coherence(freqs), dtype=np.float64), 0.0, 1.0)

    def bins() -> np.ndarray:
        return np.exp(2j * np.pi * rng.random(freqs.size))

    shared, left, right = bins(), bins(), bins()
    spread = np.sqrt(np.maximum(0.0, 1.0 - coh**2))
    spec_l = magnitude * (coh * shared + spread * left)
    spec_r = magnitude * (coh * shared + spread * right)

    out = np.stack(
        [np.fft.irfft(spec_l, n), np.fft.irfft(spec_r, n)], axis=1
    )
    return out


def lfo(n: int, cycles: int, phase: float = 0.0) -> np.ndarray:
    """Sine of exactly [cycles] periods across the loop, so it meets itself."""
    t = np.arange(n) / n
    return np.sin(2.0 * np.pi * (cycles * t + phase))


def drift(n: int, rng: np.random.Generator, cycles, depth: float = 1.0) -> np.ndarray:
    """Sum of [cycles] LFOs at random phase, normalised to roughly -1..1.

    Several mutually prime rates read as "wandering" rather than as a pulse,
    while every term still closes at the seam.
    """
    out = np.zeros(n)
    for c in cycles:
        out += lfo(n, c, rng.random())
    peak = np.max(np.abs(out))
    return depth * out / peak if peak > 0 else out


def add_wrapped(buffer: np.ndarray, start: int, event: np.ndarray) -> None:
    """Mix [event] into [buffer] at [start], wrapping past the end.

    Wrapping rather than clipping is what stops the loop having a quiet patch
    at the join where no event was allowed to begin.
    """
    n = buffer.shape[0]
    length = event.shape[0]
    if length >= n:
        raise ValueError("event longer than the loop")
    idx = (start + np.arange(length)) % n
    buffer[idx] += event


def to_stereo(mono: np.ndarray, pan: float) -> np.ndarray:
    """Constant-power pan of a mono event; [pan] is -1 (left) to 1 (right)."""
    angle = (np.clip(pan, -1.0, 1.0) + 1.0) * (np.pi / 4.0)
    return np.stack([mono * np.cos(angle), mono * np.sin(angle)], axis=1)


def noise_burst(
    rng: np.random.Generator,
    length: int,
    low: float,
    high: float,
    order: float = 2.0,
) -> np.ndarray:
    """Short band-limited noise, shaped in the frequency domain like everything else."""
    freqs = np.fft.rfftfreq(length, 1.0 / SAMPLE_RATE)
    spec = bandpass(freqs, low, high, order) * np.exp(2j * np.pi * rng.random(freqs.size))
    out = np.fft.irfft(spec, length)
    peak = np.max(np.abs(out))
    return out / peak if peak > 0 else out


def decay(length: int, tau_seconds: float, attack_seconds: float = 0.0005) -> np.ndarray:
    """Percussive envelope: near-instant attack, exponential tail."""
    t = np.arange(length) / SAMPLE_RATE
    attack = np.clip(t / max(attack_seconds, 1e-6), 0.0, 1.0)
    return attack * np.exp(-t / max(tau_seconds, 1e-6))


# --------------------------------------------------------------------------
# Loudness
# --------------------------------------------------------------------------


def k_weighted_lufs(audio: np.ndarray) -> float:
    """Integrated loudness, BS.1770's weighting applied in the frequency domain.

    Matched loudness across the set is the point: the old assets each carried a
    hand-tuned `gain` in FocusSound to undo the fact that they were levelled by
    peak, and hand-tuned numbers drift as soon as an asset is regenerated.
    """
    n = audio.shape[0]
    freqs = np.fft.rfftfreq(n, 1.0 / SAMPLE_RATE)
    # Stage 1: +4 dB shelf above ~1.5 kHz (the head's acoustic response).
    shelf_gain = 10.0 ** (4.0 / 20.0)
    ratio = (freqs / 1_500.0) ** 2
    shelf = np.sqrt((1.0 + shelf_gain**2 * ratio) / (1.0 + ratio))
    weight = shelf * highpass(freqs, 38.0, 2.0)

    power = 0.0
    for ch in range(audio.shape[1]):
        spec = np.fft.rfft(audio[:, ch]) * weight
        power += np.sum(np.abs(np.fft.irfft(spec, n)) ** 2) / n
    return -0.691 + 10.0 * np.log10(max(power, 1e-20))


def rms(audio: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(audio))))


def at_rms(audio: np.ndarray, level: float) -> np.ndarray:
    """Scales a layer to a known RMS so mixes are stated, not discovered.

    Beds are set by RMS and events by peak (:func:`at_peak`) because that is
    how each is heard: a bed's level is its average, a raindrop's is its
    loudest moment. Mixing raw generator output instead leaves the balance at
    the mercy of whatever the random draw happened to produce.
    """
    current = rms(audio)
    return audio * (level / current) if current > 0 else audio


def at_peak(audio: np.ndarray, level: float) -> np.ndarray:
    current = float(np.max(np.abs(audio)))
    return audio * (level / current) if current > 0 else audio


def soft_limit(audio: np.ndarray, knee: float = 0.55) -> np.ndarray:
    """Rounds off peaks above [knee] instead of scaling the whole loop down.

    Fire crackles and close raindrops are a few samples tall and carry almost
    no loudness, so levelling by peak would drop the whole loop several dB to
    accommodate them and undo the matching this module exists for. Above the
    knee the curve is a tanh, which is gentle enough to be inaudible on
    transients this short.
    """
    over = np.abs(audio) > knee
    if not over.any():
        return audio
    out = audio.copy()
    excess = (np.abs(audio[over]) - knee) / (1.0 - knee)
    out[over] = np.sign(audio[over]) * (knee + (1.0 - knee) * np.tanh(excess))
    return out


def normalise(audio: np.ndarray, target_lufs: float, peak_ceiling: float = 0.89) -> np.ndarray:
    """Levels to [target_lufs], then backs off if that would clip.

    The ceiling is generous (-1 dBFS) because the player scales by
    MUSIC_VOLUME anyway; the point is only that the 16-bit write cannot wrap.
    """
    scaled = audio * 10.0 ** ((target_lufs - k_weighted_lufs(audio)) / 20.0)
    peak = np.max(np.abs(scaled))
    if peak > peak_ceiling:
        scaled *= peak_ceiling / peak
    return scaled
