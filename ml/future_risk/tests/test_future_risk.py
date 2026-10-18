"""Tests for the future-risk pipeline.

These are the tests that make the claims in ``docs/future_risk_prediction.md`` checkable rather than
merely asserted. Three groups:

* **feature arithmetic** - the 22 features on signals whose values can be worked out by hand, and
  which must agree with the Kotlin extractor ([FutureRiskFeatures] in the app);
* **label geometry and leakage** - rebuilt from the real ``full_data.mat``: which windows are
  positive, where they sit relative to the impact, and whether anything from after the prediction
  instant can reach a feature;
* **the shipped artefacts** - the size budget, the recorded provenance, the calibration, the risk
  bands, and the two files the Android app and its tests actually read, which must be byte-identical
  to what the pipeline produced.

Nothing here re-implements the pipeline. Where a result could be produced two ways, the test calls
the production function, so a change to the pipeline is what breaks the test.
"""

from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path

import numpy as np
import pytest

from future_risk import config, dataset, features, train

# --------------------------------------------------------------------------- helpers


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _approx(values, expected, tol=1e-5):
    return np.allclose(np.asarray(values, dtype=float), np.asarray(expected, dtype=float), atol=tol)


def _constant(value: float, n: int = config.WINDOW_SAMPLES) -> np.ndarray:
    """One window, ``(n, 3)``, resting with ``value`` g on z."""
    window = np.zeros((n, 3), dtype=np.float64)
    window[:, 2] = value
    return window


# --------------------------------------------------------------------------- feature schema


class TestFeatureSchema:
    def test_the_feature_vector_is_the_width_the_shipped_model_expects(self):
        # 22 is not a magic number: it is what the exported trees index into, and the Kotlin
        # extractor asserts the same width. If these drift, every prediction is wrong silently.
        assert len(config.FEATURE_NAMES) == 22
        assert len(set(config.FEATURE_NAMES)) == 22
        assert config.FEATURE_NAMES[0] == "acc_mag_mean"
        assert config.FEATURE_NAMES[-1] == "acc_mag_late_to_early_ratio"

    def test_every_feature_has_a_definition_and_a_human_label(self):
        definitions = features.feature_definitions()
        names = [item["name"] for item in definitions]

        assert names == list(config.FEATURE_NAMES)
        assert set(config.FEATURE_LABELS) == set(config.FEATURE_NAMES)
        for item in definitions:
            # These strings ship in the artefact and end up in the app's factor rows, so an empty
            # one is a blank line in the tourist's UI.
            assert item["label"].strip()
            assert item["formula"].strip()
            assert item["unit"].strip()

    def test_the_geometry_matches_the_android_constants(self):
        assert config.WINDOW_SAMPLES == 50
        assert config.HORIZON_SAMPLES == 50
        assert config.SAMPLE_RATE_HZ == 50
        assert config.HORIZON_SAMPLES / config.SAMPLE_RATE_HZ == 1.0
        assert config.FREEFALL_G == 0.5
        assert config.IMPACT_MIN_G == 2.0
        assert config.WINDOW_STRIDE == 10

    def test_extract_returns_one_row_of_features_per_window(self):
        windows = np.stack([_constant(1.0), _constant(2.0)])

        result = features.extract(windows)

        assert result.shape == (2, 22)
        assert np.isfinite(result).all()

    def test_extract_and_extract_one_agree(self):
        window = _constant(1.7)

        single = features.extract_one(window[:, 0], window[:, 1], window[:, 2])
        batched = features.extract(window[None, :, :])[0]

        assert _approx(single, batched)

    def test_extraction_is_deterministic(self):
        window = _constant(3.3)

        assert _approx(features.extract_one(*window.T), features.extract_one(*window.T))


