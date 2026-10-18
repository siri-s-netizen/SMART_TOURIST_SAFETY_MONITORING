"""
evaluate.py - honest evaluation of the unsupervised anomaly model.

Why there are no "accuracy" numbers
-----------------------------------
GeoLife contains **no danger / anomaly ground truth** (its only labels are
transportation modes, which do not mark anomalies). A supervised metric such as
accuracy, F1 or a confusion matrix is therefore **impossible** to compute, and
any number presented as one would be fabricated. This script says so explicitly.

What it *does* compute (all on the held-out, trajectory-disjoint test split)
--------------------------------------------------------------------------
1. **Injection-based validation.** Known synthetic anomalies (a GPS "teleport"
   burst) are injected into *real* held-out trajectories at *known* indices; the
   model's movement anomaly score is then asked to rank the injected points
   above the untouched ones. This yields a ROC-AUC / average-precision /
   precision@k that measures *detector discrimination*. The **data is real**,
   only the **labels are synthetic**.
2. **Label-shuffle control.** The same metric with shuffled labels should be
   ~0.5 (sanity check that the metric is meaningful).
3. **Score/threshold stability.** Flagged fraction across score quantiles.

Outputs -> ``ml/outputs/eval/``:
  * ``eval_report.json``, ``eval_metrics.csv``, ``injection_scores.csv``,
    ``top_anomalies.csv``, ``findings.md``

Run:  python ml/evaluate.py [--synthetic] [--seed 42]
"""

from __future__ import annotations

import argparse
import sys
import time

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score, roc_auc_score

import common as C
import train as T

TELEPORT_DEG = 0.02          # ~2.2 km N and ~1.7 km E at Beijing latitude
RUN_LEN = 3                  # points moved per injected burst
INJECT_FRACTION = 0.03       # approx fraction of points used to start a burst


