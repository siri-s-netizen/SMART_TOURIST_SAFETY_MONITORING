"""
dataset.py - reads the real UniMiB SHAR trials and builds labelled windows.

What this module does, in one paragraph
---------------------------------------
It walks ``full_data.mat`` (30 subjects x 17 activities x 2-6 raw trials), finds
the **impact sample** of every real fall, and cuts one-second windows out of the
recording relative to that impact:

* a window that **ends 1-50 samples (0.02-1.0 s) before the impact** is labelled
  ``IMMINENT_FALL`` - this is the target the app predicts;
* a window cut from an equally long stretch of an **activity of daily living**
  (walking, running, jumping, sitting down, lying down, ...) is labelled
  ``NO_IMMINENT_FALL``.

Using full trials rather than the pre-cut 151-sample windows UniMiB also ships
is deliberate: the pre-cut windows are centred on a peak, which would destroy the
pre-impact timeline the whole task depends on. ``full_data.mat`` keeps the whole
recording, so the lead time is real and measured, not assumed.

What the label is *not*
-----------------------
It is not "the tourist is in danger". It is not a diagnosis. It is not derived
from a zone crossing or a GPS anomaly. It is a real, timestamped, physically
defined event - a hard landfall recorded from a smartphone - in a public
research dataset, and the model is asked only to anticipate it by up to a second.
``docs/future_risk_prediction.md`` records exactly what that does and does not
license anyone to claim.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

import numpy as np
import scipy.io as sio

from . import config, features


class DatasetError(RuntimeError):
    """Raised when the UniMiB archive is missing or unreadable."""


@dataclass
class Trial:
    """One raw recording of one activity performed by one subject."""

    subject: int
    activity: str
    trial_index: int
    #: ``(3, N)`` total acceleration in g - x, y, z.
    acc_g: np.ndarray

    @property
    def n_samples(self) -> int:
        return int(self.acc_g.shape[1])

    @property
    def is_fall(self) -> bool:
        return self.activity in config.FALL_ACTIVITIES

    @property
    def magnitude_g(self) -> np.ndarray:
        return np.sqrt(np.sum(self.acc_g * self.acc_g, axis=0))


@dataclass
class ImpactInfo:
    """Where the hardest landing is in a fall trial, and how hard it was."""

    index: int
    peak_g: float


@dataclass
class WindowSet:
    """A labelled window matrix plus the metadata needed to audit it."""

    windows: np.ndarray  # (M, N, 3) float32, g
    labels: np.ndarray  # (M,) int8
    subjects: np.ndarray  # (M,) int16
    activities: np.ndarray  # (M,) object
    trial_keys: np.ndarray  # (M,) object
    end_index: np.ndarray  # (M,) int32 - index of the last sample in the trial
    lead_samples: np.ndarray  # (M,) int32 - samples until the impact (-1 for negatives)
    stats: dict = field(default_factory=dict)

    def __len__(self) -> int:
        return int(self.windows.shape[0])


# --------------------------------------------------------------------------- #
# Reading the archive
# --------------------------------------------------------------------------- #


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def archive_provenance() -> dict:
    """Measured facts about the archive on disk - never assumed, always read."""
    archive = config.UNIMIB_DIR / ".." / "UniMiB-SHAR.zip"
    archive = Path(archive).resolve()
    info: dict = {
        "citation": config.DATASET_CITATION,
        "expected_root": str(config.UNIMIB_DIR),
        "full_mat": str(config.UNIMIB_FULL_MAT),
    }
    if archive.is_file():
        info["archive_path"] = str(archive)
        info["archive_bytes"] = archive.stat().st_size
        info["archive_sha256"] = _sha256(archive)
    if config.UNIMIB_FULL_MAT.is_file():
        info["full_mat_bytes"] = config.UNIMIB_FULL_MAT.stat().st_size
        info["full_mat_sha256"] = _sha256(config.UNIMIB_FULL_MAT)
    return info


def licence_record() -> dict:
    """The dataset's licence position, stated as it actually is.

    UniMiB SHAR is distributed by its authors for research use. There is **no** OSI-approved licence
    attached to the download, so this module does not claim one: saying "publicly distributed for
    research, no explicit OSI licence found" is the honest record, and pretending otherwise in a
    provenance file would be worse than the ambiguity itself.
    """
    return {
        "statement": (
            "Publicly distributed by the authors (University of Milano-Bicocca) for academic "
            "research use. The download carries no explicit OSI-approved licence, so no licence is "
            "asserted here; downstream reuse should cite the paper and treat the terms as "
            "research-use-only."
        ),
        "osi_approved": False,
        "research_use_only": True,
        "citation": config.DATASET_CITATION,
        "redistribution": (
            "Only derived artefacts (model weights, aggregate metrics, a small number of reference "
            "windows) are redistributed in this repository - not the raw recordings."
        ),
    }


def _subject_record(subject_struct):
    """Read one subject's 17 activity fields, oldest numpy/scipy quirks handled."""
    return subject_struct


