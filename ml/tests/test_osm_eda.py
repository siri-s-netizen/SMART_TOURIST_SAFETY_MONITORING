"""
test_osm_eda.py - pytest suite for the pure OSM parsing / aggregation helpers.

No network is touched: every test runs on a small, hand-written Overpass-style
JSON fixture that mimics the real response shape (nodes with lat/lon, ways with
a ``center``, tags on both). The live fetch is exercised in ``osm_eda.py`` itself,
not here, so the suite stays fast and offline.
"""

from __future__ import annotations

import json

import pytest

import osm_eda as O

# --------------------------------------------------------------------------- #
# fixture: a tiny, realistic Overpass payload
# --------------------------------------------------------------------------- #

FIXTURE = {
    "version": 0.6,
    "generator": "Overpass API",
    "elements": [
        {"type": "node", "id": 1, "lat": 39.91, "lon": 116.39,
         "tags": {"highway": "crossing", "crossing": "marked"}},
        {"type": "node", "id": 2, "lat": 39.9105, "lon": 116.391,
         "tags": {"amenity": "cafe", "name": "Cafe A"}},
        {"type": "node", "id": 3, "lat": 39.911, "lon": 116.392},  # no tags
        {"type": "way", "id": 10, "center": {"lat": 39.912, "lon": 116.393},
         "tags": {"building": "yes", "height": "12"}},
        {"type": "way", "id": 11, "center": {"lat": 39.9125, "lon": 116.394},
         "tags": {"highway": "footway"}},
        {"type": "relation", "id": 20, "center": {"lat": 39.913, "lon": 116.395},
         "tags": {"type": "multipolygon", "building": "yes"}},
    ],
}


@pytest.fixture()
def elements():
    return O.parse_elements(FIXTURE)


# --------------------------------------------------------------------------- #
# parsing
# --------------------------------------------------------------------------- #

def test_parse_elements_row_count_and_geometry(elements):
    assert len(elements) == 6
    # a node takes lat/lon directly
    node = elements[elements["id"] == 1].iloc[0]
    assert node["type"] == "node"
    assert node["lat"] == 39.91 and node["lon"] == 116.39
    # a way uses the Overpass-supplied centre
    way = elements[elements["id"] == 10].iloc[0]
    assert way["type"] == "way"
    assert way["lat"] == 39.912 and way["lon"] == 116.393


def test_parse_elements_records_tag_keys(elements):
    assert elements[elements["id"] == 1].iloc[0]["keys"] == ["crossing", "highway"]
    # untagged node -> empty key list, zero tags
    untagged = elements[elements["id"] == 3].iloc[0]
    assert untagged["keys"] == [] and untagged["n_tags"] == 0


def test_parse_elements_handles_empty_payload():
    assert O.parse_elements({"elements": []}).empty


# --------------------------------------------------------------------------- #
# aggregation
# --------------------------------------------------------------------------- #

def test_counts_by_type(elements):
    counts = dict(zip(O.counts_by_type(elements)["element_type"],
                      O.counts_by_type(elements)["n_elements"]))
    assert counts == {"node": 3, "way": 2, "relation": 1}


def test_tag_key_counts_counts_elements_not_occurrences(elements):
    keys = O.tag_key_counts(elements, top_n=10)
    lookup = dict(zip(keys["tag_key"], keys["n_elements"]))
    # highway appears on node 1 and way 11 -> 2 elements
    assert lookup["highway"] == 2
    # building appears on way 10 and relation 20 -> 2 elements
    assert lookup["building"] == 2
    assert lookup["name"] == 1
    # sorted descending
    assert list(keys["n_elements"]) == sorted(keys["n_elements"], reverse=True)


def test_tag_key_counts_respects_top_n(elements):
    assert len(O.tag_key_counts(elements, top_n=2)) == 2


def test_feature_class_counts(elements):
    highway = dict(zip(*[O.feature_class_counts(FIXTURE, "highway")[c]
                         for c in ("feature_class", "n_elements")]))
    assert highway == {"footway": 1, "crossing": 1}
    amenity = O.feature_class_counts(FIXTURE, "amenity")
    assert amenity.iloc[0]["feature_class"] == "cafe"
    # a key nobody uses gives an empty, well-formed table
    assert O.feature_class_counts(FIXTURE, "railway").empty


def test_node_density(elements):
    d = O.node_density(elements, area_km2=2.0)
    assert d["n_elements"] == 6
    assert d["n_nodes"] == 3
    assert d["n_ways"] == 2
    assert d["nodes_per_km2"] == 1.5
    assert d["elements_per_km2"] == 3.0


# --------------------------------------------------------------------------- #
# bbox / query construction
# --------------------------------------------------------------------------- #

def test_bbox_from_center_is_square_and_centred():
    bbox = O.bbox_from_center(39.9087, 116.3975, 1.5)
    assert bbox["south"] < 39.9087 < bbox["north"]
    assert bbox["west"] < 116.3975 < bbox["east"]
    assert O.bbox_area_km2(bbox) == pytest.approx(2.25, abs=0.01)


def test_build_query_is_well_formed():
    bbox = O.bbox_from_center(39.9087, 116.3975, 1.5)
    query = O.build_query(bbox)
    assert "out center;" in query
    assert "node(" in query and "way(" in query
    assert f"{bbox['south']},{bbox['west']}" in query


# --------------------------------------------------------------------------- #
# network failure is loud, not silent
# --------------------------------------------------------------------------- #

def test_fetch_overpass_raises_when_all_endpoints_fail():
    # a loopback port that nothing listens on -> connection refused, no internet needed
    with pytest.raises(O.OverpassError):
        O.fetch_overpass("out;", ["http://127.0.0.1:9/interpreter"], timeout_s=2)


def test_json_roundtrip_of_fixture_is_stable():
    """The aggregation must depend only on the parsed JSON, not on a live call."""
    payload = json.loads(json.dumps(FIXTURE))
    assert len(O.parse_elements(payload)) == 6
