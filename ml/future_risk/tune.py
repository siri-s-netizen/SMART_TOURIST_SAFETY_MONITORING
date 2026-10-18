"""
tune.py - validation-only hyper-parameter selection for the future-risk forest.

Why this exists as its own script
---------------------------------
The test subjects are held out and are touched exactly once, by ``train.py``.
Choosing the forest's depth by looking at test performance would make every
number in the model card a fitted number rather than a measured one, so the
search runs entirely on the *validation* subjects and records what it found.

Run::

    python ml/future_risk/tune.py

It writes ``ml/outputs/future_risk/tuning.json`` with every configuration tried,
then ``train.py`` uses the selected values from ``config.py``.
"""

from __future__ import annotations

import itertools
import json
import sys
from pathlib import Path

import numpy as np

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from future_risk import config, dataset, features  # type: ignore
    from future_risk.train import export_forest, split_by_subject  # type: ignore
else:
    from . import config, dataset, features
    from .train import export_forest, split_by_subject

from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import average_precision_score, precision_recall_curve, roc_auc_score


def forest_export_size(forest: RandomForestClassifier) -> tuple[int, int]:
    """Exported asset size in bytes and total node count.

    Measured by running the *same* exporter and the *same* JSON separators the training stage uses,
    so the number here is the number the app would carry - not an estimate from the model's memory
    footprint.
    """
    payload = export_forest(forest)
    blob = json.dumps(payload, separators=(",", ":"))
    n_nodes = int(sum(len(tree["feature"]) for tree in payload["trees"]))
    return len(blob.encode("utf-8")), n_nodes


def _best_f1_threshold(labels: np.ndarray, probability: np.ndarray) -> tuple[float, float]:
    precision, recall, thresholds = precision_recall_curve(labels, probability)
    p, r, t = precision[:-1], recall[:-1], thresholds
    f1 = np.where((p + r) > 0, 2 * p * r / np.maximum(p + r, 1e-12), 0.0)
    best = int(np.argmax(f1))
    return float(t[best]), float(f1[best])


def main() -> dict:
    trials, _ = dataset.sanitize_trials(dataset.load_trials())
    window_set = dataset.build_windows(trials)
    train, val, test, split_info = split_by_subject(window_set)
    x_all = features.extract(window_set.windows)
    x_train, y_train = x_all[train["index"]], train["labels"]
    x_val, y_val = x_all[val["index"]], val["labels"]

    grid = list(
        itertools.product(
            [300],  # trees: more trees only ever help; 300 is the point of diminishing returns
            [8, 12, 16, None],  # max_depth
            [1, 2, 4, 8],  # min_samples_leaf
            ["sqrt", 0.3],  # max_features
            ["balanced_subsample", None],  # class_weight
        ),
        # The size budget is a *deployment* constraint, not a modelling one: the forest has to be
        # exported to JSON and walked on a phone, so it has to fit in an app asset. Searched together
        # with the quality metrics so the trade-off is measured instead of asserted.
    )
    grid.append((config.N_ESTIMATORS, config.MAX_DEPTH, config.MIN_SAMPLES_LEAF, config.MAX_FEATURES, config.CLASS_WEIGHT))
    print(f"[tune] {len(grid)} configurations on the VALIDATION split only")

    results = []
    for n_estimators, max_depth, min_samples_leaf, max_features, class_weight in grid:
        forest = RandomForestClassifier(
            n_estimators=n_estimators,
            max_depth=max_depth,
            min_samples_leaf=min_samples_leaf,
            max_features=max_features,
            class_weight=class_weight,
            random_state=config.SEED,
            n_jobs=-1,
        )
        forest.fit(x_train, y_train)
        probability = forest.predict_proba(x_val)[:, 1]
        pr_auc = float(average_precision_score(y_val, probability))
        roc_auc = float(roc_auc_score(y_val, probability))
        threshold, f1 = _best_f1_threshold(y_val, probability)
        alarm_fraction = float((probability[y_val == 0] >= threshold).mean())
        asset_bytes, n_nodes = forest_export_size(forest)
        results.append(
            {
                "n_estimators": n_estimators,
                "max_depth": max_depth,
                "min_samples_leaf": min_samples_leaf,
                "max_features": max_features,
                "class_weight": class_weight,
                "val_pr_auc": round(pr_auc, 6),
                "val_roc_auc": round(roc_auc, 6),
                "val_best_f1": round(f1, 6),
                "val_threshold_at_best_f1": round(threshold, 6),
                "val_negative_alarm_fraction": round(alarm_fraction, 6),
                "asset_bytes": asset_bytes,
                "asset_kb": round(asset_bytes / 1024, 1),
                "n_nodes": n_nodes,
                "within_budget": asset_bytes <= config.MAX_ARTEFACT_BYTES,
            }
        )
        print(
            f"[tune] depth={str(max_depth):>4} leaf={min_samples_leaf} mf={str(max_features):>4} "
            f"cw={str(class_weight):>19} -> pr_auc={pr_auc:.4f} roc_auc={roc_auc:.4f} "
            f"f1={f1:.4f} neg_alarm={alarm_fraction:.4f} size={asset_bytes/1024:9.1f} KB"
        )

    ranked = sorted(results, key=lambda row: -row["val_pr_auc"])
    # The rule the shipped model was chosen by, applied here so it is reproducible rather than a
    # hand-computed note in a doc: best validation PR-AUC among the configurations that *fit*.
    within_budget = [row for row in ranked if row["within_budget"]]
    selected = within_budget[0] if within_budget else ranked[0]
    payload = {
        "selection_metric": "validation PR-AUC (average precision)",
        "selection_rule": (
            "highest validation PR-AUC among the configurations whose exported JSON asset fits "
            "config.MAX_ARTEFACT_BYTES; falls back to the unconstrained best if none fit"
        ),
        "selection_split": "validation subjects only (test subjects never inspected)",
        "budget_bytes": config.MAX_ARTEFACT_BYTES,
        "n_configurations": len(results),
        "val_subjects": split_info["val_subjects"],
        "unconstrained_best": ranked[0],
        "selected": selected,
        "n_within_budget": len(within_budget),
        "ranked": ranked,
    }
    out = config.OUT_DIR / "tuning.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("")
    print(
        f"[tune] unconstrained best: {ranked[0]['val_pr_auc']:.4f} at "
        f"{ranked[0]['asset_kb']:.1f} KB (budget {config.MAX_ARTEFACT_BYTES} bytes)"
    )
    print(
        f"[tune] selected within budget ({len(within_budget)} of {len(results)} fit): "
        f"{selected['val_pr_auc']:.4f} at {selected['asset_kb']:.1f} KB"
    )
    print(f"[tune] wrote {out}")
    return payload


if __name__ == "__main__":
    main()
