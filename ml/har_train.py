"""Stage 2 - train, evaluate and export the activity-recognition models (real UCI HAR data).

Two models are trained on the *same* subject-disjoint split, on purpose:

1. **Report model** - a Random Forest on all **561** precomputed features. This is the number that
   goes in the report and the viva: it is what the dataset is normally benchmarked with.

2. **Deployable model** - a forest on **24 hand-made features** computed from the raw 128-sample
   inertial window (total acceleration and gyroscope only - the two channels an Android phone
   definitely exposes). It is exported to JSON and executed **on the phone** in Kotlin. The point of
   showing both is the honest trade-off: how much accuracy is really given up for a model that fits
   in an asset file and runs without a server. That comparison is plotted.

Nothing here is a toy: the split is the dataset's own and it is subject-disjoint (verified in stage 1),
so no subject appears in both train and test, and every figure in the report comes from this run.

Outputs (``ml/outputs/har/train/``)
----------------------------------
* ``har_train_report.json``        - accuracies, F1s, split sizes, chosen model, feature counting.
* ``har_model_metrics.csv``        - one row per model, for the report table.
* ``har_activity_model.json``      - the deployable forest, flat-array format (shipped in the APK).
* ``har_activity_model_spec.json`` - feature order + definitions + the exact JSON contract.
* ``har_reference_cases.json``     - real test windows + scikit-learn's own probabilities, used by the
                                     Kotlin unit test to prove the on-device inference matches.
* ``fig_har_*.png``                - class balance, both confusion matrices, importances, the
                                     accuracy-vs-features trade-off.
"""

from __future__ import annotations

import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
)

from har_preprocess import ACTIVITY_LABELS, load_raw, load_split

ML_DIR = Path(__file__).resolve().parent
OUT_DIR = ML_DIR / "outputs" / "har" / "train"
CLEAN_DIR = ML_DIR / "outputs" / "har" / "clean"

CLASS_ORDER = [ACTIVITY_LABELS[c] for c in sorted(ACTIVITY_LABELS)]
CLASS_CODES = sorted(ACTIVITY_LABELS)

# --- the 24 deployable features ---------------------------------------------------------------
# Deliberately time-domain only: every one of these is a handful of arithmetic operations on a
# 128-sample window, so the identical numbers can be produced on the phone. No FFT, no filter bank,
# nothing that would need a DSP library on the device.
FEATURE_SPECS: list[dict[str, str]] = [
    {"name": "acc_mag_mean", "formula": "mean(sqrt(ax^2+ay^2+az^2))", "unit": "g"},
    {"name": "acc_mag_std", "formula": "std(sqrt(ax^2+ay^2+az^2))", "unit": "g"},
    {"name": "acc_mag_min", "formula": "min(magnitude)", "unit": "g"},
    {"name": "acc_mag_max", "formula": "max(magnitude)", "unit": "g"},
    {"name": "acc_mag_range", "formula": "max(magnitude) - min(magnitude)", "unit": "g"},
    {"name": "acc_mag_energy", "formula": "mean(magnitude^2)", "unit": "g^2"},
    {"name": "acc_mag_peak_jerk", "formula": "max(|diff(magnitude)|)", "unit": "g/sample"},
    {"name": "acc_mag_zcr", "formula": "zero crossings of (magnitude - mean)", "unit": "count"},
    # The per-axis MEANS are the gravity vector. They are what separates lying (phone flat, one axis
    # near 1 g) from standing and sitting (phone in a pocket or hand, gravity spread across axes).
    # Without them the model sits around 0.76; with them it gains roughly ten points - a good example
    # of feature engineering mattering more than model capacity.
    {"name": "acc_x_mean", "formula": "mean(ax)", "unit": "g"},
    {"name": "acc_y_mean", "formula": "mean(ay)", "unit": "g"},
    {"name": "acc_z_mean", "formula": "mean(az)", "unit": "g"},
    {"name": "acc_x_std", "formula": "std(ax)", "unit": "g"},
    {"name": "acc_y_std", "formula": "std(ay)", "unit": "g"},
    {"name": "acc_z_std", "formula": "std(az)", "unit": "g"},
    {"name": "acc_xy_corr", "formula": "corr(ax, ay)", "unit": "-"},
    {"name": "acc_xz_corr", "formula": "corr(ax, az)", "unit": "-"},
    {"name": "acc_yz_corr", "formula": "corr(ay, az)", "unit": "-"},
    {"name": "gyro_mag_mean", "formula": "mean(sqrt(gx^2+gy^2+gz^2))", "unit": "rad/s"},
    {"name": "gyro_mag_std", "formula": "std(gyroscope magnitude)", "unit": "rad/s"},
    {"name": "gyro_mag_max", "formula": "max(gyroscope magnitude)", "unit": "rad/s"},
    {"name": "gyro_mag_energy", "formula": "mean(gyroscope magnitude^2)", "unit": "(rad/s)^2"},
    {"name": "gyro_x_std", "formula": "std(gx)", "unit": "rad/s"},
    {"name": "gyro_y_std", "formula": "std(gy)", "unit": "rad/s"},
    {"name": "gyro_z_std", "formula": "std(gz)", "unit": "rad/s"},
]

