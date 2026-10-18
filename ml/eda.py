"""
eda.py - exploratory data analysis on the cleaned GeoLife data.

Every table and figure is produced from data that was actually read from disk
(``ml/outputs/clean/*.csv`` produced by ``preprocess.py``). Nothing is
hard-coded. The sampling-interval / speed / dwell / time-of-day / density /
correlation analyses use the cleaned *point sample* (default 1200 trajectories,
fixed seed); the trajectory length / duration analyses use the *full*
per-trajectory summary (all trajectories, ``--summary-scope all``).

Outputs -> ``ml/outputs/eda/``:
  * ``fig_01..fig_12*.png``   figures
  * ``table_01..table_10*.csv`` tables
  * ``findings.md``           measured numbers + explicit N/A statements

Run:  python ml/eda.py [--synthetic]
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import common as C  # noqa: E402

STOP_SPEED_MPS = 0.5      # below this a point counts as "not moving"
STOP_MIN_DURATION_S = 120.0  # a stop must last at least this long
EDA_FRACTION_PLOT = 0.15  # fraction of sample points drawn in the scatter (readability)


def _savefig(fig, path: Path, source: C.DataSource) -> None:
    if source.is_synthetic:
        fig.text(
            0.5,
            0.005,
            "SYNTHETIC DATA - NOT GeoLife - plumbing test only",
            ha="center",
            fontsize=8,
            color="crimson",
        )
    fig.tight_layout(rect=(0, 0.02, 1, 1))
    fig.savefig(path, dpi=110)
    plt.close(fig)
    print(f"  wrote {path.name}")


def _detect_stops(pts: pd.DataFrame) -> pd.DataFrame:
    """Detect stops (low speed sustained >= STOP_MIN_DURATION_S) vectorised."""
    d = pts.sort_values(["traj_uid", "timestamp"], kind="mergesort").reset_index(drop=True)
    low = d["speed_mps"].notna() & (d["speed_mps"] < STOP_SPEED_MPS)
    run = (~low).groupby(d["traj_uid"], sort=False).cumsum()
    stop_key = d["traj_uid"].astype(str) + "#" + run.astype(str)
    sub = d[low].copy()
    sub["_stop_key"] = stop_key[low]
    if sub.empty:
        return pd.DataFrame(
            columns=["traj_uid", "start", "end", "n_points", "duration_s", "mean_lat", "mean_lon"]
        )
    stops = sub.groupby("_stop_key", sort=False).agg(
        traj_uid=("traj_uid", "first"),
        start=("timestamp", "min"),
        end=("timestamp", "max"),
        n_points=("timestamp", "size"),
        mean_lat=("lat", "mean"),
        mean_lon=("lon", "mean"),
    )
    stops["duration_s"] = (stops["end"] - stops["start"]).dt.total_seconds()
    stops = stops[stops["duration_s"] >= STOP_MIN_DURATION_S].reset_index(drop=True)
    return stops


def _iqr_outliers(s: pd.Series) -> tuple[float, float, int]:
    x = s.dropna().to_numpy(dtype=float)
    if x.size == 0:
        return (np.nan, np.nan, 0)
    q1, q3 = np.percentile(x, [25, 75])
    iqr = q3 - q1
    lo, hi = q1 - 1.5 * iqr, q3 + 1.5 * iqr
    n = int(((x < lo) | (x > hi)).sum())
    return (float(lo), float(hi), n)


def run(args: argparse.Namespace) -> int:
    C.ensure_dirs()
    C.EDA_DIR.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    source = C.resolve_data_source(args.data_root, force_synthetic=args.synthetic)
    banner = source.banner()
    print(banner)
    syn = source.is_synthetic

    pts = C.load_clean_points()
    summ = C.load_trajectory_summary()
    meta = C.read_json(C.CLEAN_DIR / "preprocess_meta.json")
    print(f"[eda] points sample: {len(pts):,} rows, {pts['traj_uid'].nunique():,} trajectories")
    print(f"[eda] trajectory summary: {len(summ):,} trajectories")

    findings: list[str] = []
    if syn:
        findings.append(
            "# EDA findings\n\n> **SYNTHETIC DATA - NOT GeoLife.** All numbers below come "
            "from the tiny synthetic fixture and are plumbing-test values only.\n"
        )
    else:
        findings.append("# EDA findings\n")
    findings.append(
        f"Data source: **{source.label}**  \n"
        f"Root: `{source.root}`  \n"
        f"Generated: `ml/eda.py` (seed {C.SEED})\n"
    )

    # ---------------- 1. shape / columns / dtypes / sample rows ------------ #
    schema = pd.DataFrame(
        {
            "column": pts.columns,
            "dtype": [str(t) for t in pts.dtypes],
            "non_null": [int(pts[c].notna().sum()) for c in pts.columns],
        }
    )
    schema.to_csv(C.EDA_DIR / "table_01_schema.csv", index=False)
    pts.head(20).to_csv(C.EDA_DIR / "table_01b_sample_rows.csv", index=False)
    findings.append("## 1. Shape / columns / dtypes\n")
    findings.append(
        f"- Cleaned point sample: **{pts.shape[0]:,} rows x {pts.shape[1]} columns**.\n"
        f"- Trajectory summary: **{summ.shape[0]:,} rows x {summ.shape[1]} columns**.\n"
    )
    findings.append(
        "\n| column | dtype | non-null |\n|---|---|---|\n"
        + "\n".join(
            f"| {r.column} | {r.dtype} | {r.non_null:,} |" for r in schema.itertuples()
        )
        + "\n"
    )

    # ---------------- 2. missing + duplicate counts ------------------------ #
    miss = pd.DataFrame(
        {
            "column": pts.columns,
            "n_missing": [int(pts[c].isna().sum()) for c in pts.columns],
            "pct_missing": [float(pts[c].isna().mean() * 100) for c in pts.columns],
        }
    )
    dup_removed = int(summ["n_removed_duplicate_timestamp"].sum())
    inval_removed = int(summ["n_removed_invalid_coord"].sum())
    bad_ts_removed = int(summ["n_removed_bad_timestamp"].sum())
    n_in_total = int(summ["n_in"].sum())
    n_clean_total = int(summ["n_clean"].sum())
    miss.to_csv(C.EDA_DIR / "table_02_missingness.csv", index=False)
    pd.DataFrame(
        [
            {"metric": "rows_in_summary_scope", "value": n_in_total},
            {"metric": "rows_after_cleaning", "value": n_clean_total},
            {"metric": "removed_duplicate_timestamp", "value": dup_removed},
            {"metric": "removed_invalid_coord", "value": inval_removed},
            {"metric": "removed_bad_timestamp", "value": bad_ts_removed},
            {
                "metric": "malformed_rows_dropped_at_parse",
                "value": meta["counts"]["n_malformed_rows"],
            },
        ]
    ).to_csv(C.EDA_DIR / "table_02b_removal_counts.csv", index=False)
    findings.append("## 2. Missing values & duplicates\n")
    findings.append(
        f"- Across the summary scope ({n_in_total:,} raw rows): "
        f"**{dup_removed:,}** duplicate (trajectory, timestamp) rows, "
        f"**{inval_removed:,}** invalid-coordinate rows, "
        f"**{bad_ts_removed:,}** unparsable-timestamp rows, "
        f"**{meta['counts']['n_malformed_rows']:,}** malformed rows dropped at parse.\n"
        f"- In the cleaned point sample, NaN appears only in the derived "
        f"`dt_s`/`step_m`/`speed_mps`/`bearing_deg` columns at segment starts "
        f"(expected): `dt_s` missing = {int(pts['dt_s'].isna().sum()):,} rows.\n"
    )

    # ---------------- 3. lat/lon scatter + density ------------------------- #
    frac = min(1.0, EDA_FRACTION_PLOT)
    sc = pts.sample(frac=frac, random_state=C.SEED) if frac < 1 else pts
    fig, ax = plt.subplots(figsize=(6, 6))
    ax.scatter(sc["lon"], sc["lat"], s=1, alpha=0.15, linewidths=0)
    ax.set_xlabel("longitude")
    ax.set_ylabel("latitude")
    ax.set_title(f"GPS point scatter (sample of {len(sc):,} points)")
    _savefig(fig, C.EDA_DIR / "fig_01_latlon_scatter.png", source)

    fig, ax = plt.subplots(figsize=(6.4, 6))
    hb = ax.hexbin(pts["lon"], pts["lat"], gridsize=70, bins="log", cmap="viridis")
    fig.colorbar(hb, ax=ax, label="log10(count)")
    ax.set_xlabel("longitude")
    ax.set_ylabel("latitude")
    ax.set_title(f"Spatial density of {len(pts):,} sample points")
    _savefig(fig, C.EDA_DIR / "fig_02_latlon_density.png", source)

    lat_range = (float(pts["lat"].min()), float(pts["lat"].max()))
    lon_range = (float(pts["lon"].min()), float(pts["lon"].max()))
    findings.append("## 3. Spatial extent (sample)\n")
    findings.append(
        f"- Latitude range: **{lat_range[0]:.5f} .. {lat_range[1]:.5f}**; "
        f"longitude range: **{lon_range[0]:.5f} .. {lon_range[1]:.5f}**.\n"
        f"- See `fig_01_latlon_scatter.png`, `fig_02_latlon_density.png`.\n"
    )

    # ---------------- 4. trajectory length & duration histograms ----------- #
    for col, name, fname in (
        ("n_points", "trajectory length (points)", "fig_03_traj_length_hist.png"),
        ("duration_s", "trajectory duration (s)", "fig_04_traj_duration_hist.png"),
    ):
        vals = summ[col].replace(0, np.nan).dropna()
        fig, ax = plt.subplots(figsize=(7, 4))
        ax.hist(vals, bins=np.logspace(np.log10(max(vals.min(), 1)), np.log10(vals.max()), 50))
        ax.set_xscale("log")
        ax.set_xlabel(name + " (log scale)")
        ax.set_ylabel("number of trajectories")
        ax.set_title(f"{name} - all {len(summ):,} trajectories")
        _savefig(fig, C.EDA_DIR / fname, source)
    findings.append("## 4. Trajectory length & duration (all trajectories)\n")
    findings.append(
        f"- points/trajectory: median **{summ['n_points'].median():.0f}**, "
        f"mean **{summ['n_points'].mean():.1f}**, max **{summ['n_points'].max():.0f}**.\n"
        f"- duration (s): median **{summ['duration_s'].median():.0f}**, "
        f"mean **{summ['duration_s'].mean():.0f}**, max **{summ['duration_s'].max():.0f}**.\n"
        f"- total distance (m) summed: **{summ['total_distance_m'].sum():,.0f}** "
        f"(~{summ['total_distance_m'].sum()/1000:,.0f} km).\n"
    )
    summ.describe().T.to_csv(C.EDA_DIR / "table_03_trajectory_stats.csv")

    # ---------------- 5. sampling interval histogram ----------------------- #
    dt = pts["dt_s"].dropna()
    dt = dt[(dt > 0) & (dt < 3600)]
    fig, ax = plt.subplots(figsize=(7, 4))
    ax.hist(dt, bins=np.logspace(np.log10(max(dt.min(), 1e-3)), np.log10(dt.max()), 60))
    ax.set_xscale("log")
    ax.axvline(dt.median(), color="crimson", linestyle="--", label=f"median={dt.median():.1f}s")
    ax.set_xlabel("sampling interval dt (s, log)")
    ax.set_ylabel("count")
    ax.set_title("Sampling interval between consecutive fixes")
    ax.legend()
    _savefig(fig, C.EDA_DIR / "fig_05_sampling_interval_hist.png", source)
    dt_desc = dt.describe()
    dt_desc.to_csv(C.EDA_DIR / "table_04_sampling_interval.csv")
    pct_1_5 = float(((dt >= 1) & (dt <= 5)).mean() * 100)
    findings.append("## 5. Sampling interval\n")
    findings.append(
        f"- median dt = **{dt.median():.2f} s**, mean = **{dt.mean():.2f} s**, "
        f"p95 = **{dt.quantile(0.95):.2f} s**, max = **{dt.max():.1f} s**.\n"
        f"- **{pct_1_5:.1f}%** of intervals are in the dense 1-5 s band.\n"
    )

    # ---------------- 6. speed distribution (jumps handled) ---------------- #
    sp_all = pts["speed_mps"].dropna()
    sp_ok = sp_all[sp_all <= C.JUMP_SPEED_MPS]
    n_jumps = int((sp_all > C.JUMP_SPEED_MPS).sum())
    fig, ax = plt.subplots(figsize=(7, 4))
    ax.hist(sp_ok, bins=80)
    if n_jumps:
        ax.axvline(C.JUMP_SPEED_MPS, color="crimson", linestyle="--",
                   label=f"jump threshold {C.JUMP_SPEED_MPS:.0f} m/s ({n_jumps} excluded)")
        ax.legend()
    ax.set_xlabel("speed (m/s)")
    ax.set_ylabel("count")
    ax.set_title("Speed distribution (GPS jumps excluded)")
    _savefig(fig, C.EDA_DIR / "fig_06_speed_hist.png", source)
    pd.DataFrame(
        [
            {"metric": "n_speed_obs", "value": int(len(sp_all))},
            {"metric": "n_jumps_above_threshold", "value": n_jumps},
            {"metric": "jump_threshold_mps", "value": C.JUMP_SPEED_MPS},
            {"metric": "mean_speed_mps_excl_jumps", "value": float(sp_ok.mean())},
            {"metric": "median_speed_mps_excl_jumps", "value": float(sp_ok.median())},
            {"metric": "p95_speed_mps_excl_jumps", "value": float(sp_ok.quantile(0.95))},
            {"metric": "max_speed_mps_excl_jumps", "value": float(sp_ok.max())},
        ]
    ).to_csv(C.EDA_DIR / "table_05_speed_stats.csv", index=False)
    findings.append("## 6. Speed (GPS jumps handled)\n")
    findings.append(
        f"- **{n_jumps:,}** of {len(sp_all):,} per-step speeds exceed the "
        f"{C.JUMP_SPEED_MPS:.0f} m/s jump threshold and are excluded from the stats.\n"
        f"- speed (m/s) excl. jumps: mean **{sp_ok.mean():.2f}**, median "
        f"**{sp_ok.median():.2f}**, p95 **{sp_ok.quantile(0.95):.2f}**, max "
        f"**{sp_ok.max():.2f}**.\n"
    )

    # ---------------- 7. dwell-time / stop detection ----------------------- #
    stops = _detect_stops(pts)
    stops.to_csv(C.EDA_DIR / "table_06_stops.csv", index=False)
    fig, ax = plt.subplots(figsize=(7, 4))
    if len(stops):
        d = stops["duration_s"]
        ax.hist(d, bins=np.logspace(np.log10(max(d.min(), 1)), np.log10(d.max()), 40))
        ax.set_xscale("log")
        ax.set_xlabel("stop duration (s, log)")
        ax.set_ylabel("number of stops")
        ax.set_title(f"Detected stops (speed<{STOP_SPEED_MPS} m/s, >= {STOP_MIN_DURATION_S:.0f}s)")
    else:
        ax.text(0.5, 0.5, "no stops detected", ha="center")
        ax.set_title("Detected stops")
    _savefig(fig, C.EDA_DIR / "fig_07_dwell_stops.png", source)
    findings.append("## 7. Dwell time / stops\n")
    if len(stops):
        findings.append(
            f"- Detected **{len(stops):,}** stops (speed < {STOP_SPEED_MPS} m/s sustained "
            f">= {STOP_MIN_DURATION_S:.0f} s) across the point sample.\n"
            f"- stop duration (s): median **{stops['duration_s'].median():.0f}**, "
            f"mean **{stops['duration_s'].mean():.0f}**, max **{stops['duration_s'].max():.0f}**.\n"
        )
    else:
        findings.append("- No stops met the threshold in the point sample.\n")

    # ---------------- 8. time-of-day patterns ------------------------------ #
    hours = pts["timestamp"].dt.hour
    hourly = hours.value_counts().sort_index().reindex(range(24), fill_value=0)
    hourly.to_csv(C.EDA_DIR / "table_07_hourly_counts.csv")
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.bar(hourly.index, hourly.values)
    ax.set_xlabel("hour of day (local, as recorded)")
    ax.set_ylabel("point count")
    ax.set_xticks(range(0, 24, 2))
    ax.set_title("Time-of-day distribution of fixes")
    _savefig(fig, C.EDA_DIR / "fig_08_time_of_day.png", source)
    peak_hour = int(hourly.idxmax())
    findings.append("## 8. Time-of-day patterns\n")
    findings.append(
        f"- Busiest hour: **{peak_hour:02d}:00** with **{int(hourly.max()):,}** fixes; "
        f"quietest: **{int(hourly.idxmin()):02d}:00** with **{int(hourly.min()):,}**.\n"
        f"- Note: GeoLife timestamps are local clock time with no timezone stored.\n"
    )

    # ---------------- 9. spatial trajectory plot --------------------------- #
    fig, ax = plt.subplots(figsize=(7, 7))
    shown = 0
    for uid, grp in pts.groupby("traj_uid", sort=False):
        if shown >= 60:
            break
        grp = grp.sort_values("timestamp")
        ax.plot(grp["lon"], grp["lat"], linewidth=0.7, alpha=0.7)
        shown += 1
    ax.set_xlabel("longitude")
    ax.set_ylabel("latitude")
    ax.set_title(f"{shown} sample trajectories")
    _savefig(fig, C.EDA_DIR / "fig_09_spatial_trajectories.png", source)

    # ---------------- 10. outlier analysis --------------------------------- #
    feat = C.make_features(pts)
    num_cols = list(C.FEATURE_COLUMNS)
    rows = []
    for c in num_cols:
        lo, hi, n = _iqr_outliers(feat[c])
        rows.append({"feature": c, "iqr_low": lo, "iqr_high": hi, "n_outliers": n,
                     "pct_outliers": 100.0 * n / len(feat)})
    out_df = pd.DataFrame(rows)
    out_df.to_csv(C.EDA_DIR / "table_08_outlier_summary.csv", index=False)
    fig, ax = plt.subplots(figsize=(9, 5))
    ax.boxplot(
        [feat[c].to_numpy() for c in num_cols],
        tick_labels=num_cols,
        orientation="horizontal",
        showfliers=False,
    )
    ax.set_title("Feature spread (boxplots, outliers hidden)")
    _savefig(fig, C.EDA_DIR / "fig_10_feature_boxplots.png", source)
    top_out = out_df.sort_values("n_outliers", ascending=False).head(5)
    findings.append("## 10. Outlier analysis\n")
    findings.append(
        "- IQR-flag outliers per engineered feature (top 5 by count):\n\n"
        "| feature | n_outliers | % |\n|---|---|---|\n"
        + "\n".join(
            f"| {r.feature} | {r.n_outliers:,} | {r.pct_outliers:.2f} |"
            for r in top_out.itertuples()
        )
        + "\n"
    )

    # ---------------- 11. correlation heatmap ------------------------------ #
    corr = feat[num_cols].corr(numeric_only=True)
    corr.to_csv(C.EDA_DIR / "table_09_correlation.csv")
    fig, ax = plt.subplots(figsize=(8.5, 7))
    im = ax.imshow(corr.to_numpy(), cmap="coolwarm", vmin=-1, vmax=1)
    ax.set_xticks(range(len(num_cols)))
    ax.set_xticklabels(num_cols, rotation=90)
    ax.set_yticks(range(len(num_cols)))
    ax.set_yticklabels(num_cols)
    fig.colorbar(im, ax=ax, label="Pearson r")
    ax.set_title("Numeric feature correlation")
    _savefig(fig, C.EDA_DIR / "fig_11_correlation_heatmap.png", source)
    off = corr.where(~np.eye(len(num_cols), dtype=bool))
    strongest = off.abs().stack().idxmax()
    findings.append("## 11. Correlation\n")
    findings.append(
        f"- Strongest feature correlation: **{strongest[0]} ~ {strongest[1]}** "
        f"(r = {corr.loc[strongest[0], strongest[1]]:.3f}).\n"
        f"- Full matrix: `table_09_correlation.csv`, `fig_11_correlation_heatmap.png`.\n"
    )

    # ---------------- 12. class balance (labels) --------------------------- #
    findings.append("## 12. Class balance / labels\n")
    labels = None
    if not syn and source.source_kind == "geolife":
        frames = []
        for user_dir in sorted(p for p in Path(source.root).iterdir() if p.is_dir()):
            df = C.read_transport_labels(user_dir)
            if df is not None and len(df):
                df = df.copy()
                df["user_id"] = user_dir.name
                frames.append(df)
        if frames:
            labels = pd.concat(frames, ignore_index=True)
            bal = labels["mode"].value_counts().rename_axis("transportation_mode").reset_index(name="n_segments")
            bal.to_csv(C.EDA_DIR / "table_10_transport_labels.csv", index=False)
            fig, ax = plt.subplots(figsize=(7, 4))
            ax.bar(bal["transportation_mode"], bal["n_segments"])
            ax.set_ylabel("labelled segments")
            ax.set_title(f"GeoLife transportation-mode labels ({len(frames)} users)")
            plt.setp(ax.get_xticklabels(), rotation=45, ha="right")
            _savefig(fig, C.EDA_DIR / "fig_12_transport_mode_balance.png", source)
            findings.append(
                f"- GeoLife `labels.txt` (transportation mode) exists for **{len(frames)}** "
                f"users with **{len(labels):,}** labelled segments.\n"
                f"- Modes: " + ", ".join(
                    f"{r.transportation_mode} ({r.n_segments:,})" for r in bal.itertuples()
                ) + ".\n"
            )
    findings.append(
        "- **There are NO danger / anomaly labels in GeoLife.** The only labels are "
        "transportation modes (above). Therefore the anomaly model is **unsupervised** "
        "and outputs a *movement anomaly score*, never a danger probability. "
        "Class balance for a danger task: **N/A**.\n"
    )

    # ---------------- write findings + summary json ------------------------ #
    findings.append("## Reproducibility\n")
    findings.append(
        f"- thresholds: jump_speed={C.JUMP_SPEED_MPS} m/s, segment_gap="
        f"{C.SEGMENT_GAP_S} s, stop_speed={STOP_SPEED_MPS} m/s, "
        f"stop_min_duration={STOP_MIN_DURATION_S} s.\n"
        f"- seed: {C.SEED}; total EDA wall time: {time.time() - t0:.1f}s.\n"
    )
    (C.EDA_DIR / "findings.md").write_text("\n".join(findings), encoding="utf-8")
    print(f"[eda] wrote {C.EDA_DIR / 'findings.md'}")
    print(f"[eda] total wall time: {time.time() - t0:.1f}s")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="EDA on cleaned GeoLife data.")
    p.add_argument("--data-root", default=None)
    p.add_argument("--synthetic", action="store_true")
    return p


def main(argv: "list[str] | None" = None) -> int:
    return run(build_parser().parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