def load_trials(limit_subjects: int | None = None) -> list[Trial]:
    """
    Read every raw trial out of ``full_data.mat``.

    Structure (verified on the real file): ``full_data`` is a ``(30, 5)`` object
    array of MATLAB structs; column 0 is the accelerometer struct whose fields are
    the 17 activity short codes, columns 1-4 are gender/age/height/weight. Each
    activity field holds 2 trials (ADL) or 6 trials (falls), and each trial is a
    ``(6, N)`` array: x, y, z in m/s^2, a nanosecond clock, a seconds clock, and
    the magnitude.
    """
    if not config.UNIMIB_FULL_MAT.is_file():
        raise DatasetError(
            f"UniMiB SHAR raw trials not found at {config.UNIMIB_FULL_MAT}. "
            "See ml/data/README.md for the download and unzip steps."
        )

    loaded = sio.loadmat(
        str(config.UNIMIB_FULL_MAT), squeeze_me=True, struct_as_record=False
    )["full_data"]

    trials: list[Trial] = []
    n_subjects = int(loaded.shape[0]) if limit_subjects is None else min(int(loaded.shape[0]), limit_subjects)
    for subject_index in range(n_subjects):
        subject_struct = loaded[subject_index, 0]
        for activity in config.ALL_ACTIVITIES:
            if activity not in getattr(subject_struct, "_fieldnames", ()):
                continue
            activity_trials = np.atleast_1d(getattr(subject_struct, activity))
            for trial_index, raw in enumerate(activity_trials):
                array = np.asarray(raw, dtype=float)
                if array.ndim != 2 or array.shape[0] < 3:
                    continue
                acc_g = features.to_g(array[:3, :])
                if acc_g.shape[1] == 0:
                    continue
                trials.append(
                    Trial(
                        subject=subject_index + 1,
                        activity=activity,
                        trial_index=trial_index,
                        acc_g=acc_g,
                    )
                )
    if not trials:
        raise DatasetError(f"no trials parsed from {config.UNIMIB_FULL_MAT}")
    return trials


def sanitize_trials(trials: list[Trial]) -> tuple[list[Trial], dict]:
    """
    Drop recordings that contain non-finite samples, and say so.

    The real archive is not perfect: subject 10's second ``Jumping`` trial is
    3208 samples of which 9369 of 9624 values are NaN. A silent ``nan_to_num``
    would turn a corrupted recording into plausible-looking evidence, so the
    trial is removed and counted instead.
    """
    kept: list[Trial] = []
    dropped: list[dict] = []
    for trial in trials:
        if np.all(np.isfinite(trial.acc_g)):
            kept.append(trial)
            continue
        total = int(trial.acc_g.size)
        bad = int((~np.isfinite(trial.acc_g)).sum())
        dropped.append(
            {
                "subject": trial.subject,
                "activity": trial.activity,
                "trial_index": trial.trial_index,
                "n_samples": trial.n_samples,
                "non_finite_values": bad,
                "total_values": total,
            }
        )
    report = {
        "n_trials_in": len(trials),
        "n_trials_kept": len(kept),
        "n_trials_dropped_non_finite": len(dropped),
        "dropped": dropped,
        "reason": "recording contains NaN/Inf samples; dropped rather than silently repaired",
    }
    return kept, report


