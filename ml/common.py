"""
common.py - shared utilities for the Tourist Safety Monitoring ML module.

This module is deliberately dependency-light (pandas / numpy only) and holds
everything that must be *identical* between preprocessing, training, evaluation
and inference so that the three stages cannot drift apart:

  * path helpers,
  * `.plt` parsing (GeoLife format) and schema validation,
  * timestamp parsing,
  * distance / bearing helpers,
  * the deterministic cleaning + feature-engineering pipeline.

IMPORTANT (honesty rules baked into the code)
---------------------------------------------
* Nothing here fabricates statistics. Every helper either reads real files or
  raises.
* Real GeoLife data and the SYNTHETIC plumbing fixture are strictly separated.
  A data source is resolved once and the caller is told, loudly, whether it is
  real (``is_synthetic == False``) or the synthetic fixture
  (``is_synthetic == True``). Scripts must print :func:`synthetic_banner` when
  ``is_synthetic`` is true and must never report synthetic numbers as dataset
  results.

GeoLife `.plt` format (VERIFIED against the real downloaded archive)
--------------------------------------------------------------------
A `.plt` file is ASCII, CRLF line endings, and starts with **6 header lines**::

    Geolife trajectory
    WGS 84
    Altitude is in Feet
    Reserved 3
    0,2,255,My Track,0,0,2,8421376
    0

Every following data line has **7 comma separated tokens** (the documented
"6 columns" because the date itself contains a comma)::

    latitude,longitude,0,altitude,days_since_1899-12-30,YYYY-MM-DD,HH:MM:SS

Example (real, user 000)::

    39.984702,116.318417,0,492,39744.1201851852,2008-10-23,02:53:04

The header count and token count above were verified on real files (60 sampled
files, 85,307 data lines, 0 malformed) - see ``ml/data/README.md``.
"""

from __future__ import annotations

import io
import json
import math
import os
import random
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable, Iterator, Optional, Sequence

import numpy as np
import pandas as pd

# --------------------------------------------------------------------------- #
# Global constants
# --------------------------------------------------------------------------- #

# A single fixed seed is used everywhere (splitting, sampling, model fitting,
# synthetic-injection in evaluation) so the whole pipeline is reproducible.
SEED = 42

# --- path helpers ---------------------------------------------------------- #
ML_ROOT: Path = Path(__file__).resolve().parent
DATA_DIR: Path = ML_ROOT / "data"
RAW_DIR: Path = DATA_DIR / "raw"
GEOLIFE_UNZIP_DIR: Path = DATA_DIR / "geolife"
# <data_dir>/geolife/Geolife Trajectories 1.3/Data/<user>/Trajectory/<id>.plt
GEOLIFE_DATA_DIR: Path = GEOLIFE_UNZIP_DIR / "Geolife Trajectories 1.3" / "Data"
SYNTHETIC_ROOT: Path = DATA_DIR / "synthetic_sample"
OUTPUTS_DIR: Path = ML_ROOT / "outputs"
CLEAN_DIR: Path = OUTPUTS_DIR / "clean"
EDA_DIR: Path = OUTPUTS_DIR / "eda"
EVAL_DIR: Path = OUTPUTS_DIR / "eval"
MODELS_DIR: Path = ML_ROOT / "models"

PLT_HEADER_LINES = 6
PLT_COLUMNS = ("lat", "lon", "zero", "altitude", "date_days", "date_str", "time_str")
GEOLIFE_EXCEL_EPOCH = datetime(1899, 12, 30)

# --- cleaning / segmentation thresholds (documented, single source of truth) #
# GPS receivers occasionally emit impossible positions. A sustained ground
# speed above this is treated as a GPS jump / teleport artefact, not a person.
JUMP_SPEED_MPS = 60.0          # ~216 km/h
# GeoLife "dense" trajectories sample every 1-5 s; a gap larger than this is a
# recording break, so the trajectory is split into segments there.
SEGMENT_GAP_S = 120.0
# Plausible coordinate envelope (rejects null-island / sign errors / defaults).
LAT_RANGE = (-90.0, 90.0)
LON_RANGE = (-180.0, 180.0)
# Altitude sanity band in the units the file declares (feet). Values outside are
# flagged but never silently dropped, because GeoLife altitude is known-noisy.
ALT_RANGE_FT = (-400.0, 60000.0)
MAX_SPEED_MPS_FOR_STATS = JUMP_SPEED_MPS

# Feature columns produced by :func:`make_features` (single source of truth used
# by train.py, evaluate.py and predict.py).
FEATURE_COLUMNS: tuple[str, ...] = (
    "speed_mps",
    "log_dt_s",
    "step_m",
    "accel_mps2",
    "turn_deg",
    "speed_mean_w5",
    "speed_std_w5",
    "step_mean_w5",
    "turn_mean_w5",
    "straightness_w5",
    "altitude",
    "alt_change",
    "tod_sin",
    "tod_cos",
)
ROLL_WINDOW = 5


class SchemaError(ValueError):
    """Raised when data does not match the expected `.plt` / points schema."""


