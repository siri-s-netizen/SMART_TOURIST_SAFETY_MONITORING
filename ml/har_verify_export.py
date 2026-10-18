"""Stage 4 - verify the exported models, with no scikit-learn and no numpy involved.

This is the check that makes the on-device model trustworthy, and it is deliberately written as a
*second*, independent implementation:

* the features are recomputed here in plain Python from the raw window samples, using only the
  standard library - the same arithmetic a phone would do;
* the forest is walked here in plain Python, reading the same JSON the phone reads;
* both are compared against the numbers **scikit-learn itself** produced when the model was trained.

If the JSON contract, the feature order, the "go left when value <= threshold" rule or the leaf-voting
rule were wrong in any way, this fails loudly - which is exactly what happened the first time the
thresholds were rounded for readability. It is much cheaper to find that here than on a phone.

Run it after ``har_train.py`` and ``har_fall.py``:

    python ml/har_verify_export.py
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

ML_DIR = Path(__file__).resolve().parent
ACTIVITY_DIR = ML_DIR / "outputs" / "har" / "train"
FALL_DIR = ML_DIR / "outputs" / "har" / "fall"

FEATURE_NAMES = [
    "acc_mag_mean", "acc_mag_std", "acc_mag_min", "acc_mag_max", "acc_mag_range",
    "acc_mag_energy", "acc_mag_peak_jerk", "acc_mag_zcr",
    "acc_x_mean", "acc_y_mean", "acc_z_mean",
    "acc_x_std", "acc_y_std", "acc_z_std",
    "acc_xy_corr", "acc_xz_corr", "acc_yz_corr",
    "gyro_mag_mean", "gyro_mag_std", "gyro_mag_max", "gyro_mag_energy",
    "gyro_x_std", "gyro_y_std", "gyro_z_std",
]


def mean(values: list[float]) -> float:
    return sum(values) / len(values)


def std(values: list[float], known_mean: float | None = None) -> float:
    centre = mean(values) if known_mean is None else known_mean
    return math.sqrt(sum((v - centre) ** 2 for v in values) / len(values))


def is_negative(value: float) -> bool:
    """Sign-bit test, matching numpy's signbit (so -0.0 counts as negative)."""
    return math.copysign(1.0, value) < 0.0


def correlation(a: list[float], b: list[float]) -> float:
    mean_a = mean(a)
    mean_b = mean(b)
    numerator = sum((a[i] - mean_a) * (b[i] - mean_b) for i in range(len(a)))
    denominator = math.sqrt(
        sum((v - mean_a) ** 2 for v in a) * sum((v - mean_b) ** 2 for v in b)
    )
    return 0.0 if denominator <= 1e-12 else numerator / denominator


def extract_features(window: dict[str, list[float]]) -> list[float]:
    """Independently recomputes the 24 features from raw samples."""
    ax, ay, az = window["total_acc_x"], window["total_acc_y"], window["total_acc_z"]
    gx, gy, gz = window["body_gyro_x"], window["body_gyro_y"], window["body_gyro_z"]
    n = len(ax)

    acc_mag = [math.sqrt(ax[i] ** 2 + ay[i] ** 2 + az[i] ** 2) for i in range(n)]
    gyro_mag = [math.sqrt(gx[i] ** 2 + gy[i] ** 2 + gz[i] ** 2) for i in range(n)]
    acc_mean = mean(acc_mag)
    centred = [v - acc_mean for v in acc_mag]

    zero_crossings = sum(
        1 for i in range(1, n) if is_negative(centred[i]) != is_negative(centred[i - 1])
    )
    peak_jerk = max(abs(acc_mag[i] - acc_mag[i - 1]) for i in range(1, n))

    return [
        acc_mean,
        std(acc_mag, acc_mean),
        min(acc_mag),
        max(acc_mag),
        max(acc_mag) - min(acc_mag),
        sum(v * v for v in acc_mag) / n,
        peak_jerk,
        float(zero_crossings),
        mean(ax), mean(ay), mean(az),
        std(ax), std(ay), std(az),
        correlation(ax, ay), correlation(ax, az), correlation(ay, az),
        mean(gyro_mag), std(gyro_mag), max(gyro_mag), sum(v * v for v in gyro_mag) / n,
        std(gx), std(gy), std(gz),
    ]


