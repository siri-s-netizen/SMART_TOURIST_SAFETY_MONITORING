"""
features.py - the 22 accelerometer features, computed identically in Python and Kotlin.

The Kotlin mirror is ``app/src/main/java/com/touristsafety/app/motion/FutureRiskFeatures.kt``.
The two implementations are held together by
``FutureRiskReferenceTest``, which recomputes the features for real recorded
UniMiB windows from the shipped asset and asserts the numbers match the ones
Python produced, so they cannot silently drift apart.

Design notes
------------
* **Accelerometer only.** UniMiB SHAR records no gyroscope, so a gyroscope
  feature could not be trained on real data. Rather than invent one, this
  feature set uses the accelerometer alone; the app still has the six-channel
  UCI HAR features for the activity/impact models.
* **Time domain only.** No FFT, no filter, no DSP dependency: a mid-range phone
  computes all 22 features for a 50-sample window in microseconds.
* **Float64 accumulation, float32 result** - exactly what ``MotionFeatures`` does
  for the HAR models, so the Kotlin and Python agree to well under 1e-4 relative.
* Every feature is documented with its formula and its unit in
  ``FEATURE_LABELS`` / ``feature_definitions()``, and exported into the model
  artefact so the app, the model card and the API all quote the same thing.

The feature *order* is the contract with the exported forest (each tree node
stores a feature **index**). Reordering ``config.FEATURE_NAMES`` invalidates
every exported model - the reference test fails loudly if that happens.
"""

from __future__ import annotations

import math

import numpy as np

from . import config

#: Windows shorter than this are rejected: a one-second window is the unit of
#: meaning, and "features of three samples" would be noise dressed as evidence.
MIN_WINDOW_SAMPLES: int = config.WINDOW_SAMPLES

#: A mean-vector this small carries no usable orientation (the phone is in free
#: fall or the samples cancel out), so the orientation features report 0 instead
#: of a meaningless angle.
_MIN_VECTOR_NORM: float = 0.1

#: Floor for the late/early ratio denominator, so a still window yields a large
#: finite number rather than a division by zero.
_RATIO_FLOOR: float = 1e-3

_EPS: float = 1e-12


def feature_definitions() -> list[dict]:
    """The feature schema as data, for the artefact and the docs."""
    formulas = {
        "acc_mag_mean": "mean(sqrt(ax^2+ay^2+az^2))",
        "acc_mag_std": "population std of the magnitude",
        "acc_mag_min": "min(magnitude)",
        "acc_mag_max": "max(magnitude)",
        "acc_mag_range": "max(magnitude) - min(magnitude)",
        "acc_mag_energy": "mean(magnitude^2)",
        "acc_mag_peak_jerk": "max(|magnitude[i] - magnitude[i-1]|)",
        "acc_mag_zcr": "count of sign changes of (magnitude - mean(magnitude))",
        "acc_x_mean": "mean(ax)",
        "acc_y_mean": "mean(ay)",
        "acc_z_mean": "mean(az)",
        "acc_x_std": "population std of ax",
        "acc_y_std": "population std of ay",
        "acc_z_std": "population std of az",
        "acc_xy_corr": "Pearson correlation of ax and ay (0 when either is constant)",
        "acc_xz_corr": "Pearson correlation of ax and az",
        "acc_yz_corr": "Pearson correlation of ay and az",
        "freefall_fraction": "share of samples with magnitude < %.1f g" % config.FREEFALL_G,
        "tilt_deg": "angle between the mean acceleration vector and the device z axis",
        "orientation_change_deg": "angle between the mean vector of the first and second half",
        "acc_mag_slope": "least-squares slope of the magnitude against the sample index",
        "acc_mag_late_to_early_ratio": "mean(magnitude in last quarter) / mean(magnitude in first quarter)",
    }
    units = {
        "acc_mag_energy": "g^2",
        "acc_mag_zcr": "count",
        "acc_xy_corr": "unitless",
        "acc_xz_corr": "unitless",
        "acc_yz_corr": "unitless",
        "freefall_fraction": "fraction",
        "tilt_deg": "degrees",
        "orientation_change_deg": "degrees",
        "acc_mag_late_to_early_ratio": "ratio",
    }
    return [
        {
            "name": name,
            "label": config.FEATURE_LABELS[name],
            "formula": formulas[name],
            "unit": units.get(name, "g"),
        }
        for name in config.FEATURE_NAMES
    ]


def _population_std(values: np.ndarray, mean: np.ndarray) -> np.ndarray:
    """Population (ddof=0) standard deviation, matching numpy's default and the Kotlin."""
    centred = values - mean[..., None]
    return np.sqrt(np.mean(centred * centred, axis=-1))


