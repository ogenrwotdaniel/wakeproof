"""Renders the focus timer's two tones into ``app/src/main/res/raw``.

    python -m tools.sounds.chime            # write the app's assets
    python -m tools.sounds.chime --out /tmp # audition somewhere harmless

``focus_chime`` marks the end of a block or break. ``tree_warning`` is one low
note, told apart at once from the chime: it means "get back to work", not
"next phase".

The timer used to play the phone's default notification tone on the alarm
stream at full volume, which landed as a jolt at the end of every block. This
is two soft bell notes a fifth apart: a slow attack so it never clicks in, a
long decay so it fades instead of stopping, and partials kept low so nothing
shrill sits on top. It is a one-shot, so unlike the loops it is not circular.
"""

from __future__ import annotations

import argparse
import pathlib

import numpy as np

from .generate import DEFAULT_OUT, write_ogg
from .synth import SAMPLE_RATE

SECONDS = 2.6

#: C5 then G5. A rising fifth reads as "next", not as an alarm.
NOTES_HZ = (523.25, 783.99)
#: G4 alone, a fourth under the chime's first note, for the tree warning.
WARNING_HZ = (392.0,)
#: Each asset and its notes.
TONES = {"focus_chime": NOTES_HZ, "tree_warning": WARNING_HZ}
#: The second note starts this long after the first.
NOTE_GAP_S = 0.32
#: Raised-cosine attack. Under ~10 ms a bell clicks; 30 ms is still crisp.
ATTACK_S = 0.03
#: Decay time constant of the fundamental; overtones die faster.
DECAY_S = 0.55
#: (multiple of the fundamental, relative level, decay factor). Soft overtones only.
PARTIALS = ((1.0, 1.0, 1.0), (2.0, 0.22, 0.55), (3.0, 0.06, 0.35))
#: The file's peak. The app scales it down further (CHIME_VOLUME).
PEAK = 0.5
#: The last stretch fades to exact silence so the player never ends on a step.
TAIL_FADE_S = 0.25


def _note(freq: float, frames: int) -> np.ndarray:
    t = np.arange(frames) / SAMPLE_RATE
    tone = sum(
        level * np.sin(2 * np.pi * freq * mult * t) * np.exp(-t / (DECAY_S * decay))
        for mult, level, decay in PARTIALS
    )
    attack = int(ATTACK_S * SAMPLE_RATE)
    tone[:attack] *= 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, attack))
    return tone


def render(notes: tuple[float, ...] = NOTES_HZ) -> np.ndarray:
    """[notes] as stereo float samples, peak [PEAK], silent at both ends."""
    frames = int(SECONDS * SAMPLE_RATE)
    mono = np.zeros(frames)
    for i, freq in enumerate(notes):
        start = int(i * NOTE_GAP_S * SAMPLE_RATE)
        mono[start:] += _note(freq, frames - start) * (0.85 if i else 1.0)
    tail = int(TAIL_FADE_S * SAMPLE_RATE)
    mono[-tail:] *= np.linspace(1.0, 0.0, tail)
    mono *= PEAK / np.max(np.abs(mono))
    return np.stack([mono, mono], axis=1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT)
    out = parser.parse_args().out
    out.mkdir(parents=True, exist_ok=True)
    for name, notes in TONES.items():
        write_ogg(out / f"{name}.ogg", render(notes))


if __name__ == "__main__":
    main()
