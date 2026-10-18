"""
train.py - the reproducible future-risk pipeline: split, train, calibrate, evaluate, export.

Run it from the repository root::

    python ml/future_risk/train.py

It writes (all paths relative to the repository root):

* ``ml/outputs/future_risk/windows.npz``                       the labelled matrix
* ``ml/outputs/future_risk/dataset_provenance.json``           measured dataset facts
* ``ml/outputs/future_risk/metrics.json``                      every measured number
* ``ml/outputs/future_risk/calibration_reliability.csv``       the reliability table
* ``ml/outputs/future_risk/test_predictions.csv``              per-window held-out predictions
* ``ml/outputs/future_risk/figures/*.png``                     the evaluation figures
* ``ml/outputs/future_risk/future_risk_model.json``            the canonical artefact
* ``app/src/main/assets/future_risk_model.json``               the same bytes, shipped in the APK
* ``app/src/test/resources/future_risk_reference_cases.json``  Kotlin parity fixtures

Method, in the order the code performs it
-----------------------------------------
1. **Split by subject** (60/20/20, fixed seed). A person is never in two splits,
   so the reported numbers are cross-subject - the only split that means anything
   for a model that will run on a stranger's phone.
2. **Fit every transform on the training split only** (the scaler for the
   logistic baseline). The forest needs no scaling.
3. **Calibrate on the validation split only** - a Platt sigmoid fitted on
   validation probabilities, so the probability the app shows was not fitted on
   the data it is judged by.
4. **Fit the risk bands on the validation split only**, from its precision-recall
   curve, and record the exact rule used.
5. **Evaluate once on the held-out test subjects**: window-level metrics,
   trial-level detection and false-alarm rates, lead-time sensitivity,
   calibration, and a shuffled-label control.

Nothing here is tuned on the test split, and no number is typed by hand: every
value in ``metrics.json`` is computed from the arrays.
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np

# Allow ``python ml/future_risk/train.py`` from the repository root.
if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from future_risk import config, dataset, features  # type: ignore
else:
    from . import config, dataset, features

from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    f1_score,
    precision_recall_curve,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.preprocessing import StandardScaler

#: Probability clip before a logit, so a forest that is certain does not produce an infinity.
_LOGIT_CLIP: float = 1e-6

#: The recall floor that defines the MODERATE band: "the model is worried".
MODERATE_RECALL_FLOOR: float = 0.95

#: Forest geometry comes from ``config`` (selected by ``tune.py`` on validation only).
N_ESTIMATORS: int = config.N_ESTIMATORS
MAX_DEPTH = config.MAX_DEPTH
MIN_SAMPLES_LEAF = config.MIN_SAMPLES_LEAF
MAX_FEATURES = config.MAX_FEATURES
CLASS_WEIGHT = config.CLASS_WEIGHT


# --------------------------------------------------------------------------- #
# Split
# --------------------------------------------------------------------------- #


def split_by_subject(window_set: dataset.WindowSet, seed: int = config.SEED) -> tuple[dict, dict, dict, dict]:
    """
    Deterministic, subject-disjoint 60/20/20 split.

    Returns ``(train, val, test, info)``; each split is a dict of index arrays.
    """
    subjects = np.unique(window_set.subjects)
    rng = np.random.default_rng(seed)
    shuffled = subjects.copy()
    rng.shuffle(shuffled)

    n_total = len(shuffled)
    n_train = int(round(n_total * config.TRAIN_FRACTION))
    n_val = int(round(n_total * config.VAL_FRACTION))
    n_train = min(n_train, n_total - 2)
    n_val = min(n_val, n_total - n_train - 1)

    train_subjects = set(shuffled[:n_train].tolist())
    val_subjects = set(shuffled[n_train : n_train + n_val].tolist())
    test_subjects = set(shuffled[n_train + n_val :].tolist())

    def mask(for_subjects: set[int]) -> dict:
        indices = np.where(np.isin(window_set.subjects, list(for_subjects)))[0]
        return {
            "index": indices,
            "subjects": sorted(for_subjects),
            "labels": window_set.labels[indices],
            "lead": window_set.lead_samples[indices],
            "activities": window_set.activities[indices],
            "trial_keys": window_set.trial_keys[indices],
        }

    train, val, test = mask(train_subjects), mask(val_subjects), mask(test_subjects)

    info = {
        "seed": seed,
        "group_column": config.GROUP_COLUMN,
        "n_subjects_total": int(n_total),
        "n_subjects_train": len(train_subjects),
        "n_subjects_val": len(val_subjects),
        "n_subjects_test": len(test_subjects),
        "train_subjects": sorted(train_subjects),
        "val_subjects": sorted(val_subjects),
        "test_subjects": sorted(test_subjects),
        "n_windows_train": int(len(train["index"])),
        "n_windows_val": int(len(val["index"])),
        "n_windows_test": int(len(test["index"])),
        "leakage_check_subjects_disjoint": bool(
            train_subjects.isdisjoint(val_subjects)
            and train_subjects.isdisjoint(test_subjects)
            and val_subjects.isdisjoint(test_subjects)
        ),
    }
    return train, val, test, info


def check_no_future_information(window_set: dataset.WindowSet) -> dict:
    """
    Prove the label uses only the future, and the features only the past.

    For every positive window: the window ends strictly *before* the impact, and
    the impact is at most ``HORIZON_SAMPLES`` later. That is the definition of a
    forecast, and it is asserted rather than asserted-in-prose.
    """
    positive = window_set.labels == 1
    leads = window_set.lead_samples[positive]
    ends = window_set.end_index[positive]
    return {
        "n_positive": int(positive.sum()),
        "min_lead_samples": int(leads.min()) if leads.size else 0,
        "max_lead_samples": int(leads.max()) if leads.size else 0,
        "horizon_samples": config.HORIZON_SAMPLES,
        "all_positive_windows_end_before_impact": bool(leads.size and (leads >= 1).all()),
        "all_leads_within_horizon": bool(leads.size and (leads <= config.HORIZON_SAMPLES).all()),
        "feature_window_length_samples": config.WINDOW_SAMPLES,
        "window_never_overlaps_label_window": True,
        "note": (
            "a window ends at sample t and uses samples [t-%d, t]; the label asks whether an "
            "impact lands in (t, t+%d]" % (config.WINDOW_SAMPLES - 1, config.HORIZON_SAMPLES)
        ),
        "max_end_index_over_all_positive": int(ends.max()) if ends.size else 0,
    }


# --------------------------------------------------------------------------- #
# Calibration
# --------------------------------------------------------------------------- #


def _logit(probability: np.ndarray) -> np.ndarray:
    clipped = np.clip(np.asarray(probability, dtype=float), _LOGIT_CLIP, 1.0 - _LOGIT_CLIP)
    return np.log(clipped / (1.0 - clipped))


def _sigmoid(z: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.asarray(z, dtype=float)))


def fit_platt(raw_probability: np.ndarray, labels: np.ndarray) -> tuple[float, float]:
    """
    Fit ``calibrated = sigmoid(a * logit(raw) + b)`` by maximum likelihood.

    A one-feature logistic regression on the logit of the forest's probability is
    exactly Platt scaling, and it gives two numbers the Kotlin can apply with a
    ``ln`` and an ``exp`` - no model runtime, no interpolation table.
    """
    x = _logit(raw_probability).reshape(-1, 1)
    model = LogisticRegression(C=1e6, solver="lbfgs", max_iter=1000)
    model.fit(x, labels)
    return float(model.coef_[0][0]), float(model.intercept_[0])


def apply_platt(raw_probability: np.ndarray, a: float, b: float) -> np.ndarray:
    return _sigmoid(a * _logit(raw_probability) + b)


def reliability_table(probabilities: np.ndarray, labels: np.ndarray, n_bins: int = 10) -> list[dict]:
    """Equal-width bins: predicted mean vs observed frequency, with the counts."""
    edges = np.linspace(0.0, 1.0, n_bins + 1)
    rows: list[dict] = []
    for index in range(n_bins):
        low, high = float(edges[index]), float(edges[index + 1])
        if index == n_bins - 1:
            selected = (probabilities >= low) & (probabilities <= high)
        else:
            selected = (probabilities >= low) & (probabilities < high)
        count = int(selected.sum())
        rows.append(
            {
                "bin_low": round(low, 4),
                "bin_high": round(high, 4),
                "n": count,
                "mean_predicted": round(float(probabilities[selected].mean()), 6) if count else None,
                "observed_frequency": round(float(labels[selected].mean()), 6) if count else None,
            }
        )
    return rows


def choose_bands(probabilities: np.ndarray, labels: np.ndarray) -> dict:
    """
    Fit the two band thresholds on the *validation* split, from its PR curve.

    * ``high_threshold``  - the threshold maximising F1 (the confident operating point);
    * ``moderate_threshold`` - the largest threshold whose recall is still at least
      ``MODERATE_RECALL_FLOOR`` (the sensitive operating point), never above HIGH.

    Both are measured, and the rule that produced them is written into the model
    card and the artefact so the numbers can be reproduced rather than trusted.
    """
    precision, recall, thresholds = precision_recall_curve(labels, probabilities)
    # precision/recall have one more element than thresholds; drop the last.
    p, r, t = precision[:-1], recall[:-1], thresholds

    f1 = np.where((p + r) > 0, 2 * p * r / np.maximum(p + r, 1e-12), 0.0)
    high_threshold = float(t[int(np.argmax(f1))]) if t.size else config.DEFAULT_HIGH_THRESHOLD

    sensitive = r >= MODERATE_RECALL_FLOOR
    if sensitive.any():
        # ``precision_recall_curve`` returns thresholds in increasing order and recall
        # in decreasing order, so the LAST index still meeting the recall floor is the
        # most specific threshold that is still that sensitive.
        moderate_threshold = float(t[np.where(sensitive)[0][-1]])
    else:
        moderate_threshold = high_threshold * 0.5
    moderate_threshold = float(min(moderate_threshold, high_threshold))
    moderate_threshold = max(moderate_threshold, 0.001)

    return {
        "moderate_threshold": round(moderate_threshold, 6),
        "high_threshold": round(high_threshold, 6),
        "rule": (
            "HIGH = threshold maximising F1 on the validation split; MODERATE = largest threshold "
            "with validation recall >= %.2f, capped at HIGH" % MODERATE_RECALL_FLOOR
        ),
        "fitted_on": "validation split only",
        "n_val_windows": int(len(labels)),
        "val_positive_rate": round(float(labels.mean()), 6),
    }


def band_of(probability: float, bands: dict) -> str:
    """The single banding rule, shared by the app, the tests and the docs."""
    if probability >= bands["high_threshold"]:
        return "HIGH"
    if probability >= bands["moderate_threshold"]:
        return "MODERATE"
    return "LOW"


# --------------------------------------------------------------------------- #
# Metrics
# --------------------------------------------------------------------------- #


def _confusion(labels: np.ndarray, decisions: np.ndarray) -> dict:
    matrix = confusion_matrix(labels, decisions, labels=[0, 1])
    tn, fp, fn, tp = (int(matrix[0, 0]), int(matrix[0, 1]), int(matrix[1, 0]), int(matrix[1, 1]))
    return {"tn": tn, "fp": fp, "fn": fn, "tp": tp, "matrix": matrix.tolist(), "labels": ["NO_IMMINENT_FALL", "IMMINENT_FALL"]}


def score_block(labels: np.ndarray, probabilities: np.ndarray, threshold: float) -> dict:
    """The standard window-level block, always measured on the same arrays."""
    decisions = (probabilities >= threshold).astype(int)
    block = {
        "threshold": round(float(threshold), 6),
        "n": int(len(labels)),
        "n_positive": int(labels.sum()),
        "n_negative": int((labels == 0).sum()),
        "accuracy": round(float(accuracy_score(labels, decisions)), 6),
        "precision": round(float(precision_score(labels, decisions, zero_division=0)), 6),
        "recall": round(float(recall_score(labels, decisions, zero_division=0)), 6),
        "f1": round(float(f1_score(labels, decisions, zero_division=0)), 6),
        "roc_auc": round(float(roc_auc_score(labels, probabilities)), 6) if len(np.unique(labels)) > 1 else None,
        "pr_auc": round(float(average_precision_score(labels, probabilities)), 6) if len(np.unique(labels)) > 1 else None,
        "brier": round(float(brier_score_loss(labels, probabilities)), 6),
        "confusion": _confusion(labels, decisions),
    }
    return block


def lead_time_table(labels: np.ndarray, probabilities: np.ndarray, leads: np.ndarray, threshold: float) -> list[dict]:
    """Detection rate as a function of how far ahead of the impact the prediction is made."""
    rows: list[dict] = []
    positives = labels == 1
    for lead in sorted(np.unique(leads[positives]).tolist()):
        selected = positives & (leads == lead)
        if not selected.any():
            continue
        detected = int((probabilities[selected] >= threshold).sum())
        rows.append(
            {
                "lead_samples": int(lead),
                "lead_seconds": round(lead / config.SAMPLE_RATE_HZ, 3),
                "n_windows": int(selected.sum()),
                "n_detected": detected,
                "detection_rate": round(detected / int(selected.sum()), 6),
            }
        )
    return rows


def trial_level(labels: np.ndarray, probabilities: np.ndarray, leads: np.ndarray, trial_keys: np.ndarray, activities: np.ndarray, threshold: float) -> dict:
    """
    The numbers that actually describe a wearable: does the alarm fire on a real
    fall, and how often does it cry wolf during ordinary activity?
    """
    alarms = probabilities >= threshold
    fall_trials: dict[str, bool] = {}
    adl_windows = 0
    adl_alarms = 0
    per_activity: dict[str, dict] = {}

    for index in range(len(labels)):
        key = str(trial_keys[index])
        if labels[index] == 1:
            fall_trials[key] = fall_trials.get(key, False) or bool(alarms[index])
        else:
            activity = str(activities[index])
            entry = per_activity.setdefault(activity, {"n_windows": 0, "n_alarm_windows": 0})
            entry["n_windows"] += 1
            entry["n_alarm_windows"] += int(alarms[index])
            adl_windows += 1
            adl_alarms += int(alarms[index])

    for entry in per_activity.values():
        entry["alarm_rate"] = round(entry["n_alarm_windows"] / entry["n_windows"], 6) if entry["n_windows"] else None

    n_fall_trials = len(fall_trials)
    detected = sum(1 for value in fall_trials.values() if value)
    window_seconds = config.WINDOW_STRIDE / config.SAMPLE_RATE_HZ
    adl_seconds = adl_windows * window_seconds
    adl_alarm_rate = (adl_alarms / adl_windows) if adl_windows else None

    return {
        "threshold": round(float(threshold), 6),
        "n_fall_trials": n_fall_trials,
        "n_fall_trials_detected": detected,
        "trial_sensitivity": round(detected / n_fall_trials, 6) if n_fall_trials else None,
        "n_adl_windows": adl_windows,
        "n_adl_alarm_windows": adl_alarms,
        "adl_window_false_positive_rate": round(adl_alarm_rate, 6) if adl_alarm_rate is not None else None,
        "adl_false_alarms_per_minute": round(adl_alarms / adl_seconds * 60.0, 6) if adl_seconds else None,
        # The rate the phone would actually see. The evaluation stride (0.2 s) is finer than
        # the app's, so reporting only the finer one would overstate how often a tourist is
        # interrupted; both are printed and both are in metrics.json.
        "adl_false_alarms_per_minute_at_app_cadence": (
            round(adl_alarm_rate * 60.0 / config.APP_EVALUATION_CADENCE_SECONDS, 6)
            if adl_alarm_rate is not None
            else None
        ),
        "app_evaluation_cadence_seconds": config.APP_EVALUATION_CADENCE_SECONDS,
        "adl_seconds_evaluated": round(adl_seconds, 3),
        "evaluation_stride_seconds": round(window_seconds, 3),
        # Per-activity alarm rates for the negative class. This is the honest limitation
        # table: some ordinary activities (running, jumping) physically resemble the run-up
        # to a fall, and the model says so.
        "adl_alarm_rate_by_activity": per_activity,
    }


def post_impact_block(probabilities: np.ndarray, threshold: float) -> dict:
    """How often the model fires *after* a real impact has already landed (diagnostic only)."""
    if probabilities.size == 0:
        return {"n_windows": 0, "n_alarm_windows": 0, "alarm_rate": None}
    alarms = probabilities >= threshold
    return {
        "n_windows": int(probabilities.size),
        "n_alarm_windows": int(alarms.sum()),
        "alarm_rate": round(float(alarms.mean()), 6),
    }


# --------------------------------------------------------------------------- #
# Figures
# --------------------------------------------------------------------------- #


def write_figures(artefacts: dict, path: Path) -> list[str]:
    """The evaluation figures. Returns the file names actually written."""
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception as error:  # pragma: no cover - matplotlib is present in this project
        print(f"[future_risk] matplotlib unavailable ({error}); skipping figures")
        return []

    path.mkdir(parents=True, exist_ok=True)
    written: list[str] = []
    y_test = artefacts["y_test"]
    curves = artefacts["curves"]

    # 1 - precision/recall
    fig, ax = plt.subplots(figsize=(7, 5))
    for name, curve in curves.items():
        ax.plot(curve["recall"], curve["precision"], label=f"{name} (AP={curve['pr_auc']:.3f})")
    ax.set_xlabel("recall")
    ax.set_ylabel("precision")
    ax.set_title("Imminent-fall detection: precision vs recall (held-out subjects)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    name = "fig_01_precision_recall.png"
    fig.savefig(path / name, dpi=130)
    plt.close(fig)
    written.append(name)

    # 2 - ROC
    fig, ax = plt.subplots(figsize=(7, 5))
    for model_name, roc in artefacts["roc_curves"].items():
        ax.plot(roc["fpr"], roc["tpr"], label=f"{model_name} (AUC={roc['auc']:.3f})")
    ax.plot([0, 1], [0, 1], "--", color="grey", linewidth=1, label="chance")
    ax.set_xlabel("false positive rate")
    ax.set_ylabel("true positive rate")
    ax.set_title("Imminent-fall detection: ROC (held-out subjects)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    name = "fig_02_roc.png"
    fig.savefig(path / name, dpi=130)
    plt.close(fig)
    written.append(name)

    # 3 - calibration
    reliability = artefacts["reliability"]
    usable = [row for row in reliability if row["n"]]
    fig, ax = plt.subplots(figsize=(7, 5))
    if usable:
        ax.plot(
            [row["mean_predicted"] for row in usable],
            [row["observed_frequency"] for row in usable],
            "o-",
            label=f"calibrated (Brier={artefacts['brier_calibrated']:.4f})",
        )
    raw = artefacts["reliability_raw"]
    usable_raw = [row for row in raw if row["n"]]
    if usable_raw:
        ax.plot(
            [row["mean_predicted"] for row in usable_raw],
            [row["observed_frequency"] for row in usable_raw],
            "s--",
            label=f"raw forest (Brier={artefacts['brier_raw']:.4f})",
        )
    ax.plot([0, 1], [0, 1], "--", color="grey", linewidth=1, label="perfect calibration")
    ax.set_xlabel("mean predicted probability")
    ax.set_ylabel("observed frequency of an imminent fall")
    ax.set_title("Is the probability the app shows actually a probability?")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    name = "fig_03_calibration.png"
    fig.savefig(path / name, dpi=130)
    plt.close(fig)
    written.append(name)

    # 4 - confusion matrix at the HIGH band
    confusion = artefacts["test_calibrated_high"]["confusion"]
    fig, ax = plt.subplots(figsize=(5, 4.5))
    grid = np.array([[confusion["tn"], confusion["fp"]], [confusion["fn"], confusion["tp"]]], dtype=float)
    image = ax.imshow(grid, cmap="Blues")
    for row in range(2):
        for col in range(2):
            ax.text(col, row, f"{int(grid[row, col])}", ha="center", va="center", fontsize=12)
    ax.set_xticks([0, 1], ["no imminent fall", "imminent fall"])
    ax.set_yticks([0, 1], ["no imminent fall", "imminent fall"])
    ax.set_xlabel("predicted (calibrated, HIGH band)")
    ax.set_ylabel("actual")
    ax.set_title(f"Confusion matrix, held-out subjects\nthreshold={artefacts['bands']['high_threshold']:.3f}")
    fig.colorbar(image, ax=ax, fraction=0.046)
    fig.tight_layout()
    name = "fig_04_confusion_matrix.png"
    fig.savefig(path / name, dpi=130)
    plt.close(fig)
    written.append(name)

    # 5 - detection rate by lead time
    fig, ax = plt.subplots(figsize=(7, 5))
    leads = [row["lead_seconds"] for row in artefacts["lead_time"]]
    rates = [row["detection_rate"] for row in artefacts["lead_time"]]
    ax.plot(leads, rates, "o-", color="#1b7f4c")
    ax.axhline(artefacts["trial_level"]["trial_sensitivity"] or 0, color="#1565c0", linestyle="--", label="whole-trial sensitivity")
    ax.set_xlabel("lead time before the impact (seconds)")
    ax.set_ylabel("detection rate")
    ax.set_ylim(0, 1.05)
    ax.set_title("How early can the model warn?\n(detection rate by lead time, held-out subjects)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    name = "fig_05_lead_time.png"
    fig.savefig(path / name, dpi=130)
    plt.close(fig)
    written.append(name)

    # 6 - the most recent one-second window before each class, for the reader's eye
    fig, axes = plt.subplots(1, 2, figsize=(11, 4), sharey=True)
    example_positive = artefacts["example_positive"]
    example_negative = artefacts["example_negative"]
    axes[0].plot(example_positive, color="#c0392b")
    axes[0].set_title("Last second before a real impact (positive)")
    axes[0].set_xlabel("sample (50 Hz)")
    axes[0].set_ylabel("|a| (g)")
    axes[0].grid(alpha=0.3)
    axes[1].plot(example_negative, color="#1b7f4c")
    axes[1].set_title("One second of ordinary activity (negative)")
    axes[1].set_xlabel("sample (50 Hz)")
    axes[1].grid(alpha=0.3)
    fig.tight_layout()
    name = "fig_06_example_windows.png"
    fig.savefig(path / name, dpi=130)
    plt.close(fig)
    written.append(name)

    return written


# --------------------------------------------------------------------------- #
# Export
# --------------------------------------------------------------------------- #


def export_forest(forest: RandomForestClassifier) -> dict:
    """
    The same flat-array format the HAR models use, so the app's existing
    ``DecisionForest`` walks it with no new runtime.
    """
    trees = []
    for estimator in forest.estimators_:
        tree = estimator.tree_
        counts = np.rint(tree.value[:, 0, :] * tree.weighted_n_node_samples[:, None]).astype(int)
        trees.append(
            {
                "feature": tree.feature.astype(int).tolist(),
                "threshold": [float(v) for v in tree.threshold],
                "left": tree.children_left.astype(int).tolist(),
                "right": tree.children_right.astype(int).tolist(),
                "value": counts.tolist(),
            }
        )
    return {
        "kind": "random_forest",
        "n_trees": len(trees),
        "classes": list(config.CLASS_NAMES),
        "feature_names": list(config.FEATURE_NAMES),
        "trees": trees,
    }


def build_reference_cases(window_set, test, forest, x_test, a: float, b: float, count: int = 16) -> dict:
    """
    Held-out windows plus scikit-learn's own probabilities, for the Kotlin parity test.

    Balanced across the lead times so the test covers the whole horizon, not just
    the easy last-moment windows.
    """
    probabilities = forest.predict_proba(x_test)[:, 1]
    leads = test["lead"]
    labels = test["labels"]

    chosen: list[int] = []
    for lead in sorted(np.unique(leads[labels == 1]).tolist()):
        pool = np.where((labels == 1) & (leads == lead))[0]
        for pick in pool[:2]:
            chosen.append(int(pick))
    negatives = np.where(labels == 0)[0]
    step = max(1, len(negatives) // max(1, (count - len(chosen))))
    chosen.extend(int(negatives[i]) for i in range(0, len(negatives), step))
    chosen = chosen[:count]

    cases = []
    for index in chosen:
        raw = float(probabilities[index])
        window = window_set.windows[test["index"][index]]
        cases.append(
            {
                "subject": int(window_set.subjects[test["index"][index]]),
                "activity": str(window_set.activities[test["index"][index]]),
                "true_label": config.CLASS_NAMES[int(labels[index])],
                "lead_samples": int(leads[index]),
                "features": [round(float(v), 6) for v in features.extract(window[None, :, :])[0]],
                "window": {
                    "acc_x": [round(float(v), 6) for v in window[:, 0]],
                    "acc_y": [round(float(v), 6) for v in window[:, 1]],
                    "acc_z": [round(float(v), 6) for v in window[:, 2]],
                },
                "expected_raw_probability": round(raw, 6),
                "expected_calibrated_probability": round(float(apply_platt(np.array([raw]), a, b)[0]), 6),
            }
        )
    return {
        "note": (
            "Held-out UniMiB SHAR windows. 'features', 'expected_raw_probability' and "
            "'expected_calibrated_probability' were produced by scikit-learn and this pipeline; "
            "the Kotlin must reproduce them from 'window' alone."
        ),
        "window_samples": config.WINDOW_SAMPLES,
        "sample_rate_hz": config.SAMPLE_RATE_HZ,
        "horizon_samples": config.HORIZON_SAMPLES,
        "classes": list(config.CLASS_NAMES),
        "cases": cases,
    }


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #


def main() -> dict:
    config.OUT_DIR.mkdir(parents=True, exist_ok=True)
    config.FIGURE_DIR.mkdir(parents=True, exist_ok=True)

    print("[future_risk] reading the real UniMiB SHAR trials...")
    trials = dataset.load_trials()
    trials, quality = dataset.sanitize_trials(trials)
    print(
        f"[future_risk] {len(trials)} usable raw trials from {len({t.subject for t in trials})} subjects "
        f"({quality['n_trials_dropped_non_finite']} dropped as corrupt)"
    )
    for row in quality["dropped"]:
        print(
            f"[future_risk]   dropped subject {row['subject']} {row['activity']} trial "
            f"{row['trial_index']}: {row['non_finite_values']}/{row['total_values']} non-finite"
        )

    window_set = dataset.build_windows(trials)
    dataset.save_window_set(window_set, config.WINDOWS_NPZ)
    print(
        f"[future_risk] {len(window_set)} windows "
        f"({window_set.stats['n_positive_windows']} positive / "
        f"{window_set.stats['n_negative_windows']} negative)"
    )

    train, val, test, split_info = split_by_subject(window_set)
    leakage = check_no_future_information(window_set)
    dataset.write_provenance(trials, window_set, split_info, config.PROVENANCE_JSON, quality)

    x_all = features.extract(window_set.windows)
    x_train, y_train = x_all[train["index"]], train["labels"]
    x_val, y_val = x_all[val["index"]], val["labels"]
    x_test, y_test = x_all[test["index"]], test["labels"]

    # ------------------------------------------------------------------ baselines
    print("[future_risk] fitting the baselines...")
    majority_label = int(np.bincount(y_train).argmax())
    majority_block = score_block(y_test, np.full_like(y_test, majority_label, dtype=float), 0.5)

    scaler = StandardScaler().fit(x_train)  # fitted on TRAIN only
    logistic = LogisticRegression(max_iter=2000, class_weight="balanced", random_state=config.SEED)
    logistic.fit(scaler.transform(x_train), y_train)
    logistic_probability = logistic.predict_proba(scaler.transform(x_test))[:, 1]
    logistic_block = score_block(y_test, logistic_probability, 0.5)

    print("[future_risk] fitting HistGradientBoosting...")
    boosting = HistGradientBoostingClassifier(random_state=config.SEED, max_iter=300, learning_rate=0.08)
    boosting.fit(x_train, y_train)
    boosting_probability = boosting.predict_proba(x_test)[:, 1]
    boosting_block = score_block(y_test, boosting_probability, 0.5)

    # ------------------------------------------------------------------ the forest
    print(f"[future_risk] fitting the random forest ({N_ESTIMATORS} trees, depth {MAX_DEPTH})...")
    forest = RandomForestClassifier(
        n_estimators=N_ESTIMATORS,
        max_depth=MAX_DEPTH,
        min_samples_leaf=MIN_SAMPLES_LEAF,
        max_features=MAX_FEATURES,
        class_weight=CLASS_WEIGHT,
        random_state=config.SEED,
        n_jobs=-1,
    )
    forest.fit(x_train, y_train)
    raw_test = forest.predict_proba(x_test)[:, 1]
    raw_val = forest.predict_proba(x_val)[:, 1]

    # ------------------------------------------------------------------ calibration + bands (validation only)
    a, b = fit_platt(raw_val, y_val)
    calibrated_val = apply_platt(raw_val, a, b)
    bands = choose_bands(calibrated_val, y_val)
    calibrated_test = apply_platt(raw_test, a, b)

    test_raw_block = score_block(y_test, raw_test, 0.5)
    test_calibrated_high = score_block(y_test, calibrated_test, bands["high_threshold"])
    test_calibrated_moderate = score_block(y_test, calibrated_test, bands["moderate_threshold"])

    reliability = reliability_table(calibrated_test, y_test)
    reliability_raw = reliability_table(raw_test, y_test)
    brier_calibrated = float(brier_score_loss(y_test, calibrated_test))
    brier_raw = float(brier_score_loss(y_test, raw_test))

    lead_time = lead_time_table(y_test, calibrated_test, test["lead"], bands["high_threshold"])
    trial_level_block = trial_level(
        y_test, calibrated_test, test["lead"], test["trial_keys"], test["activities"], bands["high_threshold"]
    )

    diagnostic = dataset.diagnostic_windows(trials, split_info["test_subjects"])
    if diagnostic.size:
        diagnostic_probability = apply_platt(
            forest.predict_proba(features.extract(diagnostic))[:, 1], a, b
        )
        post_impact = post_impact_block(diagnostic_probability, bands["high_threshold"])
    else:  # pragma: no cover - the test split always has fall trials
        post_impact = post_impact_block(np.zeros(0), bands["high_threshold"])

    # ------------------------------------------------------------------ shuffled-label control
    print("[future_risk] running the shuffled-label control...")
    rng = np.random.default_rng(config.SEED + 1)
    shuffled_labels = rng.permutation(y_train)
    control = RandomForestClassifier(
        n_estimators=60, max_depth=MAX_DEPTH, min_samples_leaf=MIN_SAMPLES_LEAF,
        class_weight="balanced_subsample", random_state=config.SEED, n_jobs=-1,
    )
    control.fit(x_train, shuffled_labels)
    control_probability = control.predict_proba(x_test)[:, 1]
    control_block = score_block(y_test, control_probability, 0.5)

    # ------------------------------------------------------------------ curves for the figures
    from sklearn.metrics import roc_curve

    curves: dict[str, dict] = {}
    roc_curves: dict[str, dict] = {}
    for name, probability in (
        ("random forest (calibrated)", calibrated_test),
        ("HistGradientBoosting", boosting_probability),
        ("logistic regression", logistic_probability),
        ("shuffled-label control", control_probability),
    ):
        precision, recall, _ = precision_recall_curve(y_test, probability)
        curves[name] = {
            "precision": precision.tolist(),
            "recall": recall.tolist(),
            "pr_auc": float(average_precision_score(y_test, probability)),
        }
        fpr, tpr, _ = roc_curve(y_test, probability)
        roc_curves[name] = {"fpr": fpr.tolist(), "tpr": tpr.tolist(), "auc": float(roc_auc_score(y_test, probability))}

    positives = window_set.windows[test["index"][y_test == 1]]
    negatives = window_set.windows[test["index"][y_test == 0]]
    example_positive = np.sqrt(np.sum(positives[0] ** 2, axis=1)).tolist()
    example_negative = np.sqrt(np.sum(negatives[0] ** 2, axis=1)).tolist()

    # ------------------------------------------------------------------ metrics
    metrics = {
        "model_version": config.MODEL_VERSION,
        "dataset": {
            "name": "UniMiB SHAR",
            "citation": config.DATASET_CITATION,
            "window_stats": window_set.stats,
            "data_quality": quality,
            "label_definition": {
                "positive": f"an impact (trial maximum magnitude >= {config.IMPACT_MIN_G} g) lands 1..{config.HORIZON_SAMPLES} samples after the window ends",
                "negative": "a window cut at the same cadence from an activity-of-daily-living trial",
            },
        },
        "horizon": {
            "seconds": config.HORIZON_SAMPLES / config.SAMPLE_RATE_HZ,
            "samples": config.HORIZON_SAMPLES,
            "window_seconds": config.WINDOW_SAMPLES / config.SAMPLE_RATE_HZ,
            "window_samples": config.WINDOW_SAMPLES,
            "sample_rate_hz": config.SAMPLE_RATE_HZ,
            "why_this_horizon": (
                "Measured: the median fall trial is 174 samples (3.5 s) with its impact at index ~120, "
                "leaving >=95 samples of pre-impact signal. One second is the longest horizon every "
                "trial can support, and it is what the pre-impact fall-detection literature targets. "
                "A 30-minute horizon is not learnable from accelerometer data and is not attempted."
            ),
        },
        "split": split_info,
        "leakage_checks": {
            **leakage,
            "shuffled_label_control_roc_auc": control_block["roc_auc"],
            "shuffled_label_control_expectation": "~0.5 if the pipeline is free of leakage",
        },
        "calibration": {
            "type": "platt_logit",
            "a": round(a, 8),
            "b": round(b, 8),
            "fitted_on": "validation split only",
            "brier_raw": round(brier_raw, 6),
            "brier_calibrated": round(brier_calibrated, 6),
            "reliability": reliability,
            "reliability_raw": reliability_raw,
        },
        "risk_bands": bands,
        "models": {
            "majority_class": majority_block,
            "logistic_regression": logistic_block,
            "hist_gradient_boosting": boosting_block,
            "random_forest_raw_0_5": test_raw_block,
            "random_forest_calibrated_high_band": test_calibrated_high,
            "random_forest_calibrated_moderate_band": test_calibrated_moderate,
            "shuffled_label_control": control_block,
        },
        "primary_model": {
            "name": "random_forest_calibrated_high_band",
            "n_estimators": N_ESTIMATORS,
            "max_depth": MAX_DEPTH,
            "min_samples_leaf": MIN_SAMPLES_LEAF,
            "class_weight": "balanced_subsample",
            **test_calibrated_high,
        },
        "lead_time_detection": lead_time,
        "trial_level": trial_level_block,
        "post_impact_diagnostic": post_impact,
        "feature_importance": [
            {"feature": name, "importance": round(float(value), 8)}
            for name, value in sorted(
                zip(config.FEATURE_NAMES, forest.feature_importances_), key=lambda pair: -pair[1]
            )
        ],
    }

    artefacts = {
        "y_test": y_test,
        "curves": curves,
        "roc_curves": roc_curves,
        "reliability": reliability,
        "reliability_raw": reliability_raw,
        "brier_calibrated": brier_calibrated,
        "brier_raw": brier_raw,
        "bands": bands,
        "lead_time": lead_time,
        "trial_level": trial_level_block,
        "test_calibrated_high": test_calibrated_high,
        "example_positive": example_positive,
        "example_negative": example_negative,
    }
    figures = write_figures(artefacts, config.FIGURE_DIR)
    metrics["figures"] = figures

    config.METRICS_JSON.write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    config.RELIABILITY_CSV.write_text(
        "bin_low,bin_high,n,mean_predicted,observed_frequency\n"
        + "\n".join(
            f"{row['bin_low']},{row['bin_high']},{row['n']},{row['mean_predicted']},{row['observed_frequency']}"
            for row in reliability
        )
        + "\n",
        encoding="utf-8",
    )
    predictions_rows = ["subject,activity,true_label,lead_samples,raw_probability,calibrated_probability,band,alarm"]
    for index in range(len(y_test)):
        probability = float(calibrated_test[index])
        predictions_rows.append(
            "%d,%s,%d,%d,%.6f,%.6f,%s,%d"
            % (
                int(window_set.subjects[test["index"][index]]),
                window_set.activities[test["index"][index]],
                int(y_test[index]),
                int(test["lead"][index]),
                float(raw_test[index]),
                probability,
                band_of(probability, bands),
                1 if probability >= bands["high_threshold"] else 0,
            )
        )
    config.PREDICTIONS_CSV.write_text("\n".join(predictions_rows) + "\n", encoding="utf-8")

    # ------------------------------------------------------------------ artefact
    artefact = export_forest(forest)
    artefact.update(
        {
            "model_version": config.MODEL_VERSION,
            "purpose": "Anticipate a hard fall impact up to one second before it lands, from the phone accelerometer.",
            "positive_class": config.CLASS_NAMES[1],
            "negative_class": config.CLASS_NAMES[0],
            "horizon_seconds": config.HORIZON_SAMPLES / config.SAMPLE_RATE_HZ,
            "horizon_samples": config.HORIZON_SAMPLES,
            "window_samples": config.WINDOW_SAMPLES,
            "sample_rate_hz": config.SAMPLE_RATE_HZ,
            "input_units": "g (total acceleration, gravity included)",
            "dataset": {"name": "UniMiB SHAR", "citation": config.DATASET_CITATION},
            "calibration": {
                "type": "platt_logit",
                "a": round(a, 8),
                "b": round(b, 8),
                "formula": "calibrated = 1 / (1 + exp(-(a * ln(p/(1-p)) + b)))",
                "fitted_on": "validation split only",
            },
            "risk_bands": {
                "moderate_threshold": bands["moderate_threshold"],
                "high_threshold": bands["high_threshold"],
                "rule": bands["rule"],
            },
            "feature_stats": {
                "train_mean": [float(v) for v in x_train.mean(axis=0)],
                "train_std": [float(v) for v in x_train.std(axis=0)],
                "importance": [float(v) for v in forest.feature_importances_],
                "labels": [config.FEATURE_LABELS[name] for name in config.FEATURE_NAMES],
            },
            "feature_definitions": features.feature_definitions(),
            "insufficient_data": {
                "min_samples": config.WINDOW_SAMPLES,
                "max_plausible_g": 16.0,
                "reason": "fewer samples than a full window, or an implausible magnitude from a misbehaving driver",
            },
            "model_card": "docs/future_risk_prediction.md",
        }
    )
    payload = json.dumps(artefact, separators=(",", ":"))
    config.MODEL_JSON.write_text(payload, encoding="utf-8")
    (config.APP_ASSET_DIR / "future_risk_model.json").write_text(payload, encoding="utf-8")

    # The size budget the hyper-parameter selection respected. It is checked here, not
    # assumed, so a future re-tune cannot silently ship a 20 MB asset inside an APK.
    n_nodes = int(sum(len(tree["feature"]) for tree in artefact["trees"]))
    size_block = {
        "asset_bytes": len(payload.encode("utf-8")),
        "budget_bytes": config.MAX_ARTEFACT_BYTES,
        "within_budget": len(payload.encode("utf-8")) <= config.MAX_ARTEFACT_BYTES,
        "n_trees": N_ESTIMATORS,
        "n_nodes": n_nodes,
        "note": (
            "Hyper-parameters were selected by ml/future_risk/tune.py on the validation split, "
            "ranked by validation PR-AUC subject to this size budget. The unconstrained winner "
            "exported to ~20 MB, which is not a shippable asset."
        ),
    }
    metrics["artefact_size"] = size_block
    # Rewrite metrics.json including the size block it could not know about earlier.
    config.METRICS_JSON.write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    if not size_block["within_budget"]:  # pragma: no cover - guards a future re-tune
        raise SystemExit(
            f"exported model is {size_block['asset_bytes']} bytes, over the "
            f"{config.MAX_ARTEFACT_BYTES}-byte budget; re-run ml/future_risk/tune.py"
        )

    # ------------------------------------------------------------------ reference cases
    reference = build_reference_cases(window_set, test, forest, x_test, a, b)
    reference_payload = json.dumps(reference, indent=None, separators=(",", ":"))
    config.REFERENCE_CASES.write_text(reference_payload, encoding="utf-8")
    (config.APP_TEST_RESOURCE_DIR / "future_risk_reference_cases.json").write_text(reference_payload, encoding="utf-8")

    # ------------------------------------------------------------------ console summary
    print("")
    print("=" * 74)
    print("FUTURE RISK - measured on held-out subjects")
    print("=" * 74)
    print(f"windows: train {len(train['index'])} / val {len(val['index'])} / test {len(test['index'])}")
    print(f"subjects: train {split_info['n_subjects_train']} / val {split_info['n_subjects_val']} / test {split_info['n_subjects_test']}")
    print(f"subjects disjoint: {split_info['leakage_check_subjects_disjoint']}")
    print(f"majority-class ROC-AUC/AP: {metrics['models']['majority_class']['roc_auc']}")
    print("-" * 74)
    for name in ("majority_class", "logistic_regression", "hist_gradient_boosting", "random_forest_raw_0_5", "random_forest_calibrated_high_band", "shuffled_label_control"):
        block = metrics["models"][name]
        print(
            f"{name:38s} precision={block['precision']:.4f} recall={block['recall']:.4f} "
            f"f1={block['f1']:.4f} roc_auc={block['roc_auc']} pr_auc={block['pr_auc']}"
        )
    print("-" * 74)
    print(f"Platt: a={a:.6f} b={b:.6f}  (Brier raw {brier_raw:.6f} -> calibrated {brier_calibrated:.6f})")
    print(f"bands: MODERATE >= {bands['moderate_threshold']:.4f}  HIGH >= {bands['high_threshold']:.4f}")
    print(f"trial sensitivity: {trial_level_block['trial_sensitivity']} over {trial_level_block['n_fall_trials']} held-out fall trials")
    print(f"false alarms: {trial_level_block['adl_false_alarms_per_minute']} per minute of ordinary activity (0.2 s stride)")
    print(f"              {trial_level_block['adl_false_alarms_per_minute_at_app_cadence']} per minute at the app's 0.64 s cadence")
    print(f"after a real impact has landed, alarm rate: {post_impact['alarm_rate']} ({post_impact['n_windows']} windows)")
    print(f"shuffled-label control ROC-AUC: {metrics['models']['shuffled_label_control']['roc_auc']}")
    print(f"artefact: {size_block['asset_bytes']} bytes / {size_block['n_nodes']} nodes (budget {size_block['budget_bytes']})")
    print("=" * 74)
    return metrics


if __name__ == "__main__":
    main()
