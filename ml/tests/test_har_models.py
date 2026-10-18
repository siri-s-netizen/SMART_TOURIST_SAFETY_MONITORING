"""
test_har_models.py - pytest suite for the trained HAR activity model and the impact/fall detector.

These are the artefacts the Android app actually ships, so the suite guards the *contract* the Kotlin
code depends on rather than the training code's internals:

  * the tree JSON schema, and the fact that its ``value`` arrays are real integer class counts
    (the original exporter wrote ``np.rint(tree.value)``, which silently collapsed every leaf to a
    one-hot vector because scikit-learn's ``tree_.value`` holds *fractions*, not counts -- that bug
    would have shipped a subtly wrong model, so it gets a regression test);
  * the exported forest reproducing scikit-learn's own probabilities on real held-out windows;
  * the 24 deployable features being named and ordered exactly as ``MotionFeatures.kt`` implements
    them;
  * the model card not misstating the size of the test split;
  * the files under ``app/src/main/assets`` and ``app/src/test/resources`` being byte-identical to
    the training outputs, so "I forgot to copy the retrained model" cannot happen quietly.

Everything here reads committed artefacts only; it is fast and needs no dataset.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]

ACTIVITY_MODEL = REPO_ROOT / "ml" / "outputs" / "har" / "train" / "har_activity_model.json"
ACTIVITY_SPEC = REPO_ROOT / "ml" / "outputs" / "har" / "train" / "har_activity_model_spec.json"
ACTIVITY_CASES = REPO_ROOT / "ml" / "outputs" / "har" / "train" / "har_reference_cases.json"
FALL_MODEL = REPO_ROOT / "ml" / "outputs" / "har" / "fall" / "har_fall_model.json"
FALL_CASES = REPO_ROOT / "ml" / "outputs" / "har" / "fall" / "har_fall_reference_cases.json"

# (training output, the copy the app ships)
SHIPPED_PAIRS = [
    (ACTIVITY_MODEL, REPO_ROOT / "app" / "src" / "main" / "assets" / "har_activity_model.json"),
    (ACTIVITY_SPEC, REPO_ROOT / "app" / "src" / "main" / "assets" / "har_activity_model_spec.json"),
    (FALL_MODEL, REPO_ROOT / "app" / "src" / "main" / "assets" / "har_fall_model.json"),
    (
        ACTIVITY_CASES,
        REPO_ROOT / "app" / "src" / "test" / "resources" / "har_activity_reference_cases.json",
    ),
    (
        FALL_CASES,
        REPO_ROOT / "app" / "src" / "test" / "resources" / "har_fall_reference_cases.json",
    ),
]

# The deployable feature set, in order. This list is the contract: it must match
# MotionFeatures.kt exactly, and it must match what the exporter wrote.
DEPLOYABLE_FEATURES = [
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
    "gyro_mag_mean",
    "gyro_mag_std",
    "gyro_mag_max",
    "gyro_mag_energy",
    "gyro_x_std",
    "gyro_y_std",
    "gyro_z_std",
]

WINDOW_SAMPLES = 128
SAMPLE_RATE_HZ = 50


# --------------------------------------------------------------------------- #
# helpers: a tiny re-implementation of the exported forest
# --------------------------------------------------------------------------- #

def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def tree_probabilities(tree: dict, features: list[float]) -> list[float]:
    """Walk one exported tree and return the normalised class distribution at its leaf."""
    node = 0
    while tree["left"][node] != -1:
        if features[tree["feature"][node]] <= tree["threshold"][node]:
            node = tree["left"][node]
        else:
            node = tree["right"][node]
    counts = tree["value"][node]
    total = sum(counts)
    assert total > 0, "a leaf with no training samples must never be exported"
    return [count / total for count in counts]


def forest_probabilities(model: dict, features: list[float]) -> list[float]:
    n_classes = len(model["classes"])
    totals = [0.0] * n_classes
    for tree in model["trees"]:
        for index, probability in enumerate(tree_probabilities(tree, features)):
            totals[index] += probability
    return [total / len(model["trees"]) for total in totals]


@pytest.fixture(scope="module")
def activity_model() -> dict:
    return load(ACTIVITY_MODEL)


@pytest.fixture(scope="module")
def fall_model() -> dict:
    return load(FALL_MODEL)


# --------------------------------------------------------------------------- #
# schema contract
# --------------------------------------------------------------------------- #

@pytest.mark.parametrize(
    "path", [ACTIVITY_MODEL, ACTIVITY_SPEC, ACTIVITY_CASES, FALL_MODEL, FALL_CASES]
)
def test_artefacts_exist(path: Path):
    assert path.is_file(), f"{path} is missing -- run ml/har_train.py / ml/har_fall.py"


def test_activity_model_contract(activity_model: dict):
    assert activity_model["kind"] == "random_forest"
    assert activity_model["feature_names"] == DEPLOYABLE_FEATURES
    assert activity_model["classes"] == [
        "WALKING",
        "WALKING_UPSTAIRS",
        "WALKING_DOWNSTAIRS",
        "SITTING",
        "STANDING",
        "LAYING",
    ]
    assert activity_model["n_trees"] == len(activity_model["trees"]) == 32


def test_fall_model_contract(fall_model: dict):
    assert fall_model["kind"] == "fall_detector"
    # The detector is binary and its labels are honest about what they are: a synthesised impact
    # signature was injected to create the positive class (see docs/model_card_movement.md).
    assert fall_model["classes"] == ["NO_FALL", "FALL"]
    assert fall_model["n_trees"] == len(fall_model["trees"]) == 40
    # Same 24 features, same order -- the app feeds one feature vector to both models.
    assert fall_model["feature_names"] == DEPLOYABLE_FEATURES


@pytest.mark.parametrize("model_fixture", ["activity_model", "fall_model"])
def test_trees_are_well_formed(model_fixture, request):
    model = request.getfixturevalue(model_fixture)
    n_classes = len(model["classes"])
    for tree_index, tree in enumerate(model["trees"]):
        n_nodes = len(tree["feature"])
        for key in ("threshold", "left", "right", "value"):
            assert len(tree[key]) == n_nodes, f"tree {tree_index}: {key} has the wrong length"
        for node in range(n_nodes):
            is_leaf = tree["left"][node] == -1
            assert len(tree["value"][node]) == n_classes
            if is_leaf:
                # -1 means "no child"; -2 is the exporter's padding for an unused feature/threshold.
                assert tree["right"][node] == -1
            else:
                assert 0 <= tree["feature"][node] < len(DEPLOYABLE_FEATURES)
                assert 0 <= tree["left"][node] < n_nodes
                assert 0 <= tree["right"][node] < n_nodes


@pytest.mark.parametrize("model_fixture", ["activity_model", "fall_model"])
def test_exported_values_are_integer_counts_not_rounded_fractions(model_fixture, request):
    """
    Regression guard for the exporter bug.

    scikit-learn stores ``tree_.value`` as *class fractions*; the first version of the exporter did
    ``np.rint(tree.value)``, which turned every node into a one-hot vector of 0s and 1s. Walking that
    forest would have produced near-arbitrary answers while every other test still passed. Real counts
    are integers and the root node holds every sample that reached the tree, so its sum is large.
    """
    model = request.getfixturevalue(model_fixture)
    mixed_nodes = 0
    for tree_index, tree in enumerate(model["trees"]):
        for node, row in enumerate(tree["value"]):
            assert all(isinstance(count, int) for count in row), (
                f"tree {tree_index} node {node}: value {row} is not an integer count"
            )
            assert sum(row) >= 1, f"tree {tree_index} node {node}: no training samples"
            if sum(1 for count in row if count > 0) > 1:
                mixed_nodes += 1
        root_total = sum(tree["value"][0])
        assert root_total >= 100, (
            f"tree {tree_index}: root holds only {root_total} sample(s) -- these look like rounded "
            "fractions rather than the real counts scikit-learn stores in tree_.value"
        )
    assert mixed_nodes > 0, "no node carries more than one class: the counts were collapsed"


# --------------------------------------------------------------------------- #
# the exported forest must reproduce scikit-learn
# --------------------------------------------------------------------------- #

def test_exported_activity_forest_matches_scikit_learn(activity_model: dict):
    cases = load(ACTIVITY_CASES)
    assert cases["window_samples"] == WINDOW_SAMPLES
    assert cases["sample_rate_hz"] == SAMPLE_RATE_HZ
    assert len(cases["cases"]) >= 10

    for case in cases["cases"]:
        features = case["features"]
        assert len(features) == len(DEPLOYABLE_FEATURES)
        got = forest_probabilities(activity_model, features)
        expected = case["expected_probabilities"]
        assert len(expected) == len(activity_model["classes"])
        for index, (a, b) in enumerate(zip(got, expected)):
            assert abs(a - b) < 1e-5, (
                f"class {activity_model['classes'][index]} differs: exported {a} vs scikit-learn {b}"
            )
        # and the decisions agree, which is what the UI actually shows
        predicted = activity_model["classes"][got.index(max(got))]
        assert predicted == case["expected_activity"]


def test_exported_fall_forest_matches_scikit_learn(fall_model: dict):
    cases = load(FALL_CASES)
    assert cases["classes"] == fall_model["classes"]
    assert len(cases["cases"]) >= 10

    for case in cases["cases"]:
        features = case["features"]
        got = forest_probabilities(fall_model, features)
        for index, (a, b) in enumerate(zip(got, case["expected_probabilities"])):
            assert abs(a - b) < 1e-5, (
                f"class {fall_model['classes'][index]} differs: exported {a} vs scikit-learn {b}"
            )


def test_fall_reference_cases_contain_both_classes(fall_model: dict):
    """A reference set with only one class would make the comparison above vacuous."""
    cases = load(FALL_CASES)["cases"]
    labels = {case["true_label"] for case in cases}
    assert labels == set(fall_model["classes"])
    # the positive class really is marked as injected, and the negative class as untouched
    assert all(case["origin"]["injected"] == (case["true_label"] == "FALL") for case in cases)


# --------------------------------------------------------------------------- #
# model card / spec honesty
# --------------------------------------------------------------------------- #

def test_spec_reports_the_real_test_split_size():
    """
    The spec previously claimed 2949 test windows when the UCI HAR test split is 2947. A wrong count
    in a model card is unverifiable hand-waving, so it is asserted here.
    """
    spec = load(ACTIVITY_SPEC)
    trained_on = spec["trained_on"]
    assert "2947" in trained_on, f"spec does not report the real test split: {trained_on!r}"
    assert "2949" not in trained_on, f"spec still carries the stale count: {trained_on!r}"
    assert "7352" in trained_on, f"spec does not report the real train split: {trained_on!r}"


def test_spec_accuracy_matches_the_shipped_model_choice(activity_model: dict):
    spec = load(ACTIVITY_SPEC)
    assert 0.0 < spec["accuracy"] <= 1.0
    assert 0.0 < spec["macro_f1"] <= 1.0
    assert spec["classes"] == activity_model["classes"]
    names = [feature["name"] for feature in spec["features"]]
    assert names == DEPLOYABLE_FEATURES


# --------------------------------------------------------------------------- #
# the app must ship exactly what was verified
# --------------------------------------------------------------------------- #

@pytest.mark.parametrize("source,shipped", SHIPPED_PAIRS, ids=lambda p: Path(p).name)
def test_app_ships_the_verified_artefact(source: Path, shipped: Path):
    assert shipped.is_file(), f"{shipped} is missing"
    assert source.read_bytes() == shipped.read_bytes(), (
        f"{shipped} differs from {source} -- the retrained model was not copied into the app, so the "
        "tests that exercise the shipped asset would be testing something else"
    )
