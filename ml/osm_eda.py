"""
osm_eda.py - real OpenStreetMap EDA for the tourist-safety project area.

Why this module exists
----------------------
The GeoLife trajectories describe tourists moving through Beijing, but they say
nothing about *what is around them*. For a safety-monitoring product the
surrounding infrastructure matters (how many roads, amenities, buildings a
tourist is exposed to). OpenStreetMap is the only free, verifiable source of
that map context, so this module fetches a small, real OSM extract for the
project's home area (Tiananmen, Beijing - the geographic centre of the GeoLife
dataset) and measures it.

Honesty / reproducibility rules
-------------------------------
* The extract is fetched live from the Overpass API (the free OSM query
  service). The raw JSON is cached under ``ml/data/osm/`` and every figure and
  table is computed from that exact response - nothing is hand-typed.
* If every Overpass endpoint fails the run writes ``NOT_RUN.md`` with the exact
  error and exits non-zero. It never substitutes synthetic map data.
* All parsing / aggregation is done with the standard library + pandas; no new
  dependencies are added.
* Data is (c) OpenStreetMap contributors, licensed ODbL. The attribution is
  recorded in ``findings.md`` and stamped on every figure.

Outputs (``ml/outputs/osm/``)
----------------------------
* ``fig_01_element_types.png``      counts by element type (node/way/relation)
* ``fig_02_tag_keys.png``           top 25 tag keys
* ``fig_03_highway_classes.png``    highway=* feature classes
* ``fig_04_amenity_classes.png``    amenity=* feature classes
* ``fig_05_building_classes.png``   building=* feature classes
* ``fig_06_element_map.png``        node + way-centroid scatter
* ``table_01..table_07*.csv``       the underlying counts
* ``findings.md``                   exact bbox, query, endpoint, UTC time, counts

Run:  python ml/osm_eda.py [--refresh]
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import common as C  # noqa: E402

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #

# The project's home area: a 1.5 km x 1.5 km box centred on Tiananmen Square,
# Beijing (39.9087 N, 116.3975 E) - the geographic centre of the GeoLife
# trajectories used everywhere else in this project.
CENTER_LAT = 39.9087
CENTER_LON = 116.3975
BOX_SIDE_KM = 1.5

#: free Overpass instances, tried in this order
OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]
USER_AGENT = (
    "TouristSafetyMonitoring-ml/1.0 (academic EDA; "
    "https://github.com/tourist-safety-monitoring)"
)
HTTP_TIMEOUT_S = 120

OSM_DATA_DIR = C.DATA_DIR / "osm"
OSM_OUT_DIR = C.OUTPUTS_DIR / "osm"
RAW_JSON_PATH = OSM_DATA_DIR / "tiananmen_1p5km_overpass.json"

ATTRIBUTION = "Data (c) OpenStreetMap contributors, available under the ODbL (https://www.openstreetmap.org/copyright)"
TOP_TAG_KEYS = 25
NODE_PLOT_SAMPLE = 4000

#: tag keys for which we break the value distribution out into its own table
FEATURE_KEYS = ("highway", "amenity", "building")


# --------------------------------------------------------------------------- #
# Bounding box + query construction (pure)
# --------------------------------------------------------------------------- #

def bbox_from_center(lat: float, lon: float, side_km: float) -> dict[str, float]:
    """
    Build an axis-aligned lat/lon box of ``side_km`` x ``side_km`` around a centre.

    Uses the local flat-earth approximation for the half extents, which is exact
    enough over ~1.5 km (well under 0.01 % error) and keeps the box honest and
    reproducible.
    """
    half_km = side_km / 2.0
    dlat = half_km / 111.32
    dlon = half_km / (111.32 * math.cos(math.radians(lat)))
    return {
        "south": round(lat - dlat, 7),
        "west": round(lon - dlon, 7),
        "north": round(lat + dlat, 7),
        "east": round(lon + dlon, 7),
    }


def bbox_area_km2(bbox: dict[str, float]) -> float:
    """Approximate area of a small lat/lon box in km^2."""
    mean_lat = (bbox["south"] + bbox["north"]) / 2.0
    height = (bbox["north"] - bbox["south"]) * 111.32
    width = (bbox["east"] - bbox["west"]) * 111.32 * math.cos(math.radians(mean_lat))
    return abs(height * width)


def build_query(bbox: dict[str, float], timeout_s: int = HTTP_TIMEOUT_S) -> str:
    """
    The exact Overpass QL text used for the extract.

    ``out center`` returns nodes with ``lat``/``lon`` and adds a ``center`` point
    for every way, so way footprints can be placed on the map without a second
    query. No relations are requested (they would mostly duplicate way tags and
    blow up the response).
    """
    b = f"{bbox['south']},{bbox['west']},{bbox['north']},{bbox['east']}"
    return (
        f"[out:json][timeout:{timeout_s}];\n"
        "(\n"
        f"  node({b});\n"
        f"  way({b});\n"
        ");\n"
        "out center;"
    )


# --------------------------------------------------------------------------- #
# Network (only used by main; tests never hit the network)
# --------------------------------------------------------------------------- #

class OverpassError(RuntimeError):
    """Raised when every Overpass endpoint fails."""


def fetch_overpass(
    query: str,
    endpoints: "list[str] | None" = None,
    *,
    timeout_s: int = HTTP_TIMEOUT_S,
    user_agent: str = USER_AGENT,
) -> tuple[dict, str, float]:
    """
    POST ``query`` to the first endpoint that answers -> ``(payload, endpoint, seconds)``.

    Raises :class:`OverpassError` carrying every endpoint's error if all fail, so
    the failure is loud and complete rather than a vague timeout.
    """
    endpoints = endpoints or OVERPASS_ENDPOINTS
    errors: list[str] = []
    for endpoint in endpoints:
        request = urllib.request.Request(
            endpoint,
            data=query.encode("utf-8"),
            headers={"User-Agent": user_agent, "Content-Type": "text/plain; charset=utf-8"},
            method="POST",
        )
        started = time.time()
        try:
            with urllib.request.urlopen(request, timeout=timeout_s) as response:
                body = response.read().decode("utf-8")
            payload = json.loads(body)
            return payload, endpoint, time.time() - started
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError,
                json.JSONDecodeError, ValueError) as exc:
            errors.append(f"  - {endpoint}: {type(exc).__name__}: {exc}")
    raise OverpassError(
        "all Overpass endpoints failed:\n" + "\n".join(errors)
    )


# --------------------------------------------------------------------------- #
# Pure parsing / aggregation (unit-tested on a fixture, no network)
# --------------------------------------------------------------------------- #

def parse_elements(payload: dict) -> pd.DataFrame:
    """
    Normalise an Overpass response into one row per element.

    Columns: ``type, id, lat, lon, n_tags, keys`` where ``keys`` is the sorted
    list of the element's tag keys (empty list when it has no tags). For ways
    the Overpass ``center`` point is used as (lat, lon); a way without a centre
    (impossible with ``out center``, but handled defensively) gets NaN.
    """
    rows = []
    for element in payload.get("elements", []):
        if element.get("type") == "node":
            lat, lon = element.get("lat"), element.get("lon")
        else:
            center = element.get("center") or {}
            lat, lon = center.get("lat"), center.get("lon")
        tags = element.get("tags") or {}
        rows.append(
            {
                "type": element.get("type"),
                "id": element.get("id"),
                "lat": lat,
                "lon": lon,
                "n_tags": len(tags),
                "keys": sorted(tags.keys()),
            }
        )
    return pd.DataFrame(rows, columns=["type", "id", "lat", "lon", "n_tags", "keys"])


def counts_by_type(elements: pd.DataFrame) -> pd.DataFrame:
    """Count elements per type (node / way / relation), deterministic order."""
    counts = elements["type"].value_counts()
    order = [t for t in ("node", "way", "relation") if t in counts.index]
    counts = counts.reindex(order + [t for t in counts.index if t not in order])
    return counts.rename_axis("element_type").reset_index(name="n_elements")


def tag_key_counts(elements: pd.DataFrame, top_n: int = TOP_TAG_KEYS) -> pd.DataFrame:
    """
    Count how many *elements* carry each tag key, top ``top_n`` first.

    Counting elements (not key occurrences) is the meaningful number for map
    context: "how many objects in this box are tagged as a road/amenity/...".
    """
    counts: dict[str, int] = {}
    for keys in elements["keys"]:
        for key in keys:
            counts[key] = counts.get(key, 0) + 1
    series = pd.Series(counts, dtype="int64")
    if series.empty:
        return pd.DataFrame(columns=["tag_key", "n_elements"])
    series = series.sort_values(ascending=False, kind="mergesort").head(top_n)
    return series.rename_axis("tag_key").reset_index(name="n_elements")


def feature_class_counts(
    payload: dict, key: str, top_n: int | None = None
) -> pd.DataFrame:
    """Value distribution of a single tag key (e.g. ``highway``) across elements."""
    counts: dict[str, int] = {}
    for element in payload.get("elements", []):
        value = (element.get("tags") or {}).get(key)
        if value is not None:
            counts[value] = counts.get(value, 0) + 1
    series = pd.Series(counts, dtype="int64")
    if series.empty:
        return pd.DataFrame(columns=["feature_class", "n_elements"])
    series = series.sort_values(ascending=False, kind="mergesort")
    if top_n is not None:
        series = series.head(top_n)
    return (
        series.rename_axis("feature_class")
        .reset_index(name="n_elements")
        .rename(columns={"feature_class": "feature_class"})
    )


def node_density(elements: pd.DataFrame, area_km2: float) -> dict[str, float]:
    """Elements (and nodes) per km^2 inside the query box."""
    n = int(len(elements))
    n_nodes = int((elements["type"] == "node").sum())
    n_ways = int((elements["type"] == "way").sum())
    return {
        "area_km2": float(area_km2),
        "n_elements": n,
        "n_nodes": n_nodes,
        "n_ways": n_ways,
        "elements_per_km2": (n / area_km2) if area_km2 else float("nan"),
        "nodes_per_km2": (n_nodes / area_km2) if area_km2 else float("nan"),
    }


# --------------------------------------------------------------------------- #
# Figures
# --------------------------------------------------------------------------- #

def _savefig(fig, path: Path) -> None:
    fig.tight_layout(rect=(0, 0.02, 1, 1))
    fig.text(
        0.5,
        0.002,
        ATTRIBUTION,
        ha="center",
        fontsize=6,
        color="dimgray",
    )
    fig.savefig(path, dpi=120)
    plt.close(fig)
    print(f"  wrote {path.name}")


def _bar_counts(df: pd.DataFrame, label_col: str, value_col: str, title: str,
                path: Path, *, horizontal: bool, color: str) -> None:
    fig, ax = plt.subplots(figsize=(8, 5.2 if horizontal else 4.2))
    if horizontal:
        ax.barh(df[label_col][::-1], df[value_col][::-1], color=color)
    else:
        ax.bar(df[label_col], df[value_col], color=color)
        plt.setp(ax.get_xticklabels(), rotation=30, ha="right")
    ax.set_title(title)
    ax.set_xlabel("elements")
    if horizontal:
        ax.set_xlabel("elements")
    _savefig(fig, path)


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #

def _write_not_run(reason: str) -> None:
    OSM_OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OSM_OUT_DIR / "NOT_RUN.md").write_text(
        "# OSM EDA - NOT RUN\n\n"
        f"{reason}\n\n"
        "No figures or tables were produced. No substitute or synthetic map data "
        "was generated: the OSM EDA requires a live Overpass response.\n",
        encoding="utf-8",
    )


def run(args: argparse.Namespace) -> int:
    C.ensure_dirs()
    OSM_DATA_DIR.mkdir(parents=True, exist_ok=True)
    OSM_OUT_DIR.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    bbox = bbox_from_center(CENTER_LAT, CENTER_LON, BOX_SIDE_KM)
    area_km2 = bbox_area_km2(bbox)
    query = build_query(bbox)
    print(f"[osm] bbox {bbox}  area ~{area_km2:.3f} km^2")

    use_cache = RAW_JSON_PATH.is_file() and not args.refresh
    if use_cache:
        payload = C.read_json(RAW_JSON_PATH)
        endpoint = payload.get("_meta", {}).get("endpoint", "cache")
        fetch_time = payload.get("_meta", {}).get("fetch_utc", "unknown")
        seconds = payload.get("_meta", {}).get("seconds", float("nan"))
        print(f"[osm] using cached response {RAW_JSON_PATH}")
    else:
        try:
            payload, endpoint, seconds = fetch_overpass(query)
        except OverpassError as exc:
            _write_not_run(f"All Overpass endpoints failed.\n\n```\n{exc}\n```")
            print(f"[osm] NOT RUN: {exc}", file=sys.stderr)
            return 3
        fetch_time = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        payload["_meta"] = {
            "endpoint": endpoint,
            "fetch_utc": fetch_time,
            "seconds": seconds,
            "query": query,
            "bbox": bbox,
        }
        C.write_json(RAW_JSON_PATH, payload)
        print(f"[osm] fetched {endpoint} in {seconds:.1f}s")

    elements = parse_elements(payload)
    if elements.empty:
        _write_not_run("Overpass returned zero elements; refusing to draw an empty map.")
        return 3

    by_type = counts_by_type(elements)
    by_type.to_csv(OSM_OUT_DIR / "table_01_element_counts.csv", index=False)
    keys = tag_key_counts(elements, TOP_TAG_KEYS)
    keys.to_csv(OSM_OUT_DIR / "table_02_tag_keys.csv", index=False)

    class_tables: dict[str, pd.DataFrame] = {}
    for index, key in enumerate(FEATURE_KEYS, start=3):
        table = feature_class_counts(payload, key)
        class_tables[key] = table
        table.to_csv(OSM_OUT_DIR / f"table_{index:02d}_{key}_classes.csv", index=False)

    density = node_density(elements, area_km2)
    pd.DataFrame([density]).to_csv(OSM_OUT_DIR / "table_07_density.csv", index=False)

    # centroid sample table (cap the file size, keep it human-auditable)
    pts = elements.dropna(subset=["lat", "lon"]).copy()
    pts.head(2000).to_csv(OSM_OUT_DIR / "table_06_element_centroids.csv", index=False)

    # ---- figures ---------------------------------------------------------- #
    _bar_counts(by_type, "element_type", "n_elements",
                "OSM elements by type (Tiananmen 1.5 km box)",
                OSM_OUT_DIR / "fig_01_element_types.png", horizontal=False, color="#1a73e8")
    _bar_counts(keys, "tag_key", "n_elements",
                f"Top {len(keys)} OSM tag keys (elements carrying the key)",
                OSM_OUT_DIR / "fig_02_tag_keys.png", horizontal=True, color="#1b7f4c")
    _bar_counts(class_tables["highway"], "feature_class", "n_elements",
                "highway=* feature classes",
                OSM_OUT_DIR / "fig_03_highway_classes.png", horizontal=True, color="#c0392b")
    _bar_counts(class_tables["amenity"], "feature_class", "n_elements",
                "amenity=* feature classes",
                OSM_OUT_DIR / "fig_04_amenity_classes.png", horizontal=True, color="#8e44ad")
    _bar_counts(class_tables["building"], "feature_class", "n_elements",
                "building=* feature classes",
                OSM_OUT_DIR / "fig_05_building_classes.png", horizontal=True, color="#e67e22")

    fig, ax = plt.subplots(figsize=(7.5, 7.5))
    nodes = pts[pts["type"] == "node"]
    ways = pts[pts["type"] == "way"]
    node_plot = nodes.sample(min(NODE_PLOT_SAMPLE, len(nodes)), random_state=C.SEED) \
        if len(nodes) > NODE_PLOT_SAMPLE else nodes
    ax.scatter(node_plot["lon"], node_plot["lat"], s=3, alpha=0.35, linewidths=0,
               label=f"nodes (n={len(nodes):,})", color="#1a73e8")
    ax.scatter(ways["lon"], ways["lat"], s=14, marker="^", alpha=0.7, linewidths=0,
               label=f"way centroids (n={len(ways):,})", color="#c0392b")
    ax.set_xlabel("longitude")
    ax.set_ylabel("latitude")
    ax.set_title("OSM elements in the Tiananmen 1.5 km box")
    ax.legend(loc="best", fontsize=8)
    _savefig(fig, OSM_OUT_DIR / "fig_06_element_map.png")

    # ---- findings --------------------------------------------------------- #
    n = {r.element_type: int(r.n_elements) for r in by_type.itertuples()}
    top_keys = keys.head(10)
    findings: list[str] = []
    findings.append("# OSM EDA findings (Tiananmen, Beijing)\n")
    findings.append(
        f"- **Attribution:** {ATTRIBUTION}\n"
        f"- **Endpoint used:** `{endpoint}`\n"
        f"- **Fetch time (UTC):** `{fetch_time}`\n"
        f"- **Bounding box (S,W,N,E):** "
        f"`{bbox['south']}, {bbox['west']}, {bbox['north']}, {bbox['east']}` "
        f"(1.5 km x 1.5 km centred on {CENTER_LAT} N, {CENTER_LON} E)\n"
        f"- **Box area:** {area_km2:.4f} km^2\n"
        f"- **Raw cache:** `{RAW_JSON_PATH.relative_to(C.ML_ROOT)}`\n"
    )
    findings.append("## 1. Exact Overpass query\n\n```\n" + query + "\n```\n")
    findings.append("## 2. Element counts\n")
    findings.append(
        f"- nodes **{n.get('node', 0):,}**, ways **{n.get('way', 0):,}**, "
        f"relations **{n.get('relation', 0):,}** (total **{len(elements):,}** elements).\n"
        f"- Node density: **{density['nodes_per_km2']:.1f} nodes/km^2**; "
        f"all-element density: **{density['elements_per_km2']:.1f} elements/km^2**.\n"
        f"- Table: `table_01_element_counts.csv`, figure: `fig_01_element_types.png`.\n"
    )
    findings.append("## 3. Top tag keys\n")
    findings.append(
        "| tag key | elements |\n|---|---|\n"
        + "\n".join(f"| {r.tag_key} | {r.n_elements:,} |" for r in top_keys.itertuples())
        + "\n"
    )
    for key in FEATURE_KEYS:
        table = class_tables[key]
        if table.empty:
            findings.append(f"## {key}=* class counts\n\n- No `{key}` elements found.\n")
            continue
        head = table.head(8)
        findings.append(f"## {key}=* class counts ({len(table)} distinct values)\n")
        findings.append(
            "| class | elements |\n|---|---|\n"
            + "\n".join(f"| {r.feature_class} | {r.n_elements:,} |" for r in head.itertuples())
            + "\n"
        )
    # headline map-context numbers the app's risk heuristic leans on
    n_highway = int(class_tables["highway"]["n_elements"].sum()) if not class_tables["highway"].empty else 0
    n_amenity = int(class_tables["amenity"]["n_elements"].sum()) if not class_tables["amenity"].empty else 0
    n_building = int(class_tables["building"]["n_elements"].sum()) if not class_tables["building"].empty else 0
    findings.append("## 5. Map-context summary\n")
    findings.append(
        f"- Elements carrying **highway**=* : **{n_highway:,}**;\n"
        f"- Elements carrying **amenity**=* : **{n_amenity:,}**;\n"
        f"- Elements carrying **building**=* : **{n_building:,}**.\n"
    )
    findings.append("## Reproducibility\n")
    findings.append(
        f"- total OSM EDA wall time: {time.time() - t0:.1f}s (fetch {seconds:.1f}s); "
        f"seed {C.SEED}.\n"
        f"- re-run with `python ml/osm_eda.py` (uses the cache) or "
        f"`python ml/osm_eda.py --refresh` (re-fetches).\n"
    )
    (OSM_OUT_DIR / "findings.md").write_text("\n".join(findings), encoding="utf-8")
    print(f"[osm] wrote {OSM_OUT_DIR / 'findings.md'}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Real OpenStreetMap EDA via the Overpass API.")
    p.add_argument("--refresh", action="store_true",
                   help="ignore the cache and re-fetch from Overpass")
    return p


def main(argv: "list[str] | None" = None) -> int:
    return run(build_parser().parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
