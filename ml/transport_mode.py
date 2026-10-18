"""
transport_mode.py - a GENUINE supervised model on GeoLife's transportation-mode labels.

What this model is - and is NOT
-------------------------------
GeoLife's ``labels.txt`` files carry exactly one kind of ground truth: the
**transportation mode** of each labelled time interval (walk / bike / bus / car /
train / subway / ...). This module trains a RandomForest to predict that mode
from cheap movement features of a segment. It is a demonstration that the
feature pipeline carries real signal and it is honestly labelled everywhere as:

    "next-segment transport-mode prediction - NOT a risk model"

GeoLife contains **no injury, danger or incident labels at all**, so it cannot
train a future-risk model. ``findings.md`` states that plainly and explains that
``ApproachRisk`` in the app is a documented weighted heuristic, not a learned
model. This script never claims otherwise.

Method
------
* For every user that has a ``labels.txt``, read their real ``.plt`` trajectories,
  clean them with the shared :mod:`common` pipeline, and slice out the points
  inside each labelled interval.
* Per segment compute: duration, distance, straightness (net/path), mean & p95
  speed, mean heading change, point count and time-of-day (sin/cos).
* Split **subject-disjoint** (whole users on one side) so no user leaks across
  train/test, then fit a seeded RandomForest and report accuracy, macro-F1,
  per-class precision/recall, and a confusion matrix.

Outputs (``ml/outputs/transport/``)
-----------------------------------
* ``fig_01_class_balance.png``, ``fig_02_confusion_matrix.png``,
  ``fig_03_feature_importance.png``
* ``table_01_dataset.csv``, ``table_02_per_class.csv``, ``table_03_confusion.csv``,
  ``table_04_split.csv``
* ``model_metrics.json``
* ``findings.md`` and ``model_card.md``

Run:  python ml/transport_mode.py
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import warnings
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from sklearn.ensemble import RandomForestClassifier  # noqa: E402
from sklearn.metrics import (  # noqa: E402
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
)

import common as C  # noqa: E402

OUT_DIR = C.OUTPUTS_DIR / "transport"

FEATURE_COLUMNS = [
    "duration_s",
    "distance_m",
    "straightness",
    "mean_speed_mps",
    "p95_speed_mps",
    "mean_turn_deg",
    "log_n_points",
    "tod_sin",
    "tod_cos",
]
MIN_POINTS_PER_SEGMENT = 2
MIN_SEGMENT_METERS = 1.0      # discard near-zero-movement labels (parsing artefacts)
TEST_FRAC_USERS = 0.30

MODEL_LABEL = "next-segment transport-mode prediction - NOT a risk model"


# --------------------------------------------------------------------------- #
# Pure feature helpers (unit-tested)
# --------------------------------------------------------------------------- #

def heading_change_deg(bearings) -> np.ndarray:
    """Absolute change between consecutive bearings, folded into [0, 180]."""
    b = np.asarray(bearings, dtype=float)
    if b.size < 2:
        return np.array([], dtype=float)
    diff = np.abs(np.diff(b))
    return np.minimum(diff, 360.0 - diff)


def segment_features(lat, lon, timestamps) -> dict:
    """
    Movement features for one segment (>= 2 points), computed with the shared
    equirectangular/haversine helpers. Pure: no file or model access.
    """
    lat = np.asarray(lat, dtype=float)
    lon = np.asarray(lon, dtype=float)
    t = pd.to_datetime(pd.Series(list(timestamps)))
    order = np.argsort(t.values, kind="mergesort")
    lat, lon, t = lat[order], lon[order], t.iloc[order].reset_index(drop=True)

    n = len(lat)
    if n < 2:
        return {}

    steps = C.haversine_m(lat[:-1], lon[:-1], lat[1:], lon[1:])
    dt = np.diff(t.values).astype("timedelta64[s]").astype(float)
    with np.errstate(divide="ignore", invalid="ignore"):
        speeds = np.where(dt > 0, steps / dt, np.nan)
    speeds = speeds[np.isfinite(speeds)]

    path_m = float(np.sum(steps))
    net_m = float(C.haversine_m(lat[0], lon[0], lat[-1], lon[-1]))
    bearings = C.bearing_deg(lat[:-1], lon[:-1], lat[1:], lon[1:])
    turn = heading_change_deg(bearings)

    duration_s = float((t.iloc[-1] - t.iloc[0]).total_seconds())
    start = t.iloc[0]
    seconds = int(start.hour * 3600 + start.minute * 60 + start.second)
    frac = seconds / 86400.0

    return {
        "duration_s": duration_s,
        "distance_m": path_m,
        "straightness": (net_m / path_m) if path_m > 0 else 0.0,
        "mean_speed_mps": float(np.mean(speeds)) if speeds.size else 0.0,
        "p95_speed_mps": float(np.quantile(speeds, 0.95)) if speeds.size else 0.0,
        "mean_turn_deg": float(np.mean(turn)) if turn.size else 0.0,
        "n_points": int(n),
        "log_n_points": float(np.log1p(n)),
        "tod_sin": float(np.sin(2 * np.pi * frac)),
        "tod_cos": float(np.cos(2 * np.pi * frac)),
        "start_time": start,
    }


def build_segment_table(source_root: Path) -> tuple[pd.DataFrame, dict]:
    """
    Walk every user with a ``labels.txt`` and build one row per labelled segment.

    Returns ``(segments_df, audit)`` where audit records the real per-user counts.
    """
    rows: list[dict] = []
    audit: dict[str, object] = {"n_users_with_labels": 0, "n_labels": 0,
                                "n_segments_built": 0, "n_skipped_too_short": 0,
                                "n_skipped_too_short_meters": 0, "users": []}
    user_dirs = sorted(p for p in source_root.iterdir() if p.is_dir())
    for user_dir in user_dirs:
        labels = C.read_transport_labels(user_dir)
        if labels is None or len(labels) == 0:
            continue
        audit["n_users_with_labels"] += 1
        traj_dir = user_dir / "Trajectory"
        plt_files = sorted(traj_dir.glob("*.plt")) if traj_dir.is_dir() else []
        if not plt_files:
            continue
        refs = [C.TrajectoryRef(user_dir.name, p.stem, p) for p in plt_files]
        raw, _ = C.read_plt_bulk(refs)
        clean, _ = C.clean_points(raw)
        if clean.empty:
            continue
        clean = clean.sort_values("timestamp").reset_index(drop=True)
        tvals = clean["timestamp"].values
        n_user_segments = 0
        for label in labels.itertuples():
            if pd.isna(label.start_dt) or pd.isna(label.end_dt):
                continue
            audit["n_labels"] += 1
            lo = int(np.searchsorted(tvals, np.datetime64(label.start_dt), side="left"))
            hi = int(np.searchsorted(tvals, np.datetime64(label.end_dt), side="right"))
            sub = clean.iloc[lo:hi]
            if len(sub) < MIN_POINTS_PER_SEGMENT:
                audit["n_skipped_too_short"] += 1
                continue
            feats = segment_features(sub["lat"].to_numpy(), sub["lon"].to_numpy(),
                                     sub["timestamp"].tolist())
            if not feats or feats["distance_m"] < MIN_SEGMENT_METERS:
                audit["n_skipped_too_short_meters"] += 1
                continue
            feats["user_id"] = user_dir.name
            feats["mode"] = label.mode
            rows.append(feats)
            n_user_segments += 1
        audit["users"].append({"user_id": user_dir.name, "n_trajectories": len(plt_files),
                               "n_segments": n_user_segments})
    audit["n_segments_built"] = len(rows)
    return pd.DataFrame(rows), audit


# --------------------------------------------------------------------------- #
# Figures
# --------------------------------------------------------------------------- #

def _savefig(fig, path: Path) -> None:
    fig.tight_layout()
    fig.savefig(path, dpi=130)
    plt.close(fig)
    print(f"  wrote {path.name}")


def run(args: argparse.Namespace) -> int:
    C.ensure_dirs()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    source = C.resolve_data_source(args.data_root, force_synthetic=args.synthetic)
    print(source.banner())
    if source.source_kind != "geolife" and not args.synthetic:
        print("[transport] non-GeoLife source: no transportation labels expected.")

    print("[transport] building per-segment features from real .plt + labels.txt ...")
    segments, audit = build_segment_table(Path(source.root))
    if segments.empty:
        raise SystemExit("No labelled segments were built; cannot train. (No data fabricated.)")
    print(f"[transport] {len(segments):,} labelled segments from "
          f"{audit['n_users_with_labels']} users")

    segments["start_time"] = pd.to_datetime(segments["start_time"])
    segments.to_csv(OUT_DIR / "table_01_dataset.csv", index=False)

    # ---- subject-disjoint split ------------------------------------------ #
    train_df, test_df, split_info = C.grouped_trajectory_split(
        segments, test_frac=TEST_FRAC_USERS, seed=C.SEED, group_col="user_id"
    )
    if not split_info["leakage_check_groups_disjoint"]:
        raise RuntimeError("LEAKAGE: train/test users overlap")
    pd.DataFrame([
        {"split": "train", "n_users": split_info["n_groups_train"],
         "n_segments": len(train_df),
         "users": " ".join(split_info["train_groups"])},
        {"split": "test", "n_users": split_info["n_groups_test"],
         "n_segments": len(test_df),
         "users": " ".join(split_info["test_groups"])},
    ]).to_csv(OUT_DIR / "table_04_split.csv", index=False)

    X_train = train_df[FEATURE_COLUMNS].to_numpy(dtype=float)
    X_test = test_df[FEATURE_COLUMNS].to_numpy(dtype=float)
    y_train = train_df["mode"].to_numpy()
    y_test = test_df["mode"].to_numpy()

    classes = sorted(segments["mode"].unique())
    print(f"[transport] split: {split_info['n_groups_train']} train users / "
          f"{split_info['n_groups_test']} test users; {len(train_df)}/{len(test_df)} segments")

    model = RandomForestClassifier(
        n_estimators=300, random_state=C.SEED, n_jobs=-1, min_samples_leaf=2,
        class_weight="balanced_subsample",
    )
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        model.fit(X_train, y_train)
    pred = model.predict(X_test)

    acc = float(accuracy_score(y_test, pred))
    macro_f1 = float(f1_score(y_test, pred, average="macro", zero_division=0))
    weighted_f1 = float(f1_score(y_test, pred, average="weighted", zero_division=0))

    # ---- figures ---------------------------------------------------------- #
    balance = segments["mode"].value_counts().reindex(classes)
    fig, ax = plt.subplots(figsize=(9, 4.5))
    ax.bar(balance.index, balance.values, color="#1a73e8")
    ax.set_ylabel("segments")
    ax.set_title(f"GeoLife transportation-mode labels ({len(segments):,} segments, "
                 f"{audit['n_users_with_labels']} users)")
    plt.setp(ax.get_xticklabels(), rotation=45, ha="right")
    _savefig(fig, OUT_DIR / "fig_01_class_balance.png")

    cm = confusion_matrix(y_test, pred, labels=classes)
    fig, ax = plt.subplots(figsize=(8, 6.6))
    im = ax.imshow(cm, cmap="Blues")
    ax.set_xticks(range(len(classes)))
    ax.set_yticks(range(len(classes)))
    ax.set_xticklabels(classes, rotation=45, ha="right", fontsize=8)
    ax.set_yticklabels(classes, fontsize=8)
    ax.set_xlabel("predicted")
    ax.set_ylabel("true")
    ax.set_title(f"Transport-mode confusion ({split_info['n_groups_test']} unseen users)\n"
                 f"accuracy {acc:.4f}, macro-F1 {macro_f1:.4f}")
    for i in range(cm.shape[0]):
        for j in range(cm.shape[1]):
            if cm[i, j]:
                ax.text(j, i, str(cm[i, j]), ha="center", va="center", fontsize=7,
                        color="white" if cm[i, j] > cm.max() / 2 else "black")
    fig.colorbar(im, ax=ax, shrink=0.85)
    _savefig(fig, OUT_DIR / "fig_02_confusion_matrix.png")

    order = np.argsort(model.feature_importances_)[::-1]
    fig, ax = plt.subplots(figsize=(8, 5))
    ax.barh([FEATURE_COLUMNS[i] for i in order][::-1],
            model.feature_importances_[order][::-1], color="#1b7f4c")
    ax.set_xlabel("importance")
    ax.set_title("Feature importance (transport-mode forest)")
    _savefig(fig, OUT_DIR / "fig_03_feature_importance.png")

    # ---- tables ----------------------------------------------------------- #
    report = classification_report(y_test, pred, labels=classes, output_dict=True,
                                   zero_division=0)
    per_class = pd.DataFrame([
        {"mode": cls, "precision": report[cls]["precision"], "recall": report[cls]["recall"],
         "f1": report[cls]["f1-score"], "support": int(report[cls]["support"])}
        for cls in classes
    ])
    per_class.to_csv(OUT_DIR / "table_02_per_class.csv", index=False)
    pd.DataFrame(cm, index=classes, columns=classes).rename_axis("true\\pred").to_csv(
        OUT_DIR / "table_03_confusion.csv")

    metrics = {
        "model": "RandomForestClassifier",
        "target": "GeoLife labels.txt transportation mode",
        "label": MODEL_LABEL,
        "n_estimators": 300,
        "min_samples_leaf": 2,
        "class_weight": "balanced_subsample",
        "n_segments": int(len(segments)),
        "n_users_with_labels": int(audit["n_users_with_labels"]),
        "n_labels_seen": int(audit["n_labels"]),
        "n_skipped_too_short": int(audit["n_skipped_too_short"]),
        "n_skipped_too_short_meters": int(audit["n_skipped_too_short_meters"]),
        "classes": classes,
        "split": {"n_users_train": split_info["n_groups_train"],
                  "n_users_test": split_info["n_groups_test"],
                  "n_segments_train": int(len(train_df)),
                  "n_segments_test": int(len(test_df)),
                  "subject_disjoint": bool(split_info["leakage_check_groups_disjoint"]),
                  "seed": C.SEED},
        "test_accuracy": acc,
        "test_macro_f1": macro_f1,
        "test_weighted_f1": weighted_f1,
        "per_class": {cls: {k: (float(v) if isinstance(v, float) else v)
                            for k, v in report[cls].items()} for cls in classes},
        "feature_columns": FEATURE_COLUMNS,
        "feature_importances": {name: float(v) for name, v in
                                zip(FEATURE_COLUMNS, model.feature_importances_)},
    }
    (OUT_DIR / "model_metrics.json").write_text(json.dumps(metrics, indent=2) + "\n",
                                                encoding="utf-8")

    # ---- findings --------------------------------------------------------- #
    top_feats = sorted(metrics["feature_importances"].items(), key=lambda kv: -kv[1])[:5]
    n_test_classes = sorted(set(y_test))
    f: list[str] = []
    f.append("# Transport-mode model findings\n")
    f.append(f"**This is a {MODEL_LABEL}.**\n")
    f.append(
        f"- target: GeoLife ``labels.txt`` transportation mode; "
        f"**{len(segments):,} labelled segments** built from "
        f"**{audit['n_users_with_labels']}** users.\n"
        f"- skipped: {audit['n_skipped_too_short']} intervals with < {MIN_POINTS_PER_SEGMENT} "
        f"points, {audit['n_skipped_too_short_meters']} with < {MIN_SEGMENT_METERS} m movement.\n"
        f"- split: **subject-disjoint** - {split_info['n_groups_train']} train users / "
        f"{split_info['n_groups_test']} test users "
        f"({len(train_df):,} / {len(test_df):,} segments); overlap "
        f"{not split_info['leakage_check_groups_disjoint']}.\n"
        f"- classes present: {', '.join(classes)}.\n"
    )
    f.append("## Results (on unseen users)\n")
    f.append(
        f"- **accuracy {acc:.4f}**, **macro-F1 {macro_f1:.4f}**, "
        f"weighted-F1 {weighted_f1:.4f}.\n"
        f"- classes with test support: {', '.join(n_test_classes)}.\n"
    )
    f.append("### Per-class precision / recall\n")
    f.append(
        "| mode | precision | recall | f1 | support |\n|---|---|---|---|---|\n"
        + "\n".join(
            f"| {r.mode} | {r.precision:.3f} | {r.recall:.3f} | {r.f1:.3f} | {r.support} |"
            for r in per_class.itertuples()
        )
        + "\n"
    )
    f.append("## Top features\n")
    f.append("\n".join(f"- `{name}`: {value:.4f}" for name, value in top_feats) + "\n")
    f.append("## Honest assessment: future-RISK prediction\n")
    f.append(
        "The GeoLife dataset and the UCI HAR dataset used elsewhere in this project contain "
        "**no injury, danger, incident or anomaly outcome labels of any kind**. GeoLife's only "
        "labels are the transportation modes modelled above; UCI HAR's labels are six activity "
        "classes. It is therefore **not possible to train a genuine supervised model that predicts "
        "a future safety RISK** - there is nothing to learn the risk from. Any accuracy number "
        "quoted for a 'risk model' on these datasets would be fabricated. Accordingly, "
        "``ApproachRisk`` in the app is a **documented weighted heuristic** (a hand-set combination "
        "of speed, proximity, time-of-day and zone signals with published weights), **not a learned "
        "model**, and this module makes no claim to the contrary. Building a real risk model would "
        "require a dataset with recorded incident/danger outcomes, which this project does not have.\n"
    )
    f.append("## Reproducibility\n")
    f.append(
        f"- `python ml/transport_mode.py`; seed {C.SEED}; deterministic feature build and split; "
        f"wall time {time.time() - t0:.1f}s.\n"
    )
    (OUT_DIR / "findings.md").write_text("\n".join(f), encoding="utf-8")

    model_card = f"""# Model card - GeoLife transport-mode classifier

