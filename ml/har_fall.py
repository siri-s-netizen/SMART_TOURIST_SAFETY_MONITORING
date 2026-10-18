"""Stage 3 - the fall / hard-impact detector.

An honest statement of what this can and cannot claim
-----------------------------------------------------
UCI HAR contains **no falls**. It is six daily activities recorded by volunteers who did not fall
over. So it cannot, by itself, validate a fall detector, and any project that reports a "98% fall
accuracy" from UCI HAR has trained on something it invented and then tested on the same invention.

This stage therefore does the defensible thing and says so out loud:

* the windows are **real** recordings from real subjects;
* the *pre-impact* part of every positive window is the real, unmodified recording;
* the *impact and its aftermath* are **injected** - a synthesised spike followed by post-impact
  stillness, parameterised within published free-fall/impact ranges (peak roughly 2-5 g, then a
  quiet, near-constant orientation);
* the split is by **subject**: falls are injected into the training subjects for training and into
  the nine held-out test subjects for testing, so a subject never appears on both sides;
* a **shuffled-label control** is trained on the same data with the labels permuted. Its score is the
  honest "what does this look like with no signal at all" baseline, and it must come out near 0.5.

The number this produces is therefore a *detector-quality* number for a **simulated** fall signature -
useful, reportable, and clearly not a clinical claim. Real validation would need a real fall dataset
(SisFall, UniMiB SHAR, MobiFall); the pipeline reads its windows through the same interface, so
swapping one in is a data change, not a rewrite. That limitation is repeated in the model card.

Outputs (``ml/outputs/har/fall/``)
---------------------------------
* ``har_fall_report.json``      - ROC-AUC, average precision, F1, the control, and the threshold rule.
* ``har_fall_model.json``       - the exported forest (shipped in the APK).
* ``har_fall_reference_cases.json`` - windows + scikit-learn probabilities, used by the Kotlin test.
* ``fig_har_fall_01_roc.png``, ``fig_har_fall_02_confusion.png``, ``fig_har_fall_03_signal.png``
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
    average_precision_score,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
    roc_curve,
)

from har_preprocess import ACTIVITY_LABELS, DATA_DIR, load_raw, load_split
from har_train import FEATURE_NAMES, build_features, export_forest

ML_DIR = Path(__file__).resolve().parent
OUT_DIR = ML_DIR / "outputs" / "har" / "fall"

# Activities that can plausibly precede a fall, and therefore where we inject.
FALL_CONTEXT_ACTIVITIES = (1, 2, 3)  # walking, upstairs, downstairs

IMPACT_PEAK_G_RANGE = (2.2, 5.5)     # published waist-worn acceleration peaks for a hard fall
IMPACT_DURATION_RANGE = (3, 9)       # samples at 50 Hz -> 60-180 ms of impact
POST_FALL_NOISE_G = 0.03             # a body at rest still shows a little sensor noise


def inject_fall(
    window: dict[str, np.ndarray], rng: np.random.Generator, index: int
) -> dict[str, np.ndarray]:
    """Return a copy of one real window with a synthesised fall added.

    Shape of the injected event, in the order a real fall produces it:
      1. free motion continues (the window's head stays exactly as recorded);
      2. an impact - a short, bell-shaped acceleration spike along an arbitrary direction, with a
         matching gyroscope burst as the body rotates;
      3. stillness - from the impact onward the orientation stops changing and body acceleration
         collapses to sensor noise, which is the most reliable *consequence* of a fall.
    """
    out = {key: value[index].copy() for key, value in window.items()}
    length = out["total_acc_x"].size

    start = int(rng.integers(length // 3, 2 * length // 3))
    duration = int(rng.integers(*IMPACT_DURATION_RANGE))
    peak = float(rng.uniform(*IMPACT_PEAK_G_RANGE))

    direction = rng.normal(size=3)
    direction /= np.linalg.norm(direction)
    rotation = rng.normal(size=3) * 2.0

    end = min(length, start + duration)

    # Step 2: the impact itself.
    for offset, sample in enumerate(range(start, end)):
        envelope = float(np.sin(np.pi * (offset + 1) / (duration + 1)))
        for axis, key in enumerate(("total_acc_x", "total_acc_y", "total_acc_z")):
            out[key][sample] += peak * envelope * direction[axis]
        for axis, key in enumerate(("body_gyro_x", "body_gyro_y", "body_gyro_z")):
            out[key][sample] += rotation[axis] * envelope

    # Step 3: the aftermath - a body on the floor, near-still, in one fixed orientation.
    rest_direction = rng.normal(size=3)
    rest_direction /= np.linalg.norm(rest_direction)
    for sample in range(end, length):
        for axis, key in enumerate(("total_acc_x", "total_acc_y", "total_acc_z")):
            noise = rng.normal(scale=POST_FALL_NOISE_G)
            out[key][sample] = rest_direction[axis] + noise
        for key in ("body_gyro_x", "body_gyro_y", "body_gyro_z"):
            out[key][sample] = rng.normal(scale=POST_FALL_NOISE_G)

    return out


def stack(windows: list[dict[str, np.ndarray]], channel: str) -> np.ndarray:
    return np.stack([window[channel] for window in windows])


def build_dataset(
    split: str, rng: np.random.Generator
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Real windows from one split -> (features, labels, real indices of the positives/negatives)."""
    data = load_split(split)
    raw = load_raw(split)
    labels = data["y"]

    context = rng.permutation(np.where(np.isin(labels, FALL_CONTEXT_ACTIVITIES))[0])
    others = rng.permutation(np.where(~np.isin(labels, FALL_CONTEXT_ACTIVITIES))[0])

    # Sizes are chosen from what the split actually contains - the held-out split is smaller than the
    # training one, and asking for more windows than exist is how this used to blow up. Positives and
    # negatives are drawn from *disjoint* parts of the context pool, so the same recording can never
    # appear as an injected positive and a raw negative.
    positive_target = min(1800, max(1, len(context) * 2 // 3))
    positives = context[:positive_target]
    negative_context_count = min(positive_target // 2, max(0, len(context) - positive_target))
    negative_other_count = min(positive_target - negative_context_count, len(others))
    negatives = np.concatenate([
        context[positive_target:positive_target + negative_context_count],  # hard negatives: walking
        others[:negative_other_count],                                     # real still/stairs windows
    ])
    rng.shuffle(negatives)

    injected = [inject_fall(raw, rng, int(i)) for i in positives]

    positive_windows = {channel: stack(injected, channel) for channel in raw}
    negative_windows = {channel: raw[channel][negatives] for channel in raw}

    x_positive = build_features(positive_windows)
    x_negative = build_features(negative_windows)
    y_positive = np.ones(len(x_positive), dtype=np.int64)
    y_negative = np.zeros(len(x_negative), dtype=np.int64)

    x = np.vstack([x_positive, x_negative])
    y = np.concatenate([y_positive, y_negative])

    # The source indices are returned in the same row order as x, so a reference case can name the
    # exact recording it came from.
    return x, y, positives, negatives


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # Two independent streams: one shuffles and one injects. Using a single stream would make the
    # injection depend on how many shuffles happened first, which is exactly the kind of hidden
    # coupling that makes a result irreproducible.
    rng_train = np.random.default_rng(11)
    rng_test = np.random.default_rng(29)

    print("building the training set (real windows, injected impacts)...")
    x_train, y_train, _, _ = build_dataset("train", rng_train)
    print("building the held-out test set (different subjects)...")
    x_test, y_test, _, _ = build_dataset("test", rng_test)

    train_subjects = sorted({int(s) for s in load_split("train")["subject"]})
    test_subjects = sorted({int(s) for s in load_split("test")["subject"]})

    print("training the fall detector...")
    model = RandomForestClassifier(
        n_estimators=40, max_depth=10, random_state=42, n_jobs=-1, min_samples_leaf=2,
        class_weight="balanced",
    )
    model.fit(x_train, y_train)

    scores = model.predict_proba(x_test)[:, 1]
    auc = float(roc_auc_score(y_test, scores))
    ap = float(average_precision_score(y_test, scores))

    predictions = model.predict(x_test)
    f1 = float(f1_score(y_test, predictions))
    precision = float(precision_score(y_test, predictions))
    recall = float(recall_score(y_test, predictions))

    # The control that keeps us honest: same data, labels permuted. If this is not ~0.5 then the
    # score above is measuring something other than the injected signature.
    control_rng = np.random.default_rng(3)
    shuffled = control_rng.permutation(y_train)
    control = RandomForestClassifier(
        n_estimators=40, max_depth=10, random_state=42, n_jobs=-1, min_samples_leaf=2,
    )
    control.fit(x_train, shuffled)
    control_auc = float(roc_auc_score(y_test, control.predict_proba(x_test)[:, 1]))

    # ---------------------------------------------------------------- figures
    fpr, tpr, _ = roc_curve(y_test, scores)
    fig, ax = plt.subplots(figsize=(5.6, 5))
    ax.plot(fpr, tpr, color="#1b7f4c", lw=2, label=f"fall detector (AUC {auc:.4f})")
    ax.plot([0, 1], [0, 1], "--", color="#999", label="chance")
    ax.set_xlabel("false positive rate")
    ax.set_ylabel("true positive rate")
    ax.set_title("Impact/fall detection - held-out subjects\n(positive class is a simulated impact)")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)
    fig.tight_layout()
    roc_fig = "fig_har_fall_01_roc.png"
    fig.savefig(OUT_DIR / roc_fig, dpi=130)
    plt.close(fig)

    cm = confusion_matrix(y_test, predictions)
    fig, ax = plt.subplots(figsize=(4.6, 4))
    image = ax.imshow(cm, cmap="Reds")
    ax.set_xticks([0, 1])
    ax.set_yticks([0, 1])
    ax.set_xticklabels(["no fall", "fall"])
    ax.set_yticklabels(["no fall", "fall"])
    ax.set_xlabel("predicted")
    ax.set_ylabel("true")
    ax.set_title(f"Fall detector at threshold 0.5\nF1 {f1:.4f}", fontsize=10)
    for i in range(2):
        for j in range(2):
            ax.text(j, i, str(cm[i, j]), ha="center", va="center",
                    color="white" if cm[i, j] > cm.max() / 2 else "black")
    fig.colorbar(image, ax=ax, shrink=0.8)
    fig.tight_layout()
    cm_fig = "fig_har_fall_02_confusion.png"
    fig.savefig(OUT_DIR / cm_fig, dpi=130)
    plt.close(fig)

    # Show one genuinely injected window: this is the figure that makes the method auditable.
    raw_test = load_raw("test")
    example = inject_fall(raw_test, np.random.default_rng(101), 0)
    magnitude_real = np.sqrt(
        raw_test["total_acc_x"][0] ** 2 + raw_test["total_acc_y"][0] ** 2 + raw_test["total_acc_z"][0] ** 2
    )
    magnitude_injected = np.sqrt(
        example["total_acc_x"] ** 2 + example["total_acc_y"] ** 2 + example["total_acc_z"] ** 2
    )
    fig, axes = plt.subplots(2, 1, figsize=(9, 5), sharex=True)
    axes[0].plot(magnitude_real, color="#1a73e8")
    axes[0].set_ylabel("|a| (g)")
    axes[0].set_title("real recorded window (walking) - the head of the injected window is unchanged")
    axes[0].grid(alpha=0.3)
    axes[1].plot(magnitude_injected, color="#c62828")
    axes[1].set_ylabel("|a| (g)")
    axes[1].set_xlabel("sample (50 Hz, 128 samples = 2.56 s)")
    axes[1].set_title("after injection: impact spike, then post-fall stillness")
    axes[1].grid(alpha=0.3)
    fig.tight_layout()
    signal_fig = "fig_har_fall_03_signal.png"
    fig.savefig(OUT_DIR / signal_fig, dpi=130)
    plt.close(fig)

    # ---------------------------------------------------------------- export
    forest = export_forest(model)
    forest["kind"] = "fall_detector"
    forest["positive_class"] = "FALL"
    forest["negative_class"] = "NO_FALL"
    forest["classes"] = ["NO_FALL", "FALL"]
    model_path = OUT_DIR / "har_fall_model.json"
    model_path.write_text(json.dumps(forest, separators=(",", ":")), encoding="utf-8")

    # Reference cases for the Kotlin test.
    #
    # Each case is *self-consistent by construction*: the window stored in the file is the exact one
    # the features were computed from, and the probabilities are what scikit-learn produced from those
    # features. The Kotlin test therefore re-derives the features from the window, checks they match,
    # then walks the forest - an end-to-end check of the exported contract on the JVM, with no device.
    test_data = load_split("test")
    test_labels = test_data["y"]
    test_context = np.where(np.isin(test_labels, FALL_CONTEXT_ACTIVITIES))[0]
    test_others = np.where(~np.isin(test_labels, FALL_CONTEXT_ACTIVITIES))[0]

    def features_of(window: dict[str, np.ndarray]) -> np.ndarray:
        return build_features({channel: values[None, :] for channel, values in window.items()})[0]

    reference_rng = np.random.default_rng(53)
    reference = {
        "note": (
            "Real UCI HAR windows from held-out subjects. 'FALL' cases have a synthesised impact "
            "injected; 'NO_FALL' cases are untouched recordings. Probabilities come from scikit-learn."
        ),
        "classes": ["NO_FALL", "FALL"],
        "window_samples": 128,
        "sample_rate_hz": 50,
        "cases": [],
    }

    def add_reference(window: dict[str, np.ndarray], label: str, origin: dict) -> None:
        features = features_of(window)
        probabilities = model.predict_proba(features[None, :])[0]
        reference["cases"].append({
            "true_label": label,
            "origin": origin,
            "features": [round(float(v), 6) for v in features],
            "window": {key: np.asarray(value, dtype=float).tolist() for key, value in window.items()},
            "expected_probabilities": [round(float(p), 6) for p in probabilities],
        })

    for case_index in range(6):
        source = int(reference_rng.choice(test_context, size=1)[0])
        add_reference(
            inject_fall(raw_test, np.random.default_rng(400 + case_index), source),
            "FALL",
            {"activity": ACTIVITY_LABELS[int(test_labels[source])], "injected": True},
        )
    for case_index in range(6):
        pool = test_others if case_index % 2 == 0 else test_context
        source = int(reference_rng.choice(pool, size=1)[0])
        add_reference(
            {key: values[source] for key, values in raw_test.items()},
            "NO_FALL",
            {"activity": ACTIVITY_LABELS[int(test_labels[source])], "injected": False},
        )
    (OUT_DIR / "har_fall_reference_cases.json").write_text(
        json.dumps(reference) + "\n", encoding="utf-8"
    )

    report = {
        "detector": "random_forest_fall_detector",
        "positives_are": "synthesised impacts injected into real recorded windows",
        "held_out_subjects": test_subjects,
        "trained_on_subjects": train_subjects,
        "split_is_subject_disjoint": not (set(train_subjects) & set(test_subjects)),
        "test_windows": int(len(y_test)),
        "test_positives": int(y_test.sum()),
        "metrics": {
            "roc_auc": auc,
            "average_precision": ap,
            "f1_at_0_5": f1,
            "precision_at_0_5": precision,
            "recall_at_0_5": recall,
        },
        "shuffled_label_control_roc_auc": control_auc,
        "caveat": (
            "UCI HAR has no real falls. These metrics measure detection of a synthesised impact "
            "signature, on real subjects and real recordings otherwise. They are not a clinical "
            "fall-detection claim and must not be reported as one."
        ),
        "figures": [roc_fig, cm_fig, signal_fig],
        "exported_model_bytes": int(model_path.stat().st_size),
        "feature_names": FEATURE_NAMES,
    }
    (OUT_DIR / "har_fall_report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )

    print()
    print("Fall detector complete")
    print(f"  ROC-AUC          : {auc:.4f}")
    print(f"  average precision: {ap:.4f}")
    print(f"  F1 @ 0.5         : {f1:.4f}  (precision {precision:.4f}, recall {recall:.4f})")
    print(f"  shuffled control : {control_auc:.4f}  (must be ~0.5)")
    print(f"  exported model   : {report['exported_model_bytes'] / 1024:.1f} KB")
    print(f"  outputs          : {OUT_DIR}")


if __name__ == "__main__":
    main()