def inject_teleports(
    test_pts: pd.DataFrame, seed: int
) -> tuple[pd.DataFrame, np.ndarray]:
    """
    Inject GPS teleport bursts into real held-out points.

    Returns ``(injected_pts, labels)`` on the same row order as ``test_pts``
    (labels are 1 for points inside an injected burst window, else 0).
    Rows are identified by (traj_uid, timestamp).
    """
    rng = np.random.default_rng(seed)
    inj = test_pts.copy().reset_index(drop=True)
    labels = np.zeros(len(inj), dtype=int)
    keys: set[tuple[str, pd.Timestamp]] = set()

    for uid, idx in inj.groupby("traj_uid", sort=False).indices.items():
        idx = np.sort(np.asarray(idx))
        n = len(idx)
        if n < 40:
            continue
        n_runs = max(1, int(INJECT_FRACTION * n) // RUN_LEN)
        for _ in range(n_runs):
            s = int(rng.integers(0, n - RUN_LEN - 1))
            sel = idx[s : s + RUN_LEN]
            inj.loc[sel, "lat"] += TELEPORT_DEG
            inj.loc[sel, "lon"] += TELEPORT_DEG
            lo = int(idx[max(0, s - 1)])
            hi = int(idx[min(n - 1, s + RUN_LEN)])
            for i in range(lo, hi + 1):
                keys.add((inj.at[i, "traj_uid"], inj.at[i, "timestamp"]))

    labels = np.array(
        [(u, t) in keys for u, t in zip(inj["traj_uid"], inj["timestamp"])], dtype=int
    )
    return inj, labels


def _top_anomalies(feat: pd.DataFrame, scores: np.ndarray, k: int = 25) -> pd.DataFrame:
    d = feat.copy()
    d["movement_anomaly_score"] = scores
    cols = ["user_id", "trajectory_id", "timestamp", "lat", "lon",
            "speed_mps", "step_m", "turn_deg", "accel_mps2",
            "movement_anomaly_score"]
    return d.sort_values("movement_anomaly_score", ascending=False)[cols].head(k)


def run(args: argparse.Namespace) -> int:
    C.ensure_dirs()
    C.EVAL_DIR.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    source = C.resolve_data_source(args.data_root, force_synthetic=args.synthetic)
    print(source.banner())

    if not T.MODEL_PATH.is_file():
        raise SystemExit(
            f"model not found at {T.MODEL_PATH}; run `python ml/train.py` first"
        )
    bundle = joblib.load(T.MODEL_PATH)
    split = C.read_json(T.SPLIT_PATH)
    print(
        f"[evaluate] model: {bundle['model_kind']} | "
        f"features={len(bundle['feature_columns'])} | seed={bundle['seed']}"
    )

    pts = C.load_clean_points()
    feat = C.make_features(pts)
    test_groups = set(split["test_groups"])
    train_groups = set(split["train_groups"])

    # hard leakage guard: no trajectory may be in both sets
    overlap = train_groups & test_groups
    if overlap:
        raise SystemExit(f"LEAKAGE: {len(overlap)} trajectories in both splits")

    test_rows = feat["traj_uid"].isin(test_groups)
    test_feat = feat.loc[test_rows].reset_index(drop=True)
    test_pts = pts.loc[
        pts["traj_uid"].isin(test_groups)
    ].reset_index(drop=True)
    print(
        f"[evaluate] held-out test: {len(test_feat):,} points, "
        f"{test_feat['traj_uid'].nunique():,} trajectories (disjoint from train)"
    )

    # ----- 1. baseline scores on held-out data ---------------------------- #
    base_scores = T.score_bundle(bundle, test_feat)
    thr = float(np.quantile(base_scores, 1.0 - float(bundle["contamination"])))
    flagged = base_scores > thr

    # ----- 2. injection-based validation ---------------------------------- #
    inj_pts, inj_labels = inject_teleports(test_pts, seed=args.seed)
    inj_clean, _ = C.clean_points(inj_pts)  # recompute dt/step/speed after injection
    inj_feat = C.make_features(inj_clean)
    inj_scores = T.score_bundle(bundle, inj_feat)

    n_pos = int(inj_labels.sum())
    k = max(n_pos, 1)
    order = np.argsort(-inj_scores)
    topk = inj_labels[order[:k]]
    prec_at_k = float(topk.mean()) if len(topk) else float("nan")
    rec_at_k = float(topk.sum() / n_pos) if n_pos else float("nan")

    auc = float(roc_auc_score(inj_labels, inj_scores)) if 0 < n_pos < len(inj_labels) else float("nan")
    ap = float(average_precision_score(inj_labels, inj_scores)) if 0 < n_pos < len(inj_labels) else float("nan")

    # label-shuffle control: metric should collapse toward 0.5
    rng = np.random.default_rng(args.seed)
    shuffled = rng.permutation(inj_labels)
    auc_shuffled = (
        float(roc_auc_score(shuffled, inj_scores))
        if 0 < shuffled.sum() < len(shuffled)
        else float("nan")
    )

    inj_tbl = inj_feat[["user_id", "trajectory_id", "timestamp", "lat", "lon"]].copy()
    inj_tbl["movement_anomaly_score"] = inj_scores
    inj_tbl["injected_anomaly"] = inj_labels
    inj_tbl.to_csv(C.EVAL_DIR / "injection_scores.csv", index=False)

    # ----- 3. threshold stability ----------------------------------------- #
    qs = [0.90, 0.95, 0.98, 0.99, 0.995]
    stability = pd.DataFrame(
        {
            "score_quantile": qs,
            "threshold": [float(np.quantile(base_scores, q)) for q in qs],
            "frac_flagged": [float((base_scores > np.quantile(base_scores, q)).mean()) for q in qs],
        }
    )
    stability.to_csv(C.EVAL_DIR / "eval_metrics.csv", index=False)

    _top_anomalies(test_feat, base_scores).to_csv(C.EVAL_DIR / "top_anomalies.csv", index=False)

    report = {
        "generated_by": "ml/evaluate.py",
        "supervised_metrics_available": False,
        "why_no_accuracy": (
            "GeoLife has no anomaly/danger labels; only transportation-mode labels "
            "exist. Accuracy/F1/precision on real anomalies CANNOT be computed."
        ),
        "protocol": "injection-based validation on REAL held-out trajectories; "
                    "labels are synthetic (injected), data is real.",
        "test_set": {
            "n_points": int(len(test_feat)),
            "n_trajectories": int(test_feat["traj_uid"].nunique()),
            "leakage_overlap_groups": int(len(overlap)),
        },
        "injection": {
            "type": "GPS teleport burst",
            "teleport_deg": TELEPORT_DEG,
            "run_len": RUN_LEN,
            "n_injected_points_affected": n_pos,
            "roc_auc": auc,
            "average_precision": ap,
            "precision_at_k": prec_at_k,
            "recall_at_k": rec_at_k,
            "k": int(k),
            "roc_auc_shuffled_control": auc_shuffled,
        },
        "baseline": {
            "contamination": bundle["contamination"],
            "threshold": thr,
            "flagged_fraction": float(flagged.mean()),
        },
        "runtime_seconds": round(time.time() - t0, 2),
        "is_synthetic_data": source.is_synthetic,
    }
    C.write_json(C.EVAL_DIR / "eval_report.json", report)

    # ----- findings -------------------------------------------------------- #
    f = ["# Evaluation findings\n"]
    if source.is_synthetic:
        f.append("> **SYNTHETIC DATA - NOT GeoLife.** Plumbing-test values only.\n")
    f.append(
        "## Supervised metrics: NOT COMPUTED\n\n"
        "GeoLife has **no anomaly/danger ground truth**, so accuracy / F1 / a "
        "confusion matrix are not reported - they cannot be computed honestly. "
        "The only labels in GeoLife are transportation modes (see EDA), which do "
        "not mark anomalies. The model is **unsupervised** and its output is a "
        "*movement anomaly score*, never a danger probability.\n"
    )
    f.append(
        "## Injection-based validation (labels synthetic, data real)\n\n"
        f"- Held-out test set: **{len(test_feat):,}** points / "
        f"**{test_feat['traj_uid'].nunique():,}** trajectories, disjoint from train.\n"
        f"- Injected GPS teleport bursts (3 points moved ~2 km); "
        f"**{n_pos:,}** points marked affected.\n"
        f"- **ROC-AUC = {auc:.4f}**, **average precision = {ap:.4f}**, "
        f"**precision@k = {prec_at_k:.4f}**, **recall@k = {rec_at_k:.4f}** (k={k:,}).\n"
        f"- Label-shuffle control ROC-AUC = **{auc_shuffled:.4f}** "
        f"(should be ~0.5; confirms the metric is meaningful).\n"
    )
    f.append(
        "## Baseline scoring\n\n"
        f"- contamination = {bundle['contamination']}; threshold = {thr:.4f}; "
        f"flagged fraction = **{flagged.mean()*100:.2f}%** (by construction).\n"
        + "\n".join(
            f"- score quantile {r.score_quantile}: thr={r.threshold:.4f}, "
            f"flagged={r.frac_flagged*100:.2f}%"
            for r in stability.itertuples()
        )
        + "\n"
    )
    f.append(
        "\n## Interpretation limits\n\n"
        "- High ROC-AUC means the detector separates *teleport artefacts* from "
        "normal movement well. It does **not** mean it detects real danger.\n"
        "- Injection is one anomaly family (GPS teleports); other anomaly types "
        "(detours, unusual stops) are not covered by this single experiment.\n"
    )
    (C.EVAL_DIR / "findings.md").write_text("\n".join(f), encoding="utf-8")

    print(f"[evaluate] ROC-AUC(injection)={auc:.4f} AP={ap:.4f} "
          f"P@k={prec_at_k:.4f} R@k={rec_at_k:.4f} shuffled_auc={auc_shuffled:.4f}")
    print(f"[evaluate] wrote {C.EVAL_DIR}")
    print(f"[evaluate] total wall time: {time.time() - t0:.1f}s")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Evaluate the unsupervised anomaly model.")
    p.add_argument("--data-root", default=None)
    p.add_argument("--synthetic", action="store_true")
    p.add_argument("--seed", type=int, default=C.SEED)
    return p


def main(argv: "list[str] | None" = None) -> int:
    return run(build_parser().parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
