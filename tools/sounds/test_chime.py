"""The transition chime must stay gentle: no click in, no hard stop, no shrill top."""

import numpy as np

from tools.sounds import chime
from tools.sounds.synth import SAMPLE_RATE


def test_chime_starts_and_ends_in_silence():
    audio = chime.render()[:, 0]
    assert abs(audio[0]) < 1e-6
    assert abs(audio[-1]) < 1e-6


def test_chime_peak_leaves_headroom():
    assert np.max(np.abs(chime.render())) <= chime.PEAK + 1e-9


def test_chime_does_not_jump_in():
    audio = chime.render()[:, 0]
    first_ms = int(0.005 * SAMPLE_RATE)
    assert np.max(np.abs(audio[:first_ms])) < 0.1 * chime.PEAK


def test_tree_warning_is_lower_than_the_chime():
    def dominant(audio):
        spectrum = np.abs(np.fft.rfft(audio[:, 0]))
        return np.fft.rfftfreq(audio.shape[0], 1 / SAMPLE_RATE)[np.argmax(spectrum)]

    assert dominant(chime.render(chime.WARNING_HZ)) < dominant(chime.render()) - 50


def test_chime_energy_sits_below_2_5_khz():
    audio = chime.render()[:, 0]
    spectrum = np.abs(np.fft.rfft(audio)) ** 2
    freqs = np.fft.rfftfreq(audio.size, 1 / SAMPLE_RATE)
    assert spectrum[freqs > 2_500].sum() / spectrum.sum() < 0.01