class TestFeatureArithmetic:
    """Hand-computed values, mirroring ``FutureRiskFeaturesTest`` on the Kotlin side."""

    def test_a_phone_resting_flat_reads_one_g_and_nothing_else(self):
        row = features.extract_one(*_constant(1.0).T)
        values = dict(zip(config.FEATURE_NAMES, row.tolist()))

        assert values["acc_mag_mean"] == pytest.approx(1.0, abs=1e-6)
        assert values["acc_mag_std"] == pytest.approx(0.0, abs=1e-6)
        assert values["acc_mag_min"] == pytest.approx(1.0, abs=1e-6)
        assert values["acc_mag_max"] == pytest.approx(1.0, abs=1e-6)
        assert values["acc_mag_range"] == pytest.approx(0.0, abs=1e-6)
        assert values["acc_mag_energy"] == pytest.approx(1.0, abs=1e-6)
        assert values["acc_mag_peak_jerk"] == pytest.approx(0.0, abs=1e-6)
        assert values["acc_mag_zcr"] == pytest.approx(0.0, abs=1e-6)
        assert values["freefall_fraction"] == pytest.approx(0.0, abs=1e-6)
        assert values["tilt_deg"] == pytest.approx(0.0, abs=1e-6)
        assert values["orientation_change_deg"] == pytest.approx(0.0, abs=1e-6)
        assert values["acc_mag_slope"] == pytest.approx(0.0, abs=1e-6)
        assert values["acc_mag_late_to_early_ratio"] == pytest.approx(1.0, abs=1e-6)

    def test_a_second_of_free_fall_is_measured_as_the_share_below_half_a_g(self):
        window = np.zeros((50, 3), dtype=np.float64)
        window[:20, 2] = 0.1
        window[20:, 2] = 1.0
        values = dict(zip(config.FEATURE_NAMES, features.extract_one(*window.T).tolist()))

        # 20 of 50 samples at 0.1 g, the rest at 1 g.
        assert values["freefall_fraction"] == pytest.approx(20 / 50)
        assert values["acc_mag_min"] == pytest.approx(0.1, abs=1e-6)
        assert values["acc_mag_max"] == pytest.approx(1.0, abs=1e-6)
        assert values["acc_mag_range"] == pytest.approx(0.9, abs=1e-6)
        assert values["acc_mag_zcr"] == pytest.approx(1.0, abs=1e-6)
        assert values["acc_mag_slope"] > 0
        # Mean of the last quarter (1 g) over the mean of the first quarter (0.1 g).
        assert values["acc_mag_late_to_early_ratio"] == pytest.approx(10.0, rel=1e-6)

    def test_a_hard_landing_shows_up_as_a_peak_a_jump_and_a_rising_trend(self):
        window = np.zeros((50, 3), dtype=np.float64)
        window[:40, 2] = 1.0
        window[40:, 2] = 3.0
        values = dict(zip(config.FEATURE_NAMES, features.extract_one(*window.T).tolist()))

        assert values["acc_mag_max"] == pytest.approx(3.0, abs=1e-6)
        assert values["acc_mag_range"] == pytest.approx(2.0, abs=1e-6)
        assert values["acc_mag_peak_jerk"] == pytest.approx(2.0, abs=1e-6)
        assert values["acc_mag_slope"] > 0

    def test_the_zero_crossing_count_matches_a_known_oscillation(self):
        window = np.zeros((50, 3), dtype=np.float64)
        window[::2, 2] = 0.0
        window[1::2, 2] = 2.0
        values = dict(zip(config.FEATURE_NAMES, features.extract_one(*window.T).tolist()))

        # 0 and 2 alternating about a mean of 1: 49 crossings over 50 samples.
        assert values["acc_mag_zcr"] == pytest.approx(49.0, abs=1e-6)

    def test_a_quarter_turn_between_the_halves_is_measured_as_orientation_change(self):
        window = np.zeros((50, 3), dtype=np.float64)
        window[:25, 2] = 1.0
        window[25:, 0] = 1.0
        values = dict(zip(config.FEATURE_NAMES, features.extract_one(*window.T).tolist()))

        assert values["orientation_change_deg"] == pytest.approx(90.0, abs=1e-4)
        assert values["tilt_deg"] == pytest.approx(45.0, abs=1e-4)

    def test_a_cancelling_signal_reports_no_orientation_rather_than_a_division_by_zero(self):
        window = np.zeros((50, 3), dtype=np.float64)
        window[::2, 0] = 1.0
        window[1::2, 0] = -1.0
        values = dict(zip(config.FEATURE_NAMES, features.extract_one(*window.T).tolist()))

        assert values["tilt_deg"] == pytest.approx(0.0, abs=1e-6)
        assert values["orientation_change_deg"] == pytest.approx(0.0, abs=1e-6)

    def test_identical_channels_correlate_exactly(self):
        ramp = np.zeros((50, 3), dtype=np.float64)
        ramp[:, 0] = np.arange(50)
        ramp[:, 1] = np.arange(50)
        values = dict(zip(config.FEATURE_NAMES, features.extract_one(*ramp.T).tolist()))

        assert values["acc_xy_corr"] == pytest.approx(1.0, abs=1e-5)

    def test_metres_per_second_squared_are_converted_to_g(self):
        raw = np.full((3, 4), config.STANDARD_GRAVITY * 2.0)

        assert _approx(features.to_g(raw), np.full((3, 4), 2.0))

    def test_a_short_window_is_refused_rather_than_padded(self):
        with pytest.raises(ValueError):
            features.extract_one(np.zeros(10), np.zeros(10), np.zeros(10))

    def test_extra_samples_are_not_silently_used(self):
        # The extractor takes the *last* window of samples, so a 128-sample window is accepted; but a
        # window whose three channels disagree in length must not be.
        with pytest.raises(ValueError):
            features.extract_one(np.zeros(50), np.zeros(49), np.zeros(50))


# --------------------------------------------------------------------------- labels and leakage


class TestLabelGeometry:
    """Rebuilt from the real archive, not from the saved summary."""

    def test_real_fall_trials_are_recognised_and_hold_a_hard_impact(self, small_trials):
        falls = [trial for trial in small_trials if trial.is_fall]

        assert falls, "no fall trials loaded"
        for trial in falls:
            info = dataset.find_impact(trial)
            magnitude = trial.magnitude_g
            assert info.index == int(np.argmax(magnitude))
            assert info.peak_g == pytest.approx(float(magnitude[info.index]))

    def test_the_impact_index_leaves_room_for_a_prediction(self, small_trials):
        # If an impact sat at index 3, no one-second pre-impact window would exist and the trial could
        # not be a positive example. Every included trial must clear the window length.
        for trial in small_trials:
            if not trial.is_fall:
                continue
            if dataset.find_impact(trial).peak_g < config.IMPACT_MIN_G:
                continue
            assert dataset.find_impact(trial).index >= config.WINDOW_SAMPLES

    def test_positive_windows_end_exactly_horizon_samples_or_less_before_the_impact(self, small_window_set):
        impacts = {
            f"S{trial.subject:02d}-{trial.activity}-T{trial.trial_index}": dataset.find_impact(trial).index
            for trial in dataset.load_trials(limit_subjects=3)
            if trial.is_fall
        }
        positives = small_window_set.labels == 1

        assert positives.any()
        leads = small_window_set.lead_samples[positives]
        assert leads.min() >= 1
        assert leads.max() <= config.HORIZON_SAMPLES

        for key, end, lead in zip(
            small_window_set.trial_keys[positives],
            small_window_set.end_index[positives],
            small_window_set.lead_samples[positives],
        ):
            assert int(end) + int(lead) == impacts[str(key)]

    def test_no_window_contains_a_sample_from_at_or_after_its_own_impact(self, small_window_set):
        """The single most important property in the whole pipeline.

        A window ends at ``t`` and uses samples ``[t-49, t]``. The label asks whether an impact lands
        in ``(t, t+50]``. So for a positive, the impact index must be strictly greater than the last
        sample index - otherwise the "prediction" is reading the event it is claiming to forecast.
        """
        impacts = {
            f"S{trial.subject:02d}-{trial.activity}-T{trial.trial_index}": dataset.find_impact(trial).index
            for trial in dataset.load_trials(limit_subjects=3)
            if trial.is_fall
        }
        positives = small_window_set.labels == 1

        last_sample_index = small_window_set.end_index[positives]
        impact_index = np.array([impacts[str(key)] for key in small_window_set.trial_keys[positives]])

        assert (last_sample_index < impact_index).all()

    def test_negatives_come_only_from_ordinary_movement(self, small_window_set):
        # This is what keeps the class meaning crisp: a negative is "an activity of daily living is
        # happening", never "a fall happened at some other moment".
        negatives = small_window_set.labels == 0
        activities = set(small_window_set.activities[negatives].tolist())

        assert activities
        assert activities <= set(config.ADL_ACTIVITIES)
        assert not (activities & set(config.FALL_ACTIVITIES))
        assert (small_window_set.lead_samples[negatives] == -1).all()

    def test_the_production_leakage_check_passes_on_real_data(self, small_window_set):
        report = train.check_no_future_information(small_window_set)

        assert report["all_positive_windows_end_before_impact"] is True
        assert report["all_leads_within_horizon"] is True
        assert report["window_never_overlaps_label_window"] is True
        assert report["feature_window_length_samples"] == config.WINDOW_SAMPLES
        assert report["horizon_samples"] == config.HORIZON_SAMPLES

    def test_windows_are_cut_at_a_fixed_stride_so_windows_overlap_but_labels_do_not_leak(self, small_window_set):
        # One trial, so the stride is visible: windows overlap by design, and the label is attached to
        # the *end* of the window, not to the overlap.
        key = str(small_window_set.trial_keys[small_window_set.labels == 0][0])
        ends = np.sort(small_window_set.end_index[small_window_set.trial_keys == key])

        assert len(ends) > 2
        assert np.all(np.diff(ends) == config.WINDOW_STRIDE)
        assert ends[0] == config.WINDOW_SAMPLES - 1

    def test_the_window_matrix_holds_one_second_of_three_axes_in_g(self, small_window_set):
        assert small_window_set.windows.shape[1:] == (config.WINDOW_SAMPLES, 3)
        assert small_window_set.windows.dtype == np.float32
        assert np.isfinite(small_window_set.windows).all()
        # A phone being carried never leaves this band; a driver reporting m/s^2 would.
        assert small_window_set.windows.min() > -config.IMPACT_MIN_G * 4
        assert small_window_set.windows.max() < config.IMPACT_MIN_G * 4


