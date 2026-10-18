"""
history.py - historical trajectory analysis, revisit behaviour and zone-crossing trends.

Why this module exists
----------------------
The rest of the project looks at *one* trajectory at a time (anomaly scoring) or
at the whole sample in aggregate (EDA). A tourist-safety product also needs the
*long view*: who travels how much, when the city is busiest, which places people
return to, and how many people cross in and out of a monitored zone over time.

Everything here is measured from the real GeoLife data already on disk:
  * ``ml/outputs/clean/trajectory_summary.csv`` - the FULL per-trajectory summary
    (all 18,670 trajectories) for length / distance / active-days / monthly counts;
  * ``ml/outputs/clean/points_clean_sample.csv`` - the cleaned point sample
    (1,200 trajectories, fixed seed) for speed, stops, revisit and zone crossings,
    because point-level work over all 24.8 M rows would not be a batch job.

The zone used for the crossing trend is **derived from the data**: the densest
0.02 deg x 0.02 deg grid cell of the sample inside the Beijing metro window, then
expanded by +/- 0.03 deg. How it is defined is written verbatim in ``findings.md``.

Stop detection reuses :func:`eda._detect_stops` (same speed/duration thresholds)
so the two modules can never disagree about what a stop is.

Outputs (``ml/outputs/history/``)
---------------------------------
* ``fig_01..fig_06*.png``  temporal / revisit / zone figures
* ``table_01..table_10*.csv``
* ``findings.md``          real numbers + exact zone definition

Run:  python ml/history.py
"""

from __future__ import annotations

import argparse
import math
import sys
import time
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import common as C  # noqa: E402
from eda import _detect_stops  # reuse the exact stop definition from eda.py

HISTORY_DIR = C.OUTPUTS_DIR / "history"

# --- reuse the eda stop rule (documented there) ---------------------------- #
STOP_SPEED_MPS = 0.5
STOP_MIN_DURATION_S = 120.0

# --- revisit clustering: ~150 m grid at Beijing's latitude ----------------- #
REVISIT_GRID_DEG = 0.0015

# --- zone derivation ------------------------------------------------------- #
ZONE_GRID_DEG = 0.02          # 0.02 deg ~ 2.2 km lat
ZONE_EXPAND_DEG = 0.03        # expand the densest cell into a workable zone
# plausible Beijing metro window, used only to keep global outliers out of the
# densest-cell search (documented, not a data filter on the other analyses)
BJ_WINDOW = {"lat_min": 39.5, "lat_max": 40.5, "lon_min": 115.5, "lon_max": 117.0}

EARTH_RADIUS_M = 6_371_008.8


# --------------------------------------------------------------------------- #
# Pure geometry / bucketing helpers (unit-tested)
# --------------------------------------------------------------------------- #

def equirect_distance_m(lat1, lon1, lat2, lon2):
    """Flat-earth (equirectangular) distance in metres for short hops."""
    lat1 = np.asarray(lat1, dtype=float)
    lat2 = np.asarray(lat2, dtype=float)
    lon1 = np.asarray(lon1, dtype=float)
    lon2 = np.asarray(lon2, dtype=float)
    mean_lat = np.radians((lat1 + lat2) / 2.0)
    dx = np.radians(lon2 - lon1) * np.cos(mean_lat) * EARTH_RADIUS_M
    dy = np.radians(lat2 - lat1) * EARTH_RADIUS_M
    return np.hypot(dx, dy)


def inside_polygon_mask(lat, lon, polygon: "list[tuple[float, float]]") -> np.ndarray:
    """
    Ray-casting point-in-polygon test.

    ``polygon`` is a list of ``(lat, lon)`` vertices (closed implicitly). Returns
    a boolean array, one per input point.
    """
    lat = np.asarray(lat, dtype=float)
    lon = np.asarray(lon, dtype=float)
    inside = np.zeros(lat.shape, dtype=bool)
    n = len(polygon)
    for i in range(n):
        y1, x1 = polygon[i]
        y2, x2 = polygon[(i + 1) % n]
        crosses = ((y1 > lat) != (y2 > lat))
        with np.errstate(divide="ignore", invalid="ignore"):
            x_at = (x2 - x1) * (lat - y1) / (y2 - y1) + x1
        inside ^= crosses & (lon < x_at)
    return inside


