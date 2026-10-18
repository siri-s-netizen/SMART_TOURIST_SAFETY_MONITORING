"""
test_pipeline.py - pytest suite for the ML module.

Covers the required behaviours:
  * `.plt` schema validation
  * missing / malformed handling
  * feature engineering correctness
  * train/test leakage prevention by GROUPING BY trajectory/user id
  * reproducible training with a fixed seed
  * model save/load consistency
  * inference on valid input
  * graceful handling of invalid input

The suite runs entirely on the SYNTHETIC fixture so it is fast and does not
depend on the (large) real dataset.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

import common as C
import predict as P
import train as T

HEADER6 = [
    "Geolife trajectory",
    "WGS 84",
    "Altitude is in Feet",
    "Reserved 3",
    "0,2,255,My Track,0,0,2,8421376",
    "0",
]


def _plt_text(rows) -> str:
    return "\r\n".join(HEADER6 + list(rows)) + "\r\n"


# --------------------------------------------------------------------------- #
# schema validation
# --------------------------------------------------------------------------- #

def test_plt_schema_verified(synth_source):
    refs = C.discover_trajectories(synth_source.root)
    df, meta = C.read_plt_file(refs[0])
    assert meta["header_lines"] == C.PLT_HEADER_LINES == 6
    assert meta["n_points"] > 0
    for col in ("user_id", "trajectory_id", "lat", "lon", "altitude", "timestamp"):
        assert col in df.columns
    assert pd.api.types.is_datetime64_any_dtype(df["timestamp"])


def test_real_file_schema_if_present():
    """If the real GeoLife data is present, verify the documented schema on it."""
    if not C.GEOLIFE_DATA_DIR.is_dir():
        pytest.skip("real GeoLife data not present")
    refs = C.discover_trajectories(C.GEOLIFE_DATA_DIR)
    df, meta = C.read_plt_file(refs[0])
    assert meta["header_lines"] == 6
    assert meta["n_malformed"] == 0
    # the two independent time encodings agree to well under a second
    assert meta["days_vs_string_max_abs_diff_s"] < 1.0


def test_validate_points_schema_raises(synth_points):
    _, clean, _ = synth_points
    # missing a required column
    broken = clean.drop(columns=["lat"])
    with pytest.raises(C.SchemaError):
        C.validate_points_schema(broken)
    # empty frame
    with pytest.raises(C.SchemaError):
        C.validate_points_schema(pd.DataFrame())
    # non-datetime timestamp
    broken2 = clean.copy()
    broken2["timestamp"] = broken2["timestamp"].astype(str)
    with pytest.raises(C.SchemaError):
        C.validate_points_schema(broken2)
    # the good frame reports a sane summary
    report = C.validate_points_schema(clean)
    assert report["n_rows"] == len(clean)


def test_validate_points_schema_requires_features(synth_points):
    _, clean, _ = synth_points
    with pytest.raises(C.SchemaError):
        C.validate_points_schema(clean, require_features=True)  # no features yet


# --------------------------------------------------------------------------- #
# missing / malformed handling
# --------------------------------------------------------------------------- #

def test_malformed_lines_counted_and_dropped():
    good = "39.90,116.30,0,40,40330.375,2010-06-01,09:00:00"
    rows = [good, "garbage,row", good.replace("39.90", "39.91"),
            "39.92,116.32,0"]  # last has too few fields
    df, meta = C.parse_plt_text(_plt_text(rows), "u", "t")
    assert meta["n_malformed"] == 2
    assert len(df) == 2  # only the two well-formed rows survive


def test_invalid_coords_and_null_island_removed(synth_points):
    raw, clean, audit = synth_points
    # the fixture was built with a null-island point, a bad_lat point and a
    # teleport; invalid coordinates must be removed and counted
    assert audit["n_removed_invalid_coord"].sum() >= 2
    assert clean["lat"].between(*C.LAT_RANGE).all()
    assert clean["lon"].between(*C.LON_RANGE).all()
    assert not ((clean["lat"].abs() < 1e-9) & (clean["lon"].abs() < 1e-9)).any()


def test_duplicate_timestamp_removed(synth_points):
    _, clean, audit = synth_points
    assert audit["n_removed_duplicate_timestamp"].sum() >= 1
    for _, g in clean.groupby("traj_uid"):
        assert g["timestamp"].is_unique


def test_missing_timestamp_handling():
    rows = [
        "39.90,116.30,0,40,40330.375,2010-06-01,09:00:00",
        "39.91,116.31,0,40,,not-a-date,09:00:10",  # unparsable date/time
        "39.92,116.32,0,40,40330.3752430556,2010-06-01,09:00:20",
    ]
    df, meta = C.parse_plt_text(_plt_text(rows), "u", "t")
    assert len(df) == 2
    assert meta["n_malformed"] == 1


# --------------------------------------------------------------------------- #
# feature engineering
# --------------------------------------------------------------------------- #

def test_feature_engineering_finite_and_bounded(synth_features):
    f = synth_features
    for col in C.FEATURE_COLUMNS:
        assert np.isfinite(f[col].to_numpy(dtype=float)).all()
    assert f["turn_deg"].between(0, 180).all()
    assert f["straightness_w5"].between(0, 1).all()
    assert (f["step_m"] >= 0).all()


def test_features_do_not_cross_trajectory_boundaries(synth_features):
    # the first point of every trajectory has no predecessor -> accel/turn = 0
    f = synth_features.sort_values(["traj_uid", "timestamp"]).reset_index(drop=True)
    firsts = f.groupby("traj_uid", sort=False).head(1)
    assert (firsts["accel_mps2"] == 0).all()
    assert (firsts["turn_deg"] == 0).all()


def test_features_identical_for_identical_trajectories():
    base = _plt_text(
        [f"39.90{i:03d},116.30{i:03d},0,40,{40330.375 + i/1000:.12f},2010-06-01,09:00:{i:02d}"
         for i in range(0, 30)]
    )
    d1, _ = C.parse_plt_text(base, "u", "trajA")
    d2, _ = C.parse_plt_text(base, "u", "trajB")
    both = pd.concat([d1, d2], ignore_index=True)
    clean, _ = C.clean_points(both)
    feats = C.make_features(clean)
    a = feats[feats["traj_uid"] == "u|trajA"].reset_index(drop=True)
    b = feats[feats["traj_uid"] == "u|trajB"].reset_index(drop=True)
    pd.testing.assert_frame_equal(
        a[list(C.FEATURE_COLUMNS)], b[list(C.FEATURE_COLUMNS)]
    )


def test_traj_uid_disambiguates_same_trajectory_id_across_users():
    """The core collision bug: same trajectory file name, different users."""
    refs_a = C.parse_plt_text(
        _plt_text(["39.90,116.30,0,40,40330.375,2010-06-01,09:00:00"]), "000", "T1"
    )[0]
    refs_b = C.parse_plt_text(
        _plt_text(["40.10,116.50,0,40,40330.375,2010-06-01,09:00:00"]), "001", "T1"
    )[0]
    both = pd.concat([refs_a, refs_b], ignore_index=True)
    assert set(both["trajectory_id"]) == {"T1"}
    assert set(both["traj_uid"]) == {"000|T1", "001|T1"}
    clean, audit = C.clean_points(both)
    # must remain two separate groups, not one merged trajectory
    assert clean["traj_uid"].nunique() == 2
    assert audit["traj_uid"].nunique() == 2


# --------------------------------------------------------------------------- #
# leakage prevention (grouped split)
# --------------------------------------------------------------------------- #

def test_grouped_split_prevents_leakage(synth_features):
    train_df, test_df, info = C.grouped_trajectory_split(
        synth_features, test_frac=0.4, seed=C.SEED, group_col="traj_uid"
    )
    assert info["train_groups"] and info["test_groups"]  # both non-empty
    train_g, test_g = set(info["train_groups"]), set(info["test_groups"])
    assert train_g.isdisjoint(test_g)
    assert set(train_df["traj_uid"]).isdisjoint(set(test_df["traj_uid"]))
    assert info["leakage_check_groups_disjoint"] is True
    # every trajectory lands in exactly one side
    assert set(train_df["traj_uid"]) | set(test_df["traj_uid"]) == set(synth_features["traj_uid"])


def test_grouped_split_deterministic(synth_features):
    a = C.grouped_trajectory_split(synth_features, test_frac=0.4, seed=7)[2]
    b = C.grouped_trajectory_split(synth_features, test_frac=0.4, seed=7)[2]
    assert a["train_groups"] == b["train_groups"]
    assert a["test_groups"] == b["test_groups"]


# --------------------------------------------------------------------------- #
# reproducible training + save/load
# --------------------------------------------------------------------------- #

def test_reproducible_training_with_seed(synth_features):
    b1 = T.fit_bundle(synth_features, n_estimators=40, contamination=0.1, seed=C.SEED)
    b2 = T.fit_bundle(synth_features, n_estimators=40, contamination=0.1, seed=C.SEED)
    s1 = T.score_bundle(b1, synth_features)
    s2 = T.score_bundle(b2, synth_features)
    np.testing.assert_allclose(s1, s2, rtol=0, atol=0)


def test_model_save_load_consistency(synth_bundle, synth_features, tmp_path):
    import joblib

    path = tmp_path / "m.joblib"
    joblib.dump(synth_bundle, path)
    loaded = joblib.load(path)
    np.testing.assert_allclose(
        T.score_bundle(synth_bundle, synth_features),
        T.score_bundle(loaded, synth_features),
        rtol=0,
        atol=0,
    )
    assert list(loaded["feature_columns"]) == list(C.FEATURE_COLUMNS)


def test_scores_are_finite(synth_bundle, synth_features):
    s = T.score_bundle(synth_bundle, synth_features)
    assert np.isfinite(s).all()
    assert s.shape[0] == len(synth_features)


# --------------------------------------------------------------------------- #
# inference on valid / invalid input
# --------------------------------------------------------------------------- #

def test_inference_valid_input(synth_bundle, synth_source, tmp_path, monkeypatch):
    import joblib

    model_path = tmp_path / "model.joblib"
    split_path = tmp_path / "split.json"
    joblib.dump(synth_bundle, model_path)
    split_path.write_text("{}", encoding="utf-8")
    monkeypatch.setattr(T, "MODEL_PATH", model_path)
    monkeypatch.setattr(T, "SPLIT_PATH", split_path)

    refs = C.discover_trajectories(synth_source.root)
    plt_file = refs[0].path
    rc = P.main(["--plt", str(plt_file)])
    assert rc == 0


def test_inference_invalid_input_missing_file(synth_bundle, tmp_path, monkeypatch):
    import joblib

    model_path = tmp_path / "model.joblib"
    joblib.dump(synth_bundle, model_path)
    monkeypatch.setattr(T, "MODEL_PATH", model_path)

    rc = P.main(["--plt", str(tmp_path / "does_not_exist.plt")])
    assert rc == 2  # graceful, not an exception


def test_inference_invalid_csv_schema(synth_bundle, tmp_path, monkeypatch):
    import joblib

    model_path = tmp_path / "model.joblib"
    joblib.dump(synth_bundle, model_path)
    monkeypatch.setattr(T, "MODEL_PATH", model_path)

    bad = tmp_path / "bad.csv"
    bad.write_text("foo,bar\n1,2\n", encoding="utf-8")
    rc = P.main(["--csv", str(bad)])
    assert rc == 2


def test_inference_valid_csv(synth_bundle, tmp_path, monkeypatch):
    import joblib

    model_path = tmp_path / "model.joblib"
    joblib.dump(synth_bundle, model_path)
    monkeypatch.setattr(T, "MODEL_PATH", model_path)

    rows = ["timestamp,lat,lon"]
    for i in range(30):
        rows.append(f"2010-06-01 09:{i:02d}:00,39.9{i%10},116.30{i%10}")
    csv = tmp_path / "track.csv"
    csv.write_text("\n".join(rows), encoding="utf-8")
    rc = P.main(["--csv", str(csv), "--topk", "2"])
    assert rc == 0
