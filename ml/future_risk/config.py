"""
config.py - the single source of truth for the future-risk pipeline.

Every number here is either read from the dataset or is a documented decision.
Nothing in this module is a guess about model quality.
"""

from __future__ import annotations

from pathlib import Path

# --------------------------------------------------------------------------- #
# Paths
# --------------------------------------------------------------------------- #

PACKAGE_DIR: Path = Path(__file__).resolve().parent
ML_ROOT: Path = PACKAGE_DIR.parent
REPO_ROOT: Path = ML_ROOT.parent

#: Where the UniMiB SHAR archive is unzipped. See ``ml/data/README.md`` §UniMiB.
UNIMIB_DIR: Path = ML_ROOT / "data" / "unimib" / "UniMiB-SHAR"
UNIMIB_DATA_DIR: Path = UNIMIB_DIR / "data"
UNIMIB_FULL_MAT: Path = UNIMIB_DATA_DIR / "full_data.mat"

OUT_DIR: Path = ML_ROOT / "outputs" / "future_risk"
FIGURE_DIR: Path = OUT_DIR / "figures"

#: The canonical artefact set.
MODEL_JSON: Path = OUT_DIR / "future_risk_model.json"
METRICS_JSON: Path = OUT_DIR / "metrics.json"
PROVENANCE_JSON: Path = OUT_DIR / "dataset_provenance.json"
WINDOWS_NPZ: Path = OUT_DIR / "windows.npz"
RELIABILITY_CSV: Path = OUT_DIR / "calibration_reliability.csv"
PREDICTIONS_CSV: Path = OUT_DIR / "test_predictions.csv"
REFERENCE_CASES: Path = OUT_DIR / "future_risk_reference_cases.json"

#: Where the Android app reads them from. ``ml/tests`` asserts these are
#: byte-identical to the canonical artefacts, so a hand-edited asset cannot drift.
APP_ASSET_DIR: Path = REPO_ROOT / "app" / "src" / "main" / "assets"
APP_TEST_RESOURCE_DIR: Path = REPO_ROOT / "app" / "src" / "test" / "resources"

# --------------------------------------------------------------------------- #
# Geometry - all of this is verified against the real dataset
# --------------------------------------------------------------------------- #

#: UniMiB SHAR stores raw trials sampled from a smartphone accelerometer at 50 Hz.
#: Verified: the recorded inter-sample step in ``full_data.mat`` row 3/4 is 0.02 s.
SAMPLE_RATE_HZ: int = 50

#: One second of signal. The feature window the app computes from the live stream
#: (the last 50 samples of each emitted motion window).
WINDOW_SAMPLES: int = 50

#: The forecast horizon: how far ahead of the impact a prediction is valid.
#:
#: This is the longest horizon the recordings can support. Measured on the real
#: archive: fall trials are 151-446 samples long (median 174) and the impact peak
#: sits at index 86-170, so a trial typically has ~100 samples (2 s) of genuine
#: pre-impact signal, with a floor of ~95 samples. A 50-sample (1 s) horizon
#: therefore has room in every fall trial, and it is also the horizon the
#: literature calls "pre-impact fall detection" (300-700 ms is typical).
HORIZON_SAMPLES: int = 50

#: Samples between consecutive training windows. 0.2 s: enough overlap for the
#: model to see the approach to the impact without making every row a near-duplicate.
WINDOW_STRIDE: int = 10

#: A trial only contributes positive windows if its hardest sample reaches this
#: many g. Reuses ``FallPolicy.MIN_IMPACT_G`` from the app so the definition of
#: "a hard impact" is the same on both sides of the system.
IMPACT_MIN_G: float = 2.0

#: Below this magnitude the device is in (near) free fall - the feet-off-the-ground
#: signature that immediately precedes a landing.
FREEFALL_G: float = 0.5

#: m/s^2 per g. UniMiB records acceleration in m/s^2; the app and the model work in g.
STANDARD_GRAVITY: float = 9.80665

# --------------------------------------------------------------------------- #
# Feature schema  (mirrored exactly by FutureRiskFeatures.kt)
# --------------------------------------------------------------------------- #

FEATURE_NAMES: tuple[str, ...] = (
    "acc_mag_mean",
    "acc_mag_std",
    "acc_mag_min",
    "acc_mag_max",
    "acc_mag_range",
    "acc_mag_energy",
    "acc_mag_peak_jerk",
    "acc_mag_zcr",
    "acc_x_mean",
    "acc_y_mean",
    "acc_z_mean",
    "acc_x_std",
    "acc_y_std",
    "acc_z_std",
    "acc_xy_corr",
    "acc_xz_corr",
    "acc_yz_corr",
    "freefall_fraction",
    "tilt_deg",
    "orientation_change_deg",
    "acc_mag_slope",
    "acc_mag_late_to_early_ratio",
)

