"""Put the tools/water directory on sys.path so tests can import both the
`waterline` package and the top-level scripts (import_masks, sequences)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