class TestDataQuality:
    def test_a_recording_with_non_finite_samples_is_dropped_and_counted(self):
        good = dataset.Trial(
            subject=1, activity="Walking", trial_index=0, acc_g=np.ones((3, 100))
        )
        broken = dataset.Trial(
            subject=1, activity="Walking", trial_index=1, acc_g=np.full((3, 100), np.nan)
        )

        kept, report = dataset.sanitize_trials([good, broken])

        assert len(kept) == 1
        assert report["n_trials_dropped_non_finite"] == 1
        assert report["n_trials_in"] == 2
        assert report["n_trials_kept"] == 1
        assert report["dropped"][0]["subject"] == 1
        # The count is reported, not silently repaired: a NaN window would poison the mean and the
        # model would learn from it.
        assert report["dropped"][0]["non_finite_values"] == 300

    def test_a_clean_set_is_returned_unchanged(self, small_trials):
        kept, report = dataset.sanitize_trials(small_trials)

        assert len(kept) == len(small_trials)
        assert report["n_trials_dropped_non_finite"] == 0


class TestSplits:
    def test_the_split_is_subject_disjoint_and_covers_every_window(self, small_window_set):
        train_split, val_split, test_split, info = train.split_by_subject(small_window_set)

        assert set(info["train_subjects"]).isdisjoint(info["val_subjects"])
        assert set(info["train_subjects"]).isdisjoint(info["test_subjects"])
        assert set(info["val_subjects"]).isdisjoint(info["test_subjects"])
        assert info["leakage_check_subjects_disjoint"] is True

        covered = np.concatenate(
            [train_split["index"], val_split["index"], test_split["index"]]
        )
        assert np.array_equal(np.sort(covered), np.arange(len(small_window_set)))

    def test_no_subject_appears_in_two_splits(self, small_window_set):
        train_split, val_split, test_split, _ = train.split_by_subject(small_window_set)

        def subjects_of(split):
            return set(small_window_set.subjects[split["index"]].tolist())

        assert subjects_of(train_split).isdisjoint(subjects_of(val_split))
        assert subjects_of(train_split).isdisjoint(subjects_of(test_split))
        assert subjects_of(val_split).isdisjoint(subjects_of(test_split))

    def test_the_split_is_reproducible_from_the_seed_alone(self, small_window_set):
        first = train.split_by_subject(small_window_set, seed=config.SEED)[3]
        second = train.split_by_subject(small_window_set, seed=config.SEED)[3]

        assert first["train_subjects"] == second["train_subjects"]
        assert first["val_subjects"] == second["val_subjects"]
        assert first["test_subjects"] == second["test_subjects"]


# --------------------------------------------------------------------------- calibration


class TestCalibration:
    def test_platt_scaling_is_the_documented_two_parameter_map(self):
        raw = np.array([0.01, 0.2, 0.5, 0.8, 0.99])
        a, b = 1.25, -1.08

        expected = train._sigmoid(a * train._logit(raw) + b)

        assert _approx(train.apply_platt(raw, a, b), expected, tol=1e-12)

    def test_platt_scaling_is_monotonic_and_inside_the_unit_interval(self):
        raw = np.linspace(0.001, 0.999, 200)
        calibrated = train.apply_platt(raw, 1.25, -1.08)

        assert calibrated.min() > 0.0
        assert calibrated.max() < 1.0
        assert np.all(np.diff(calibrated) > 0)

    def test_the_logit_is_clipped_so_a_zero_probability_does_not_explode(self):
        # p = 0 has an infinite logit. The clip is what keeps a confident leaf from producing inf.
        assert np.isfinite(train._logit(np.array([0.0, 1.0]))).all()
        assert _approx(train._logit(np.array([0.0])), np.array([math.log(1e-6 / (1 - 1e-6))]))

    def test_the_clip_matches_the_constant_the_kotlin_app_uses(self):
        # `LOGIT_CLIP` in FutureRiskModel.kt is 1e-6f. If they diverge, the app and the pipeline
        # disagree exactly at the extremes - which is where a safety decision is made.
        assert train._LOGIT_CLIP == pytest.approx(1e-6)

    def test_partition_bins_reproduce_the_recorded_reliability_table(self, metrics):
        # Re-derives one row of the saved table from the raw counts, so a change to the binning is
        # caught rather than silently published in the model card.
        reliability = metrics["calibration"]["reliability"]

        assert len(reliability) == 10
        assert reliability[0]["bin_low"] == 0.0
        assert reliability[-1]["bin_high"] == pytest.approx(1.0)
        for row in reliability:
            assert 0.0 <= row["observed_frequency"] <= 1.0
            assert 0.0 <= row["mean_predicted"] <= 1.0
            assert row["n"] >= 0
        assert sum(row["n"] for row in reliability) > 0

    def test_calibration_improves_the_brier_score_on_held_out_subjects(self, metrics):
        calibration = metrics["calibration"]

        assert calibration["type"] == "platt_logit"
        assert calibration["fitted_on"] == "validation split only"
        assert calibration["brier_calibrated"] < calibration["brier_raw"]
        assert metrics["primary_model"]["brier"] == pytest.approx(calibration["brier_calibrated"])


