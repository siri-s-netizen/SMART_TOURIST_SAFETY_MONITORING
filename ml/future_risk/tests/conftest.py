"""pytest configuration for the future-risk tests: make ``ml/`` importable.

``ml/tests/conftest.py`` does the same for the older suites. pytest only loads the conftest files
that sit at or above a test file, so this package needs its own - the ``future_risk`` package is
imported as a top-level package (``from future_risk import config``), which only works with ``ml/``
on ``sys.path``.

The two session fixtures below exist because loading the UniMiB archive is the single slow step here.
They are shared across the file so the ~10 tests that need labelled windows pay for it once.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

ML_DIR = Path(__file__).resolve().parents[2]
if str(ML_DIR) not in sys.path:
    sys.path.insert(0, str(ML_DIR))

from future_risk import config, dataset  # noqa: E402


@pytest.fixture(scope="session")
def small_trials() -> list[dataset.Trial]:
    """The first three subjects, sanitised. Real recordings, small enough to be fast."""
    if not config.UNIMIB_FULL_MAT.exists():
        pytest.skip(f"UniMiB SHARE not unpacked at {config.UNIMIB_FULL_MAT}")
    trials = dataset.load_trials(limit_subjects=3)
    kept, _ = dataset.sanitize_trials(trials)
    return kept


@pytest.fixture(scope="session")
def small_window_set(small_trials) -> dataset.WindowSet:
    """Labelled windows for those three subjects, cut by the production code path."""
    return dataset.build_windows(small_trials)


@pytest.fixture(scope="session")
def metrics() -> dict:
    """The measured metrics of the last real run, or skip if the pipeline has not been run."""
    if not config.METRICS_JSON.exists():
        pytest.skip("run `python -m future_risk.train` first: ml/outputs/future_risk/metrics.json is absent")
    import json

    return json.loads(config.METRICS_JSON.read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def shipped_model() -> dict:
    """The artefact that was copied into the Android assets."""
    if not config.MODEL_JSON.exists():
        pytest.skip("run `python -m future_risk.train` first: the model artefact is absent")
    import json

    return json.loads(config.MODEL_JSON.read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def cases() -> dict:
    """The held-out windows and scikit-learn probabilities the Kotlin parity test replays."""
    if not config.REFERENCE_CASES.exists():
        pytest.skip("run `python -m future_risk.train` first: the reference cases are absent")
    import json

    return json.loads(config.REFERENCE_CASES.read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def provenance() -> dict:
    """The dataset provenance record."""
    if not config.PROVENANCE_JSON.exists():
        pytest.skip("run `python -m future_risk.train` first: the provenance file is absent")
    import json

    return json.loads(config.PROVENANCE_JSON.read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def model_card() -> str:
    """The model card, which the artefact points at by path."""
    path = config.REPO_ROOT / "docs" / "future_risk_prediction.md"
    if not path.is_file():
        pytest.skip("the model card has not been written yet: docs/future_risk_prediction.md")
    return path.read_text(encoding="utf-8")


@pytest.fixture(scope="session")
def tuning() -> dict:
    """The hyper-parameter search record."""
    if not (config.OUT_DIR / "tuning.json").exists():
        pytest.skip("run `python -m future_risk.tune` first: tuning.json is absent")
    import json

    return json.loads((config.OUT_DIR / "tuning.json").read_text(encoding="utf-8"))