class DataSourceError(RuntimeError):
    """Raised when no usable data source can be resolved."""


# --------------------------------------------------------------------------- #
# Small IO helpers
# --------------------------------------------------------------------------- #

def ensure_dirs() -> None:
    """Create every output directory used by the pipeline (idempotent)."""
    for d in (CLEAN_DIR, EDA_DIR, EVAL_DIR, MODELS_DIR, RAW_DIR, SYNTHETIC_ROOT):
        d.mkdir(parents=True, exist_ok=True)


def write_json(path: "str | Path", obj: object) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        json.dump(obj, fh, indent=2, default=str)


def read_json(path: "str | Path") -> object:
    with Path(path).open("r", encoding="utf-8") as fh:
        return json.load(fh)


def synthetic_banner(source: "DataSource") -> str:
    """A loud, unmissable banner. Empty string when the data is real GeoLife."""
    if not source.is_synthetic:
        return ""
    bar = "=" * 78
    return (
        f"{bar}\n"
        "!!  SYNTHETIC DATA  !!  This run uses the tiny synthetic .plt fixture,\n"
        "!!  NOT the Microsoft GeoLife GPS Trajectories dataset.  Every number\n"
        "!!  and figure produced from it is a PLUMBING TEST ONLY and MUST NOT be\n"
        "!!  reported as a dataset/EDA/model result.\n"
        f"!!  fixture: {source.root}\n"
        f"{bar}"
    )


def print_banner(text: str) -> None:
    if text:
        print(text)


# --------------------------------------------------------------------------- #
# Data source resolution
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class DataSource:
    root: Path            # directory containing <user>/Trajectory/*.plt
    is_synthetic: bool
    label: str            # human readable description
    source_kind: str      # "geolife" | "synthetic" | "custom"

    def banner(self) -> str:
        base = synthetic_banner(self)
        if not base:
            return (
                f"[data] REAL GeoLife trajectories: {self.root}\n"
                f"[data] source: {self.label}"
            )
        return base


def resolve_data_source(
    data_root: "str | Path | None" = None,
    *,
    force_synthetic: bool = False,
    synthetic_root: "str | Path | None" = None,
) -> DataSource:
    """
    Decide which trajectory tree to read.

    Priority:
      1. explicit ``data_root`` (never synthetic unless it is the fixture),
      2. real GeoLife unzip dir if present,
      3. the SYNTHETIC fixture (with a banner),
      4. otherwise raise :class:`DataSourceError`.
    """
    syn_root = Path(synthetic_root) if synthetic_root else SYNTHETIC_ROOT

    if force_synthetic:
        if not syn_root.is_dir():
            raise DataSourceError(f"synthetic root not found: {syn_root}")
        return DataSource(syn_root, True, "SYNTHETIC .plt fixture (not GeoLife)", "synthetic")

    if data_root is not None:
        root = Path(data_root)
        if not root.is_dir():
            raise DataSourceError(f"data_root not found: {root}")
        is_syn = "synthetic" in str(root).lower()
        return DataSource(
            root,
            is_syn,
            ("SYNTHETIC .plt fixture (not GeoLife)" if is_syn else "custom .plt tree"),
            "synthetic" if is_syn else "custom",
        )

    if GEOLIFE_DATA_DIR.is_dir() and any(GEOLIFE_DATA_DIR.iterdir()):
        return DataSource(
            GEOLIFE_DATA_DIR,
            False,
            "Microsoft GeoLife GPS Trajectories 1.3 (real)",
            "geolife",
        )

    if syn_root.is_dir() and any(syn_root.iterdir()):
        return DataSource(syn_root, True, "SYNTHETIC .plt fixture (not GeoLife)", "synthetic")

    raise DataSourceError(
        "No data source found. Expected real GeoLife at "
        f"{GEOLIFE_DATA_DIR} or the synthetic fixture at {syn_root}. "
        "See ml/data/README.md for manual download steps."
    )


# --------------------------------------------------------------------------- #
# Trajectory discovery
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class TrajectoryRef:
    user_id: str
    trajectory_id: str
    path: Path


def discover_trajectories(root: "str | Path") -> list[TrajectoryRef]:
    """
    Find every ``<root>/<user>/Trajectory/<id>.plt`` file, sorted deterministically
    by (user_id, trajectory_id).
    """
    root = Path(root)
    if not root.is_dir():
        raise DataSourceError(f"trajectory root not found: {root}")
    refs: list[TrajectoryRef] = []
    for user_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        traj_dir = user_dir / "Trajectory"
        if not traj_dir.is_dir():
            continue
        for plt in sorted(traj_dir.glob("*.plt")):
            refs.append(TrajectoryRef(user_dir.name, plt.stem, plt))
    refs.sort(key=lambda r: (r.user_id, r.trajectory_id))
    if not refs:
        raise DataSourceError(f"no .plt files under {root}")
    return refs


