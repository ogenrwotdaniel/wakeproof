"""The bundled study loops themselves: one function per sound.

Each returns a float array of shape ``(frames, 2)`` in -1..1, periodic in
``frames`` (see :mod:`tools.sounds.synth`). Levelling is left to the caller so
every loop can be matched to the same loudness at the end.

Design notes that are easy to undo by accident:

* Rain, fire and surf are **bed + events**, not one filtered noise. Filtered
  noise on its own is what makes a "rain" sample sound like a hiss: the ear
  identifies rain from the individual drops arriving at irregular intervals,
  and identifies fire from crackles that cluster.
* Event amplitudes are drawn log-normal, not uniform. Natural event streams are
  mostly quiet with an occasional close one, and a uniform draw sounds like a
  machine because every drop is about as loud as every other.
* Nothing is band-limited above ~11 kHz. There is no useful cue up there, it is
  the first thing an ambient loop makes fatiguing, and it is also the most
  expensive part of the spectrum to encode.
"""

from __future__ import annotations

import numpy as np

from .synth import (
    SAMPLE_RATE,
    add_wrapped,
    at_peak,
    at_rms,
    bandpass,
    bell,
    decay,
    drift,
    highpass,
    lfo,
    lowpass,
    noise_burst,
    spectral_noise,
    tilt,
    to_stereo,
)


#: Working level every bed is scaled to before events are added on top. The
#: absolute value is arbitrary — generate.py levels the finished loop — but
#: having one shared reference is what lets the event mixes below be written as
#: plain ratios against the bed.
BED_RMS = 0.10


def _log_normal(rng: np.random.Generator, size: int, sigma: float) -> np.ndarray:
    """Amplitudes with a long quiet tail and a few loud outliers, peak 1."""
    out = np.exp(rng.normal(0.0, sigma, size))
    return out / out.max()


# --------------------------------------------------------------------------


def brown_noise(n: int, rng: np.random.Generator) -> np.ndarray:
    """Deep, dark noise — the most neutral thing to work under.

    A gentle undulation keeps it from reading as a broken speaker; steady noise
    with no movement at all is what people describe as "tinnitus-ish".
    """
    bed = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 1.0) * highpass(f, 30.0, 2.0) * lowpass(f, 7_000.0, 1.0),
    )
    swell = 1.0 + 0.10 * drift(n, rng, cycles=(2, 3, 5))
    return at_rms(bed * swell[:, None], BED_RMS)


def pink_noise(n: int, rng: np.random.Generator) -> np.ndarray:
    """Soft static: pink, with the fizz above 6 kHz taken off."""
    bed = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 0.55)
        * highpass(f, 40.0, 2.0)
        * lowpass(f, 9_000.0, 2.0)
        * bell(f, 4_500.0, -4.0, q=0.7),
    )
    swell = 1.0 + 0.07 * drift(n, rng, cycles=(2, 5))
    return at_rms(bed * swell[:, None], BED_RMS)


def night_rain(n: int, rng: np.random.Generator) -> np.ndarray:
    """Steady rain heard from inside: a sheet of hiss, drops, distant rumble."""
    sheet = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 0.6) * bandpass(f, 260.0, 8_500.0, 1.5),
    )
    rumble = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 1.3) * highpass(f, 35.0, 2.0) * lowpass(f, 380.0, 2.0),
    )
    # Rain arrives in sheets; the density audibly breathes over ~10-30 s.
    gust = 1.0 + 0.22 * drift(n, rng, cycles=(3, 7, 11))
    bed = at_rms(sheet * gust[:, None], BED_RMS) + at_rms(rumble, BED_RMS * 0.5)

    drops = np.zeros((n, 2))
    count = int(n / SAMPLE_RATE * 26)  # ~26 audible drops a second
    starts = rng.integers(0, n, count)
    centres = np.exp(rng.uniform(np.log(1_100.0), np.log(6_500.0), count))
    taus = rng.uniform(0.006, 0.030, count)
    amps = _log_normal(rng, count, sigma=0.75)
    pans = rng.uniform(-0.85, 0.85, count)
    for start, centre, tau, amp, pan in zip(starts, centres, taus, amps, pans):
        length = max(64, int(tau * 6 * SAMPLE_RATE))
        mono = noise_burst(rng, length, centre * 0.55, centre * 1.9) * decay(length, tau)
        add_wrapped(drops, int(start), to_stereo(mono * amp, pan))

    # Drops peak a little over three times the bed's average level: loud
    # enough to be individual events, quiet enough not to be a leaky tap.
    return bed + at_peak(drops, BED_RMS * 3.4)