def crossing_times(times, inside) -> tuple[list, list]:
    """
    Entry/exit instants of a point sequence against a binary inside mask.

    An **entry** is the first inside point after outside; an **exit** is the first
    outside point after inside. Times are returned in sequence order.
    """
    entries: list = []
    exits: list = []
    prev = None
    for t, ins in zip(times, inside):
        ins = bool(ins)
        if prev is not None and ins != prev:
            (entries if ins else exits).append(t)
        prev = ins
    return entries, exits


def bucket_counts(times, freq: str = "M") -> pd.Series:
    """Count events per calendar bucket (``freq`` is a pandas period alias)."""
    if len(times) == 0:
        return pd.Series(dtype="int64")
    periods = pd.to_datetime(pd.Series(list(times))).dt.to_period(freq).astype(str)
    return periods.value_counts().sort_index()


def densest_cell(lat, lon, grid_deg: float) -> tuple[float, float, int]:
    """Centre (lat, lon) and population of the densest ``grid_deg`` cell."""
    lat = np.asarray(lat, dtype=float)
    lon = np.asarray(lon, dtype=float)
    if lat.size == 0:
        return (float("nan"), float("nan"), 0)
    lat_bin = np.floor(lat / grid_deg)
    lon_bin = np.floor(lon / grid_deg)
    counts: dict[tuple[int, int], int] = {}
    for a, b in zip(lat_bin.astype(np.int64), lon_bin.astype(np.int64)):
        key = (int(a), int(b))
        counts[key] = counts.get(key, 0) + 1
    (best_a, best_b), best_n = max(counts.items(), key=lambda kv: kv[1])
    centre_lat = (best_a + 0.5) * grid_deg
    centre_lon = (best_b + 0.5) * grid_deg
    return (centre_lat, centre_lon, best_n)


def location_key(lat, lon, grid_deg: float = REVISIT_GRID_DEG):
    """Grid key for a location, used to cluster stops into 'places'."""
    lat = np.asarray(lat, dtype=float)
    lon = np.asarray(lon, dtype=float)
    return np.floor(lat / grid_deg).astype(np.int64) * 100_000 + np.floor(lon / grid_deg).astype(np.int64)


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #

def _savefig(fig, path: Path) -> None:
    fig.tight_layout()
    fig.savefig(path, dpi=120)
    plt.close(fig)
    print(f"  wrote {path.name}")