def select_trajectories(
    refs: Sequence[TrajectoryRef],
    n: "int | None" = None,
    seed: int = SEED,
) -> list[TrajectoryRef]:
    """Deterministically pick ``n`` trajectories (fixed seed) or all of them."""
    refs = sorted(refs, key=lambda r: (r.user_id, r.trajectory_id))
    if n is None or n >= len(refs):
        return list(refs)
    rng = random.Random(seed)
    return sorted(rng.sample(refs, n), key=lambda r: (r.user_id, r.trajectory_id))


# --------------------------------------------------------------------------- #
# .plt parsing  (VERIFIED format)
# --------------------------------------------------------------------------- #

def _looks_like_data_line(tokens: Sequence[str]) -> bool:
    if len(tokens) != len(PLT_COLUMNS):
        return False
    try:
        float(tokens[0])
        float(tokens[1])
        float(tokens[4])
    except (ValueError, TypeError):
        return False
    return True


# Column dtype specification for the strict (fast) CSV parse.
_PLT_DTYPES = {
    "lat": float,
    "lon": float,
    "zero": float,
    "altitude": float,
    "date_days": float,
    "date_str": str,
    "time_str": str,
}
_BULK_DTYPES = {"user_id": str, "trajectory_id": str, **_PLT_DTYPES}
_DATA_VALUE_COLS = ["lat", "lon", "zero", "altitude", "date_days", "date_str", "time_str"]
_BULK_COLUMNS = ["user_id", "trajectory_id", *PLT_COLUMNS]


def add_traj_uid(df: pd.DataFrame) -> pd.DataFrame:
    """
    Ensure a globally unique per-trajectory key ``traj_uid = user_id|trajectory_id``.

    GeoLife trajectory file names are timestamps that **collide across users**
    (verified: 847 of 17,784 distinct trajectory ids are shared by >1 user), so
    every grouping / split must key on the (user, trajectory) pair, never on the
    trajectory id alone.
    """
    if "traj_uid" in df.columns:
        return df
    out = df.copy()
    out["traj_uid"] = out["user_id"].astype(str) + "|" + out["trajectory_id"].astype(str)
    return out


def _split_header(text: str) -> tuple[int, list[str]]:
    """
    Split a `.plt` body into (header_line_count, data_lines).

    The header block always sits at the top of a GeoLife file, so only the
    leading lines are inspected.
    """
    lines = text.splitlines()
    start = 0
    n = len(lines)
    while start < n and not _looks_like_data_line(lines[start].split(",")):
        start += 1
    return start, lines[start:]


def _read_csv_block(buf: str, names: list[str], dtypes: dict) -> pd.DataFrame:
    """
    Read one comma block with the strict C parser; on a type error fall back to
    an object/coerce read so a single bad cell cannot abort a whole batch.
    """
    try:
        return pd.read_csv(
            io.StringIO(buf),
            header=None,
            names=names,
            dtype=dtypes,
            engine="c",
            on_bad_lines="skip",
            skip_blank_lines=True,
        )
    except (ValueError, TypeError):
        df = pd.read_csv(
            io.StringIO(buf),
            header=None,
            names=names,
            dtype="object",
            engine="c",
            on_bad_lines="skip",
            skip_blank_lines=True,
        )
        for c in names:
            if dtypes.get(c) is float:
                df[c] = pd.to_numeric(df[c], errors="coerce")
        return df