def fireplace(n: int, rng: np.random.Generator) -> np.ndarray:
    """A fire a couple of metres away: low roar, crackles in bursts, few pops."""
    roar = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 1.15) * highpass(f, 45.0, 2.0) * lowpass(f, 1_100.0, 1.5),
    )
    air = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 0.8) * bandpass(f, 700.0, 5_000.0, 1.0),
    )
    # Flames breathe: the roar swells and settles over a few seconds.
    breath = 0.72 + 0.28 * (0.5 + 0.5 * drift(n, rng, cycles=(2, 3, 5, 7, 11)))
    bed = at_rms(roar * breath[:, None], BED_RMS) + at_rms(
        air * breath[:, None], BED_RMS * 0.22
    )

    crackle = np.zeros((n, 2))
    clusters = int(n / SAMPLE_RATE * 3.2)  # a burst of crackling every ~300 ms
    for _ in range(clusters):
        origin = int(rng.integers(0, n))
        pan = float(rng.uniform(-0.7, 0.7))
        for _ in range(int(rng.integers(1, 7))):
            offset = int(rng.uniform(0.0, 0.22) * SAMPLE_RATE)
            tau = float(rng.uniform(0.0012, 0.006))
            centre = float(np.exp(rng.uniform(np.log(900.0), np.log(5_200.0))))
            amp = float(np.exp(rng.normal(0.0, 0.8)))
            length = max(48, int(tau * 8 * SAMPLE_RATE))
            mono = noise_burst(rng, length, centre * 0.6, centre * 2.0) * decay(length, tau)
            add_wrapped(
                crackle,
                origin + offset,
                to_stereo(mono * amp, pan + float(rng.uniform(-0.15, 0.15))),
            )

    # The occasional resonant pop — a knot letting go. Rare on purpose.
    pops = np.zeros((n, 2))
    for _ in range(int(n / SAMPLE_RATE * 0.35)):
        start = int(rng.integers(0, n))
        freq = float(rng.uniform(180.0, 520.0))
        tau = float(rng.uniform(0.03, 0.09))
        length = int(tau * 8 * SAMPLE_RATE)
        t = np.arange(length) / SAMPLE_RATE
        mono = np.sin(2 * np.pi * freq * t) * decay(length, tau, attack_seconds=0.001)
        mono += noise_burst(rng, length, 400.0, 3_000.0) * decay(length, tau * 0.25) * 0.6
        add_wrapped(pops, start, to_stereo(mono * 0.5, float(rng.uniform(-0.5, 0.5))))

    # Crackles sit further above the bed than raindrops do — a fire is mostly
    # its crackles — but the pops stay below them so they read as occasional.
    return bed + at_peak(crackle, BED_RMS * 4.5) + at_peak(pops, BED_RMS * 3.0)


def ocean_waves(n: int, rng: np.random.Generator) -> np.ndarray:
    """Surf: slow swells that brighten as each one breaks, then withdraw."""
    dark = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 0.95) * highpass(f, 40.0, 2.0) * lowpass(f, 1_400.0, 1.5),
    )
    bright = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 0.35) * bandpass(f, 800.0, 10_000.0, 1.5),
    )

    # Waves are near-periodic but never identical; three mutually prime rates
    # give a set that does not settle into an obvious pulse.
    raw = drift(n, rng, cycles=(6, 7, 9))
    swell = ((raw + 1.0) / 2.0) ** 1.5
    swell /= swell.max()

    # Foam only at the crest, so the brightness tracks the break rather than
    # sitting on top of the whole loop.
    # The trough never reaches silence. A wider swing is more literal, but it
    # forces the levelling stage to set the whole loop by its crests, which
    # leaves the sound several dB quieter than the rest of the set.
    body = (0.42 + 0.58 * swell)[:, None]
    foam = (swell**2.2)[:, None]
    # The foam layer is gated by the swell, so it is near silent most of the
    # loop: setting it by RMS would scale its crests to enormous peaks and
    # force the levelling stage to pull the whole sound down. Gated layers are
    # set by peak throughout this module for that reason.
    return at_rms(dark * body, BED_RMS) + at_peak(bright * foam, BED_RMS * 2.4)