def _correlation(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Row-wise Pearson correlation, returning 0 where either channel is constant."""
    a_centred = a - a.mean(axis=-1, keepdims=True)
    b_centred = b - b.mean(axis=-1, keepdims=True)
    numerator = np.sum(a_centred * b_centred, axis=-1)
    denominator = np.sqrt(np.sum(a_centred**2, axis=-1) * np.sum(b_centred**2, axis=-1))
    return np.where(denominator <= _EPS, 0.0, numerator / np.where(denominator <= _EPS, 1.0, denominator))


def _angle_between(u: np.ndarray, v: np.ndarray) -> np.ndarray:
    """Angle in degrees between two row vectors; 0 when either is too small to orient."""
    norm_u = np.linalg.norm(u, axis=-1)
    norm_v = np.linalg.norm(v, axis=-1)
    valid = (norm_u >= _MIN_VECTOR_NORM) & (norm_v >= _MIN_VECTOR_NORM)
    cos = np.sum(u * v, axis=-1) / np.where(valid, norm_u * norm_v, 1.0)
    cos = np.clip(cos, -1.0, 1.0)
    return np.where(valid, np.degrees(np.arccos(cos)), 0.0)


def extract(windows: np.ndarray) -> np.ndarray:
    """
    Feature matrix for a batch of windows.

    ``windows`` has shape ``(M, N, 3)`` and holds **total** acceleration in **g**
    (x, y, z). Returns ``(M, len(config.FEATURE_NAMES))`` as float64.
    """
    windows = np.asarray(windows, dtype=float)
    if windows.ndim != 3 or windows.shape[2] != 3:
        raise ValueError(f"expected windows of shape (M, N, 3), got {windows.shape}")
    n = windows.shape[1]
    if n < MIN_WINDOW_SAMPLES:
        raise ValueError(f"a window needs at least {MIN_WINDOW_SAMPLES} samples, got {n}")

    ax = windows[:, :, 0]
    ay = windows[:, :, 1]
    az = windows[:, :, 2]
    mag = np.sqrt(ax * ax + ay * ay + az * az)

    mag_mean = mag.mean(axis=-1)
    mag_std = _population_std(mag, mag_mean)
    mag_min = mag.min(axis=-1)
    mag_max = mag.max(axis=-1)
    mag_energy = np.mean(mag * mag, axis=-1)

    # Peak sample-to-sample change of the magnitude: the "suddenness" of the
    # hardest edge in the window, which is what a landing looks like.
    if n > 1:
        peak_jerk = np.abs(np.diff(mag, axis=-1)).max(axis=-1)
    else:  # pragma: no cover - guarded by MIN_WINDOW_SAMPLES
        peak_jerk = np.zeros_like(mag_mean)

    # Zero crossings of the mean-centred magnitude. ``np.signbit`` treats -0.0 as
    # negative, which is exactly what the Kotlin's raw-bit comparison does.
    signbits = np.signbit(mag - mag_mean[:, None])
    zcr = (signbits[:, 1:] != signbits[:, :-1]).sum(axis=-1).astype(float)

    x_mean = ax.mean(axis=-1)
    y_mean = ay.mean(axis=-1)
    z_mean = az.mean(axis=-1)

    # Free fall: the phone is accelerating toward the ground and the magnitude
    # collapses toward zero. The physical precursor of an impact.
    freefall_fraction = (mag < config.FREEFALL_G).mean(axis=-1)

    # Orientation: the mean acceleration vector is the best gravity proxy available
    # from a single accelerometer, and it is only meaningful when it is non-zero.
    mean_vector = windows.mean(axis=1)
    mean_norm = np.linalg.norm(mean_vector, axis=-1)
    valid_orientation = mean_norm >= _MIN_VECTOR_NORM
    tilt_cos = np.clip(z_mean / np.where(valid_orientation, mean_norm, 1.0), -1.0, 1.0)
    tilt_deg = np.where(valid_orientation, np.degrees(np.arccos(tilt_cos)), 0.0)

    half = n // 2
    orientation_change_deg = _angle_between(windows[:, :half, :].mean(axis=1), windows[:, half:, :].mean(axis=1))

    # Trend of the magnitude across the window, and the crude "is it building?"
    # ratio used as a coarse, robust companion to the slope.
    index = np.arange(n, dtype=float)
    index_centred = index - index.mean()
    index_var = float(np.sum(index_centred * index_centred))
    slope = (np.sum(index_centred[None, :] * (mag - mag_mean[:, None]), axis=-1) / index_var)

    quarter = n // 4
    if quarter >= 1:
        early = mag[:, :quarter].mean(axis=-1)
        late = mag[:, -quarter:].mean(axis=-1)
        late_to_early = late / np.maximum(early, _RATIO_FLOOR)
    else:  # pragma: no cover - guarded by MIN_WINDOW_SAMPLES
        late_to_early = np.ones_like(mag_mean)

    columns = [
        mag_mean,
        mag_std,
        mag_min,
        mag_max,
        mag_max - mag_min,
        mag_energy,
        peak_jerk,
        zcr,
        x_mean,
        y_mean,
        z_mean,
        _population_std(ax, x_mean),
        _population_std(ay, y_mean),
        _population_std(az, z_mean),
        _correlation(ax, ay),
        _correlation(ax, az),
        _correlation(ay, az),
        freefall_fraction,
        tilt_deg,
        orientation_change_deg,
        slope,
        late_to_early,
    ]
    matrix = np.stack(columns, axis=-1)
    if matrix.shape[1] != len(config.FEATURE_NAMES):  # pragma: no cover - self-check
        raise AssertionError(
            f"produced {matrix.shape[1]} features but the schema declares {len(config.FEATURE_NAMES)}"
        )
    if not np.all(np.isfinite(matrix)):
        raise ValueError("feature extraction produced a non-finite value")
    return matrix


def extract_one(ax, ay, az) -> np.ndarray:
    """Features for a single window, given three equal-length sequences in g."""
    window = np.stack([np.asarray(ax, dtype=float), np.asarray(ay, dtype=float), np.asarray(az, dtype=float)], axis=-1)
    return extract(window[None, :, :])[0]


def to_g(meters_per_second_squared: np.ndarray) -> np.ndarray:
    """UniMiB stores m/s^2; the app and the model work in g."""
    return np.asarray(meters_per_second_squared, dtype=float) / config.STANDARD_GRAVITY