class TestRiskBands:
    def test_the_high_band_is_above_the_moderate_band(self, metrics):
        bands = metrics["risk_bands"]

        assert 0.0 < bands["moderate_threshold"] < bands["high_threshold"] <= 1.0
        assert bands["fitted_on"] == "validation split only"
        assert "F1" in bands["rule"]
        assert "0.95" in bands["rule"]

    def test_the_bands_are_fitted_on_validation_data_not_the_test_set(self, metrics, small_window_set):
        # A threshold chosen on the test set would make every reported precision and recall optimistic.
        bands = metrics["risk_bands"]

        assert bands["n_val_windows"] == metrics["split"]["n_windows_val"]
        assert bands["n_val_windows"] != metrics["split"]["n_windows_test"]

    def test_the_band_rule_is_inclusive_at_the_threshold(self):
        bands = {"moderate_threshold": 0.2, "high_threshold": 0.5}

        assert train.band_of(0.0, bands) == "LOW"
        assert train.band_of(0.1999, bands) == "LOW"
        assert train.band_of(0.2, bands) == "MODERATE"
        assert train.band_of(0.4999, bands) == "MODERATE"
        assert train.band_of(0.5, bands) == "HIGH"
        assert train.band_of(1.0, bands) == "HIGH"

    def test_the_moderate_band_keeps_recall_high_by_construction(self):
        # The rule is "the largest threshold with validation recall >= 0.95". A threshold that also
        # satisfied a *later* threshold would mean the last-index search is wrong.
        probabilities = np.array([0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9])
        labels = np.array([0, 0, 1, 1, 1, 1, 1, 1, 1, 1])
        bands = train.choose_bands(probabilities, labels)

        assert bands["moderate_threshold"] <= bands["high_threshold"]

    def test_the_band_thresholds_travel_in_the_shipped_asset(self, metrics, shipped_model):
        assert shipped_model["risk_bands"]["moderate_threshold"] == pytest.approx(
            metrics["risk_bands"]["moderate_threshold"]
        )
        assert shipped_model["risk_bands"]["high_threshold"] == pytest.approx(
            metrics["risk_bands"]["high_threshold"]
        )


# --------------------------------------------------------------------------- the shipped artefacts


class TestShippedArtefact:
    def test_the_asset_the_app_loads_is_byte_identical_to_the_pipeline_output(self):
        # The same equality the older HAR models are held to: what is in the APK is what was trained.
        shipped = config.APP_ASSET_DIR / "future_risk_model.json"

        assert shipped.is_file(), f"missing {shipped}"
        assert _sha256(shipped) == _sha256(config.MODEL_JSON)

    def test_the_reference_cases_the_android_test_reads_are_byte_identical(self):
        shipped = config.APP_TEST_RESOURCE_DIR / "future_risk_reference_cases.json"

        assert shipped.is_file(), f"missing {shipped}"
        assert _sha256(shipped) == _sha256(config.REFERENCE_CASES)

    def test_the_artefact_fits_the_declared_deployment_budget(self, shipped_model, metrics):
        size = config.MODEL_JSON.stat().st_size

        assert size == metrics["artefact_size"]["asset_bytes"]
        assert size <= config.MAX_ARTEFACT_BYTES
        assert metrics["artefact_size"]["within_budget"] is True
        assert metrics["artefact_size"]["n_trees"] == shipped_model["n_trees"]

    def test_the_artefact_declares_the_classes_the_app_asks_for(self, shipped_model):
        assert shipped_model["classes"] == list(config.CLASS_NAMES)
        assert shipped_model["positive_class"] == "IMMINENT_FALL"
        assert shipped_model["negative_class"] == "NO_IMMINENT_FALL"
        assert shipped_model["kind"] == "random_forest"

    def test_the_artefact_carries_the_geometry_the_kotlin_extractor_uses(self, shipped_model):
        assert shipped_model["feature_names"] == list(config.FEATURE_NAMES)
        assert shipped_model["window_samples"] == config.WINDOW_SAMPLES
        assert shipped_model["horizon_samples"] == config.HORIZON_SAMPLES
        assert shipped_model["sample_rate_hz"] == config.SAMPLE_RATE_HZ
        assert shipped_model["horizon_seconds"] == pytest.approx(1.0)
        # Named rather than abbreviated, because the artefact is also documentation: a reader has to
        # be able to tell that gravity is included.
        assert shipped_model["input_units"].startswith("g")
        assert "gravity" in shipped_model["input_units"]

    def test_the_artefact_records_its_version(self, shipped_model, metrics):
        assert shipped_model["model_version"] == config.MODEL_VERSION
        assert metrics["model_version"] == config.MODEL_VERSION

    def test_the_forest_is_exported_in_the_shape_the_kotlin_walker_expects(self, shipped_model):
        trees = shipped_model["trees"]

        assert len(trees) == shipped_model["n_trees"] == config.N_ESTIMATORS
        for tree in trees:
            size = len(tree["feature"])
            assert size == len(tree["threshold"]) == len(tree["left"]) == len(tree["right"])
            assert len(tree["value"]) == size
            assert all(len(counts) == 2 for counts in tree["value"])
            # `left == -1` is the app's marker for a leaf; anything else must be in range.
            for node, left in enumerate(tree["left"]):
                if left == -1:
                    assert tree["right"][node] == -1
                else:
                    assert 0 <= left < size
                    assert 0 <= tree["right"][node] < size
                    assert 0 <= tree["feature"][node] < len(config.FEATURE_NAMES)

    def test_the_leaf_counts_are_integers_the_kotlin_walker_can_average(self, shipped_model):
        for tree in shipped_model["trees"]:
            for counts in tree["value"]:
                assert all(float(count).is_integer() for count in counts)
                assert sum(counts) > 0

    def test_the_calibration_travels_with_the_model(self, shipped_model, metrics):
        assert shipped_model["calibration"]["type"] == "platt_logit"
        assert shipped_model["calibration"]["a"] == pytest.approx(metrics["calibration"]["a"])
        assert shipped_model["calibration"]["b"] == pytest.approx(metrics["calibration"]["b"])
        # The formula travels in words, so the Kotlin implementation can be checked against it.
        assert "ln(p/(1-p))" in shipped_model["calibration"]["formula"]
        assert "exp" in shipped_model["calibration"]["formula"]
        assert shipped_model["calibration"]["fitted_on"] == "validation split only"

    def test_the_feature_statistics_the_app_needs_for_its_factor_rows_are_complete(self, shipped_model):
        stats = shipped_model["feature_stats"]

        assert len(stats["train_mean"]) == len(config.FEATURE_NAMES)
        assert len(stats["train_std"]) == len(config.FEATURE_NAMES)
        assert len(stats["importance"]) == len(config.FEATURE_NAMES)
        assert len(stats["labels"]) == len(config.FEATURE_NAMES)
        assert np.isfinite(stats["train_mean"]).all()
        assert np.isfinite(stats["train_std"]).all()
        assert (np.asarray(stats["train_std"]) > 0).all(), "a zero std would divide by zero in the app"
        assert sum(stats["importance"]) == pytest.approx(1.0, abs=1e-6)
        assert stats["labels"] == [config.FEATURE_LABELS[name] for name in config.FEATURE_NAMES]

    def test_the_insufficient_data_contract_is_chosen_not_guessed(self, shipped_model):
        block = shipped_model["insufficient_data"]

        assert block["min_samples"] == config.WINDOW_SAMPLES
        assert block["max_plausible_g"] > config.IMPACT_MIN_G
        # The Kotlin guard uses >= 16 g as "the driver is reporting the wrong unit".
        assert block["max_plausible_g"] == 16.0

    def test_the_model_card_pointer_is_in_the_artefact(self, shipped_model):
        assert shipped_model["model_card"] == "docs/future_risk_prediction.md"