## What it does
Predicts the **transportation mode** of a movement segment (walk / bike / bus /
car / train / subway / taxi / ...) from cheap, pose-free movement features.

> {MODEL_LABEL}

## Intended use
- Demonstrating that the shared GeoLife feature pipeline carries real signal.
- Teaching / coursework evidence of a genuine supervised model on a real target.
- **NOT** a safety or risk model. It must never be presented as predicting danger.

## Data
- Source: Microsoft GeoLife GPS Trajectories 1.3, ``labels.txt`` (transportation mode).
- {len(segments):,} labelled segments from {audit['n_users_with_labels']} users (no fabricated rows).

## Training
- Algorithm: RandomForestClassifier (300 trees, min_samples_leaf=2, class_weight=balanced_subsample).
- Split: subject-disjoint ({split_info['n_groups_train']} train / {split_info['n_groups_test']} test users, seed {C.SEED}).

## Metrics (held-out, unseen users)
- accuracy: {acc:.4f}
- macro-F1: {macro_f1:.4f}
- weighted-F1: {weighted_f1:.4f}

## Limitations
- Class imbalance is severe (walk dominates); rare modes (boat, run, motorcycle,
  airplane) have very few examples, so their per-class scores are noisy.
- GeoLife is overwhelmingly Beijing / motorised urban travel; no terrain or
  weather context is included in the features.
- This model predicts the **current/next segment's transport mode**, not any
  future adverse event.

## Ethical / honesty note
There are no danger or incident labels in GeoLife, so no risk model can be
trained from it. ``ApproachRisk`` in the app is a documented weighted heuristic.
"""
    (OUT_DIR / "model_card.md").write_text(model_card, encoding="utf-8")

    print(f"[transport] accuracy {acc:.4f}  macro-F1 {macro_f1:.4f}")
    print(f"[transport] wrote {OUT_DIR / 'findings.md'} and {OUT_DIR / 'model_card.md'}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Supervised transport-mode model on GeoLife labels (NOT a risk model).")
    p.add_argument("--data-root", default=None)
    p.add_argument("--synthetic", action="store_true")
    return p


def main(argv: "list[str] | None" = None) -> int:
    return run(build_parser().parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
