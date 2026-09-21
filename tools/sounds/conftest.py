"""Puts the repo root on sys.path so `from tools.sounds import ...` resolves
however pytest was invoked."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