#: Human-readable meaning, shown in the app's "why this prediction" rows and in
#: the model card. Kept here so the two can never disagree.
FEATURE_LABELS: dict[str, str] = {
    "acc_mag_mean": "Average force on the phone",
    "acc_mag_std": "How much the force is changing",
    "acc_mag_min": "Lightest moment (near zero = free fall)",
    "acc_mag_max": "Hardest moment in the last second",
    "acc_mag_range": "Spread between lightest and hardest moment",
    "acc_mag_energy": "Overall movement energy",
    "acc_mag_peak_jerk": "Suddenness of the sharpest change",
    "acc_mag_zcr": "How quickly the force is oscillating",
    "acc_x_mean": "Average tilt on the x axis",
    "acc_y_mean": "Average tilt on the y axis",
    "acc_z_mean": "Average tilt on the z axis",
    "acc_x_std": "x-axis wobble",
    "acc_y_std": "y-axis wobble",
    "acc_z_std": "z-axis wobble",
    "acc_xy_corr": "How x and y move together",
    "acc_xz_corr": "How x and z move together",
    "acc_yz_corr": "How y and z move together",
    "freefall_fraction": "Share of the second spent in free fall",
    "tilt_deg": "How the phone is oriented",
    "orientation_change_deg": "How much the phone rotated during the second",
    "acc_mag_slope": "Whether the force is building or fading",
    "acc_mag_late_to_early_ratio": "Second half vs first half of the second",
}

#: Class order. Index 1 is the positive (imminent) class, matching the app's
#: ``CALIBRATED_POSITIVE_INDEX``.
CLASS_NAMES: tuple[str, str] = ("NO_IMMINENT_FALL", "IMMINENT_FALL")

# --------------------------------------------------------------------------- #
# Splitting - grouped by subject, so no person is in two splits
# --------------------------------------------------------------------------- #

SEED: int = 42
TRAIN_FRACTION: float = 0.60
VAL_FRACTION: float = 0.20
#: TEST_FRACTION is whatever is left (0.20); asserted in the tests.

# --------------------------------------------------------------------------- #
# Risk banding
# --------------------------------------------------------------------------- #
#: The band thresholds are *fitted on the validation split* by ``train.py`` and
#: written into the model card; these two defaults exist only so an unconfigured
#: consumer has a defined behaviour. ``train.py`` never reports a band produced
#: from a hand-picked number.
DEFAULT_MODERATE_THRESHOLD: float = 0.20
DEFAULT_HIGH_THRESHOLD: float = 0.50

#: Model version, stamped into the artefact, the API payload and the app card.
MODEL_VERSION: str = "future-risk-unimib-v1"

# --------------------------------------------------------------------------- #
# Forest geometry - selected by tune.py on the VALIDATION split only
# --------------------------------------------------------------------------- #
#: ``python ml/future_risk/tune.py`` searched 64 configurations on the validation
#: subjects and ranked them by validation PR-AUC. The unbounded-depth winner
#: (300 trees, val PR-AUC 0.8759) exports to a 20 MB JSON, which is not a serious
#: APK asset. Selection is therefore *quality subject to an artefact-size budget*,
#: which is a deployment constraint, not a test-set decision - and the -0.031
#: PR-AUC it costs is stated in the model card rather than hidden.
#:
#: Best configuration at or below ``MAX_ARTEFACT_BYTES``:
#:   n_estimators=120, max_depth=11, min_samples_leaf=4, max_features="sqrt",
#:   class_weight="balanced_subsample"  -> val PR-AUC 0.8451, val ROC-AUC 0.9709,
#:   exported forest 1.88 MB / 66 894 nodes.
N_ESTIMATORS: int = 120
MAX_DEPTH: int | None = 11
MIN_SAMPLES_LEAF: int = 4
MAX_FEATURES: str = "sqrt"
CLASS_WEIGHT: str = "balanced_subsample"

#: The size budget the selection above respects.
MAX_ARTEFACT_BYTES: int = 2_000_000

#: The cadence the Android app evaluates at: one 50-sample feature window every
#: ``MotionWindowDefaults.HOP_SAMPLES`` (32) samples. Used to report false-alarm
#: rates at the rate the phone would actually see them.
APP_EVALUATION_CADENCE_SECONDS: float = 0.64

#: Dataset citation, written verbatim into the model card and the API payload docs.
DATASET_CITATION: str = (
    "Micucci, D., Mobilio, M., Napoletano, P. (2017). UniMiB SHAR: A Dataset for "
    "Human Activity Recognition Using Acceleration Data from Smartphones. "
    "arXiv:1611.07688v2."
)

#: Short codes used by ``full_data.mat`` field names.
FALL_ACTIVITIES: tuple[str, ...] = (
    "FallingForw",
    "FallingBack",
    "FallingRight",
    "FallingLeft",
    "Syncope",
    "FallingBackSC",
    "FallingWithPS",
    "HittingObstacle",
)

ADL_ACTIVITIES: tuple[str, ...] = (
    "Walking",
    "Running",
    "GoingUpS",
    "GoingDownS",
    "Jumping",
    "SittingDown",
    "StandingUpFS",
    "LyingDownFS",
    "StandingUpFL",
)

ALL_ACTIVITIES: tuple[str, ...] = ADL_ACTIVITIES + FALL_ACTIVITIES

#: The subject-independence guarantee we make in the docs, asserted by the tests.
GROUP_COLUMN: str = "subject"
