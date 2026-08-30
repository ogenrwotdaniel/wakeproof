"""Tests for the breadth path: line-labelled singles and mask-derived labels.

The breadth corpus is where internet data enters, so these tests care most about
the ways a borrowed label can be WRONG. A dataset annotated for someone else's
task will contain reflections traced as liquid, polygons drawn round the whole
bottle, and images where nobody marked the liquid at all. Importing any of those
as a water line silently poisons the corpus, and a poisoned corpus reports a
number that looks like accuracy and is not.
"""

from __future__ import annotations

import csv
import json

import numpy as np
import pytest

from waterline import lines as lines_module
from waterline.synth import render

import_masks = pytest.importorskip("import_masks")


# --- deriving a line from a liquid mask ---------------------------------------

def _coco(tmp_path, annotations, categories=None, images=None):
    payload = {
        "images": images or [{"id": 0, "file_name": "a.jpg", "width": 200, "height": 400}],
        "categories": categories or [{"id": 1, "name": "bottle"}, {"id": 2, "name": "liquid"}],
        "annotations": annotations,
    }
    path = tmp_path / "_annotations.coco.json"
    path.write_text(json.dumps(payload))
    return path


def _box(ident, image_id, category_id, x, y, w, h):
    return {
        "id": ident, "image_id": image_id, "category_id": category_id,
        "bbox": [x, y, w, h],
        "segmentation": [[x, y, x + w, y, x + w, y + h, x, y + h]],
    }


def test_liquid_mask_top_becomes_the_water_line(tmp_path):
    """A container 0..300 with liquid from 90..300 is 30% empty: line = 0.30."""
    path = _coco(tmp_path, [
        _box(1, 0, 1, 20, 0, 100, 300),
        _box(2, 0, 2, 20, 90, 100, 210),
    ])
    rows, skipped = import_masks.convert(path, source="t")
    assert len(rows) == 1
    assert float(rows[0]["line"]) == pytest.approx(0.30, abs=1e-3)
    assert rows[0]["crop"] == "20,0,100,300"
    assert not skipped


def test_a_narrow_reflection_is_not_mistaken_for_liquid(tmp_path):
    """A sliver down one side of the bottle is a highlight, not a water line."""
    path = _coco(tmp_path, [
        _box(1, 0, 1, 20, 0, 100, 300),
        _box(2, 0, 2, 22, 40, 8, 200),  # 8px wide inside a 100px bottle
    ])
    rows, skipped = import_masks.convert(path, source="t")
    assert rows == []
    assert skipped["liquid mask too narrow"] == 1


def test_a_mask_traced_round_the_whole_bottle_is_rejected(tmp_path):
    path = _coco(tmp_path, [
        _box(1, 0, 1, 20, 0, 100, 300),
        _box(2, 0, 2, 20, 0, 100, 340),  # taller than the container
    ])
    rows, skipped = import_masks.convert(path, source="t")
    assert rows == []
    assert skipped["liquid mask taller than container"] == 1


def test_an_unannotated_container_is_skipped_not_guessed_empty(tmp_path):
    """"Nobody marked the liquid" and "the bottle was empty" are
    indistinguishable in a COCO file. Guessing would invent false empties."""
    path = _coco(tmp_path, [_box(1, 0, 1, 20, 0, 100, 300)])
    rows, skipped = import_masks.convert(path, source="t")
    assert rows == []
    assert skipped["no liquid mask inside container"] == 1

    rows, _ = import_masks.convert(path, source="t", empty_as_full_height=True)
    assert len(rows) == 1 and float(rows[0]["line"]) == pytest.approx(1.0)


def test_liquid_is_matched_to_the_container_it_sits_in(tmp_path):
    """Datasets hold several bottles per photograph; the wrong pairing would
    put one bottle's line inside another bottle's crop."""
    path = _coco(tmp_path, [
        _box(1, 0, 1, 0, 0, 80, 300),     # left bottle
        _box(2, 0, 1, 120, 0, 80, 300),   # right bottle
        _box(3, 0, 2, 0, 150, 80, 150),   # left is half full  -> line 0.50
        _box(4, 0, 2, 120, 60, 80, 240),  # right is fuller    -> line 0.20
    ])
    rows, _ = import_masks.convert(path, source="t")
    by_crop = {r["crop"]: float(r["line"]) for r in rows}
    assert by_crop["0,0,80,300"] == pytest.approx(0.50, abs=1e-3)
    assert by_crop["120,0,80,300"] == pytest.approx(0.20, abs=1e-3)


