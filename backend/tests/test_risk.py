"""Trained-model **predictions** travelling through the same outbox as everything else.

A prediction is not an incident. These tests exist mainly to hold that line: a risk summary must be
stored, readable and countable, and it must never turn into an SOS, never be counted as one, and never
change the state the dashboard uses to decide who is in trouble.
"""

from __future__ import annotations

from conftest import TOURIST_ID


def risk_payload(**overrides) -> dict:
    """A payload byte-compatible with what ``OutboxPayloads.futureRisk`` emits."""
    payload = {
        "type": "RISK",
        "timestampMillis": 1_700_000_000_000,
        "modelVersion": "future-risk-unimib-v1",
        "horizonSeconds": 1.0,
        "riskBand": "MODERATE",
        "probability": 0.42,
        "windowSamples": 50,
        "observedSamples": 50,
        "dataQuality": {"ok": True, "samples": 50, "reason": None},
        "factors": [
            {"label": "Hardest moment in the last second", "detail": "now 2.10, typically 1.02"},
        ],
    }
    payload.update(overrides)
    return payload


def risk_event(dedupe_key: str = "risk:1700000000000", **overrides) -> dict:
    return {
        "dedupeKey": dedupe_key,
        "kind": "RISK",
        "origin": "ON_DEVICE_MODEL",
        "payload": risk_payload(**overrides),
    }


def raw_event(dedupe_key: str, payload: dict) -> dict:
    """An event whose payload is built by hand, so a field can be missing entirely."""
    return {
        "dedupeKey": dedupe_key,
        "kind": "RISK",
        "origin": "ON_DEVICE_MODEL",
        "payload": payload,
    }


def push(api, headers, *events: dict) -> dict:
    response = api.post("/api/v1/sync/events", headers=headers, json={"events": list(events)})
    assert response.status_code == 200, response.get_data(as_text=True)
    return response.get_json()


class TestIngest:
    def test_a_prediction_is_delivered_and_readable_back(self, api, as_tourist):
        body = push(api, as_tourist, risk_event())

        assert body["results"][0]["status"] == "DELIVERED"
        latest = api.get("/api/v1/me", headers=as_tourist).get_json()["latestRisk"]
        assert latest["riskBand"] == "MODERATE"
        assert latest["probability"] == 0.42
        assert latest["modelVersion"] == "future-risk-unimib-v1"
        assert latest["horizonSeconds"] == 1.0
        assert latest["assessedAtMillis"] == 1_700_000_000_000

    def test_the_prediction_states_on_the_wire_that_it_is_only_a_prediction(self, api, as_tourist):
        push(api, as_tourist, risk_event())

        latest = api.get("/api/v1/me", headers=as_tourist).get_json()["latestRisk"]

        assert latest["kind"] == "PREDICTION"
        assert "not an SOS" in latest["disclaimer"]

    def test_an_uncalibrated_prediction_may_carry_no_probability(self, api, as_tourist):
        push(api, as_tourist, risk_event(probability=None))

        latest = api.get("/api/v1/me", headers=as_tourist).get_json()["latestRisk"]
        assert latest["probability"] is None

    def test_the_same_prediction_twice_is_a_duplicate_and_is_stored_once(
        self, api, as_tourist, as_officer
    ):
        first = push(api, as_tourist, risk_event())
        second = push(api, as_tourist, risk_event())

        assert first["results"][0]["status"] == "DELIVERED"
        assert second["results"][0]["status"] == "DUPLICATE"
        overview = api.get("/api/v1/authority/overview", headers=as_officer).get_json()
        assert overview["counts"]["riskAssessments"] == 1

    def test_the_newest_prediction_is_the_one_reported(self, api, as_tourist):
        push(
            api,
            as_tourist,
            risk_event("risk:1", riskBand="LOW", timestampMillis=1_000, probability=0.01),
            risk_event("risk:2", riskBand="HIGH", timestampMillis=2_000, probability=0.91),
        )

        latest = api.get("/api/v1/me", headers=as_tourist).get_json()["latestRisk"]
        assert latest["riskBand"] == "HIGH"
        assert latest["probability"] == 0.91

    def test_an_unreadable_band_is_rejected_and_nothing_is_written(self, api, as_tourist):
        body = push(api, as_tourist, risk_event(riskBand="CATASTROPHIC"))

        assert body["results"][0]["status"] == "REJECTED"
        assert "riskBand" in body["results"][0]["reason"]
        assert api.get("/api/v1/me", headers=as_tourist).get_json()["latestRisk"] is None