def wind_in_pines(n: int, rng: np.random.Generator) -> np.ndarray:
    """Wind through trees: a low body with gusts that hiss through the needles."""
    body = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 1.1) * highpass(f, 45.0, 2.0) * lowpass(f, 700.0, 1.5),
    )
    needles = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 0.45) * bandpass(f, 1_800.0, 9_000.0, 1.5),
    )
    # A narrow band that the gust sweeps through reads as air moving past an
    # edge — the difference between "wind" and "someone left a tap running".
    whistle = spectral_noise(
        n,
        rng,
        lambda f: bandpass(f, 620.0, 1_500.0, 4.0),
    )

    gust = (drift(n, rng, cycles=(2, 3, 5, 7)) + 1.0) / 2.0
    return (
        at_rms(body * (0.45 + 0.55 * gust)[:, None], BED_RMS)
        + at_peak(needles * (gust**2.4)[:, None], BED_RMS * 2.0)
        + at_peak(whistle * (gust**3.5)[:, None], BED_RMS * 1.0)
    )


def ambient_tones(n: int, rng: np.random.Generator) -> np.ndarray:
    """A slow, wide pad — the one loop with pitch in it.

    Partials are snapped to exact multiples of 1/loop so the pad is periodic to
    the sample; at a 75 s loop that grid is 1/75 Hz, far finer than the ear can
    resolve, so detuning survives the snap intact. The chord is quartal (A-E-B-F#)
    rather than major or minor: it has no resolution to wait for, which is what
    lets it sit under work for half an hour without asking for attention.
    """
    duration = n / SAMPLE_RATE
    t = np.arange(n) / SAMPLE_RATE
    out = np.zeros((n, 2))

    voices = [
        (55.00, 0.55, 0.0),    # A1 sub
        (110.00, 0.70, -0.25),  # A2
        (164.81, 0.50, 0.30),   # E3
        (220.00, 0.42, -0.45),  # A3
        (246.94, 0.30, 0.50),   # B3
        (329.63, 0.26, -0.55),  # E4
        (369.99, 0.18, 0.60),   # F#4
        (440.00, 0.14, -0.35),  # A4
    ]

    for index, (freq, level, pan) in enumerate(voices):
        # Two slightly detuned copies per voice: the beating between them is
        # what stops a sine stack sounding like a test tone.
        for detune, side in ((-0.14, -1.0), (0.14, 1.0)):
            snapped = round((freq + detune) * duration) / duration
            phase = rng.random()
            partial = np.sin(2 * np.pi * (snapped * t + phase))
            # Each voice fades in and out on its own slow cycle, so the chord
            # is never quite the same twice round.
            envelope = 0.55 + 0.45 * (0.5 + 0.5 * lfo(n, 1 + index, rng.random()))
            out += to_stereo(partial * level * envelope * 0.5, pan + 0.15 * side)

    # A breath of air on top: without it the pad sounds synthetic and airless.
    air = spectral_noise(
        n,
        rng,
        lambda f: tilt(f, 0.5) * bandpass(f, 1_400.0, 5_500.0, 2.0),
    )
    out = at_rms(out, BED_RMS)
    return out + at_rms(air * (0.20 + 0.10 * drift(n, rng, cycles=(3, 5)))[:, None], BED_RMS * 0.07)


#: Loop id -> (generator, seed). The id is the file name in res/raw and the
#: value stored in the focusSoundId setting; changing one without the other
#: silently falls back to the default sound.
LOOPS = {
    "focus_ambient": (ambient_tones, 20_260_921),
    "night_rain": (night_rain, 1_042),
    "fireplace": (fireplace, 7_731),
    "ocean_waves": (ocean_waves, 5_150),
    "deep_noise": (brown_noise, 909),
    "soft_static": (pink_noise, 3_311),
    "forest_wind": (wind_in_pines, 8_806),
}