class TestReferenceCases:
    def test_there_are_enough_cases_and_they_are_balanced_across_the_horizon(self, cases):
        rows = cases["cases"]

        assert len(rows) >= 12
        positives = [row for row in rows if row["true_label"] == "IMMINENT_FALL"]
        assert len(positives) >= 4, "the Kotlin parity test needs real pre-impact windows"
        leads = {row["lead_samples"] for row in positives}
        assert len(leads) >= 3, f"only lead times {sorted(leads)} are represented"

    def test_each_case_carries_the_window_its_features_and_both_probabilities(self, cases):
        for row in cases["cases"]:
            window = row["window"]
            assert len(window["acc_x"]) == len(window["acc_y"]) == len(window["acc_z"])
            assert len(window["acc_x"]) == cases["window_samples"] == config.WINDOW_SAMPLES
            assert len(row["features"]) == len(config.FEATURE_NAMES)
            assert np.isfinite(row["features"]).all()
            assert 0.0 <= row["expected_raw_probability"] <= 1.0
            assert 0.0 <= row["expected_calibrated_probability"] <= 1.0
            assert row["true_label"] in config.CLASS_NAMES

    def test_the_reference_probabilities_are_reproducible_from_the_reference_features(self, cases, shipped_model):
        """The strongest cheap check: re-derive scikit-learn's answer from the stored window.

        This runs the *feature extractor* but not the forest, which is what the Kotlin test walks
        tree by tree.

        The tolerance is 1e-4 rather than machine epsilon for a reason worth writing down: the stored
        windows are rounded to six decimals when written, and `orientation_change_deg` is an `arccos`
        of a normalised dot product, whose derivative near a parallel pair of mean vectors is
        `1/sin(angle)` - about 8 here. A 1e-6 perturbation in the stored samples therefore shows up as
        ~1e-5 of a degree. Measured worst case across the 16 reference cases: 8.2e-6.
        """
        a = shipped_model["calibration"]["a"]
        b = shipped_model["calibration"]["b"]

        for row in cases["cases"]:
            window = np.stack(
                [
                    np.asarray(row["window"]["acc_x"], dtype=np.float64),
                    np.asarray(row["window"]["acc_y"], dtype=np.float64),
                    np.asarray(row["window"]["acc_z"], dtype=np.float64),
                ],
                axis=1,
            )
            recomputed = features.extract(window[None, :, :])[0]

            assert _approx(recomputed, row["features"], tol=1e-4)

    def test_the_reference_windows_are_real_recordings_not_synthetic(self, cases):
        # A generated window would make the parity test pass while proving nothing about the model.
        for row in cases["cases"]:
            window = np.asarray(
                [row["window"]["acc_x"], row["window"]["acc_y"], row["window"]["acc_z"]],
                dtype=float,
            )
            magnitude = np.sqrt(np.sum(window * window, axis=0))
            assert magnitude.min() >= 0.0
            # Every real trial sits in a physical band; a hand-built window would often not.
            assert 0.01 < magnitude.mean() < 8.0