class TestValidation:
    def test_an_unavailable_prediction_is_refused(self, api, as_tourist):
        # "We could not tell" is not a prediction; storing it would let the dashboard imply the model
        # made a call it did not make, and the app never sends it either.
        body = push(api, as_tourist, risk_event(riskBand="UNAVAILABLE"))
        assert body["results"][0]["status"] == "REJECTED"

    def test_a_probability_above_one_is_refused(self, api, as_tourist):
        body = push(api, as_tourist, risk_event(probability=1.4))
        assert body["results"][0]["status"] == "REJECTED"
        assert "probability" in body["results"][0]["reason"]

    def test_a_negative_probability_is_refused(self, api, as_tourist):
        assert push(api, as_tourist, risk_event(probability=-0.1))["results"][0]["status"] == "REJECTED"

    def test_a_zero_horizon_is_refused(self, api, as_tourist):
        body = push(api, as_tourist, risk_event(horizonSeconds=0))
        assert body["results"][0]["status"] == "REJECTED"
        assert "horizonSeconds" in body["results"][0]["reason"]

    def test_an_absurd_horizon_is_refused(self, api, as_tourist):
        # Ten minutes is the ceiling: this model forecasts one second, so a longer horizon is a
        # client fault rather than a long-range forecast.
        body = push(api, as_tourist, risk_event(horizonSeconds=3_600))
        assert body["results"][0]["status"] == "REJECTED"

    def test_a_missing_model_version_is_refused(self, api, as_tourist):
        payload = risk_payload()
        del payload["modelVersion"]
        body = push(api, as_tourist, raw_event("risk:x", payload))
        assert body["results"][0]["status"] == "REJECTED"

    def test_a_window_of_zero_samples_is_refused(self, api, as_tourist):
        assert push(api, as_tourist, risk_event(windowSamples=0))["results"][0]["status"] == "REJECTED"

    def test_a_missing_data_quality_block_is_refused(self, api, as_tourist):
        payload = risk_payload()
        del payload["dataQuality"]
        body = push(api, as_tourist, raw_event("risk:x", payload))
        assert body["results"][0]["status"] == "REJECTED"
        assert "dataQuality" in body["results"][0]["reason"]

    def test_a_non_boolean_data_quality_flag_is_refused(self, api, as_tourist):
        body = push(
            api, as_tourist, risk_event(dataQuality={"ok": "yes", "samples": 50, "reason": None})
        )
        assert body["results"][0]["status"] == "REJECTED"

    def test_more_than_five_factors_is_refused(self, api, as_tourist):
        factors = [{"label": f"f{index}", "detail": "d"} for index in range(6)]
        body = push(api, as_tourist, risk_event(factors=factors))
        assert body["results"][0]["status"] == "REJECTED"

    def test_a_factor_without_a_label_is_refused(self, api, as_tourist):
        body = push(api, as_tourist, risk_event(factors=[{"detail": "d"}]))
        assert body["results"][0]["status"] == "REJECTED"

    def test_an_empty_factor_list_is_allowed(self, api, as_tourist):
        assert push(api, as_tourist, risk_event(factors=[]))["results"][0]["status"] == "DELIVERED"

    def test_a_missing_timestamp_is_refused(self, api, as_tourist):
        payload = risk_payload()
        del payload["timestampMillis"]
        body = push(api, as_tourist, raw_event("risk:x", payload))
        assert body["results"][0]["status"] == "REJECTED"


