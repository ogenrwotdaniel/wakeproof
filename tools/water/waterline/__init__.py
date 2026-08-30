"""Offline water-line verification: prototype, evaluation harness and corpus tools."""

from .detect import (
    Observation,
    colour_signature,
    detect,
    find_bottle_columns,
    same_container,
    signature_similarity,
    to_features,
    welch_t_curve,
)

__all__ = [
    "Observation",
    "colour_signature",
    "detect",
    "find_bottle_columns",
    "same_container",
    "signature_similarity",
    "to_features",
    "welch_t_curve",
]
