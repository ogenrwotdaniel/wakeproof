"""Renders the bundled study loops into ``app/src/main/res/raw``.

    python -m tools.sounds.generate            # write the app's assets
    python -m tools.sounds.generate --out /tmp # audition somewhere harmless

Output is OGG Vorbis at 44.1 kHz stereo. The previous assets were 22.05 kHz
mono WAV, which costs twice over: 22.05 kHz puts a wall at 11 kHz, exactly
where rain and fire live, and mono collapses an ambience that wants to be all
around the listener. Vorbis at this quality is transparent on material this
dense while being a third of the size, so the whole set costs less than the
five WAVs did.

Every loop is levelled to the same K-weighted loudness here, which is why
FocusSound no longer carries a per-sound gain.
"""

from __future__ import annotations

import argparse
import pathlib

import numpy as np
import soundfile as sf

from .loops import LOOPS
from .synth import SAMPLE_RATE, k_weighted_lufs, normalise, soft_limit

#: 75 s is a compromise: long enough that the ear stops noticing the return,
#: short enough that the set stays a sensible size in the APK.
DEFAULT_SECONDS = 75

#: All loops land here. Chosen so the middle of the old set is unchanged in
#: perceived level once PomodoroTimerService scales by MUSIC_VOLUME — a
#: regenerated asset should not make anyone reach for the volume keys.
TARGET_LUFS = -14.0

#: Vorbis VBR setting, 0 = best quality. Noise is the hardest material there is
#: for a transform codec — a bad encode makes rain "swirl", the noise floor
#: pumping band by band — so this is measured rather than guessed:
#: ``test_generate.py::test_encoder_setting_does_not_make_noise_swirl`` asserts
#: the per-band energy fluctuation of the decoded file matches the source.
#: 0.6 lands around 120 kbps with the excess under 0.1 dB.
COMPRESSION_LEVEL = 0.6

DEFAULT_OUT = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/raw"


def render(name: str, seconds: int = DEFAULT_SECONDS) -> np.ndarray:
    """Renders one loop, levelled and ready to write."""
    generator, seed = LOOPS[name]
    frames = int(seconds * SAMPLE_RATE)
    audio = generator(frames, np.random.default_rng(seed))
    # Level first, so the limiter's knee means the same thing for every loop,
    # then level again because rounding the peaks off changes the loudness a
    # little. Limiting before levelling would apply a fixed knee to whatever
    # arbitrary amplitude the generator happened to produce.
    return normalise(soft_limit(normalise(audio, TARGET_LUFS)), TARGET_LUFS)


def write_ogg(path: pathlib.Path, audio: np.ndarray) -> None:
    """Writes [audio] as Vorbis, a second at a time.

    Handing libsndfile a whole 75 s stereo buffer in one call segfaults its
    Vorbis encoder; streaming it in blocks is both the documented usage and the
    one that survives.
    """
    with sf.SoundFile(
        path,
        "w",
        samplerate=SAMPLE_RATE,
        channels=audio.shape[1],
        format="OGG",
        subtype="VORBIS",
        compression_level=COMPRESSION_LEVEL,
    ) as out:
        block = SAMPLE_RATE
        for start in range(0, audio.shape[0], block):
            out.write(audio[start : start + block].astype(np.float32))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT)
    parser.add_argument("--seconds", type=int, default=DEFAULT_SECONDS)
    parser.add_argument("--only", nargs="*", default=None, help="loop ids to render")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    names = args.only or list(LOOPS)
    total = 0
    for name in names:
        audio = render(name, args.seconds)
        path = args.out / f"{name}.ogg"
        write_ogg(path, audio)
        size = path.stat().st_size
        total += size
        print(
            f"{name:15} {size / 1e6:5.2f} MB  "
            f"{k_weighted_lufs(audio):6.2f} LUFS  peak {np.abs(audio).max():.3f}"
        )
    print(f"{'total':15} {total / 1e6:5.2f} MB")


if __name__ == "__main__":
    main()
