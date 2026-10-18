"""
predict.py - score a trajectory and explain the top contributing features.

Loads the trained unsupervised IsolationForest bundle and produces a
**movement anomaly score** (higher = more unusual) for every point of an input
trajectory. For the most anomalous points it prints a **feature-scaling
explanation** (feature z-scores vs the training distribution) and a
**leave-one-feature-out contribution** (drop in anomaly score when a feature is
reset to the training mean). No SHAP dependency is used.

Accepted inputs
---------------
  * ``--plt PATH``  a GeoLife-format ``.plt`` file
  * ``--csv PATH``  a CSV with at least ``lat,lon,timestamp`` columns
                    (optional: ``user_id,trajectory_id,altitude``)
  * (default) the first trajectory of the resolved data source

Invalid input is reported gracefully (clear message, non-zero exit), never a
traceback.

Run examples
------------
  python ml/predict.py --plt "ml/data/synthetic_sample/SYNTHETIC_000/Trajectory/SYNTHETIC_traj_01.plt"
  python ml/predict.py --csv my_track.csv --topk 5
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

import common as C
import train as T

PREDICT_DIR = C.OUTPUTS_DIR / "predict"


def load_input(args: argparse.Namespace) -> tuple[pd.DataFrame, str]:
    """Return (points_df, label) for the requested input source."""
    if args.plt:
        path = Path(args.plt)
        if not path.is_file():
            raise FileNotFoundError(f"--plt file not found: {path}")
        text = path.read_text(encoding="ascii", errors="replace")
        df, meta = C.parse_plt_text(text, args.user_id or "input_user", path.stem)
        if df.empty:
            raise C.SchemaError(f"no valid points parsed from {path}")
        return df, path.stem

    if args.csv:
        path = Path(args.csv)
        if not path.is_file():
            raise FileNotFoundError(f"--csv file not found: {path}")
        df = pd.read_csv(path)
        missing = [c for c in ("lat", "lon", "timestamp") if c not in df.columns]
        if missing:
            raise C.SchemaError(f"CSV missing required columns: {missing}")
        if "user_id" not in df.columns:
            df["user_id"] = args.user_id or "input_user"
        if "trajectory_id" not in df.columns:
            df["trajectory_id"] = path.stem
        if "altitude" not in df.columns:
            df["altitude"] = 0.0
        df["timestamp"] = pd.to_datetime(df["timestamp"], errors="coerce")
        df["user_id"] = df["user_id"].astype(str)
        df["trajectory_id"] = df["trajectory_id"].astype(str)
        df = C.add_traj_uid(df)
        return df, path.stem

    # default: first trajectory of the resolved data source
    source = C.resolve_data_source(args.data_root, force_synthetic=args.synthetic)
    refs = C.discover_trajectories(source.root)
    df, _ = C.read_plt_file(refs[0])
    return df, refs[0].trajectory_id


def explain_point(
    bundle: dict, x_raw: np.ndarray, top_n: int = 6
) -> list[dict]:
    """
    Feature-scaling + leave-one-feature-out explanation for one point.

    * ``z`` = (x - train_mean) / train_scale  (how many std from training mean)
    * ``contribution`` = score(x) - score(x with this feature set to train mean)
      (how much THIS feature drives the anomaly; positive = pushes anomalous)
    """
    scaler = bundle["scaler"]
    mean = scaler.mean_
    scale = scaler.scale_
    feats = bundle["feature_columns"]

    z = (x_raw - mean) / scale
    x_scaled = z.reshape(1, -1)
    base = -bundle["model"].score_samples(x_scaled)[0]

    contribs = []
    for j, name in enumerate(feats):
        x2 = x_scaled.copy()
        x2[0, j] = 0.0  # 0 in scaled space == training mean
        s2 = -bundle["model"].score_samples(x2)[0]
        contribs.append(
            {"feature": name, "value": float(x_raw[j]), "z": float(z[j]),
             "contribution": float(base - s2)}
        )
    contribs.sort(key=lambda d: abs(d["contribution"]), reverse=True)
    return contribs[:top_n]


def run(args: argparse.Namespace) -> int:
    C.ensure_dirs()
    PREDICT_DIR.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    if not T.MODEL_PATH.is_file():
        print(f"[predict] ERROR: model not found at {T.MODEL_PATH}; run train.py first")
        return 2
    bundle = joblib.load(T.MODEL_PATH)

    try:
        raw, label = load_input(args)
    except (FileNotFoundError, C.SchemaError) as exc:
        print(f"[predict] ERROR: could not read input: {exc}")
        return 2

    # Synthetic-safety: never let a synthetic result be mistaken for real data.
    input_str = f"{args.plt or ''} {args.csv or ''}".lower()
    is_synth = bool(args.synthetic) or "synthetic" in input_str
    if is_synth:
        print("!" * 74)
        print("SYNTHETIC INPUT - NOT REAL GEOLIFE DATA.")
        print("The scores below are a plumbing test only and mean nothing about")
        print("any real journey.")
        print("!" * 74)

    clean, audit = C.clean_points(raw)
    if clean.empty:
        print("[predict] ERROR: input contained no usable points after cleaning")
        return 2
    feat = C.make_features(clean)
    scores = T.score_bundle(bundle, feat)

    feat = feat.copy()
    feat["movement_anomaly_score"] = scores
    thr = float(np.quantile(scores, 1.0 - float(bundle["contamination"])))
    flagged = scores >= thr

    out_dir = PREDICT_DIR / "SYNTHETIC" if is_synth else PREDICT_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    out_csv = out_dir / f"{label}_scored.csv"
    out_cols = ["trajectory_id", "timestamp", "lat", "lon", "speed_mps",
                "step_m", "turn_deg", "movement_anomaly_score"]
    feat[[c for c in out_cols if c in feat.columns]].to_csv(out_csv, index=False)
    if is_synth:
        (out_dir / f"{label}_scored.SYNTHETIC_WARNING.txt").write_text(
            "SYNTHETIC INPUT - NOT REAL GEOLIFE DATA.\n"
            f"This scored CSV ({out_csv.name}) was produced from the SYNTHETIC "
            "fixture and is a plumbing test only.\n",
            encoding="utf-8",
        )

    order = np.argsort(-scores)
    topk = order[: max(args.topk, 1)]

    print("=" * 74)
    print("MOVEMENT ANOMALY SCORES (unsupervised; NOT a danger probability)")
    print("=" * 74)
    print(f"input              : {label}")
    print(f"points scored      : {len(feat):,}")
    print(f"model              : {bundle['model_kind']} (seed {bundle['seed']})")
    print(f"flagged threshold  : {thr:.4f} (contamination={bundle['contamination']})")
    print(f"flagged points     : {int(flagged.sum()):,} ({flagged.mean()*100:.1f}%)")
    n_dupes = int(audit["n_removed_duplicate_timestamp"].sum()) if len(audit) else 0
    n_invalid = int(audit["n_removed_invalid_coord"].sum()) if len(audit) else 0
    n_bad_ts = int(audit["n_removed_bad_timestamp"].sum()) if len(audit) else 0
    n_removed_total = n_dupes + n_invalid + n_bad_ts
    print(f"cleaning removed   : {n_removed_total} rows "
          f"(dupes={n_dupes}, invalid_coord={n_invalid}, bad_timestamp={n_bad_ts})")
    print(f"scores CSV         : {out_csv}")
    print()

    X = C.feature_matrix(feat)
    for rank, i in enumerate(topk, start=1):
        row = feat.iloc[i]
        print("-" * 74)
        print(f"#{rank}  score={scores[i]:.4f}  at {row['timestamp']}  "
              f"({row['lat']:.5f}, {row['lon']:.5f})")
        expl = explain_point(bundle, X[i], top_n=args.explain_features)
        for e in expl:
            sign = "+" if e["contribution"] >= 0 else "-"
            print(f"     {e['feature']:<16} value={e['value']:>12.3f} "
                  f"z={e['z']:>+7.2f}  contribution={sign}{abs(e['contribution']):.4f}")
    print("-" * 74)
    print("Explanation: 'z' is the value in training-std units; 'contribution' is the")
    print("drop in anomaly score when that feature alone is reset to the training mean")
    print("(larger contribution = stronger driver of the anomaly).")
    print(f"[predict] total wall time: {time.time() - t0:.1f}s")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Score a trajectory for movement anomalies.")
    p.add_argument("--plt", default=None, help="path to a GeoLife .plt file")
    p.add_argument("--csv", default=None, help="path to a CSV with lat,lon,timestamp")
    p.add_argument("--data-root", default=None)
    p.add_argument("--synthetic", action="store_true")
    p.add_argument("--user-id", default=None)
    p.add_argument("--topk", type=int, default=3, help="number of points to explain")
    p.add_argument("--explain-features", type=int, default=6)
    return p


def main(argv: "list[str] | None" = None) -> int:
    return run(build_parser().parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
