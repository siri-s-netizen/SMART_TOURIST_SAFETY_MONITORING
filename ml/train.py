"""
train.py - feature engineering + unsupervised IsolationForest anomaly model.

Honesty notes
-------------
* GeoLife has **no danger / anomaly labels** (its only labels are transportation
  modes). This is therefore an **unsupervised** anomaly detector. Its output is
  a **movement anomaly score** (higher = more unusual), NEVER a "danger
  probability".
* The train/test split is **grouped by trajectory** (``traj_uid`` =
  ``user_id|trajectory_id``), so points from one trajectory never appear in both
  sets. Grouping by trajectory id alone would be wrong because GeoLife file
  names collide across users.
* Everything is seeded (``SEED``) so a re-run reproduces the model bit-for-bit.

Outputs
-------
  * ``ml/models/isolation_forest.joblib``  model bundle (model, scaler, features, meta)
  * ``ml/models/split.json``               the exact train/test trajectory split
  * ``ml/models/model_meta.json``          honest metadata (no fake metrics)
  * ``ml/outputs/train/train_report.json`` training summary + score distributions

Run:  python ml/train.py [--synthetic] [--contamination 0.02] [--n-estimators 200]
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler

import common as C

MODEL_PATH = C.MODELS_DIR / "isolation_forest.joblib"
SPLIT_PATH = C.MODELS_DIR / "split.json"
MODEL_META_PATH = C.MODELS_DIR / "model_meta.json"
TRAIN_REPORT_DIR = C.OUTPUTS_DIR / "train"

MODEL_KIND = "IsolationForest (unsupervised)"
SCORE_CONVENTION = (
    "movement_anomaly_score = -score_samples(scaled_features); "
    "higher = more anomalous. NOT a probability."
)


def build_model(n_estimators: int, contamination: "float | str", seed: int) -> IsolationForest:
    return IsolationForest(
        n_estimators=n_estimators,
        max_samples="auto",
        contamination=contamination,
        random_state=seed,
        n_jobs=-1,
    )


def fit_bundle(
    train_feat: pd.DataFrame,
    *,
    n_estimators: int = 200,
    contamination: "float | str" = 0.02,
    seed: int = C.SEED,
) -> dict:
    """
    Fit scaler + IsolationForest on a feature frame. Returns a bundle dict.

    Deterministic for a fixed ``seed`` (tested).
    """
    scaler = StandardScaler()
    X_train = C.feature_matrix(train_feat)
    X_train_s = scaler.fit_transform(X_train)
    model = build_model(n_estimators, contamination, seed)
    model.fit(X_train_s)
    bundle = {
        "model": model,
        "scaler": scaler,
        "feature_columns": list(C.FEATURE_COLUMNS),
        "seed": seed,
        "n_estimators": n_estimators,
        "contamination": contamination,
        "model_kind": MODEL_KIND,
        "score_convention": SCORE_CONVENTION,
    }
    return bundle


def score_bundle(bundle: dict, feat: pd.DataFrame) -> np.ndarray:
    """Movement anomaly score for a feature frame (higher = more anomalous)."""
    X = bundle["scaler"].transform(C.feature_matrix(feat))
    return -bundle["model"].score_samples(X)


def run(args: argparse.Namespace) -> int:
    C.ensure_dirs()
    TRAIN_REPORT_DIR.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    source = C.resolve_data_source(args.data_root, force_synthetic=args.synthetic)
    print(source.banner())

    pts = C.load_clean_points()
    print(f"[train] loaded {len(pts):,} clean points, {pts['traj_uid'].nunique():,} trajectories")
    feat = C.make_features(pts)
    print(f"[train] engineered {len(C.FEATURE_COLUMNS)} features for {len(feat):,} points")

    train_feat, test_feat, split_info = C.grouped_trajectory_split(
        feat, test_frac=args.test_frac, seed=C.SEED, group_col="traj_uid"
    )
    if not split_info["leakage_check_groups_disjoint"]:
        raise RuntimeError("LEAKAGE: train/test trajectory groups overlap")
    print(
        f"[train] split by traj_uid: {split_info['n_groups_train']:,} train / "
        f"{split_info['n_groups_test']:,} test trajectories "
        f"({split_info['n_rows_train']:,} / {split_info['n_rows_test']:,} points)"
    )

    bundle = fit_bundle(
        train_feat,
        n_estimators=args.n_estimators,
        contamination=args.contamination,
        seed=C.SEED,
    )

    # score distributions (NOT accuracy - there are no labels)
    s_train = score_bundle(bundle, train_feat)
    s_test = score_bundle(bundle, test_feat)

    C.MODELS_DIR.mkdir(parents=True, exist_ok=True)
    joblib.dump(bundle, MODEL_PATH)
    C.write_json(SPLIT_PATH, split_info)

    meta = {
        "generated_by": "ml/train.py",
        "model_kind": MODEL_KIND,
        "supervised": False,
        "labels_used": None,
        "score_convention": SCORE_CONVENTION,
        "feature_columns": list(C.FEATURE_COLUMNS),
        "seed": C.SEED,
        "n_estimators": args.n_estimators,
        "contamination": args.contamination,
        "training": {
            "n_train_points": int(len(train_feat)),
            "n_test_points": int(len(test_feat)),
            "n_train_trajectories": split_info["n_groups_train"],
            "n_test_trajectories": split_info["n_groups_test"],
            "group_col": "traj_uid",
        },
        "data_source": {
            "root": str(source.root),
            "is_synthetic": source.is_synthetic,
            "label": source.label,
        },
        "warning": (
            "Unsupervised anomaly detector. No accuracy/F1/precision is reported "
            "because GeoLife has no anomaly labels. Do not interpret the score as "
            "a probability of danger."
        ),
    }
    C.write_json(MODEL_META_PATH, meta)

    report = {
        "trained": True,
        "model_path": str(MODEL_PATH),
        "split_path": str(SPLIT_PATH),
        "meta": meta,
        "score_stats": {
            "train_mean": float(np.mean(s_train)),
            "train_std": float(np.std(s_train)),
            "train_p50": float(np.percentile(s_train, 50)),
            "train_p95": float(np.percentile(s_train, 95)),
            "train_p99": float(np.percentile(s_train, 99)),
            "test_mean": float(np.mean(s_test)),
            "test_std": float(np.std(s_test)),
            "test_p50": float(np.percentile(s_test, 50)),
            "test_p95": float(np.percentile(s_test, 95)),
            "test_p99": float(np.percentile(s_test, 99)),
            "train_flagged_fraction": float((s_train > np.percentile(s_train, 98)).mean()),
        },
        "runtime_seconds": round(time.time() - t0, 2),
    }
    C.write_json(TRAIN_REPORT_DIR / "train_report.json", report)

    print(f"[train] saved model  -> {MODEL_PATH}")
    print(f"[train] saved split  -> {SPLIT_PATH}")
    print(f"[train] saved meta   -> {MODEL_META_PATH}")
    print(f"[train] train score: mean={np.mean(s_train):.4f} p95={np.percentile(s_train,95):.4f}")
    print(f"[train] test  score: mean={np.mean(s_test):.4f} p95={np.percentile(s_test,95):.4f}")
    print(f"[train] NOTE: unsupervised - no accuracy metric exists (no labels).")
    print(f"[train] total wall time: {time.time() - t0:.1f}s")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Train the unsupervised anomaly model.")
    p.add_argument("--data-root", default=None)
    p.add_argument("--synthetic", action="store_true")
    p.add_argument("--n-estimators", type=int, default=200)
    p.add_argument("--contamination", type=float, default=0.02)
    p.add_argument("--test-frac", type=float, default=0.25)
    return p


def main(argv: "list[str] | None" = None) -> int:
    return run(build_parser().parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
