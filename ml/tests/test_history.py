"""
test_history.py - pytest suite for the pure history helpers.

No data files: the tests exercise the distance, point-in-polygon, crossing,
bucketing, densest-cell and location-key functions directly, so the historical
analysis logic is covered without loading the 233 MB point sample.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

import history as H


# --------------------------------------------------------------------------- #
# distance
# --------------------------------------------------------------------------- #

def test_equirect_distance_is_zero_for_identical_points():
    assert H.equirect_distance_m(39.9, 116.4, 39.9, 116.4) == pytest.approx(0.0, abs=1e-9)


def test_equirect_distance_one_degree_latitude_is_one_degree_of_arc():
    # one degree of latitude is ~111.19 km on the WGS84 sphere used here
    d = H.equirect_distance_m(39.0, 116.0, 40.0, 116.0)
    assert 110_000 < float(d) < 112_000


def test_equirect_distance_is_vectorised():
    out = H.equirect_distance_m(
        np.array([39.9, 40.0]), np.array([116.4, 116.4]),
        np.array([39.9, 40.0]), np.array([116.4, 116.5]),
    )
    assert out.shape == (2,)
    assert out[0] == pytest.approx(0.0, abs=1e-9)
    assert out[1] > 0


# --------------------------------------------------------------------------- #
# point in polygon
# --------------------------------------------------------------------------- #

SQUARE = [(0.0, 0.0), (0.0, 2.0), (2.0, 2.0), (2.0, 0.0)]  # (lat, lon)


def test_inside_polygon_mask_basic():
    lat = np.array([1.0, 3.0, -1.0, 1.0])
    lon = np.array([1.0, 1.0, 1.0, 5.0])
    mask = H.inside_polygon_mask(lat, lon, SQUARE)
    assert mask.tolist() == [True, False, False, False]


def test_inside_polygon_mask_all_inside():
    assert H.inside_polygon_mask([0.5, 1.5], [0.5, 1.5], SQUARE).all()


# --------------------------------------------------------------------------- #
# crossing detection
# --------------------------------------------------------------------------- #

def test_crossing_times_entry_and_exit():
    times = [10, 11, 12, 13, 14, 15, 16]
    inside = [False, True, True, False, False, True, True]
    entries, exits = H.crossing_times(times, inside)
    assert entries == [11, 15]
    assert exits == [13]


def test_crossing_times_starting_inside_gives_no_spurious_entry():
    times = [1, 2, 3]
    inside = [True, True, False]
    entries, exits = H.crossing_times(times, inside)
    assert entries == []
    assert exits == [3]


def test_crossing_times_no_crossing():
    entries, exits = H.crossing_times([1, 2, 3], [False, False, False])
    assert entries == [] and exits == []


# --------------------------------------------------------------------------- #
# bucketing
# --------------------------------------------------------------------------- #

def test_bucket_counts_monthly():
    times = pd.to_datetime(["2011-01-05", "2011-01-20", "2011-02-01"])
    counts = H.bucket_counts(times, "M")
    assert counts.loc["2011-01"] == 2
    assert counts.loc["2011-02"] == 1


def test_bucket_counts_empty():
    assert H.bucket_counts([], "M").empty


# --------------------------------------------------------------------------- #
# densest cell + location key
# --------------------------------------------------------------------------- #

def test_densest_cell_finds_the_cluster():
    lat = [39.90, 39.901, 39.902, 39.91, 40.10]
    lon = [116.30, 116.301, 116.3005, 116.31, 116.50]
    clat, clon, n = H.densest_cell(lat, lon, grid_deg=0.01)
    # the first three points land in the same 0.01-deg cell
    assert n == 3
    assert 39.90 <= clat <= 39.91
    assert 116.30 <= clon <= 116.31


def test_densest_cell_empty():
    clat, clon, n = H.densest_cell([], [], 0.01)
    assert n == 0 and np.isnan(clat) and np.isnan(clon)


def test_location_key_groups_nearby_points():
    keys = H.location_key([39.9000, 39.9001, 39.9100], [116.3000, 116.3001, 116.3000], 0.0015)
    assert keys[0] == keys[1]
    assert keys[0] != keys[2]