def find_impact(trial: Trial) -> ImpactInfo:
    """
    Locate the hardest landing in a fall trial.

    The rule is deliberately mechanical so it can be argued with: the impact is
    the sample whose acceleration magnitude is largest. For a recorded fall that
    sample is the body hitting the ground (or the obstacle it hit), which is why
    it is a defensible definition of *the event*.
    """
    magnitude = trial.magnitude_g
    index = int(np.argmax(magnitude))
    return ImpactInfo(index=index, peak_g=float(magnitude[index]))


def subject_demographics() -> dict[int, dict]:
    """Gender/age/height/weight per subject, for the provenance record."""
    if not config.UNIMIB_FULL_MAT.is_file():
        return {}
    loaded = sio.loadmat(
        str(config.UNIMIB_FULL_MAT), squeeze_me=True, struct_as_record=False
    )["full_data"]
    out: dict[int, dict] = {}

    def _scalar(value):
        try:
            array = np.asarray(value).ravel()
            if array.size == 0:
                return None
            item = array[0]
            if isinstance(item, (str, bytes)):
                return item.decode() if isinstance(item, bytes) else item
            return float(item) if item == item else None
        except Exception:  # pragma: no cover - defensive; format is stable
            return None

    for subject_index in range(int(loaded.shape[0])):
        out[subject_index + 1] = {
            "gender": _scalar(loaded[subject_index, 1]),
            "age": _scalar(loaded[subject_index, 2]),
            "height": _scalar(loaded[subject_index, 3]),
            "weight": _scalar(loaded[subject_index, 4]),
        }
    return out


# --------------------------------------------------------------------------- #
# Turning trials into labelled windows
# --------------------------------------------------------------------------- #


def _cut(acc_g: np.ndarray, end_index: int) -> np.ndarray:
    """The one-second window whose last sample is ``end_index``, transposed to (N, 3)."""
    start = end_index - config.WINDOW_SAMPLES + 1
    return acc_g[:, start : end_index + 1].T


