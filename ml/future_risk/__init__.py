"""
future_risk - a genuinely supervised *future-event* model for the Tourist Safety app.

Everything in this package exists to answer one question honestly:

    "Given the last second of phone accelerometer data, will a hard fall impact
     happen within the next second?"

That question has a **real, timestamped outcome label** in a public dataset
(UniMiB SHAR: 30 subjects, 9 activities of daily living and 8 real falls,
recorded from a smartphone accelerometer), which is what separates this module
from ``ml/train.py`` (an unsupervised anomaly model over GeoLife trajectories)
and from ``ApproachRiskModel`` in the app (a published weighted checklist).

It is deliberately **not** a "30-minute danger prediction". Accelerometer data
cannot see 30 minutes into the future, the four datasets named in
``DATASETS.pdf`` contain no incident/injury outcome at all, and inventing one
would be the exact dishonesty the project's own docs forbid. The horizon is the
longest one the data actually supports, and it is measured below.

Sub-modules
-----------
``config``    paths, the horizon/window geometry, feature names, thresholds
``features``  the 22 accelerometer features (mirrored in Kotlin, byte-for-byte)
``dataset``   reads UniMiB SHAR raw trials and builds labelled (window, label) rows
``train``     leak-free subject-disjoint split, baselines, forest, calibration, export

Run it with ``python ml/future_risk/train.py`` (see the module docstring), or as
part of ``ml/run_all.ps1 -FutureRisk``.
"""

from __future__ import annotations

__all__ = ["config", "features", "dataset", "train"]
