import json
import sys
from pathlib import Path

import pytest

CLIP_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = CLIP_DIR.parents[1]
sys.path.insert(0, str(CLIP_DIR))

VECTORS = REPO_ROOT / "app/src/test/resources/clip/vectors.json"


@pytest.fixture(scope="session")
def vectors():
    return json.loads(VECTORS.read_text("utf-8"))