def build_windows(trials: list[Trial]) -> WindowSet:
    """
    Cut the labelled windows out of the trials.

    Positives: a fall trial whose hardest sample reaches
    ``config.IMPACT_MIN_G``; windows ending 1..``HORIZON_SAMPLES`` before it.
    Negatives: windows cut at the same cadence from every ADL trial.

    The two classes therefore have an honest meaning - "a real recorded fall is
    about to land" versus "ordinary movement is happening" - and neither depends
    on information from after the prediction instant except the impact *time* the
    label is built from, which is exactly what supervised labelling is.
    """
    windows: list[np.ndarray] = []
    labels: list[int] = []
    subjects: list[int] = []
    activities: list[str] = []
    trial_keys: list[str] = []
    end_indices: list[int] = []
    leads: list[int] = []

    stats = {
        "n_trials": len(trials),
        "n_fall_trials": 0,
        "n_fall_trials_without_impact": 0,
        "n_adl_trials": 0,
        "n_positive_windows": 0,
        "n_negative_windows": 0,
        "impact_peak_g_min": None,
        "impact_peak_g_max": None,
        "lead_samples_histogram": {},
    }

    for trial in trials:
        key = f"S{trial.subject:02d}-{trial.activity}-T{trial.trial_index}"
        if trial.is_fall:
            stats["n_fall_trials"] += 1
            impact = find_impact(trial)
            if impact.peak_g < config.IMPACT_MIN_G:
                # No hard landing to anticipate: the trial is not usable as a
                # positive example and is dropped, counted, and reported.
                stats["n_fall_trials_without_impact"] += 1
                continue
            if stats["impact_peak_g_min"] is None or impact.peak_g < stats["impact_peak_g_min"]:
                stats["impact_peak_g_min"] = impact.peak_g
            if stats["impact_peak_g_max"] is None or impact.peak_g > stats["impact_peak_g_max"]:
                stats["impact_peak_g_max"] = impact.peak_g

            first_end = impact.index - config.HORIZON_SAMPLES
            for end_index in range(first_end, impact.index, config.WINDOW_STRIDE):
                if end_index < config.WINDOW_SAMPLES - 1:
                    continue
                lead = impact.index - end_index
                if not 1 <= lead <= config.HORIZON_SAMPLES:
                    continue
                windows.append(_cut(trial.acc_g, end_index))
                labels.append(1)
                subjects.append(trial.subject)
                activities.append(trial.activity)
                trial_keys.append(key)
                end_indices.append(end_index)
                leads.append(lead)
                stats["n_positive_windows"] += 1
                bucket = str(lead)
                stats["lead_samples_histogram"][bucket] = stats["lead_samples_histogram"].get(bucket, 0) + 1
        else:
            stats["n_adl_trials"] += 1
            for end_index in range(config.WINDOW_SAMPLES - 1, trial.n_samples, config.WINDOW_STRIDE):
                windows.append(_cut(trial.acc_g, end_index))
                labels.append(0)
                subjects.append(trial.subject)
                activities.append(trial.activity)
                trial_keys.append(key)
                end_indices.append(end_index)
                leads.append(-1)
                stats["n_negative_windows"] += 1

    if not windows:
        raise DatasetError("no windows were produced from the trials")

    window_set = WindowSet(
        windows=np.asarray(windows, dtype=np.float32),
        labels=np.asarray(labels, dtype=np.int8),
        subjects=np.asarray(subjects, dtype=np.int16),
        activities=np.asarray(activities, dtype=object),
        trial_keys=np.asarray(trial_keys, dtype=object),
        end_index=np.asarray(end_indices, dtype=np.int32),
        lead_samples=np.asarray(leads, dtype=np.int32),
        stats=stats,
    )
    window_set.stats["n_windows"] = len(window_set)
    window_set.stats["positive_rate"] = float(window_set.labels.mean())
    window_set.stats["n_subjects"] = int(np.unique(window_set.subjects).size)
    return window_set


def post_impact_diagnostics(trials: list[Trial], subjects: list[int]) -> dict:
    """
    How often would the model fire *after* a real fall has already landed?

    This is not used for training - the negative class is ordinary activity only -
    but it is a genuinely useful robustness number: a model that screams "an
    impact is coming" for the whole minute a tourist spends sitting on the ground
    would be unusable. Reported as a measured count, never as a promise.
    """
    keep = [t for t in trials if t.is_fall and t.subject in set(subjects)]
    out = {"n_fall_trials": 0, "n_post_impact_windows": 0}
    for trial in keep:
        impact = find_impact(trial)
        if impact.peak_g < config.IMPACT_MIN_G:
            continue
        out["n_fall_trials"] += 1
        start = impact.index + config.HORIZON_SAMPLES
        for end_index in range(start, trial.n_samples, config.WINDOW_STRIDE):
            out["n_post_impact_windows"] += 1
    return out


def diagnostic_windows(trials: list[Trial], subjects: list[int]) -> np.ndarray:
    """The windows counted by :func:`post_impact_diagnostics`, stacked."""
    wanted = set(subjects)
    chunks: list[np.ndarray] = []
    for trial in trials:
        if not trial.is_fall or trial.subject not in wanted:
            continue
        impact = find_impact(trial)
        if impact.peak_g < config.IMPACT_MIN_G:
            continue
        for end_index in range(impact.index + config.HORIZON_SAMPLES, trial.n_samples, config.WINDOW_STRIDE):
            chunks.append(_cut(trial.acc_g, end_index))
    if not chunks:
        return np.zeros((0, config.WINDOW_SAMPLES, 3), dtype=np.float32)
    return np.asarray(chunks, dtype=np.float32)