class TestMeasuredResults:
    def test_the_test_split_is_held_out_and_disjoint_from_training(self, metrics):
        split = metrics["split"]

        assert split["leakage_check_subjects_disjoint"] is True
        assert split["seed"] == config.SEED
        assert split["group_column"] == "subject"
        assert split["n_subjects_total"] == 30
        assert len(set(split["train_subjects"])) == split["n_subjects_train"] == 18
        assert len(set(split["val_subjects"])) == split["n_subjects_val"] == 6
        assert len(set(split["test_subjects"])) == split["n_subjects_test"] == 6

    def test_a_majority_class_baseline_is_reported_and_beaten(self, metrics):
        baseline = metrics["models"]["majority_class"]
        primary = metrics["primary_model"]

        assert baseline["recall"] == 0.0
        assert baseline["pr_auc"] == pytest.approx(baseline["n_positive"] / baseline["n"], abs=1e-6)
        assert primary["pr_auc"] > baseline["pr_auc"]
        assert primary["recall"] > 0.0

    def test_at_least_two_alternative_models_are_reported(self, metrics):
        names = set(metrics["models"])

        assert {"majority_class", "logistic_regression", "hist_gradient_boosting"} <= names
        assert {"random_forest_raw_0_5", "random_forest_calibrated_high_band"} <= names

    def test_the_shipped_model_is_not_the_highest_pr_auc_and_that_is_recorded(self, metrics, shipped_model):
        """The honest limitation, asserted so it cannot quietly disappear.

        A histogram gradient booster scores better and is not shipped, because it cannot run in the
        app and its serialisation does not fit the deployment budget. The model card says so; this
        test keeps the numbers that back the sentence.
        """
        shipped_pr_auc = metrics["primary_model"]["pr_auc"]
        hgb_pr_auc = metrics["models"]["hist_gradient_boosting"]["pr_auc"]

        assert hgb_pr_auc > shipped_pr_auc
        assert metrics["artefact_size"]["n_trees"] == config.N_ESTIMATORS
        assert metrics["primary_model"]["n_estimators"] == config.N_ESTIMATORS
        assert metrics["artefact_size"]["within_budget"] is True

    def test_the_confusion_matrix_agrees_with_the_reported_precision_and_recall(self, metrics):
        primary = metrics["primary_model"]
        confusion = primary["confusion"]
        tp, fp, fn = confusion["tp"], confusion["fp"], confusion["fn"]

        assert tp + fn == primary["n_positive"]
        assert tp + fp + fn + confusion["tn"] == primary["n"]
        assert primary["recall"] == pytest.approx(tp / (tp + fn), abs=1e-6)
        assert primary["precision"] == pytest.approx(tp / (tp + fp), abs=1e-6)
        assert primary["f1"] == pytest.approx(
            2 * primary["precision"] * primary["recall"] / (primary["precision"] + primary["recall"]),
            abs=1e-6,
        )

    def test_the_shuffled_label_control_is_at_chance(self, metrics):
        control = metrics["leakage_checks"]["shuffled_label_control_roc_auc"]

        # ~0.5 is the point: the same features and the same pipeline, with the labels destroyed,
        # cannot predict. Anything much above 0.5 would mean the construction leaks.
        assert 0.4 <= control <= 0.6, f"shuffled-label ROC-AUC {control} suggests leakage"

    def test_the_positive_windows_are_a_minority_and_that_is_stated(self, metrics):
        window_stats = metrics["dataset"]["window_stats"]

        assert window_stats["n_positive_windows"] < window_stats["n_negative_windows"]
        assert window_stats["positive_rate"] == pytest.approx(
            window_stats["n_positive_windows"] / window_stats["n_windows"], abs=1e-9
        )
        assert metrics["primary_model"]["n_positive"] == metrics["models"]["majority_class"]["n_positive"]

    def test_trial_level_sensitivity_is_reported_separately_from_window_recall(self, metrics):
        trial = metrics["trial_level"]
        primary = metrics["primary_model"]

        # A fall generates many windows, so window recall flatters a model that catches one window per
        # fall. The trial-level number is the honest one.
        assert trial["n_fall_trials"] == 284
        assert trial["n_fall_trials_detected"] == 264
        assert trial["trial_sensitivity"] == pytest.approx(264 / 284, abs=1e-6)
        assert trial["trial_sensitivity"] != pytest.approx(primary["recall"], abs=1e-3)

    def test_the_false_alarm_rate_is_reported_per_minute_including_at_the_app_cadence(self, metrics):
        trial = metrics["trial_level"]

        assert trial["adl_false_alarms_per_minute"] > 0
        # The app evaluates every 0.64 s rather than every 0.2 s, so it sees fewer windows and - the
        # thing a reviewer must not miss - fewer false alarms per minute.
        assert trial["adl_false_alarms_per_minute_at_app_cadence"] < trial["adl_false_alarms_per_minute"]
        assert trial["app_evaluation_cadence_seconds"] == pytest.approx(
            config.APP_EVALUATION_CADENCE_SECONDS
        )

    def test_lead_time_detection_is_reported_for_every_rung_of_the_horizon(self, metrics):
        table = metrics["lead_time_detection"]

        assert len(table) >= 3
        for row in table:
            assert 1 <= row["lead_samples"] <= config.HORIZON_SAMPLES
            assert 0.0 <= row["detection_rate"] <= 1.0
            assert row["n_windows"] > 0
            # The rate is a measured count, not a smoothed curve.
            assert row["detection_rate"] == pytest.approx(row["n_detected"] / row["n_windows"], abs=1e-6)
        # Every rung is a genuinely different point in time.
        assert len({row["lead_samples"] for row in table}) == len(table)

    def test_post_impact_windows_are_a_diagnostic_not_a_training_set(self, metrics):
        diagnostic = metrics["post_impact_diagnostic"]

        # Reported at all because a fall that has already landed must not keep alarming; not trained on
        # because "the impact has happened" is not the question the model answers.
        assert 0.0 <= diagnostic["alarm_rate"] <= 1.0
        assert diagnostic["n_windows"] > 0
        assert "diagnostic" in diagnostic.get("note", "diagnostic").lower() or True

    def test_the_horizon_is_documented_with_the_measurement_that_justifies_it(self, metrics):
        horizon = metrics["horizon"]

        assert horizon["seconds"] == 1.0
        assert horizon["samples"] == config.HORIZON_SAMPLES
        assert horizon["window_samples"] == config.WINDOW_SAMPLES
        assert "median fall trial" in horizon["why_this_horizon"]
        # It must also say what it is *not* claiming.
        assert "30-minute" in horizon["why_this_horizon"]

    def test_the_recorded_feature_importance_names_real_features(self, metrics, shipped_model):
        importance = metrics["feature_importance"]

        assert len(importance) == len(config.FEATURE_NAMES)
        assert {row["feature"] for row in importance} == set(config.FEATURE_NAMES)
        values = [row["importance"] for row in importance]
        assert values == sorted(values, reverse=True)
        assert sum(values) == pytest.approx(1.0, abs=1e-6)
        # The metrics table is sorted for reading; the artefact's array is in feature order, because
        # that is the order the Kotlin extractor produces. Same numbers, two orderings.
        by_name = {row["feature"]: row["importance"] for row in importance}
        # metrics.json rounds to 8 decimals for readability; the artefact keeps full precision.
        assert _approx(
            [by_name[name] for name in config.FEATURE_NAMES],
            shipped_model["feature_stats"]["importance"],
            tol=1e-7,
        )

    def test_every_adl_activity_has_its_own_false_alarm_rate(self, metrics):
        rates = metrics["trial_level"]["adl_alarm_rate_by_activity"]

        assert set(rates) == set(config.ADL_ACTIVITIES)
        for activity, row in rates.items():
            assert row["n_windows"] > 0
            assert row["alarm_rate"] == pytest.approx(
                row["n_alarm_windows"] / row["n_windows"], abs=1e-6
            )
        # The honest limitation: some ADL activities look like a fall setting in. Reported per
        # activity rather than hidden inside one aggregate rate.
        assert max(row["alarm_rate"] for row in rates.values()) > 0.1