def test_unrecognised_category_names_yield_nothing_rather_than_nonsense(tmp_path):
    path = _coco(
        tmp_path,
        [_box(1, 0, 99, 20, 0, 100, 300)],
        categories=[{"id": 99, "name": "sprocket"}],
    )
    rows, skipped = import_masks.convert(path, source="t")
    assert rows == []
    assert skipped["no container annotated"] == 1


# --- the line-labelled corpus format -----------------------------------------

def _write_corpus(tmp_path, entries):
    from PIL import Image

    images = tmp_path / "images"
    images.mkdir(exist_ok=True)
    rows = []
    for ident, fill, unreadable in entries:
        scene = render(fill, seed=hash(ident) % 1000,
                       **({"opacity": 1.0} if unreadable else {}))
        Image.fromarray(scene.rgb).save(images / f"{ident}.jpg", quality=95)
        rows.append({
            "sample_id": ident, "image": f"{ident}.jpg",
            "line": f"{1.0 - fill:.4f}", "crop": "",
            "bottle": "b", "lighting": "lamp", "source": "test",
            "unreadable": "1" if unreadable else "",
        })
    manifest = tmp_path / "lines.csv"
    lines_module.save(rows, manifest)
    return manifest


def test_round_trip_through_the_manifest(tmp_path):
    manifest = _write_corpus(tmp_path, [("s1", 0.6, False), ("s2", 0.3, False)])
    samples = lines_module.load(manifest)
    assert [s.sample_id for s in samples] == ["s1", "s2"]
    assert samples[0].line == pytest.approx(0.4, abs=1e-3)
    assert samples[0].image.ndim == 3 and samples[0].image.shape[2] == 3


def test_evaluate_scores_line_position_not_fill(tmp_path):
    """`line` is the metric for breadth: converting to fill would assume the
    crop is the water column, which a borrowed photograph never guarantees."""
    manifest = _write_corpus(tmp_path, [("s1", 0.7, False)])
    results = lines_module.evaluate(lines_module.load(manifest))
    assert len(results) == 1
    assert results[0].error <= 0.05
    assert results[0].error == abs(results[0].detected - results[0].sample.line)


def test_unreadable_samples_are_kept_out_of_the_accuracy_pool(tmp_path):
    manifest = _write_corpus(tmp_path, [("ok", 0.6, False), ("flask", 0.6, True)])
    results = lines_module.evaluate(lines_module.load(manifest))
    rejects = [r for r in results if r.sample.unreadable]
    assert len(rejects) == 1
    assert not rejects[0].readable, "an opaque body must be called unreadable"


def test_report_passes_on_good_data_and_fails_on_bad(tmp_path, capsys):
    manifest = _write_corpus(tmp_path, [(f"s{i}", 0.2 + 0.07 * i, False) for i in range(9)])
    samples = lines_module.load(manifest)
    assert lines_module.report(lines_module.evaluate(samples))

    # Corrupt every label; the report must now refuse to pass.
    for sample in samples:
        sample.line = float(np.clip(1.0 - sample.line, 0.0, 1.0))
    assert not lines_module.report(lines_module.evaluate(samples))
    capsys.readouterr()


def test_manifest_written_by_import_is_loadable_by_the_evaluator(tmp_path):
    """The two halves must actually fit: import_masks writes it, lines reads it."""
    from PIL import Image

    images = tmp_path / "images"
    images.mkdir()
    Image.fromarray(render(0.5, seed=1).rgb).save(images / "a.jpg", quality=95)

    coco = _coco(tmp_path, [
        _box(1, 0, 1, 0, 0, 160, 320),
        _box(2, 0, 2, 0, 160, 160, 160),
    ], images=[{"id": 0, "file_name": "a.jpg", "width": 160, "height": 320}])
    rows, _ = import_masks.convert(coco, source="rt")
    manifest = tmp_path / "lines.csv"
    lines_module.save(rows, manifest)

    samples = lines_module.load(manifest, images_root=images)
    assert len(samples) == 1
    assert samples[0].crop == (0, 0, 160, 320)
    assert samples[0].line == pytest.approx(0.5, abs=1e-3)


def test_fields_match_between_writer_and_reader():
    """A column added to one side and not the other loses data silently."""
    assert set(lines_module.FIELDS) == {
        "sample_id", "image", "line", "crop", "bottle", "lighting", "source", "unreadable",
    }