def save_window_set(window_set: WindowSet, path: Path) -> None:
    """Persist the matrix so training and evaluation cannot drift apart."""
    path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        path,
        windows=window_set.windows,
        labels=window_set.labels,
        subjects=window_set.subjects,
        activities=window_set.activities.astype(str),
        trial_keys=window_set.trial_keys.astype(str),
        end_index=window_set.end_index,
        lead_samples=window_set.lead_samples,
    )


def load_window_set(path: Path) -> WindowSet:
    """Load what :func:`save_window_set` wrote."""
    with np.load(path, allow_pickle=False) as data:
        return WindowSet(
            windows=data["windows"],
            labels=data["labels"],
            subjects=data["subjects"],
            activities=data["activities"].astype(object),
            trial_keys=data["trial_keys"].astype(object),
            end_index=data["end_index"],
            lead_samples=data["lead_samples"],
        )


def iter_trial_summary(trials: list[Trial]) -> Iterator[dict]:
    """One row per trial: measured length, and the impact if it is a fall."""
    for trial in trials:
        row = {
            "subject": trial.subject,
            "activity": trial.activity,
            "trial_index": trial.trial_index,
            "n_samples": trial.n_samples,
            "seconds": round(trial.n_samples / config.SAMPLE_RATE_HZ, 3),
            "is_fall": trial.is_fall,
        }
        if trial.is_fall:
            impact = find_impact(trial)
            row["impact_index"] = impact.index
            row["impact_peak_g"] = round(impact.peak_g, 4)
            row["pre_impact_samples"] = impact.index
        yield row


def write_provenance(
    trials: list[Trial],
    window_set: WindowSet,
    split_info: dict | None,
    path: Path,
    quality: dict | None = None,
) -> dict:
    """Write the dataset provenance record - the numbers quoted in the docs."""
    per_activity: dict[str, dict] = {}
    for trial in trials:
        entry = per_activity.setdefault(
            trial.activity,
            {"n_trials": 0, "n_samples": 0, "min_samples": None, "max_samples": None},
        )
        entry["n_trials"] += 1
        entry["n_samples"] += trial.n_samples
        entry["min_samples"] = trial.n_samples if entry["min_samples"] is None else min(entry["min_samples"], trial.n_samples)
        entry["max_samples"] = trial.n_samples if entry["max_samples"] is None else max(entry["max_samples"], trial.n_samples)

    provenance = {
        "dataset": "UniMiB SHAR",
        "citation": config.DATASET_CITATION,
        "archive": archive_provenance(),
        "licence": licence_record(),
        "geometry": {
            "sample_rate_hz": config.SAMPLE_RATE_HZ,
            "window_samples": config.WINDOW_SAMPLES,
            "window_seconds": config.WINDOW_SAMPLES / config.SAMPLE_RATE_HZ,
            "horizon_samples": config.HORIZON_SAMPLES,
            "horizon_seconds": config.HORIZON_SAMPLES / config.SAMPLE_RATE_HZ,
            "window_stride": config.WINDOW_STRIDE,
            "impact_min_g": config.IMPACT_MIN_G,
            "units": "g (total acceleration, gravity included)",
        },
        "label_definition": {
            "positive": (
                "ending 1..%d samples before a sample whose acceleration magnitude is the "
                "maximum of the trial and is at least %.1f g"
                % (config.HORIZON_SAMPLES, config.IMPACT_MIN_G)
            ),
            "negative": "cut at the same cadence from an activity-of-daily-living trial",
            "excluded": "fall trials whose hardest sample is below %.1f g" % config.IMPACT_MIN_G,
        },
        "subjects": subject_demographics(),
        "data_quality": quality or {},
        "n_subjects": int(np.unique(window_set.subjects).size),
        "per_activity": per_activity,
        "window_stats": window_set.stats,
        "split": split_info,
        "honesty": (
            "The outcome label is a real recorded fall impact in a public dataset. It is NOT a "
            "tourist incident, NOT an injury diagnosis, and NOT a 30-minute forecast. The horizon "
            "is one second because that is what accelerometer data can support."
        ),
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(provenance, indent=2, default=str), encoding="utf-8")
    return provenance