def _finalize_plt_frame(df: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    """
    Add the parsed ``timestamp`` (authoritative date string, Excel-days as the
    fallback) and the days-vs-string cross-check statistics.
    """
    if len(df) == 0:
        out = df.copy()
        out["timestamp"] = pd.Series(dtype="datetime64[ns]")
        out["date_days_parsed"] = pd.Series(dtype="datetime64[ns]")
        return out, {
            "n_timestamp_string_unparsable": 0,
            "days_vs_string_max_abs_diff_s": float("nan"),
            "days_vs_string_mean_abs_diff_s": float("nan"),
        }

    date_s = df["date_str"].astype(str).str.strip()
    time_s = df["time_str"].astype(str).str.strip()
    dt_str = pd.to_datetime(
        date_s + " " + time_s, format="%Y-%m-%d %H:%M:%S", errors="coerce"
    )
    dt_days = pd.to_datetime(GEOLIFE_EXCEL_EPOCH) + pd.to_timedelta(
        pd.to_numeric(df["date_days"], errors="coerce"), unit="D"
    )
    n_str_bad = int(dt_str.isna().sum())
    both = dt_str.notna() & dt_days.notna()
    if both.any():
        diff = (dt_str[both] - dt_days[both]).dt.total_seconds().abs()
        max_diff_s = float(diff.max())
        mean_diff_s = float(diff.mean())
    else:
        max_diff_s = float("nan")
        mean_diff_s = float("nan")

    out = df.copy()
    out["timestamp"] = dt_str.where(dt_str.notna(), dt_days)
    out["date_days_parsed"] = dt_days
    return out, {
        "n_timestamp_string_unparsable": n_str_bad,
        "days_vs_string_max_abs_diff_s": max_diff_s,
        "days_vs_string_mean_abs_diff_s": mean_diff_s,
    }


def parse_plt_text(
    text: str,
    user_id: str,
    trajectory_id: str,
) -> tuple[pd.DataFrame, dict]:
    """
    Parse one `.plt` file body into a DataFrame -> ``(df, meta)``.

    Malformed data lines (wrong field count, non-numeric coordinates, missing
    date/time) are counted and dropped, never silently ignored.
    """
    header_lines, body = _split_header(text)
    n_body = sum(1 for ln in body if ln.strip())

    if n_body == 0:
        empty = pd.DataFrame({c: pd.Series(dtype="float64") for c in PLT_COLUMNS})
        empty["date_str"] = pd.Series(dtype="object")
        empty["time_str"] = pd.Series(dtype="object")
        empty.insert(0, "trajectory_id", trajectory_id)
        empty.insert(0, "user_id", user_id)
        empty["traj_uid"] = f"{user_id}|{trajectory_id}"
        empty, tstats = _finalize_plt_frame(empty)
        return empty, {
            "user_id": user_id,
            "trajectory_id": trajectory_id,
            "total_lines": header_lines,
            "header_lines": header_lines,
            "n_points": 0,
            "n_malformed": 0,
            "malformed_examples": [],
            **tstats,
        }

    parsed = _read_csv_block("\n".join(body), list(PLT_COLUMNS), _PLT_DTYPES)
    n_skipped = n_body - len(parsed)
    bad = parsed[_DATA_VALUE_COLS].isna().any(axis=1)
    n_bad = int(bad.sum())
    examples = [
        "|".join(str(v) for v in row)
        for row in parsed.loc[bad, _DATA_VALUE_COLS]
        .head(5)
        .itertuples(index=False, name=None)
    ]
    parsed = parsed[~bad].reset_index(drop=True)
    parsed.insert(0, "trajectory_id", trajectory_id)
    parsed.insert(0, "user_id", user_id)
    parsed["traj_uid"] = f"{user_id}|{trajectory_id}"
    parsed, tstats = _finalize_plt_frame(parsed)

    meta = {
        "user_id": user_id,
        "trajectory_id": trajectory_id,
        "total_lines": header_lines + n_body,
        "header_lines": header_lines,
        "n_points": len(parsed),
        "n_malformed": int(n_skipped + n_bad),
        "malformed_examples": examples,
        **tstats,
    }
    return parsed, meta


def read_plt_file(ref: TrajectoryRef) -> tuple[pd.DataFrame, dict]:
    text = ref.path.read_text(encoding="ascii", errors="replace")
    return parse_plt_text(text, ref.user_id, ref.trajectory_id)


def read_plt_bulk(refs: Sequence[TrajectoryRef]) -> tuple[pd.DataFrame, dict]:
    """
    Parse many `.plt` files into one DataFrame with a *single* pandas C parse.

    Per-file ``read_csv`` calls are dominated by pandas overhead on ~1,300-row
    files, which is why the bulk path exists; it produces exactly the same rows
    as :func:`parse_plt_text` (verified in the test-suite).

    Returns ``(df, meta)`` with combined parse-quality counters.
    """
    parts: list[str] = []
    n_expected = 0
    header_anomalies = 0
    for ref in refs:
        text = ref.path.read_text(encoding="ascii", errors="replace")
        header_lines, body = _split_header(text)
        if header_lines != PLT_HEADER_LINES:
            header_anomalies += 1
        prefix = f"{ref.user_id},{ref.trajectory_id},"
        for ln in body:
            s = ln.strip()
            if s:
                parts.append(prefix + s)
                n_expected += 1

    if not parts:
        empty = pd.DataFrame({c: pd.Series(dtype="float64") for c in PLT_COLUMNS})
        empty["date_str"] = pd.Series(dtype="object")
        empty["time_str"] = pd.Series(dtype="object")
        empty.insert(0, "trajectory_id", pd.Series(dtype="object"))
        empty.insert(0, "user_id", pd.Series(dtype="object"))
        empty["traj_uid"] = pd.Series(dtype="object")
        empty, tstats = _finalize_plt_frame(empty)
        return empty, {
            "n_expected_lines": 0,
            "n_rows_parsed": 0,
            "n_malformed": 0,
            "n_files": len(refs),
            "n_header_line_anomalies": header_anomalies,
            **tstats,
        }

    df = _read_csv_block("\n".join(parts), list(_BULK_COLUMNS), _BULK_DTYPES)
    n_skipped = n_expected - len(df)
    bad = df[_DATA_VALUE_COLS].isna().any(axis=1)
    n_bad = int(bad.sum())
    df = df[~bad].reset_index(drop=True)
    df = add_traj_uid(df)
    df, tstats = _finalize_plt_frame(df)

    meta = {
        "n_expected_lines": int(n_expected),
        "n_rows_parsed": int(len(df)),
        "n_malformed": int(n_skipped + n_bad),
        "n_files": len(refs),
        "n_header_line_anomalies": int(header_anomalies),
        **tstats,
    }
    return df, meta


# --------------------------------------------------------------------------- #
# Schema validation
# --------------------------------------------------------------------------- #

#: Columns that any *cleaned* points table must contain.
REQUIRED_POINT_COLUMNS: tuple[str, ...] = (
    "user_id",
    "trajectory_id",
    "timestamp",
    "lat",
    "lon",
    "altitude",
    "point_index",
    "dt_s",
    "step_m",
    "speed_mps",
)


def validate_points_schema(df: pd.DataFrame, *, require_features: bool = False) -> dict:
    """
    Structural validation of a points table. Raises :class:`SchemaError` on
    structural problems; returns a report of value-level problems (which are
    flagged, not raised, because they are expected in noisy GPS data).
    """
    if not isinstance(df, pd.DataFrame):
        raise SchemaError(f"expected a pandas DataFrame, got {type(df).__name__}")
    if df.empty:
        raise SchemaError("points table is empty")

    required = list(REQUIRED_POINT_COLUMNS)
    if require_features:
        required += list(FEATURE_COLUMNS)
    missing = [c for c in required if c not in df.columns]
    if missing:
        raise SchemaError(f"missing required columns: {missing}")

    if not pd.api.types.is_datetime64_any_dtype(df["timestamp"]):
        raise SchemaError("`timestamp` must be a datetime64 column")
    if df["trajectory_id"].isna().any() or (df["trajectory_id"].astype(str) == "").any():
        raise SchemaError("`trajectory_id` contains empty values")
    for col in ("lat", "lon"):
        if not pd.api.types.is_numeric_dtype(df[col]):
            raise SchemaError(f"`{col}` must be numeric")

    report = {
        "n_rows": int(len(df)),
        "n_trajectories": int(df["trajectory_id"].nunique()),
        "n_users": int(df["user_id"].nunique()),
        "n_nan_timestamp": int(df["timestamp"].isna().sum()),
        "n_lat_out_of_range": int(
            (~df["lat"].between(*LAT_RANGE)).sum()
        ),
        "n_lon_out_of_range": int(
            (~df["lon"].between(*LON_RANGE)).sum()
        ),
    }
    return report


# --------------------------------------------------------------------------- #
# Geometry helpers
# --------------------------------------------------------------------------- #

EARTH_RADIUS_M = 6_371_008.8


def haversine_m(
    lat1: np.ndarray,
    lon1: np.ndarray,
    lat2: np.ndarray,
    lon2: np.ndarray,
) -> np.ndarray:
    """Vectorised great-circle distance in metres."""
    lat1 = np.radians(np.asarray(lat1, dtype=float))
    lon1 = np.radians(np.asarray(lon1, dtype=float))
    lat2 = np.radians(np.asarray(lat2, dtype=float))
    lon2 = np.radians(np.asarray(lon2, dtype=float))
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = np.sin(dlat / 2.0) ** 2 + np.cos(lat1) * np.cos(lat2) * np.sin(dlon / 2.0) ** 2
    return 2.0 * EARTH_RADIUS_M * np.arcsin(np.sqrt(np.clip(a, 0.0, 1.0)))


def bearing_deg(
    lat1: np.ndarray,
    lon1: np.ndarray,
    lat2: np.ndarray,
    lon2: np.ndarray,
) -> np.ndarray:
    """Initial bearing (0-360) from point 1 to point 2."""
    lat1 = np.radians(np.asarray(lat1, dtype=float))
    lat2 = np.radians(np.asarray(lat2, dtype=float))
    dlon = np.radians(np.asarray(lon2, dtype=float) - np.asarray(lon1, dtype=float))
    x = np.sin(dlon) * np.cos(lat2)
    y = np.cos(lat1) * np.sin(lat2) - np.sin(lat1) * np.cos(lat2) * np.cos(dlon)
    return (np.degrees(np.arctan2(x, y)) + 360.0) % 360.0


# --------------------------------------------------------------------------- #
# Cleaning
# --------------------------------------------------------------------------- #

def clean_points(df: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    """
    Clean a points table that may contain one or many trajectories.

    Steps, in order (identical whether one or many trajectories are present):
      1. drop rows with unparsable timestamps;
      2. drop out-of-range coordinates and null-island points;
      3. drop exact duplicate (trajectory, timestamp) rows, keeping the first;
      4. sort chronologically within each trajectory;
      5. split each trajectory into segments where the gap exceeds
         ``SEGMENT_GAP_S``;
      6. compute dt / step / speed / bearing per point;
      7. flag GPS jumps (speed > ``JUMP_SPEED_MPS``) and noisy altitude.

    Returns ``(clean_df, audit)`` where ``audit`` is a per-trajectory DataFrame
    of removal counters (so nothing is hidden).
    """
    audit_cols = [
        "traj_uid",
        "user_id",
        "trajectory_id",
        "n_in",
        "n_removed_bad_timestamp",
        "n_removed_invalid_coord",
        "n_removed_duplicate_timestamp",
        "n_clean",
    ]
    if df.empty:
        return df.copy(), pd.DataFrame(columns=audit_cols)

    work = add_traj_uid(df)
    work["_bad_ts"] = work["timestamp"].isna()
    in_lat = work["lat"].between(*LAT_RANGE)
    in_lon = work["lon"].between(*LON_RANGE)
    null_island = np.isclose(work["lat"], 0.0, atol=1e-6) & np.isclose(
        work["lon"], 0.0, atol=1e-6
    )
    work["_bad_coord"] = ~(in_lat & in_lon) | null_island

    kept = work[~(work["_bad_ts"] | work["_bad_coord"])].copy()
    kept = kept.sort_values(["traj_uid", "timestamp"], kind="mergesort")
    dup = kept.duplicated(subset=["traj_uid", "timestamp"], keep="first")
    dup_counts = dup.groupby(kept["traj_uid"], sort=False).sum()
    clean = kept[~dup].drop(columns=["_bad_ts", "_bad_coord"]).reset_index(drop=True)

    audit = work.groupby(["traj_uid", "user_id", "trajectory_id"], sort=False).agg(
        n_in=("lat", "size"),
        n_removed_bad_timestamp=("_bad_ts", "sum"),
        n_removed_invalid_coord=("_bad_coord", "sum"),
    )
    audit["n_removed_duplicate_timestamp"] = (
        dup_counts.reindex(audit.index.get_level_values("traj_uid"))
        .fillna(0)
        .astype(int)
        .to_numpy()
    )
    audit["n_clean"] = audit["n_in"] - audit[
        [
            "n_removed_bad_timestamp",
            "n_removed_invalid_coord",
            "n_removed_duplicate_timestamp",
        ]
    ].sum(axis=1)
    audit = audit.reset_index()

    if clean.empty:
        return clean, audit[audit_cols]

    g = clean.groupby("traj_uid", sort=False)
    clean["point_index"] = g.cumcount()
    dt = g["timestamp"].diff().dt.total_seconds()
    clean["dt_s"] = dt
    gap = (dt > SEGMENT_GAP_S) | dt.isna()
    clean["segment_id"] = (
        gap.groupby(clean["traj_uid"], sort=False).cumsum().astype(int)
    )

    prev_lat = g["lat"].shift()
    prev_lon = g["lon"].shift()
    step = pd.Series(
        haversine_m(prev_lat, prev_lon, clean["lat"], clean["lon"]), index=clean.index
    )
    boundary = gap.to_numpy()
    step = step.mask(boundary, np.nan)
    clean["step_m"] = step
    with np.errstate(divide="ignore", invalid="ignore"):
        speed = clean["step_m"] / clean["dt_s"]
    clean["speed_mps"] = speed.where(clean["dt_s"] > 0).mask(boundary, np.nan)
    clean["bearing_deg"] = pd.Series(
        bearing_deg(prev_lat, prev_lon, clean["lat"], clean["lon"]), index=clean.index
    ).mask(boundary, np.nan)

    clean["flag_jump"] = (clean["speed_mps"] > JUMP_SPEED_MPS).fillna(False)
    clean["flag_altitude"] = (
        ~clean["altitude"].between(*ALT_RANGE_FT) | (clean["altitude"] == 0)
    )
    return clean, audit[audit_cols]


def clean_trajectory(df: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    """
    Clean a *single* parsed trajectory -> ``(clean_df, stats_dict)``.

    Thin wrapper over :func:`clean_points` so there is exactly one cleaning
    implementation (no train/inference divergence).
    """
    clean, audit = clean_points(df)
    if audit.empty:
        return clean, {
            "n_in": 0,
            "n_clean": 0,
            "n_removed_bad_timestamp": 0,
            "n_removed_invalid_coord": 0,
            "n_removed_duplicate_timestamp": 0,
            "n_removed_total": 0,
            "n_segments": 0,
            "n_jump_flags": 0,
            "n_altitude_flags": 0,
        }
    a = audit.iloc[0]
    stats = {
        "n_in": int(a["n_in"]),
        "n_clean": int(a["n_clean"]),
        "n_removed_bad_timestamp": int(a["n_removed_bad_timestamp"]),
        "n_removed_invalid_coord": int(a["n_removed_invalid_coord"]),
        "n_removed_duplicate_timestamp": int(a["n_removed_duplicate_timestamp"]),
        "n_removed_total": int(a["n_in"] - a["n_clean"]),
        "n_segments": int(clean["segment_id"].nunique()) if len(clean) else 0,
        "n_jump_flags": int(clean["flag_jump"].sum()) if len(clean) else 0,
        "n_altitude_flags": int(clean["flag_altitude"].sum()) if len(clean) else 0,
    }
    return clean, stats


# --------------------------------------------------------------------------- #
# Feature engineering  (shared by train / evaluate / predict -> no skew)
# --------------------------------------------------------------------------- #

def _group_shift(df: pd.DataFrame, col: str) -> pd.Series:
    return df.groupby("traj_uid", sort=False)[col].shift()


def _group_rolling(
    values: pd.Series, groups: pd.Series, window: int, how: str
) -> pd.Series:
    """Rolling aggregate computed independently inside each trajectory group."""
    tmp = pd.DataFrame(
        {"_v": np.asarray(values, dtype=float), "_g": np.asarray(groups)}
    )
    r = tmp.groupby("_g", sort=False)["_v"].rolling(window, min_periods=1)
    out = getattr(r, how)()
    out = out.reset_index(level=0, drop=True)
    return out.sort_index()


def make_features(clean_df: pd.DataFrame) -> pd.DataFrame:
    """
    Build the per-point feature matrix used by the anomaly model.

    All temporal features are computed **strictly inside one trajectory** (via
    ``groupby('traj_uid')``) so no information leaks between trajectories, which
    is what makes the trajectory-grouped train/test split honest.
    """
    df = add_traj_uid(clean_df).sort_values(
        ["traj_uid", "timestamp"], kind="mergesort"
    ).reset_index(drop=True)
    out = pd.DataFrame(index=df.index)
    out["user_id"] = df["user_id"].values
    out["trajectory_id"] = df["trajectory_id"].values
    out["traj_uid"] = df["traj_uid"].values
    out["timestamp"] = df["timestamp"].values
    out["lat"] = df["lat"].values
    out["lon"] = df["lon"].values

    dt = df["dt_s"].astype(float)
    step = df["step_m"].astype(float)
    speed = df["speed_mps"].astype(float)

    out["speed_mps"] = speed
    out["log_dt_s"] = np.log1p(dt.clip(lower=0))
    out["step_m"] = step

    # acceleration: change of speed over the inter-point interval, in-trajectory
    prev_speed = _group_shift(df, "speed_mps")
    accel = (speed - prev_speed) / dt.where(dt > 0)
    out["accel_mps2"] = accel

    # turn: absolute change of heading, in-trajectory
    prev_bearing = _group_shift(df, "bearing_deg")
    turn = (df["bearing_deg"] - prev_bearing).abs()
    turn = np.minimum(turn, 360.0 - turn)  # 0..180
    out["turn_deg"] = turn

    groups = df["traj_uid"]
    out["speed_mean_w5"] = _group_rolling(speed, groups, ROLL_WINDOW, "mean")
    out["speed_std_w5"] = _group_rolling(speed, groups, ROLL_WINDOW, "std").fillna(0.0)
    out["step_mean_w5"] = _group_rolling(step, groups, ROLL_WINDOW, "mean")
    out["turn_mean_w5"] = _group_rolling(turn, groups, ROLL_WINDOW, "mean")

    # straightness over the window: net displacement / path length (1 = straight)
    w = ROLL_WINDOW
    lat = df["lat"]
    lon = df["lon"]
    prev_lat_w = df.groupby("traj_uid", sort=False)["lat"].shift(w - 1)
    prev_lon_w = df.groupby("traj_uid", sort=False)["lon"].shift(w - 1)
    net = pd.Series(
        haversine_m(prev_lat_w, prev_lon_w, lat, lon), index=df.index
    )
    path = _group_rolling(step, groups, w, "sum")
    with np.errstate(divide="ignore", invalid="ignore"):
        straight = (net / path).where(path > 0)
    out["straightness_w5"] = straight.clip(0.0, 1.0)

    out["altitude"] = df["altitude"].astype(float)
    prev_alt = df.groupby("traj_uid", sort=False)["altitude"].shift()
    out["alt_change"] = (df["altitude"] - prev_alt) / dt.where(dt > 0)

    seconds = (
        df["timestamp"].dt.hour * 3600
        + df["timestamp"].dt.minute * 60
        + df["timestamp"].dt.second
    )
    frac = seconds / 86400.0
    out["tod_sin"] = np.sin(2 * np.pi * frac)
    out["tod_cos"] = np.cos(2 * np.pi * frac)

    # Fill remaining NaNs (trajectory start rows) with 0 – documented, and safe
    # because the tree model treats 0 as "no deviation available yet".
    out[list(FEATURE_COLUMNS)] = out[list(FEATURE_COLUMNS)].astype(float).fillna(0.0)
    return out


def feature_matrix(feature_df: pd.DataFrame) -> np.ndarray:
    """Extract the numeric feature matrix (float64) in a fixed column order."""
    missing = [c for c in FEATURE_COLUMNS if c not in feature_df.columns]
    if missing:
        raise SchemaError(f"feature frame missing columns: {missing}")
    return feature_df[list(FEATURE_COLUMNS)].to_numpy(dtype=float)


# --------------------------------------------------------------------------- #
# Splitting (leakage-safe, grouped by trajectory)
# --------------------------------------------------------------------------- #

def grouped_trajectory_split(
    feature_df: pd.DataFrame,
    *,
    test_frac: float = 0.25,
    seed: int = SEED,
    group_col: str = "traj_uid",
) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    """
    Split rows into train/test **by whole trajectory** (or user) so that points
    from the same trajectory never appear in both sets. Returns
    ``(train_df, test_df, info)``.
    """
    if group_col not in feature_df.columns:
        raise SchemaError(f"missing group column `{group_col}` for grouped split")
    groups = np.array(sorted(feature_df[group_col].astype(str).unique()))
    if len(groups) < 2:
        raise SchemaError("need at least 2 groups for a grouped split")
    rng = np.random.default_rng(seed)
    perm = rng.permutation(len(groups))
    n_test = max(1, int(round(len(groups) * test_frac)))
    n_test = min(n_test, len(groups) - 1)
    test_groups = set(groups[perm[:n_test]].tolist())
    train_groups = set(groups[perm[n_test:]].tolist())

    mask_test = feature_df[group_col].astype(str).isin(test_groups)
    train_df = feature_df.loc[~mask_test].reset_index(drop=True)
    test_df = feature_df.loc[mask_test].reset_index(drop=True)

    info = {
        "group_col": group_col,
        "n_groups_total": int(len(groups)),
        "n_groups_train": int(len(train_groups)),
        "n_groups_test": int(len(test_groups)),
        "train_groups": sorted(train_groups),
        "test_groups": sorted(test_groups),
        "n_rows_train": int(len(train_df)),
        "n_rows_test": int(len(test_df)),
        "seed": seed,
        "test_frac": test_frac,
        "leakage_check_groups_disjoint": bool(train_groups.isdisjoint(test_groups)),
    }
    return train_df, test_df, info


# --------------------------------------------------------------------------- #
# --------------------------------------------------------------------------- #
# Clean-output loaders (keep id columns as strings)
# --------------------------------------------------------------------------- #

CLEAN_POINTS_CSV = CLEAN_DIR / "points_clean_sample.csv"
TRAJ_SUMMARY_CSV = CLEAN_DIR / "trajectory_summary.csv"


def load_clean_points(path: "str | Path | None" = None) -> pd.DataFrame:
    """Load the cleaned point sample written by preprocess.py."""
    df = pd.read_csv(
        Path(path) if path else CLEAN_POINTS_CSV,
        dtype={"user_id": str, "trajectory_id": str, "traj_uid": str},
    )
    df["timestamp"] = pd.to_datetime(df["timestamp"])
    for c in ("flag_jump", "flag_altitude"):
        if c in df.columns:
            df[c] = df[c].astype(bool)
    return df


def load_trajectory_summary(path: "str | Path | None" = None) -> pd.DataFrame:
    """Load the per-trajectory summary written by preprocess.py."""
    df = pd.read_csv(
        Path(path) if path else TRAJ_SUMMARY_CSV,
        dtype={"user_id": str, "trajectory_id": str, "traj_uid": str},
    )
    for c in ("start_time", "end_time"):
        if c in df.columns:
            df[c] = pd.to_datetime(df[c])
    return df


# --------------------------------------------------------------------------- #
# GeoLife transportation-mode labels (NOT danger labels)
# --------------------------------------------------------------------------- #

def read_transport_labels(user_dir: "str | Path") -> Optional[pd.DataFrame]:
    """
    Read a GeoLife ``labels.txt`` (transportation mode) if present.

    These are *transportation-mode* labels (walk / bike / bus / car / train /
    subway / airplane / boat / run / motorcycle), NOT danger or anomaly labels.
    They are reported in EDA for completeness only.
    """
    path = Path(user_dir) / "labels.txt"
    if not path.is_file():
        return None
    rows = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines()[1:]:
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        rows.append(tuple(p.strip() for p in parts))
    if not rows:
        return None
    df = pd.DataFrame(rows, columns=["start", "end", "mode"])
    df["start_dt"] = pd.to_datetime(df["start"], format="%Y/%m/%d %H:%M:%S", errors="coerce")
    df["end_dt"] = pd.to_datetime(df["end"], format="%Y/%m/%d %H:%M:%S", errors="coerce")
    return df


__all__ = [
    "SEED",
    "ML_ROOT",
    "DATA_DIR",
    "GEOLIFE_DATA_DIR",
    "SYNTHETIC_ROOT",
    "CLEAN_DIR",
    "EDA_DIR",
    "EVAL_DIR",
    "MODELS_DIR",
    "FEATURE_COLUMNS",
    "JUMP_SPEED_MPS",
    "SEGMENT_GAP_S",
    "SchemaError",
    "DataSourceError",
    "DataSource",
    "TrajectoryRef",
    "ensure_dirs",
    "write_json",
    "read_json",
    "synthetic_banner",
    "resolve_data_source",
    "discover_trajectories",
    "select_trajectories",
    "add_traj_uid",
    "parse_plt_text",
    "read_plt_file",
    "read_plt_bulk",
    "validate_points_schema",
    "haversine_m",
    "bearing_deg",
    "clean_points",
    "clean_trajectory",
    "make_features",
    "feature_matrix",
    "grouped_trajectory_split",
    "read_transport_labels",
    "load_clean_points",
    "load_trajectory_summary",
]