class TestPredictionIsNotAnIncident:
    def test_a_high_risk_prediction_raises_no_sos(self, api, as_tourist):
        push(api, as_tourist, risk_event(riskBand="HIGH", probability=0.99))

        body = api.get("/api/v1/me", headers=as_tourist).get_json()
        assert body["latestSos"] is None

    def test_a_high_risk_prediction_does_not_mark_the_tourist_as_in_trouble(
        self, api, as_tourist, as_officer
    ):
        push(api, as_tourist, risk_event(riskBand="HIGH", probability=0.99))

        overview = api.get("/api/v1/authority/overview", headers=as_officer).get_json()
        me = next(row for row in overview["tourists"] if row["touristId"] == TOURIST_ID)
        assert me["openSosCount"] == 0
        assert me["state"] != "SOS"

    def test_the_prediction_is_reported_in_its_own_field(self, api, as_tourist, as_officer):
        push(api, as_tourist, risk_event(riskBand="HIGH", probability=0.99))

        overview = api.get("/api/v1/authority/overview", headers=as_officer).get_json()
        me = next(row for row in overview["tourists"] if row["touristId"] == TOURIST_ID)
        assert me["latestRiskBand"] == "HIGH"
        assert me["latestRiskProbability"] == 0.99


class TestOfficerView:
    def test_the_recent_predictions_endpoint_is_officer_only(self, api, as_tourist):
        assert api.get("/api/v1/authority/risk", headers=as_tourist).status_code == 403

    def test_recent_predictions_are_listed_newest_first(self, api, as_tourist, as_officer):
        push(
            api,
            as_tourist,
            risk_event("risk:1", riskBand="LOW", timestampMillis=1_000, probability=0.02),
            risk_event("risk:2", riskBand="HIGH", timestampMillis=2_000, probability=0.93),
        )

        body = api.get("/api/v1/authority/risk", headers=as_officer).get_json()
        assert [row["riskBand"] for row in body["risk"]] == ["HIGH", "LOW"]

    def test_the_tourist_detail_carries_the_prediction_history(self, api, as_tourist, as_officer):
        push(api, as_tourist, risk_event())

        detail = api.get(f"/api/v1/authority/tourists/{TOURIST_ID}", headers=as_officer).get_json()

        assert len(detail["risk"]) == 1
        assert detail["risk"][0]["factors"][0]["label"] == "Hardest moment in the last second"
        assert detail["latestRisk"]["riskBand"] == "MODERATE"
        # The incident feed and the prediction feed stay separate.
        assert detail["sos"] == []

    def test_the_overview_counts_predictions_separately_from_sos(self, api, as_tourist, as_officer):
        push(
            api,
            as_tourist,
            risk_event("risk:1", riskBand="LOW", timestampMillis=1_000, probability=0.02),
            risk_event("risk:2", riskBand="HIGH", timestampMillis=2_000, probability=0.93),
        )

        counts = api.get("/api/v1/authority/overview", headers=as_officer).get_json()["counts"]

        assert counts["riskAssessments"] == 2
        assert counts["riskElevated"] == 1
        assert counts["sosTotal"] == 0

    def test_a_prediction_is_visible_in_the_raw_event_feed_with_its_kind(self, api, as_tourist, as_officer):
        push(api, as_tourist, risk_event())

        events = api.get("/api/v1/authority/events", headers=as_officer).get_json()["events"]

        assert any(event["kind"] == "RISK" for event in events)

    def test_the_prediction_history_is_bounded_per_tourist(self, api, as_tourist, as_officer):
        for index in range(25):
            push(
                api,
                as_tourist,
                risk_event(
                    f"risk:{index}",
                    timestampMillis=1_000 + index,
                    probability=0.1,
                ),
            )

        detail = api.get(f"/api/v1/authority/tourists/{TOURIST_ID}", headers=as_officer).get_json()
        assert len(detail["risk"]) == 20
        # Newest first, so the bounded list is the most recent twenty.
        assert detail["risk"][0]["assessedAtMillis"] == 1_024