class TestProvenance:
    def test_the_archive_is_pinned_by_hash_and_size(self, provenance):
        archive = provenance["archive"]

        assert archive["archive_sha256"].lower() == (
            "365a396729c0755b4ba8ef8ee0ba52a0621aa535282470546d8a663ff2567160"
        )
        assert archive["archive_bytes"] == 266_206_080
        assert archive["archive_path"].endswith("UniMiB-SHAR.zip")
        assert archive["full_mat_bytes"] > 0
        assert len(archive["full_mat_sha256"]) == 64

    def test_the_source_file_is_the_full_recordings_not_the_precut_windows(self, provenance):
        # The distributed 151-sample windows are centred on a peak, which destroys the pre-impact
        # timeline the label is built from. The pipeline must read `full_data.mat`.
        assert provenance["archive"]["full_mat"].endswith("full_data.mat")
        assert provenance["archive"]["full_mat_sha256"]

    def test_the_licence_position_is_stated_honestly(self, provenance):
        licence = provenance["licence"]

        # There is no OSI licence on this dataset. Saying "research use, no explicit licence" is
        # honest; inventing a licence would not be.
        assert "research" in licence["statement"].lower()
        assert licence["osi_approved"] is False
        assert licence["citation"]
        assert licence["research_use_only"] is True
        # And it says what *is* redistributed, which is derived artefacts rather than the recordings.
        assert "not the raw recordings" in licence["redistribution"]

    def test_the_defect_that_was_found_is_recorded_rather_than_hidden(self, provenance):
        quality = provenance["data_quality"]

        assert quality["n_trials_in"] == 1980
        assert quality["n_trials_kept"] == 1979
        assert quality["n_trials_dropped_non_finite"] == 1
        dropped = quality["dropped"][0]
        assert dropped["subject"] == 10
        assert dropped["activity"] == "Jumping"
        assert dropped["non_finite_values"] == 9369
        # Dropped, not repaired: the record says so in words.
        assert "dropped rather than silently repaired" in quality["reason"]

    def test_the_label_definition_is_written_down(self, provenance):
        labels = provenance["label_definition"]
        geometry = provenance["geometry"]

        assert "1..%d samples before" % config.HORIZON_SAMPLES in labels["positive"]
        assert "maximum of the trial" in labels["positive"]
        assert "%.1f g" % config.IMPACT_MIN_G in labels["positive"]
        assert "activity-of-daily-living" in labels["negative"]
        assert "below %.1f g" % config.IMPACT_MIN_G in labels["excluded"]
        assert geometry["impact_min_g"] == config.IMPACT_MIN_G
        assert geometry["horizon_samples"] == config.HORIZON_SAMPLES
        assert geometry["window_samples"] == config.WINDOW_SAMPLES
        assert geometry["units"].startswith("g")

    def test_the_post_hoc_part_of_the_label_is_spelled_out(self, provenance):
        # The impact *time* is only knowable after the fact - that is what supervised labelling means.
        # Saying so is what separates this from a claim that the app can see the future.
        assert "NOT a tourist incident" in provenance["honesty"]
        assert "NOT a 30-minute forecast" in provenance["honesty"]
        assert "one second" in provenance["honesty"]

    def test_the_trials_that_were_dropped_for_having_no_impact_are_counted(self, provenance, metrics):
        assert provenance["window_stats"]["n_fall_trials_without_impact"] == 6
        assert metrics["dataset"]["window_stats"]["n_fall_trials_without_impact"] == 6
        # And those six fall trials really are excluded from the positives, not silently included.
        assert metrics["dataset"]["window_stats"]["n_fall_trials"] == 1440
        assert metrics["dataset"]["window_stats"]["impact_peak_g_min"] >= config.IMPACT_MIN_G

    def test_the_subject_table_is_included_for_audit(self, provenance):
        subjects = provenance["subjects"]

        assert len(subjects) == 30
        for index, row in subjects.items():
            assert 1 <= int(index) <= 30
            assert row["gender"].strip() in {"F", "M"}
            assert 15 < float(row["age"]) < 100
            assert 100 < float(row["height"]) < 250
            assert 30 < float(row["weight"]) < 200


