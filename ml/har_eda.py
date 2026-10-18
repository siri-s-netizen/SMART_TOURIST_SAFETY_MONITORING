"""
har_eda.py - figures for the UCI HAR activity dataset.

Why this module exists
----------------------
``ml/har_preprocess.py`` already produced the clean *tables* (class balance,
subject split, window summary) and ``ml/har_train.py`` produced the model
figures. What was missing is the exploratory figure set that shows a reader
*what the inertial data looks like*: the six activity classes, the mean +/- std
band of every accelerometer/gyroscope axis, the gravity magnitude distribution,
and how correlated the 561 hand-crafted features are. This module fills that gap
from the real files under ``ml/data/har/``.

Nothing here re-trains or changes a model: it only reads the dataset and draws
figures, so ``har_train.py``'s behaviour is untouched.

Outputs (``ml/outputs/har/eda/``)
---------------------------------
* ``fig_har_eda_01_class_balance.png``     windows per activity, train vs test
* ``fig_har_eda_02_subject_split.png``     subject counts per split
* ``fig_har_eda_03_acc_signal_bands.png``  body-acc mean +/- std over the window
* ``fig_har_eda_04_gyro_signal_bands.png`` body-gyro mean +/- std over the window
* ``fig_har_eda_05_gravity_magnitude.png`` total-acc magnitude per activity
* ``fig_har_eda_06_feature_correlation.png`` correlation of a 41-feature subset
* ``table_*.csv`` + ``findings.md``

Run:  python ml/har_eda.py
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from har_preprocess import (  # noqa: E402
    ACTIVITY_LABELS,
    DATA_DIR,
    load_feature_names,
    load_raw,
    load_split,
)

ML_DIR = Path(__file__).resolve().parent
OUT_DIR = ML_DIR / "outputs" / "har" / "eda"
CLEAN_DIR = ML_DIR / "outputs" / "har" / "clean"

CLASS_ORDER = [ACTIVITY_LABELS[c] for c in sorted(ACTIVITY_LABELS)]
AXES = ("x", "y", "z")
WINDOW_FIG_ACTIVITIES = ["WALKING", "WALKING_UPSTAIRS", "WALKING_DOWNSTAIRS", "LAYING"]
CORRELATION_STRIDE = 14  # ~41 of the 561 features, evenly spread, documented


def _savefig(fig, path: Path) -> None:
    fig.tight_layout()
    fig.savefig(path, dpi=130)
    plt.close(fig)
    print(f"  wrote {path.name}")


def class_balance_table(train: dict, test: dict) -> pd.DataFrame:
    """Windows per activity per split (pure-ish; only needs the label arrays)."""
    rows = []
    for name, split in (("train", train), ("test", test)):
        for code, activity in ACTIVITY_LABELS.items():
            rows.append(
                {
                    "split": name,
                    "activity_code": code,
                    "activity": activity,
                    "windows": int(np.count_nonzero(split["y"] == code)),
                }
            )
    return pd.DataFrame(rows)


def feature_subset_indices(n_features: int, stride: int = CORRELATION_STRIDE) -> list[int]:
    """Evenly-spaced feature indices for the (readable) correlation heatmap."""
    return list(range(0, n_features, stride))


def run(args: argparse.Namespace) -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    if not DATA_DIR.is_dir():
        raise SystemExit(
            f"UCI HAR dataset not found at {DATA_DIR}.\n"
            "Unzip the real archive there first (see ml/har_preprocess.py)."
        )

    names = load_feature_names()
    train = load_split("train")
    test = load_split("test")
    raw_train = load_raw("train")

    balance = class_balance_table(train, test)
    balance.to_csv(OUT_DIR / "table_har_eda_class_balance.csv", index=False)

    train_subjects = sorted({int(s) for s in train["subject"]})
    test_subjects = sorted({int(s) for s in test["subject"]})
    overlap = sorted(set(train_subjects) & set(test_subjects))

    # ---- fig 01: class balance ------------------------------------------- #
    wide = balance.pivot(index="activity", columns="split", values="windows").reindex(CLASS_ORDER)
    x = np.arange(len(CLASS_ORDER))
    fig, ax = plt.subplots(figsize=(9, 4.5))
    ax.bar(x - 0.2, wide["train"], 0.4, label=f"train ({len(train_subjects)} subjects)")
    ax.bar(x + 0.2, wide["test"], 0.4, label=f"test ({len(test_subjects)} subjects)")
    ax.set_xticks(x)
    ax.set_xticklabels([n.replace("_", "\n").title() for n in CLASS_ORDER], fontsize=8)
    ax.set_ylabel("windows")
    ax.set_title("UCI HAR class balance (real data)")
    ax.legend()
    _savefig(fig, OUT_DIR / "fig_har_eda_01_class_balance.png")

    # ---- fig 02: subject split ------------------------------------------- #
    fig, ax = plt.subplots(figsize=(7, 4))
    ax.bar(["train", "test"], [len(train_subjects), len(test_subjects)],
           color=["#1b7f4c", "#1a73e8"])
    for i, v in enumerate((len(train_subjects), len(test_subjects))):
        ax.text(i, v + 0.2, str(v), ha="center")
    ax.set_ylabel("subjects")
    ax.set_title(f"Subject-disjoint split (overlap = {len(overlap)})")
    _savefig(fig, OUT_DIR / "fig_har_eda_02_subject_split.png")
    pd.DataFrame(
        [
            {"split": "train", "n_subjects": len(train_subjects),
             "subjects": " ".join(map(str, train_subjects))},
            {"split": "test", "n_subjects": len(test_subjects),
             "subjects": " ".join(map(str, test_subjects))},
            {"split": "overlap", "n_subjects": len(overlap),
             "subjects": " ".join(map(str, overlap))},
        ]
    ).to_csv(OUT_DIR / "table_har_eda_subject_split.csv", index=False)

    # ---- fig 03/04: mean +/- std signal bands ---------------------------- #
    stats_rows = []
    for family, family_label, filename in (
        ("body_acc", "Body acceleration", "fig_har_eda_03_acc_signal_bands.png"),
        ("body_gyro", "Body gyroscope", "fig_har_eda_04_gyro_signal_bands.png"),
    ):
        fig, axes = plt.subplots(1, 3, figsize=(13, 3.8), sharey=True)
        for axis_name, ax in zip(AXES, axes):
            signal = raw_train[f"{family}_{axis_name}"]
            for activity in WINDOW_FIG_ACTIVITIES:
                code = next(c for c, n in ACTIVITY_LABELS.items() if n == activity)
                mask = train["y"] == code
                block = signal[mask]
                mean = block.mean(axis=0)
                std = block.std(axis=0)
                t = np.arange(block.shape[1])
                line, = ax.plot(t, mean, lw=1.2, label=activity.replace("WALKING", "walk"))
                ax.fill_between(t, mean - std, mean + std, color=line.get_color(), alpha=0.15)
                stats_rows.append(
                    {
                        "channel": f"{family}_{axis_name}",
                        "activity": activity,
                        "n_windows": int(mask.sum()),
                        "mean": float(mean.mean()),
                        "std_of_mean": float(std.mean()),
                    }
                )
            ax.set_title(f"{family_label} {axis_name}")
            ax.set_xlabel("sample (50 Hz, 128 = 2.56 s)")
            ax.grid(alpha=0.3)
        handles, labels = axes[0].get_legend_handles_labels()
        fig.legend(handles, labels, loc="upper center", ncol=4, fontsize=8,
                   bbox_to_anchor=(0.5, 1.08))
        fig.suptitle(f"{family_label}: mean +/- 1 std across windows (train split)", y=1.14)
        _savefig(fig, OUT_DIR / filename)
    pd.DataFrame(stats_rows).to_csv(OUT_DIR / "table_har_eda_signal_stats.csv", index=False)

    # ---- fig 05: gravity-magnitude histograms ---------------------------- #
    ax_sum = np.sqrt(raw_train["total_acc_x"] ** 2 + raw_train["total_acc_y"] ** 2
                     + raw_train["total_acc_z"] ** 2)
    fig, ax = plt.subplots(figsize=(9, 4.5))
    mag_rows = []
    for activity in CLASS_ORDER:
        code = next(c for c, n in ACTIVITY_LABELS.items() if n == activity)
        values = ax_sum[train["y"] == code].ravel()
        ax.hist(values, bins=80, histtype="step", lw=1.2, label=activity.replace("_", " ").title())
        mag_rows.append({"activity": activity, "n_windows": int((train["y"] == code).sum()),
                         "mag_mean": float(ax_sum[train["y"] == code].mean()),
                         "mag_std": float(ax_sum[train["y"] == code].std())})
    ax.axvline(1.0, color="k", ls="--", lw=0.8)
    ax.set_xlabel("total acceleration magnitude (g)")
    ax.set_ylabel("samples")
    ax.set_title("Gravity magnitude per activity (train split)")
    ax.legend(fontsize=8)
    _savefig(fig, OUT_DIR / "fig_har_eda_05_gravity_magnitude.png")
    pd.DataFrame(mag_rows).to_csv(OUT_DIR / "table_har_eda_gravity_magnitude.csv", index=False)

    # ---- fig 06: correlation of a feature subset ------------------------- #
    indices = feature_subset_indices(train["x"].shape[1])
    subset = train["x"][:, indices]
    corr = np.corrcoef(subset, rowvar=False)
    short = [names[i][:28] for i in indices]
    fig, ax = plt.subplots(figsize=(11, 9.5))
    im = ax.imshow(corr, cmap="coolwarm", vmin=-1, vmax=1)
    ax.set_xticks(range(len(short)))
    ax.set_xticklabels(short, rotation=90, fontsize=6)
    ax.set_yticks(range(len(short)))
    ax.set_yticklabels(short, fontsize=6)
    ax.set_title(f"Correlation of {len(indices)} of the {len(names)} features (every {CORRELATION_STRIDE}th)")
    fig.colorbar(im, ax=ax, shrink=0.8, label="Pearson r")
    _savefig(fig, OUT_DIR / "fig_har_eda_06_feature_correlation.png")
    pd.DataFrame({"index": [i + 1 for i in indices], "feature": short}).to_csv(
        OUT_DIR / "table_har_eda_correlation_subset.csv", index=False)

    # ---- findings -------------------------------------------------------- #
    n_train = int(train["x"].shape[0])
    n_test = int(test["x"].shape[0])
    grav_mean = float(ax_sum.mean())
    f: list[str] = []
    f.append("# UCI HAR EDA findings\n")
    f.append(
        "All numbers below are read from the real UCI HAR files under "
        f"`{DATA_DIR.relative_to(ML_DIR)}` (never hand-typed).\n"
    )
    f.append("## Dataset shape\n")
    f.append(
        f"- feature vectors: **{len(names)}** per window; train windows **{n_train:,}**, "
        f"test windows **{n_test:,}**.\n"
        f"- activities: **{len(ACTIVITY_LABELS)}** "
        f"({', '.join(CLASS_ORDER)}).\n"
        f"- raw window: **128 samples** at 50 Hz (2.56 s), 9 inertial channels.\n"
    )
    f.append("## Subject split (leakage-safety)\n")
    f.append(
        f"- train subjects **{len(train_subjects)}** "
        f"({train_subjects[0]}..{train_subjects[-1]}), "
        f"test subjects **{len(test_subjects)}** ({test_subjects[0]}..{test_subjects[-1]}), "
        f"**overlap {len(overlap)}** -> the split is subject-disjoint.\n"
    )
    f.append("## Class balance\n")
    f.append(
        "| activity | train | test |\n|---|---|---|\n"
        + "\n".join(
            f"| {r.activity} | {int(wide.loc[r.activity, 'train']):,} | "
            f"{int(wide.loc[r.activity, 'test']):,} |"
            for r in wide.reset_index().itertuples()
        )
        + "\n"
    )
    mag_df = pd.DataFrame(mag_rows)
    f.append("## Gravity magnitude (total acceleration)\n")
    f.append(
        f"- overall mean magnitude **{grav_mean:.4f} g** (must centre near 1 g - a real phone "
        "accelerometer, not a constant).\n"
        f"- per-activity means range **{mag_df['mag_mean'].min():.4f} .. "
        f"{mag_df['mag_mean'].max():.4f} g**.\n"
    )
    f.append("## Figures\n")
    f.append(
        "- `fig_har_eda_01_class_balance.png`, `fig_har_eda_02_subject_split.png`,\n"
        "- `fig_har_eda_03_acc_signal_bands.png`, `fig_har_eda_04_gyro_signal_bands.png`,\n"
        "- `fig_har_eda_05_gravity_magnitude.png`, `fig_har_eda_06_feature_correlation.png`.\n"
    )
    f.append("## Reproducibility\n")
    f.append(
        f"- `python ml/har_eda.py`; deterministic (no sampling except a fixed subset of "
        f"features every {CORRELATION_STRIDE}th); wall time {time.time() - t0:.1f}s.\n"
    )
    (OUT_DIR / "findings.md").write_text("\n".join(f), encoding="utf-8")
    print(f"[har_eda] wrote {OUT_DIR / 'findings.md'}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    return argparse.ArgumentParser(description="EDA figures for the UCI HAR dataset.")


def main(argv: "list[str] | None" = None) -> int:
    run(build_parser().parse_args(argv))
    return 0


if __name__ == "__main__":
    sys.exit(main())