def run(args: argparse.Namespace) -> int:
    C.ensure_dirs()
    HISTORY_DIR.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    summ = C.load_trajectory_summary()
    pts = C.load_clean_points()
    print(f"[history] summary: {len(summ):,} trajectories; sample points: {len(pts):,} "
          f"({pts['traj_uid'].nunique():,} trajectories)")

    summ["start_time"] = pd.to_datetime(summ["start_time"])

    # ---------------- 1. per-user history ------------------------------- #
    per_user = summ.groupby("user_id", sort=True).agg(
        n_trajectories=("traj_uid", "size"),
        total_distance_m=("total_distance_m", "sum"),
        first_traj=("start_time", "min"),
        last_traj=("start_time", "max"),
    )
    active_days = (
        summ.assign(day=summ["start_time"].dt.floor("D"))
        .groupby("user_id")["day"].nunique()
        .rename("active_days")
    )
    per_user = per_user.join(active_days)
    speed_stats = pts.groupby("user_id")["speed_mps"].agg(
        median_speed_mps="median",
        p95_speed_mps=lambda s: s.quantile(0.95),
    )
    per_user = per_user.join(speed_stats)
    per_user = per_user.reset_index()
    per_user["total_distance_km"] = per_user["total_distance_m"] / 1000.0
    per_user.to_csv(HISTORY_DIR / "table_01_user_stats.csv", index=False)

    # ---------------- 2. temporal trends -------------------------------- #
    hours = pts["timestamp"].dt.hour.value_counts().sort_index().reindex(range(24), fill_value=0)
    hours.rename_axis("hour").reset_index(name="fixes").to_csv(
        HISTORY_DIR / "table_02_hour_of_day.csv", index=False)
    dow = pts["timestamp"].dt.dayofweek.value_counts().sort_index().reindex(range(7), fill_value=0)
    dow.rename_axis("day_of_week").reset_index(name="fixes").to_csv(
        HISTORY_DIR / "table_03_day_of_week.csv", index=False)

    monthly_traj = summ["start_time"].dt.to_period("M").astype(str).value_counts().sort_index()
    monthly_traj.rename_axis("month").reset_index(name="n_trajectories").to_csv(
        HISTORY_DIR / "table_04_monthly_trajectories.csv", index=False)

    fig, ax = plt.subplots(figsize=(8, 4))
    ax.bar(hours.index, hours.values, color="#1a73e8")
    ax.set_xticks(range(0, 24, 2))
    ax.set_xlabel("hour of day (local clock, as recorded)")
    ax.set_ylabel("fixes")
    ax.set_title("Historical fixes per hour of day (sample)")
    _savefig(fig, HISTORY_DIR / "fig_01_hour_of_day.png")

    dow_names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    fig, ax = plt.subplots(figsize=(7, 4))
    ax.bar(dow_names, dow.values, color="#1b7f4c")
    ax.set_ylabel("fixes")
    ax.set_title("Historical fixes per day of week (sample)")
    _savefig(fig, HISTORY_DIR / "fig_02_day_of_week.png")

    fig, ax = plt.subplots(figsize=(11, 4.5))
    ax.plot(range(len(monthly_traj)), monthly_traj.values, "-o", ms=3, color="#c0392b")
    ax.set_xticks(range(len(monthly_traj)))
    ax.set_xticklabels(monthly_traj.index, rotation=90, fontsize=6)
    ax.set_ylabel("trajectories")
    ax.set_title(f"Monthly trajectory counts (all {len(summ):,} trajectories, real span)")
    ax.grid(alpha=0.3)
    _savefig(fig, HISTORY_DIR / "fig_03_monthly_trajectories.png")

    # ---------------- 3. revisit / stay behaviour ----------------------- #
    stops = _detect_stops(pts)
    stops = stops.reset_index(drop=True)
    stops.to_csv(HISTORY_DIR / "table_05_stops.csv", index=False)
    if len(stops):
        stops["place_key"] = location_key(stops["mean_lat"].to_numpy(),
                                          stops["mean_lon"].to_numpy())
        stops["stop_day"] = pd.to_datetime(stops["start"]).dt.floor("D")
        stops["user_id"] = stops["traj_uid"].str.split("|").str[0]
    revisit_rows = []
    most_revisited_rows = []
    for user_id, grp in stops.groupby("user_id", sort=True):
        grp = grp.sort_values("start")
        seen: set = set()
        n_revisit = 0
        for key in grp["place_key"]:
            if key in seen:
                n_revisit += 1
            else:
                seen.add(key)
        n_stops = len(grp)
        revisit_rows.append({
            "user_id": user_id,
            "n_stops": n_stops,
            "n_distinct_places": len(seen),
            "n_revisits": n_revisit,
            "revisit_rate": (n_revisit / n_stops) if n_stops else float("nan"),
        })
        top = grp.groupby("place_key").agg(
            n_stops_at_place=("place_key", "size"),
            n_days=("stop_day", "nunique"),
            mean_lat=("mean_lat", "mean"),
            mean_lon=("mean_lon", "mean"),
        ).sort_values("n_stops_at_place", ascending=False).head(3)
        for r in top.itertuples():
            most_revisited_rows.append({
                "user_id": user_id,
                "place_key": int(r.Index),
                "n_stops_at_place": int(r.n_stops_at_place),
                "n_distinct_days": int(r.n_days),
                "mean_lat": float(r.mean_lat),
                "mean_lon": float(r.mean_lon),
            })
    revisit = pd.DataFrame(revisit_rows)
    if not revisit.empty:
        revisit["revisit_rate"] = revisit["revisit_rate"].round(4)
    revisit.to_csv(HISTORY_DIR / "table_06_revisit_rate.csv", index=False)
    most = pd.DataFrame(most_revisited_rows)
    most.to_csv(HISTORY_DIR / "table_07_most_revisited.csv", index=False)

    if len(most):
        top_users = most.sort_values("n_stops_at_place", ascending=False).head(15).iloc[::-1]
        fig, ax = plt.subplots(figsize=(9, 5.5))
        ax.barh([f"u{r.user_id} @ {r.mean_lat:.3f},{r.mean_lon:.3f}"
                 for r in top_users.itertuples()], top_users["n_stops_at_place"], color="#8e44ad")
        ax.set_xlabel("stops recorded at the place")
        ax.set_title(f"Most-revisited places (top {len(top_users)}, ~150 m grid)")
        _savefig(fig, HISTORY_DIR / "fig_05_top_revisit_locations.png")
    else:
        fig, ax = plt.subplots(figsize=(7, 4))
        ax.text(0.5, 0.5, "no stops detected in the sample", ha="center")
        _savefig(fig, HISTORY_DIR / "fig_05_top_revisit_locations.png")

    # ---------------- 4. zone derivation + crossings -------------------- #
    metro = pts[
        pts["lat"].between(BJ_WINDOW["lat_min"], BJ_WINDOW["lat_max"])
        & pts["lon"].between(BJ_WINDOW["lon_min"], BJ_WINDOW["lon_max"])
    ]
    clat, clon, ccount = densest_cell(metro["lat"].to_numpy(), metro["lon"].to_numpy(),
                                      ZONE_GRID_DEG)
    zone = {
        "south": clat - ZONE_GRID_DEG / 2 - ZONE_EXPAND_DEG,
        "north": clat + ZONE_GRID_DEG / 2 + ZONE_EXPAND_DEG,
        "west": clon - ZONE_GRID_DEG / 2 - ZONE_EXPAND_DEG,
        "east": clon + ZONE_GRID_DEG / 2 + ZONE_EXPAND_DEG,
    }
    polygon = [(zone["south"], zone["west"]), (zone["south"], zone["east"]),
               (zone["north"], zone["east"]), (zone["north"], zone["west"])]
    zone_rows = [
        {"field": "derivation", "value": "densest 0.02 deg cell of sample inside Beijing metro window, expanded +/-0.03 deg"},
        {"field": "densest_cell_lat", "value": clat},
        {"field": "densest_cell_lon", "value": clon},
        {"field": "densest_cell_count", "value": ccount},
        {"field": "zone_south", "value": zone["south"]},
        {"field": "zone_north", "value": zone["north"]},
        {"field": "zone_west", "value": zone["west"]},
        {"field": "zone_east", "value": zone["east"]},
    ]
    pd.DataFrame(zone_rows).to_csv(HISTORY_DIR / "table_10_zone_definition.csv", index=False)

    all_entries: list = []
    all_exits: list = []
    n_intersecting = 0
    n_crossing_trajs = 0
    for uid, grp in pts.sort_values(["traj_uid", "timestamp"]).groupby("traj_uid", sort=True):
        inside = inside_polygon_mask(grp["lat"].to_numpy(), grp["lon"].to_numpy(), polygon)
        if not inside.any():
            continue
        n_intersecting += 1
        e, x = crossing_times(list(grp["timestamp"]), inside)
        if e or x:
            n_crossing_trajs += 1
        all_entries.extend(e)
        all_exits.extend(x)

    monthly_entries = bucket_counts(all_entries, "M")
    monthly_exits = bucket_counts(all_exits, "M")
    weekly_entries = bucket_counts(all_entries, "W")
    weekly_exits = bucket_counts(all_exits, "W")
    monthly_zone = pd.DataFrame({
        "entries": monthly_entries, "exits": monthly_exits,
    }).fillna(0).astype(int)
    monthly_zone.index.name = "month"
    monthly_zone.reset_index().to_csv(HISTORY_DIR / "table_08_zone_crossings_monthly.csv", index=False)
    weekly_zone = pd.DataFrame({"entries": weekly_entries, "exits": weekly_exits}).fillna(0).astype(int)
    weekly_zone.index.name = "week"
    weekly_zone.reset_index().to_csv(HISTORY_DIR / "table_09_zone_crossings_weekly.csv", index=False)

    fig, ax = plt.subplots(figsize=(11, 4.5))
    ax.plot(range(len(monthly_zone)), monthly_zone["entries"], "-o", ms=3, label="entries", color="#1b7f4c")
    ax.plot(range(len(monthly_zone)), monthly_zone["exits"], "-s", ms=3, label="exits", color="#c0392b")
    ax.set_xticks(range(len(monthly_zone)))
    ax.set_xticklabels(monthly_zone.index, rotation=90, fontsize=6)
    ax.set_ylabel("crossings")
    ax.set_title("Zone entry / exit crossings per month")
    ax.legend()
    ax.grid(alpha=0.3)
    _savefig(fig, HISTORY_DIR / "fig_04_zone_crossings_trend.png")

    fig, ax = plt.subplots(figsize=(7, 7))
    sc = pts.sample(min(60000, len(pts)), random_state=C.SEED)
    ax.scatter(sc["lon"], sc["lat"], s=1, alpha=0.2, linewidths=0)
    ax.plot([zone["west"], zone["east"], zone["east"], zone["west"], zone["west"]],
            [zone["south"], zone["south"], zone["north"], zone["north"], zone["south"]],
            color="crimson", lw=1.6, label="monitored zone")
    ax.set_xlim(BJ_WINDOW["lon_min"], BJ_WINDOW["lon_max"])
    ax.set_ylim(BJ_WINDOW["lat_min"], BJ_WINDOW["lat_max"])
    ax.set_xlabel("longitude")
    ax.set_ylabel("latitude")
    ax.set_title("Sample points and the data-derived monitored zone")
    ax.legend()
    _savefig(fig, HISTORY_DIR / "fig_06_zone_map.png")

    # ---------------- findings ------------------------------------------ #
    overall_revisit_rate = (
        float(revisit["n_revisits"].sum() / revisit["n_stops"].sum())
        if not revisit.empty and revisit["n_stops"].sum() else float("nan")
    )
    peak_hour = int(hours.idxmax())
    busiest_dow = dow_names[int(dow.idxmax())]
    f: list[str] = []
    f.append("# Historical trajectory analysis findings\n")
    f.append(
        f"- Full per-trajectory summary: **{len(summ):,} trajectories**, "
        f"**{summ['user_id'].nunique():,} users**, real date span "
        f"**{summ['start_time'].min()} .. {summ['start_time'].max()}**.\n"
        f"- Point-level analyses use the cleaned sample: **{len(pts):,} points** / "
        f"**{pts['traj_uid'].nunique():,} trajectories** (seed {C.SEED}).\n"
    )
    f.append("## 1. Per-user history (`table_01_user_stats.csv`)\n")
    f.append(
        f"- trajectories/user: median **{per_user['n_trajectories'].median():.0f}**, "
        f"max **{per_user['n_trajectories'].max():.0f}**.\n"
        f"- total distance: **{per_user['total_distance_km'].sum():,.0f} km** across all users "
        f"(median per user {per_user['total_distance_km'].median():,.1f} km).\n"
        f"- active days/user: median **{per_user['active_days'].median():.0f}**, "
        f"max **{per_user['active_days'].max():.0f}**.\n"
        f"- sample median speed (m/s): overall median of user medians "
        f"**{per_user['median_speed_mps'].median():.2f}**, overall p95 of user p95s "
        f"**{per_user['p95_speed_mps'].median():.2f}**.\n"
    )
    f.append("## 2. Temporal trends (`fig_01`..`fig_03`)\n")
    f.append(
        f"- busiest hour of day: **{peak_hour:02d}:00** ({int(hours.max()):,} fixes); "
        f"quietest: **{int(hours.idxmin()):02d}:00** ({int(hours.min()):,}).\n"
        f"- busiest weekday: **{busiest_dow}**.\n"
        f"- monthly trajectory counts span **{monthly_traj.index[0]} .. {monthly_traj.index[-1]}** "
        f"({len(monthly_traj)} months); peak month **{monthly_traj.idxmax()}** "
        f"({int(monthly_traj.max()):,} trajectories).\n"
    )
    f.append("## 3. Revisit / stay behaviour (`table_05`..`table_07`)\n")
    f.append(
        f"- stops detected (speed < {STOP_SPEED_MPS} m/s for >= {STOP_MIN_DURATION_S:.0f} s): "
        f"**{len(stops):,}** across the sample.\n"
        f"- places clustered on a ~150 m grid ({REVISIT_GRID_DEG} deg); "
        f"overall **revisit rate {overall_revisit_rate:.3f}** "
        f"(fraction of stops at a place the same user had already stopped at).\n"
        f"- most-revisited single place: **{int(most['n_stops_at_place'].max()) if len(most) else 0}** "
        f"stops (see `fig_05_top_revisit_locations.png`).\n"
    )
    f.append("## 4. Zone-crossing trend (`table_08`..`table_10`, `fig_04`, `fig_06`)\n")
    f.append(
        "**How the zone was defined** (verbatim): take the cleaned sample points inside the "
        f"Beijing metro window lat [{BJ_WINDOW['lat_min']}, {BJ_WINDOW['lat_max']}], "
        f"lon [{BJ_WINDOW['lon_min']}, {BJ_WINDOW['lon_max']}]; find the densest "
        f"{ZONE_GRID_DEG} deg grid cell (**{clat:.4f} N, {clon:.4f} E**, {ccount:,} points); "
        f"expand it by +/- {ZONE_EXPAND_DEG} deg on every side.\n"
        f"- resulting zone: S {zone['south']:.5f}, W {zone['west']:.5f}, "
        f"N {zone['north']:.5f}, E {zone['east']:.5f}.\n"
        f"- trajectories intersecting the zone: **{n_intersecting}**; "
        f"those with at least one crossing: **{n_crossing_trajs}**.\n"
        f"- total crossings: **{len(all_entries)} entries, {len(all_exits)} exits** "
        f"over {len(monthly_zone)} months.\n"
    )
    f.append("## Reproducibility\n")
    f.append(
        f"- `python ml/history.py`; deterministic (seed {C.SEED}); wall time {time.time() - t0:.1f}s.\n"
        "- temporal/revisit/crossing analyses use the seeded point sample; monthly trajectory "
        "counts and per-user distance use the full summary.\n"
    )
    (HISTORY_DIR / "findings.md").write_text("\n".join(f), encoding="utf-8")
    print(f"[history] wrote {HISTORY_DIR / 'findings.md'}")
    print(f"[history] users={len(per_user)} stops={len(stops)} "
          f"entries={len(all_entries)} exits={len(all_exits)}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    return argparse.ArgumentParser(description="Historical GeoLife trajectory + zone-crossing analysis.")


def main(argv: "list[str] | None" = None) -> int:
    return run(build_parser().parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