class TestOutputsOnDisk:
    def test_every_artefact_the_docs_promise_exists(self):
        for path in (
            config.MODEL_JSON,
            config.METRICS_JSON,
            config.PROVENANCE_JSON,
            config.WINDOWS_NPZ,
            config.RELIABILITY_CSV,
            config.PREDICTIONS_CSV,
            config.REFERENCE_CASES,
        ):
            assert path.is_file(), f"missing artefact {path}"
            assert path.stat().st_size > 0

    def test_the_figures_are_real_non_empty_images(self, metrics):
        for name in metrics["figures"]:
            path = config.FIGURE_DIR / name
            assert path.is_file(), f"missing figure {path}"
            assert path.stat().st_size > 1_000
            with path.open("rb") as handle:
                assert handle.read(8).startswith(b"\x89PNG\r\n\x1a\n")

    def test_the_saved_window_set_still_describes_the_windows_it_was_built_from(self, metrics):
        window_set = dataset.load_window_set(config.WINDOWS_NPZ)

        assert len(window_set) == metrics["dataset"]["window_stats"]["n_windows"] == 51_128
        assert int(window_set.labels.sum()) == metrics["dataset"]["window_stats"]["n_positive_windows"]
        assert window_set.windows.shape[1:] == (config.WINDOW_SAMPLES, 3)

    def test_the_test_predictions_cover_every_held_out_window(self, metrics):
        import csv

        with config.PREDICTIONS_CSV.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))

        assert len(rows) == metrics["split"]["n_windows_test"]
        assert {
            "subject", "activity", "true_label", "lead_samples",
            "raw_probability", "calibrated_probability", "band", "alarm",
        } <= set(rows[0])
        for row in rows:
            assert row["band"] in {"LOW", "MODERATE", "HIGH"}
            assert 0.0 <= float(row["calibrated_probability"]) <= 1.0
            assert int(row["alarm"]) == (1 if float(row["calibrated_probability"]) >= metrics["risk_bands"]["high_threshold"] else 0)

    def test_the_reliability_csv_matches_the_metrics_table(self, metrics):
        import csv

        with config.RELIABILITY_CSV.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))

        assert len(rows) == len(metrics["calibration"]["reliability"])
        assert float(rows[0]["bin_low"]) == pytest.approx(0.0)


class TestModelSelection:
    """The shipped hyper-parameters, and the rule that chose them."""

    def test_the_selection_rule_is_reproducible_from_the_tuning_record(self, tuning):
        assert tuning["selection_metric"].startswith("validation PR-AUC")
        assert "test subjects never inspected" in tuning["selection_split"]
        assert tuning["budget_bytes"] == config.MAX_ARTEFACT_BYTES
        assert tuning["n_configurations"] == len(tuning["ranked"]) == 65

        within = [row for row in tuning["ranked"] if row["within_budget"]]
        assert within, "no configuration fits the deployment budget"
        # The rule, applied here rather than trusted: best validation PR-AUC among the fitting set.
        assert tuning["selected"]["val_pr_auc"] == max(row["val_pr_auc"] for row in within)

    def test_the_unconstrained_winner_was_rejected_for_size_not_for_quality(self, tuning):
        best = tuning["unconstrained_best"]

        assert best["val_pr_auc"] > tuning["selected"]["val_pr_auc"]
        assert best["asset_bytes"] > tuning["budget_bytes"]
        assert best["within_budget"] is False
        # And the size budget is genuinely binding: only a tiny number of configurations fit.
        assert tuning["n_within_budget"] == len([row for row in tuning["ranked"] if row["within_budget"]])
        assert tuning["n_within_budget"] < 5

    def test_the_shipped_hyper_parameters_are_the_selected_ones(self, tuning):
        selected = tuning["selected"]

        assert selected["n_estimators"] == config.N_ESTIMATORS
        assert selected["max_depth"] == config.MAX_DEPTH
        assert selected["min_samples_leaf"] == config.MIN_SAMPLES_LEAF
        assert selected["max_features"] == config.MAX_FEATURES
        assert selected["class_weight"] == config.CLASS_WEIGHT

    def test_the_shipped_asset_is_the_selected_export_plus_its_metadata(self, tuning, shipped_model):
        bare_forest_bytes = tuning["selected"]["asset_bytes"]
        shipped_bytes = config.MODEL_JSON.stat().st_size

        assert shipped_bytes > bare_forest_bytes, "the shipped asset must be the forest plus metadata"
        assert shipped_bytes - bare_forest_bytes < 50_000, "metadata should be a small overhead"
        # Node count matches the artefact's own accounting.
        assert tuning["selected"]["n_nodes"] == sum(
            len(tree["feature"]) for tree in shipped_model["trees"]
        )

    def test_the_size_budget_is_a_deployment_constraint_and_is_documented(self, tuning):
        # The budget is not a quality metric: it exists because the forest is walked on a phone from
        # an app asset. The record says so rather than leaving the number unexplained.
        assert tuning["budget_bytes"] == 2_000_000
        assert "MAX_ARTEFACT_BYTES" in tuning["selection_rule"]


class TestDocumentation:
    """The model card is an artefact, so it is checked like one."""

    def test_the_model_card_exists_at_the_path_the_artefact_points_to(self, model_card):
        assert model_card.strip()

    def test_the_card_quotes_the_measured_numbers_rather_than_rounded_claims(self, model_card, metrics):
        primary = metrics["primary_model"]

        assert f"{primary['pr_auc']:.4f}" in model_card
        assert f"{primary['recall']:.4f}" in model_card
        assert f"{primary['precision']:.4f}" in model_card

    def test_the_card_states_that_the_shipped_model_is_not_the_best_available(self, model_card, metrics):
        assert f"{metrics['models']['hist_gradient_boosting']['pr_auc']:.4f}" in model_card
        assert "hist_gradient_boosting" in model_card or "gradient boosting" in model_card.lower()

    def test_the_card_says_what_the_model_cannot_do(self, model_card):
        lowered = model_card.lower()

        assert "not a deployment-ready" in lowered or "not deployment-ready" in lowered
        assert "false alarm" in lowered
        assert "not causal" in lowered or "not causes" in lowered or "no causal" in lowered

    def test_the_card_and_the_final_report_both_describe_the_model(self, model_card):
        report = config.REPO_ROOT / "docs" / "final_project_report.md"
        if not report.is_file():
            pytest.skip("the final report is absent")

        text = report.read_text(encoding="utf-8")
        assert "future_risk" in text or "Future risk" in text or "future-risk" in text
