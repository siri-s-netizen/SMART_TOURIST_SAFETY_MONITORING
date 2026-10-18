"""
test_transport_mode.py - pytest suite for the pure movement-feature helpers.

No data files and no model training: the tests check the heading-change fold and
the per-segment feature computation on small, hand-built coordinate sequences.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

import common as C
import transport_mode as T


# --------------------------------------------------------------------------- #
# heading change
# --------------------------------------------------------------------------- #

def test_heading_change_is_wrapped_to_180():
    assert T.heading_change_deg([0.0, 10.0]).tolist() == [10.0]
    # 350 -> 10 is a 20 degree change, not 340
    assert T.heading_change_deg([350.0, 10.0]).tolist() == [20.0]
    assert T.heading_change_deg([0.0, 180.0]).tolist() == [180.0]


def test_heading_change_needs_two_points():
    assert T.heading_change_deg([42.0]).size == 0


# --------------------------------------------------------------------------- #
# segment features
# --------------------------------------------------------------------------- #

def _times(n):
    return pd.date_range("2011-06-01 09:00:00", periods=n, freq="10s")


def test_straight_segment_has_high_straightness():
    # a straight east-west line: net displacement ~= path length
    lat = np.full(6, 39.90)
    lon = 116.30 + np.arange(6) * 0.001  # ~85 m per step at this latitude
    feats = T.segment_features(lat, lon, _times(6))
    assert feats["straightness"] == pytest.approx(1.0, abs=0.01)
    assert feats["distance_m"] > 0
    assert feats["duration_s"] == pytest.approx(50.0)  # 5 steps x 10 s
    assert feats["mean_speed_mps"] > 0
    assert feats["n_points"] == 6


def test_out_and_back_segment_has_low_straightness():
    lat = np.full(7, 39.90)
    lon = 116.30 + np.array([0, 1, 2, 3, 2, 1, 0]) * 0.001  # returns to start
    feats = T.segment_features(lat, lon, _times(7))
    assert feats["straightness"] == pytest.approx(0.0, abs=0.01)
    assert feats["distance_m"] > 0


def test_segment_features_needs_two_points():
    assert T.segment_features([39.9], [116.3], _times(1)) == {}


def test_segment_features_is_time_order_invariant():
    lat = np.full(4, 39.90)
    lon = 116.30 + np.arange(4) * 0.001
    times = _times(4)
    forward = T.segment_features(lat, lon, times)
    backward = T.segment_features(lat[::-1], lon[::-1], times[::-1])
    assert forward["distance_m"] == pytest.approx(backward["distance_m"], rel=1e-9)
    assert forward["straightness"] == pytest.approx(backward["straightness"], rel=1e-9)


def test_segment_features_time_of_day_encoding_is_bounded():
    lat = np.full(3, 39.90)
    lon = 116.30 + np.arange(3) * 0.001
    feats = T.segment_features(lat, lon, _times(3))
    assert -1.0 <= feats["tod_sin"] <= 1.0
    assert -1.0 <= feats["tod_cos"] <= 1.0


def test_feature_columns_match_the_jsons_contract():
    """The pure helpers produce every column the model contract expects."""
    lat = np.full(4, 39.90)
    lon = 116.30 + np.arange(4) * 0.001
    feats = T.segment_features(lat, lon, _times(4))
    for column in T.FEATURE_COLUMNS:
        assert column in feats, f"{column} missing from segment features"
