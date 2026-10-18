"""
preprocess.py - clean the GeoLife trajectories and persist reproducible outputs.

What it does
------------
1. Resolves the data source (real GeoLife if present, else the SYNTHETIC fixture
   - in which case it prints a loud banner and every downstream number is a
   plumbing test only).
2. Streams over trajectories in batches (bulk parse + bulk clean) and computes a
   per-trajectory summary. By default it summarizes **all** trajectories
   (``--summary-scope all``); use ``--summary-scope sample`` for a fast run.
3. Writes a cleaned, chronologically sorted, segment-labelled point table for a
   deterministic sample of trajectories (default 1200, fixed seed) used by the
   downstream EDA / model stages.

Cleaning rules (all documented in ``common.clean_points``):
  * drop unparsable timestamps, invalid coordinates and null-island points;
  * drop exact duplicate (trajectory, timestamp) rows (keep first);
  * sort chronologically within each trajectory;
  * split trajectories into segments at gaps > SEGMENT_GAP_S (120 s);
  * compute dt / step / speed / bearing per point, and flag GPS jumps
    (speed > 60 m/s) and noisy altitude.

Grouping is by the composite key ``traj_uid = user_id|trajectory_id`` because
GeoLife trajectory file names collide across users (847 shared ids verified).

Outputs (``ml/outputs/clean/``):
  * ``points_clean_sample.csv``   cleaned points for the sampled trajectories
  * ``trajectory_summary.csv``    per-trajectory summary (scope per flag)
  * ``preprocess_meta.json``      counts, thresholds, seed, schema report
  * ``sample_manifest.json``      exactly which trajectories were sampled

Run:  python ml/preprocess.py [--summary-scope all|sample] [--sample-trajectories N]
                              [--batch-size N] [--synthetic] [--data-root DIR]
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

import common as C

POINT_OUT_COLUMNS = [
    "user_id",
    "trajectory_id",
    "traj_uid",
    "segment_id",
    "point_index",
    "timestamp",
    "lat",
    "lon",
    "altitude",
    "dt_s",
    "step_m",
    "speed_mps",
    "bearing_deg",
    "flag_jump",
    "flag_altitude",
]

SUMMARY_AGG = {
    "n_points": ("lat", "size"),
    "start_time": ("timestamp", "min"),
    "end_time": ("timestamp", "max"),
    "total_distance_m": ("step_m", "sum"),
    "mean_step_m": ("step_m", "mean"),
    "median_step_m": ("step_m", "median"),
    "median_dt_s": ("dt_s", "median"),
    "mean_lat": ("lat", "mean"),
    "mean_lon": ("lon", "mean"),
    "n_flag_jump": ("flag_jump", "sum"),
    "n_flag_altitude": ("flag_altitude", "sum"),
    "n_segments": ("segment_id", "nunique"),
    "mean_speed_mps": ("_speed_ok", "mean"),
}


def summarize_batch(clean: pd.DataFrame, audit: pd.DataFrame) -> pd.DataFrame:
    """Per-trajectory summary rows for one batch of cleaned points."""
    if clean.empty:
        return pd.DataFrame()

    # mean speed excludes flagged GPS jumps (documented) - folded into one agg
    c = clean.assign(
        _speed_ok=clean["speed_mps"].where(clean["speed_mps"] <= C.JUMP_SPEED_MPS)
    )
    g = c.groupby("traj_uid", sort=False)
    summ = g.agg(**SUMMARY_AGG)
    summ["duration_s"] = (summ["end_time"] - summ["start_time"]).dt.total_seconds()
    summ["mean_speed_mps"] = summ["mean_speed_mps"].fillna(0.0)

    # audit already carries user_id / trajectory_id for every trajectory
    merged = audit.set_index("traj_uid").join(summ, how="left")
    merged["traj_uid"] = merged.index
    return merged.reset_index(drop=True)


def run(args: argparse.Namespace) -> int:
    C.ensure_dirs()
    t0 = time.time()

    source = C.resolve_data_source(args.data_root, force_synthetic=args.synthetic)
    print(source.banner())
    print(f"[preprocess] data root: {source.root}")

    refs = C.discover_trajectories(source.root)
    print(f"[preprocess] discovered {len(refs):,} trajectories")

    sampled = C.select_trajectories(refs, args.sample_trajectories, seed=C.SEED)
    sampled_uids = {f"{r.user_id}|{r.trajectory_id}" for r in sampled}
    print(
        f"[preprocess] point-level sample: {len(sampled):,} trajectories "
        f"(seed={C.SEED}); summary scope: {args.summary_scope}; batch={args.batch_size}"
    )

    scope = refs if args.summary_scope == "all" else sampled

    summary_parts: list[pd.DataFrame] = []
    point_parts: list[pd.DataFrame] = []
    n_malformed = 0
    n_header_anomalies = 0
    n_rows_parsed = 0
    days_vs_str_max = float("nan")
    t_scan = time.time()

    for b_start in range(0, len(scope), args.batch_size):
        batch = scope[b_start : b_start + args.batch_size]
        raw, pmeta = C.read_plt_bulk(batch)
        n_malformed += pmeta["n_malformed"]
        n_header_anomalies += pmeta["n_header_line_anomalies"]
        n_rows_parsed += pmeta["n_rows_parsed"]
        days_vs_str_max = np.nanmax(
            [days_vs_str_max, pmeta["days_vs_string_max_abs_diff_s"]]
        )

        clean, audit = C.clean_points(raw)
        if not clean.empty:
            summary_parts.append(summarize_batch(clean, audit))
            sel = clean[clean["traj_uid"].isin(sampled_uids)]
            if not sel.empty:
                point_parts.append(sel[POINT_OUT_COLUMNS].copy())

        done = min(b_start + args.batch_size, len(scope))
        if done % (args.batch_size * 4) == 0 or done == len(scope):
            print(
                f"[preprocess]   scanned {done:,}/{len(scope):,} "
                f"({time.time() - t_scan:.0f}s)"
            )

    summary = (
        pd.concat(summary_parts, ignore_index=True)
        if summary_parts
        else pd.DataFrame()
    )
    summary_path = C.CLEAN_DIR / "trajectory_summary.csv"
    summary.to_csv(summary_path, index=False)

    points = (
        pd.concat(point_parts, ignore_index=True) if point_parts else pd.DataFrame(columns=POINT_OUT_COLUMNS)
    )
    if not points.empty:
        points = points.sort_values(
            ["user_id", "trajectory_id", "timestamp"], kind="mergesort"
        ).reset_index(drop=True)
    points_path = C.CLEAN_DIR / "points_clean_sample.csv"
    points.to_csv(points_path, index=False)

    schema_report = C.validate_points_schema(points) if not points.empty else {}

    meta_out = {
        "generated_by": "ml/preprocess.py",
        "seed": C.SEED,
        "data_source": {
            "root": str(source.root),
            "kind": source.source_kind,
            "label": source.label,
            "is_synthetic": source.is_synthetic,
        },
        "thresholds": {
            "jump_speed_mps": C.JUMP_SPEED_MPS,
            "segment_gap_s": C.SEGMENT_GAP_S,
            "lat_range": list(C.LAT_RANGE),
            "lon_range": list(C.LON_RANGE),
            "alt_range_ft": list(C.ALT_RANGE_FT),
        },
        "counts": {
            "n_trajectories_discovered": len(refs),
            "n_trajectories_summarized": int(len(summary)),
            "n_trajectories_sampled_for_points": len(sampled),
            "n_rows_parsed": int(n_rows_parsed),
            "n_points_in_sample_table": int(len(points)),
            "n_malformed_rows": int(n_malformed),
            "n_header_line_anomalies": int(n_header_anomalies),
            "days_vs_string_max_abs_diff_s": days_vs_str_max,
        },
        "schema_report": schema_report,
        "outputs": {
            "trajectory_summary": str(summary_path),
            "points_clean_sample": str(points_path),
        },
        "runtime_seconds": round(time.time() - t0, 2),
    }
    C.write_json(C.CLEAN_DIR / "preprocess_meta.json", meta_out)

    manifest = {
        "seed": C.SEED,
        "n_sampled": len(sampled),
        "sampled_trajectories": [
            {"user_id": r.user_id, "trajectory_id": r.trajectory_id} for r in sampled
        ],
        "is_synthetic": source.is_synthetic,
    }
    C.write_json(C.CLEAN_DIR / "sample_manifest.json", manifest)

    print("[preprocess] wrote:")
    print(f"  {summary_path}  ({len(summary):,} rows)")
    print(f"  {points_path}  ({len(points):,} rows)")
    print(f"  {C.CLEAN_DIR / 'preprocess_meta.json'}")
    print(f"  {C.CLEAN_DIR / 'sample_manifest.json'}")
    print("[preprocess] counts:", meta_out["counts"])
    print(f"[preprocess] total wall time: {time.time() - t0:.1f}s")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Clean GeoLife trajectories.")
    p.add_argument("--data-root", default=None, help="override trajectory root")
    p.add_argument("--synthetic", action="store_true", help="force the synthetic fixture")
    p.add_argument(
        "--sample-trajectories",
        type=int,
        default=1200,
        help="number of trajectories kept for point-level outputs (0 = all)",
    )
    p.add_argument(
        "--summary-scope",
        choices=["all", "sample"],
        default="all",
        help="whether the per-trajectory summary covers all or only sampled trajectories",
    )
    p.add_argument("--batch-size", type=int, default=500, help="trajectories per bulk parse")
    return p


def main(argv: "list[str] | None" = None) -> int:
    args = build_parser().parse_args(argv)
    if args.sample_trajectories == 0:
        args.sample_trajectories = None
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