FEATURE_NAMES = [spec["name"] for spec in FEATURE_SPECS]


def _corr(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Row-wise Pearson correlation; 0 where either axis is constant (never NaN into the model)."""
    a = a - a.mean(axis=1, keepdims=True)
    b = b - b.mean(axis=1, keepdims=True)
    denom = np.sqrt((a * a).sum(axis=1) * (b * b).sum(axis=1))
    num = (a * b).sum(axis=1)
    return np.divide(num, denom, out=np.zeros_like(num), where=denom > 1e-12)


def build_features(raw: dict[str, np.ndarray]) -> np.ndarray:
    """Compute the 24 deployable features for every window. Mirrors ``MotionFeatures.kt`` exactly."""
    ax, ay, az = raw["total_acc_x"], raw["total_acc_y"], raw["total_acc_z"]
    gx, gy, gz = raw["body_gyro_x"], raw["body_gyro_y"], raw["body_gyro_z"]

    acc_mag = np.sqrt(ax * ax + ay * ay + az * az)
    gyro_mag = np.sqrt(gx * gx + gy * gy + gz * gz)
    centred = acc_mag - acc_mag.mean(axis=1, keepdims=True)

    zcr = np.count_nonzero(np.diff(np.signbit(centred), axis=1), axis=1).astype(np.float32)
    jerk = np.abs(np.diff(acc_mag, axis=1)).max(axis=1)

    return np.column_stack([
        acc_mag.mean(axis=1),
        acc_mag.std(axis=1),
        acc_mag.min(axis=1),
        acc_mag.max(axis=1),
        acc_mag.max(axis=1) - acc_mag.min(axis=1),
        (acc_mag ** 2).mean(axis=1),
        jerk,
        zcr,
        ax.mean(axis=1),
        ay.mean(axis=1),
        az.mean(axis=1),
        ax.std(axis=1),
        ay.std(axis=1),
        az.std(axis=1),
        _corr(ax, ay),
        _corr(ax, az),
        _corr(ay, az),
        gyro_mag.mean(axis=1),
        gyro_mag.std(axis=1),
        gyro_mag.max(axis=1),
        (gyro_mag ** 2).mean(axis=1),
        gx.std(axis=1),
        gy.std(axis=1),
        gz.std(axis=1),
    ]).astype(np.float32)


def plot_class_balance() -> str:
    meta = json.loads((CLEAN_DIR / "har_dataset_meta.json").read_text(encoding="utf-8"))
    train = load_split("train")
    test = load_split("test")
    counts = {name: [0, 0] for name in CLASS_ORDER}
    for split_index, split in enumerate((train, test)):
        for code, name in ACTIVITY_LABELS.items():
            counts[name][split_index] = int(np.count_nonzero(split["y"] == code))

    x = np.arange(len(CLASS_ORDER))
    fig, ax = plt.subplots(figsize=(9, 4.5))
    ax.bar(x - 0.2, [counts[n][0] for n in CLASS_ORDER], 0.4, label="train (21 subjects)")
    ax.bar(x + 0.2, [counts[n][1] for n in CLASS_ORDER], 0.4, label="test (9 subjects)")
    ax.set_xticks(x)
    ax.set_xticklabels([n.replace("_", "\n").title() for n in CLASS_ORDER], fontsize=8)
    ax.set_ylabel("windows")
    ax.set_title(f"UCI HAR class balance (real data, {meta['checks']['feature_count']} features)")
    ax.legend()
    fig.tight_layout()
    path = OUT_DIR / "fig_har_01_class_balance.png"
    fig.savefig(path, dpi=130)
    plt.close(fig)
    return path.name


def plot_confusion(y_true, y_pred, title: str, filename: str) -> str:
    cm = confusion_matrix(y_true, y_pred, labels=CLASS_CODES)
    fig, ax = plt.subplots(figsize=(6.2, 5.4))
    image = ax.imshow(cm, cmap="Greens")
    ax.set_xticks(range(len(CLASS_ORDER)))
    ax.set_yticks(range(len(CLASS_ORDER)))
    ax.set_xticklabels([n.replace("_", "\n").title() for n in CLASS_ORDER], fontsize=7)
    ax.set_yticklabels(CLASS_ORDER, fontsize=7)
    ax.set_xlabel("predicted")
    ax.set_ylabel("true")
    ax.set_title(title, fontsize=10)
    for i in range(cm.shape[0]):
        for j in range(cm.shape[1]):
            if cm[i, j]:
                ax.text(j, i, str(cm[i, j]), ha="center", va="center", fontsize=7,
                        color="white" if cm[i, j] > cm.max() / 2 else "black")
    fig.colorbar(image, ax=ax, shrink=0.8)
    fig.tight_layout()
    path = OUT_DIR / filename
    fig.savefig(path, dpi=130)
    plt.close(fig)
    return path.name


def export_forest(forest: RandomForestClassifier) -> dict:
    """Flat-array export: small on disk and trivial to walk in Kotlin without a JSON tree library."""
    trees = []
    for estimator in forest.estimators_:
        tree = estimator.tree_
        # scikit-learn stores ``tree_.value`` as class *fractions* normalised per node (they sum to 1),
        # not as counts. Multiplying by the node's weighted sample count recovers the integer counts,
        # which is what we want to ship: integers keep the JSON small, and the on-device walker
        # normalises them per leaf exactly as ``RandomForestClassifier.predict_proba`` does. Exporting
        # the fractions directly, or worse rounding them as if they were counts, silently produces a
        # different model - the export-verification stage exists because that is an easy mistake to make.
        counts = np.rint(
            tree.value[:, 0, :] * tree.weighted_n_node_samples[:, None]
        ).astype(int)
        trees.append({
            "feature": tree.feature.astype(int).tolist(),
            # Full precision on purpose. scikit-learn stores a threshold as the midpoint between two
            # float32 feature values, so rounding it can push a borderline sample down the other
            # branch and change a prediction. json.dumps writes the shortest round-trippable form.
            "threshold": [float(v) for v in tree.threshold],
            "left": tree.children_left.astype(int).tolist(),
            "right": tree.children_right.astype(int).tolist(),
            "value": counts.tolist(),
        })
    return {
        "kind": "random_forest",
        "n_trees": len(trees),
        "classes": CLASS_ORDER,
        "feature_names": FEATURE_NAMES,
        "trees": trees,
    }


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    train = load_split("train")
    test = load_split("test")
    raw_train = load_raw("train")
    raw_test = load_raw("test")

    y_train, y_test = train["y"], test["y"]

    print("building the 24 deployable features from the raw 128-sample windows...")
    x_train_dep = build_features(raw_train)
    x_test_dep = build_features(raw_test)
    assert x_train_dep.shape[1] == len(FEATURE_NAMES)

    # ------------------------------------------------------------------ report model (561)
    print("training the report model (Random Forest on 561 features)...")
    report_model = RandomForestClassifier(
        n_estimators=300, random_state=42, n_jobs=-1, min_samples_leaf=1
    )
    report_model.fit(train["x"], y_train)
    pred_report = report_model.predict(test["x"])
    acc_report = float(accuracy_score(y_test, pred_report))
    f1_report = float(f1_score(y_test, pred_report, average="macro"))

    # ------------------------------------------------------------------ deployable model (24)
    print(f"training the deployable model (Random Forest on {len(FEATURE_NAMES)} features)...")
    deploy_model = RandomForestClassifier(
        n_estimators=32, max_depth=8, random_state=42, n_jobs=-1, min_samples_leaf=2
    )
    deploy_model.fit(x_train_dep, y_train)
    pred_deploy = deploy_model.predict(x_test_dep)
    acc_deploy = float(accuracy_score(y_test, pred_deploy))
    f1_deploy = float(f1_score(y_test, pred_deploy, average="macro"))

    # A single shallow tree, for the "how simple can this get" comparison in the report.
    from sklearn.tree import DecisionTreeClassifier

    single_tree = DecisionTreeClassifier(max_depth=8, random_state=42, min_samples_leaf=4)
    single_tree.fit(x_train_dep, y_train)
    acc_tree = float(accuracy_score(y_test, single_tree.predict(x_test_dep)))
    f1_tree = float(f1_score(y_test, single_tree.predict(x_test_dep), average="macro"))

    # ------------------------------------------------------------------ figures
    balance_fig = plot_class_balance()
    cm_report = plot_confusion(
        y_test, pred_report,
        f"Random Forest, 561 features (subject-disjoint test)\naccuracy {acc_report:.4f}",
        "fig_har_02_confusion_561.png",
    )
    cm_deploy = plot_confusion(
        y_test, pred_deploy,
        f"On-device forest, {len(FEATURE_NAMES)} features (subject-disjoint test)\naccuracy {acc_deploy:.4f}",
        "fig_har_03_confusion_deployable.png",
    )

    # Feature importance for both models, side by side.
    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    top = np.argsort(report_model.feature_importances_)[::-1][:15]
    axes[0].barh(range(15), report_model.feature_importances_[top][::-1], color="#1b7f4c")
    axes[0].set_yticks(range(15))
    axes[0].set_yticklabels([f"f{t + 1}" for t in top][::-1], fontsize=7)
    axes[0].set_title("561-feature model: top 15 importances", fontsize=10)
    order = np.argsort(deploy_model.feature_importances_)[::-1]
    axes[1].barh(range(len(order)), deploy_model.feature_importances_[order][::-1], color="#1a73e8")
    axes[1].set_yticks(range(len(order)))
    axes[1].set_yticklabels([FEATURE_NAMES[i] for i in order][::-1], fontsize=6)
    axes[1].set_title(f"on-device model: all {len(FEATURE_NAMES)} importances", fontsize=10)
    fig.tight_layout()
    importance_fig = "fig_har_04_feature_importance.png"
    fig.savefig(OUT_DIR / importance_fig, dpi=130)
    plt.close(fig)

    # The honest trade-off curve: accuracy of a forest as features are added, best-first.
    print("measuring the accuracy-vs-features trade-off...")
    ranking = np.argsort(report_model.feature_importances_)[::-1]
    curve: list[tuple[int, float]] = []
    for k in [1, 2, 3, 5, 8, 12, 20, 30, 50, 80, 120, 200, 300, 400, 561]:
        idx = ranking[:k]
        model = RandomForestClassifier(n_estimators=60, random_state=42, n_jobs=-1)
        model.fit(train["x"][:, idx], y_train)
        curve.append((k, float(accuracy_score(y_test, model.predict(test["x"][:, idx])))))

    fig, ax = plt.subplots(figsize=(8, 4.5))
    ax.plot([c[0] for c in curve], [c[1] for c in curve], "o-", color="#1565c0", label="561-feature forest, best-k features")
    ax.axhline(acc_deploy, color="#1b7f4c", linestyle="--",
               label=f"on-device {len(FEATURE_NAMES)}-feature forest ({acc_deploy:.4f})")
    ax.set_xscale("log")
    ax.set_xlabel("number of features used (log scale)")
    ax.set_ylabel("test accuracy")
    ax.set_title("Accuracy vs. number of features - why the on-device model is enough")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    curve_fig = "fig_har_05_accuracy_vs_features.png"
    fig.savefig(OUT_DIR / curve_fig, dpi=130)
    plt.close(fig)

    # ------------------------------------------------------------------ export
    forest_json = export_forest(deploy_model)
    model_path = OUT_DIR / "har_activity_model.json"
    model_path.write_text(json.dumps(forest_json, separators=(",", ":")), encoding="utf-8")

    spec = {
        "purpose": "On-device walking/running/still recognition from the phone accelerometer and gyroscope.",
        "input": {
            "window_samples": 128,
            "sample_rate_hz": 50,
            "window_seconds": 2.56,
            "channels": ["total_acc_x", "total_acc_y", "total_acc_z", "gyro_x", "gyro_y", "gyro_z"],
        },
        "features": FEATURE_SPECS,
        "classes": CLASS_ORDER,
        "forest_format": {
            "feature": "index into features[]",
            "threshold": "go left when value <= threshold, right otherwise",
            "left/right": "child node indices, -1 is unused",
            "value": "class counts at the node; a leaf's counts are summed over trees to vote",
        },
        # Computed, not typed: this line previously said "2949" when the real test split is 2947
        # windows, and a stale number in a model card is exactly the kind of thing a reviewer cannot
        # check. The counts now come from the arrays themselves.
        "trained_on": (
            f"UCI HAR, {len(y_train)} train windows / 21 subjects; "
            f"tested on {len(y_test)} windows / 9 disjoint subjects"
        ),
        "accuracy": acc_deploy,
        "macro_f1": f1_deploy,
    }
    (OUT_DIR / "har_activity_model_spec.json").write_text(
        json.dumps(spec, indent=2) + "\n", encoding="utf-8"
    )

    # ------------------------------------------------------------------ reference cases for Kotlin
    # A handful of REAL test windows with scikit-learn's own class probabilities, so the Kotlin
    # implementation of the same forest can be proven to agree - on the JVM, with no device.
    rng = np.random.default_rng(7)
    picks = rng.choice(len(y_test), size=12, replace=False)
    reference = {
        "note": "Real UCI HAR test windows. 'expected_probabilities' come from scikit-learn itself.",
        "window_samples": 128,
        "sample_rate_hz": 50,
        "cases": [],
    }
    for index in picks:
        index = int(index)
        window = {
            "total_acc_x": raw_test["total_acc_x"][index].tolist(),
            "total_acc_y": raw_test["total_acc_y"][index].tolist(),
            "total_acc_z": raw_test["total_acc_z"][index].tolist(),
            "body_gyro_x": raw_test["body_gyro_x"][index].tolist(),
            "body_gyro_y": raw_test["body_gyro_y"][index].tolist(),
            "body_gyro_z": raw_test["body_gyro_z"][index].tolist(),
        }
        probabilities = deploy_model.predict_proba(x_test_dep[index:index + 1])[0]
        reference["cases"].append({
            "subject": int(test["subject"][index]),
            "true_activity": ACTIVITY_LABELS[int(y_test[index])],
            "features": [round(float(v), 6) for v in x_test_dep[index]],
            "window": window,
            "expected_probabilities": [round(float(p), 6) for p in probabilities],
            "expected_activity": CLASS_ORDER[int(np.argmax(probabilities))],
        })
    (OUT_DIR / "har_reference_cases.json").write_text(
        json.dumps(reference) + "\n", encoding="utf-8"
    )

    # ------------------------------------------------------------------ metrics
    rows = ["model,features,accuracy,macro_f1"]
    rows.append(f"random_forest_report,561,{acc_report:.4f},{f1_report:.4f}")
    rows.append(f"random_forest_on_device,{len(FEATURE_NAMES)},{acc_deploy:.4f},{f1_deploy:.4f}")
    rows.append(f"single_decision_tree_on_device,{len(FEATURE_NAMES)},{acc_tree:.4f},{f1_tree:.4f}")
    (OUT_DIR / "har_model_metrics.csv").write_text("\n".join(rows) + "\n", encoding="utf-8")

    report = {
        "dataset": "UCI HAR",
        "split": {"train_windows": int(len(y_train)), "test_windows": int(len(y_test)),
                  "subject_disjoint": True},
        "models": {
            "random_forest_report": {"features": 561, "accuracy": acc_report, "macro_f1": f1_report,
                                     "n_estimators": 300},
            "random_forest_on_device": {"features": len(FEATURE_NAMES), "accuracy": acc_deploy,
                                        "macro_f1": f1_deploy, "n_estimators": 32, "max_depth": 8},
            "single_decision_tree_on_device": {"features": len(FEATURE_NAMES), "accuracy": acc_tree,
                                               "macro_f1": f1_tree, "max_depth": 8},
        },
        "accuracy_vs_features": [{"features": k, "accuracy": a} for k, a in curve],
        "per_class_report_561": classification_report(
            y_test, pred_report, labels=CLASS_CODES, target_names=CLASS_ORDER,
            output_dict=True, zero_division=0,
        ),
        "per_class_report_on_device": classification_report(
            y_test, pred_deploy, labels=CLASS_CODES, target_names=CLASS_ORDER,
            output_dict=True, zero_division=0,
        ),
        "figures": [balance_fig, cm_report, cm_deploy, importance_fig, curve_fig],
        "exported_model_bytes": int(model_path.stat().st_size),
    }
    (OUT_DIR / "har_train_report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )

    print()
    print("UCI HAR training complete")
    print(f"  report model  (561 features): accuracy {acc_report:.4f}  macro-F1 {f1_report:.4f}")
    print(f"  on-device     ({len(FEATURE_NAMES):2d} features): accuracy {acc_deploy:.4f}  macro-F1 {f1_deploy:.4f}")
    print(f"  single tree   ({len(FEATURE_NAMES):2d} features): accuracy {acc_tree:.4f}  macro-F1 {f1_tree:.4f}")
    print(f"  exported model: {report['exported_model_bytes'] / 1024:.1f} KB -> {model_path.name}")
    print(f"  outputs       : {OUT_DIR}")


if __name__ == "__main__":
    main()
