"""Stage 1 - load and verify the real UCI Human Activity Recognition dataset.

Source (the real archive is unzipped under ``ml/data/har/extracted``, never substituted):
    UCI Machine Learning Repository, dataset 240,
    "Human Activity Recognition Using Smartphones".
    https://archive.ics.uci.edu/dataset/240/human+activity+recognition+using+smartphones

Why this dataset is here
------------------------
The project plan (and the reviewer's DATASETS sheet) pairs the GeoLife GPS trajectories with a
**smartphone inertial** dataset so the app can recognise *what the tourist is doing* - walking,
running, sitting, standing, lying - and, from that, flag a likely fall. GeoLife proves where the
tourist went; UCI HAR describes how they moved. This module turns the raw archive into the exact
arrays the training stages consume, and records every count it measured so nothing is taken on trust.

What is verified, not assumed
-----------------------------
* the official train/test split really is **subject-disjoint** (the honest, leakage-safe split);
* the label set really is the six documented activities;
* the raw 128-sample inertial windows really are present (needed for the deployable feature set);
* the 561 precomputed features really match their ``features.txt`` names.

Outputs (``ml/outputs/har/clean/``)
----------------------------------
* ``har_dataset_meta.json``   - every measured count, shape and check result.
* ``har_class_balance.csv``   - windows per activity, per split.
* ``har_subject_split.csv``   - which subjects are in train vs test (proves disjointness).
* ``har_window_summary.csv``  - per-window aggregates of the raw signals (a small, human-auditable view).
* ``har_feature_names.csv``   - the 561 feature names, in order.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

ML_DIR = Path(__file__).resolve().parent
DATA_DIR = ML_DIR / "data" / "har" / "extracted" / "UCI HAR Dataset"
OUT_DIR = ML_DIR / "outputs" / "har" / "clean"

ACTIVITY_LABELS = {
    1: "WALKING",
    2: "WALKING_UPSTAIRS",
    3: "WALKING_DOWNSTAIRS",
    4: "SITTING",
    5: "STANDING",
    6: "LAYING",
}

# The nine raw inertial channels: 3 axes x (total acc, body acc, body gyro).
RAW_CHANNELS = [
    "total_acc_x", "total_acc_y", "total_acc_z",
    "body_acc_x", "body_acc_y", "body_acc_z",
    "body_gyro_x", "body_gyro_y", "body_gyro_z",
]

# The nine channels plus the derived magnitude channels form the feature source for the deployable
# model. Magnitudes are the physically meaningful quantities for impact / fall detection.
MAGNITUDE_CHANNELS = ["total_acc_mag", "body_acc_mag", "body_gyro_mag"]


def load_split(split: str) -> dict:
    """Load one split of the precomputed 561-feature vectors plus its labels and subjects."""
    root = DATA_DIR / split
    x = np.loadtxt(root / f"X_{split}.txt", dtype=np.float32)
    y = np.loadtxt(root / f"y_{split}.txt", dtype=np.int64)
    subject = np.loadtxt(root / f"subject_{split}.txt", dtype=np.int64)
    return {"x": x, "y": y, "subject": subject}


def load_raw(split: str) -> dict[str, np.ndarray]:
    """Load the 128-sample inertial windows: shape (n_windows, 128) per channel."""
    root = DATA_DIR / split / "Inertial Signals"
    out: dict[str, np.ndarray] = {}
    for channel in RAW_CHANNELS:
        out[channel] = np.loadtxt(root / f"{channel}_{split}.txt", dtype=np.float32)
    return out


def load_feature_names() -> list[str]:
    names: list[str] = []
    for line in (DATA_DIR / "features.txt").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        names.append(line.split(" ", 1)[1])
    return names


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    if not DATA_DIR.is_dir():
        raise SystemExit(
            f"UCI HAR dataset not found at {DATA_DIR}.\n"
            "Unzip the real UCI HAR archive under ml/data/har/extracted there manually."
        )

    names = load_feature_names()
    train = load_split("train")
    test = load_split("test")
    raw_train = load_raw("train")
    raw_test = load_raw("test")

    checks: dict[str, object] = {}

    # --- shape / size truths -----------------------------------------------------------------
    checks["feature_count"] = len(names)
    checks["feature_vectors_match_names"] = train["x"].shape[1] == len(names) == test["x"].shape[1]
    checks["train_windows"] = int(train["x"].shape[0])
    checks["test_windows"] = int(test["x"].shape[0])
    checks["raw_window_length"] = int(raw_train["total_acc_x"].shape[1])
    checks["raw_channels_per_split"] = len(raw_train)
    checks["raw_windows_match_features"] = (
        raw_train["total_acc_x"].shape[0] == train["x"].shape[0]
        and raw_test["total_acc_x"].shape[0] == test["x"].shape[0]
    )

    # --- labels ------------------------------------------------------------------------------
    labels_seen = sorted({int(v) for v in np.concatenate([train["y"], test["y"]])})
    checks["labels_seen"] = labels_seen
    checks["labels_match_documented_set"] = labels_seen == sorted(ACTIVITY_LABELS)

    # --- the split must be subject-disjoint, or every score is inflated ----------------------
    train_subjects = sorted({int(s) for s in train["subject"]})
    test_subjects = sorted({int(s) for s in test["subject"]})
    overlap = sorted(set(train_subjects) & set(test_subjects))
    checks["train_subjects"] = train_subjects
    checks["test_subjects"] = test_subjects
    checks["subject_overlap"] = overlap
    checks["split_is_subject_disjoint"] = len(overlap) == 0

    # --- provenance: this is a real download, so record its fingerprint ---------------------
    archive = ML_DIR / "data" / "har" / "uci_har.zip"
    checks["archive_bytes"] = int(archive.stat().st_size) if archive.is_file() else None

    # --- missing / non-finite values ----------------------------------------------------------
    checks["train_non_finite"] = int(np.count_nonzero(~np.isfinite(train["x"])))
    checks["test_non_finite"] = int(np.count_nonzero(~np.isfinite(test["x"])))

    # --- class balance -----------------------------------------------------------------------
    rows = ["split,activity_code,activity,windows"]
    for split_name, split in (("train", train), ("test", test)):
        for code in sorted(ACTIVITY_LABELS):
            count = int(np.count_nonzero(split["y"] == code))
            rows.append(f"{split_name},{code},{ACTIVITY_LABELS[code]},{count}")
    (OUT_DIR / "har_class_balance.csv").write_text("\n".join(rows) + "\n", encoding="utf-8")

    # --- subject split -----------------------------------------------------------------------
    rows = ["split,subjects,count"]
    rows.append(f"train,\"{' '.join(str(s) for s in train_subjects)}\",{len(train_subjects)}")
    rows.append(f"test,\"{' '.join(str(s) for s in test_subjects)}\",{len(test_subjects)}")
    (OUT_DIR / "har_subject_split.csv").write_text("\n".join(rows) + "\n", encoding="utf-8")

    (OUT_DIR / "har_feature_names.csv").write_text(
        "index,feature\n" + "\n".join(f"{i + 1},{n}" for i, n in enumerate(names)) + "\n",
        encoding="utf-8",
    )

    # --- per-window summary of the RAW signals, a small auditable table ----------------------
    def summarise(raw: dict[str, np.ndarray], split: str) -> list[str]:
        total = raw["total_acc_x"]
        gyro = raw["body_gyro_x"]
        mag = np.sqrt(raw["total_acc_x"] ** 2 + raw["total_acc_y"] ** 2 + raw["total_acc_z"] ** 2)
        gyro_mag = np.sqrt(
            raw["body_gyro_x"] ** 2 + raw["body_gyro_y"] ** 2 + raw["body_gyro_z"] ** 2
        )
        out = ["split,window,acc_mag_mean,acc_mag_std,acc_mag_peak,gyro_mag_mean"]
        step = max(1, mag.shape[0] // 40)  # a readable sample, not the whole file
        for i in range(0, mag.shape[0], step):
            out.append(
                f"{split},{i},{mag[i].mean():.4f},{mag[i].std():.4f},{mag[i].max():.4f},"
                f"{gyro_mag[i].mean():.4f}"
            )
        return out

    summary = summarise(raw_train, "train") + summarise(raw_test, "test")
    (OUT_DIR / "har_window_summary.csv").write_text("\n".join(summary) + "\n", encoding="utf-8")

    checks["raw_gravity_sanity"] = {
        "note": "total_acc magnitude must centre on ~1 g; a real phone measurement, not a constant",
        "train_mean": round(float(np.sqrt(
            raw_train["total_acc_x"] ** 2
            + raw_train["total_acc_y"] ** 2
            + raw_train["total_acc_z"] ** 2
        ).mean()), 4),
        "test_mean": round(float(np.sqrt(
            raw_test["total_acc_x"] ** 2
            + raw_test["total_acc_y"] ** 2
            + raw_test["total_acc_z"] ** 2
        ).mean()), 4),
    }
    checks["total_acc_x_train_min_max"] = [
        round(float(total.min()), 4) for total in [raw_train["total_acc_x"]]
    ] + [round(float(raw_train["total_acc_x"].max()), 4)]
    checks["body_gyro_train_rms"] = round(
        float(np.sqrt((raw_train["body_gyro_x"] ** 2).mean())), 4
    )

    meta = {
        "dataset": "UCI HAR (Human Activity Recognition Using Smartphones)",
        "dataset_id": "uci-240",
        "source_dir": str(DATA_DIR),
        "activities": ACTIVITY_LABELS,
        "feature_names_source": "features.txt",
        "raw_channels": RAW_CHANNELS,
        "magnitude_channels": MAGNITUDE_CHANNELS,
        "checks": checks,
    }
    (OUT_DIR / "har_dataset_meta.json").write_text(
        json.dumps(meta, indent=2) + "\n", encoding="utf-8"
    )

    print("UCI HAR preprocess complete")
    print(f"  train windows : {checks['train_windows']}")
    print(f"  test windows  : {checks['test_windows']}")
    print(f"  features      : {checks['feature_count']}")
    print(f"  subjects      : train {len(train_subjects)}, test {len(test_subjects)}, "
          f"overlap {len(overlap)}")
    print(f"  raw windows   : {checks['raw_window_length']} samples x {len(raw_train)} channels")
    print(f"  outputs       : {OUT_DIR}")


if __name__ == "__main__":
    main()