def forest_probabilities(model: dict, features: list[float]) -> list[float]:
    """Walks the exported JSON exactly as the Kotlin does."""
    class_count = len(model["classes"])
    totals = [0.0] * class_count
    trees = model["trees"]

    for tree in trees:
        left, right = tree["left"], tree["right"]
        node = 0
        while left[node] != -1:
            index = tree["feature"][node]
            node = left[node] if features[index] <= tree["threshold"][node] else right[node]
        counts = tree["value"][node]
        total = sum(counts)
        if total <= 0:
            continue
        for class_index in range(class_count):
            totals[class_index] += counts[class_index] / total

    return [total / len(trees) for total in totals]


def check(name: str, model_path: Path, cases_path: Path, feature_tolerance: float) -> bool:
    model = json.loads(model_path.read_text(encoding="utf-8"))
    cases = json.loads(cases_path.read_text(encoding="utf-8"))

    if model["feature_names"] != FEATURE_NAMES:
        print(f"FAIL {name}: the model's feature_names do not match the documented order")
        print(f"     model   : {model['feature_names']}")
        print(f"     expected: {FEATURE_NAMES}")
        return False

    max_feature_error = 0.0
    max_probability_error = 0.0
    mismatches = 0

    for index, case in enumerate(cases["cases"]):
        recomputed = extract_features(case["window"])
        stored = case["features"]
        case_feature_error = max(abs(a - b) for a, b in zip(recomputed, stored))
        max_feature_error = max(max_feature_error, case_feature_error)

        probabilities = forest_probabilities(model, recomputed)
        expected = case["expected_probabilities"]
        case_probability_error = max(abs(a - b) for a, b in zip(probabilities, expected))
        max_probability_error = max(max_probability_error, case_probability_error)

        best = max(range(len(probabilities)), key=lambda i: probabilities[i])
        expected_best = max(range(len(expected)), key=lambda i: expected[i])
        if best != expected_best:
            mismatches += 1
            print(f"  case {index}: predicted {model['classes'][best]} but expected "
                  f"{model['classes'][expected_best]}")

        if case_feature_error > feature_tolerance:
            print(f"  case {index}: feature error {case_feature_error:.3e} exceeds tolerance")
            for feature_index, (a, b) in enumerate(zip(recomputed, stored)):
                if abs(a - b) > feature_tolerance:
                    print(f"    {FEATURE_NAMES[feature_index]}: recomputed {a!r} vs stored {b!r}")
            return False

    print(f"{name}")
    print(f"  cases                : {len(cases['cases'])}")
    print(f"  max feature error    : {max_feature_error:.3e}  (tolerance {feature_tolerance:.0e})")
    print(f"  max probability error: {max_probability_error:.3e}")
    print(f"  argmax mismatches    : {mismatches}")

    ok = mismatches == 0 and max_probability_error < 1e-3
    print("  result               : " + ("PASS" if ok else "FAIL"))
    return ok


def main() -> int:
    results = [
        check(
            "Activity model (har_activity_model.json)",
            ACTIVITY_DIR / "har_activity_model.json",
            ACTIVITY_DIR / "har_reference_cases.json",
            feature_tolerance=1e-3,
        ),
        check(
            "Fall model (har_fall_model.json)",
            FALL_DIR / "har_fall_model.json",
            FALL_DIR / "har_fall_reference_cases.json",
            feature_tolerance=1e-3,
        ),
    ]
    print()
    print("export verification: " + ("ALL PASS" if all(results) else "FAILED"))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
