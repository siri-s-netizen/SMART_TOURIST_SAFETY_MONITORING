"""pytest configuration: make ``ml/`` importable and provide shared fixtures."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

ML_DIR = Path(__file__).resolve().parents[1]
if str(ML_DIR) not in sys.path:
    sys.path.insert(0, str(ML_DIR))

import common as C  # noqa: E402


@pytest.fixture(scope="session")
def synth_source() -> C.DataSource:
    """The SYNTHETIC .plt fixture (generated on demand)."""
    if not (C.SYNTHETIC_ROOT / "SYNTHETIC_000").exists():
        sys.path.insert(0, str(C.DATA_DIR))
        import make_synthetic_fixture  # type: ignore

        make_synthetic_fixture.build()
    return C.resolve_data_source(force_synthetic=True)


@pytest.fixture(scope="session")
def synth_points(synth_source: C.DataSource):
    """(raw_df, clean_df, audit) for the whole synthetic fixture."""
    refs = C.discover_trajectories(synth_source.root)
    raw, _ = C.read_plt_bulk(refs)
    clean, audit = C.clean_points(raw)
    return raw, clean, audit


@pytest.fixture(scope="session")
def synth_features(synth_points):
    _, clean, _ = synth_points
    return C.make_features(clean)


@pytest.fixture(scope="session")
def synth_bundle(synth_features):
    import train as T

    return T.fit_bundle(synth_features, n_estimators=50, contamination=0.1, seed=C.SEED)
