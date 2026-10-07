"""Regression and sanity tests for the core pipeline.  Run:  python -m pytest"""
import importlib.util
import os

import numpy as np
import pandas as pd
import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("hemispherR_py", os.path.join(ROOT, "core", "hemispherR-py.py"))
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)


def test_otsu_splits_two_modes():
    values = np.array([50] * 1000 + [200] * 1000, dtype=np.uint8)
    assert core._otsu_threshold(values) == 50.0


def test_beer_lambert_gap_fractions_recover_lai():
    """If every ring follows exp(-0.5*LAI/cos(theta)), Le and L must equal LAI and LX must be 1."""
    rings = np.array([5, 15, 25, 35, 45, 55, 65], dtype=float)
    for lai in (0.5, 1.0, 3.0, 5.0):
        gf = np.exp(-0.5 * lai / np.cos(np.radians(rings)))
        df = pd.DataFrame({"ring": rings, **{f"GF{j}": gf for j in range(1, 9)}, "id": "synthetic"})
        out = core._calculate_canopy_metrics(df)
        assert out["Le"] == pytest.approx(lai, abs=0.01)
        assert out["L"] == pytest.approx(lai, abs=0.01)
        assert out["LX"] == pytest.approx(1.0, abs=0.01)


def test_sample_photo_regression():
    """P072.jpg with the default CONFIG settings (blue channel, zonal Otsu, FC-E8 lens)."""
    path = os.path.join(ROOT, "sample_images", "P072.jpg")
    mask = {"xc": 1496, "yc": 2408, "rc": 1414}
    img = core.import_fisheye(path, channel=3, circ_mask=mask, circular=True, gamma=0.85,
                              stretch=False, display=False, message=False)
    binary = core.binarize_fisheye(img, method="Otsu", zonal=True, manual=None, display=False, export=False)
    gap = core.gapfrac_fisheye(binary, maxVZA=90, lens="FC-E8", startVZA=0, endVZA=70,
                               nrings=7, nseg=8, display=False, message=False)
    res = core.canopy_fisheye(gap).iloc[0]
    assert res["thd"] == "106_117_108_118"
    assert res["Le"] == pytest.approx(1.04, abs=0.01)
    assert res["L"] == pytest.approx(1.21, abs=0.01)
    assert res["LX"] == pytest.approx(0.86, abs=0.01)
    assert res["DIFN"] == pytest.approx(47.7, abs=0.1)
