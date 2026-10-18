"""`POST /api/v1/sync/events` — the endpoint the Android outbox actually talks to.

The shape asserted here is the one `AuthorityApi.syncBody` produces and
`AuthorityApi.parseSyncOutcomes` reads, so a change to either side that this file does not catch is a
change to both sides at once.
"""

from __future__ import annotations

import pytest

from conftest import TOURIST_ID


def zone_alert_payload(**overrides) -> dict:
    payload = {
        "type": "ZONE_ALERT",
        "zoneId": 7,
        "zoneName": "Cliff edge",
        "zoneType": "DANGEROUS",
        "eventType": "ENTRY",
        "latitude": 18.52,
        "longitude": 73.85,
        "accuracyMeters": 6.0,
        "timestampMillis": 1_000,
        "simulated": True,
    }
    payload.update(overrides)
    return payload


def sos_payload(**overrides) -> dict:
    payload = {
        "type": "SOS",
        "latitude": 18.52,
        "longitude": 73.85,
        "accuracyMeters": 6.0,
        "timestampMillis": 2_000,
        "simulated": True,
    }
    payload.update(overrides)
    return payload


def position_payload(**overrides) -> dict:
    payload = {
        "type": "POSITION",
        "latitude": 18.52,
        "longitude": 73.85,
        "accuracyMeters": 5.0,
        "timestampMillis": 3_000,
        "simulated": False,
    }
    payload.update(overrides)
    return payload


def entry(dedupe_key: str, kind: str, payload, **overrides) -> dict:
    body = {
        "dedupeKey": dedupe_key,
        "kind": kind,
        "origin": "SIMULATED_WATCH",
        "payload": payload,
        "createdAtMillis": 1_000,
        "attemptCount": 1,
    }
    body.update(overrides)
    return body


def sync(api, headers, events):
    return api.post("/api/v1/sync/events", headers=headers, json={"events": events})


class TestBatch:
    def test_a_mixed_batch_is_all_delivered(self, api, as_tourist):
        response = sync(
            api,
            as_tourist,
            [
                entry("alert:7:ENTRY:1000", "ZONE_ALERT", zone_alert_payload()),
                entry("sos:2000", "SOS", sos_payload()),
                entry("position:3000", "POSITION", position_payload()),
            ],
        )

        assert response.status_code == 200
        body = response.get_json()
        assert body["serverTimeMillis"] is not None
        statuses = [result["status"] for result in body["results"]]
        assert statuses == ["DELIVERED", "DELIVERED", "DELIVERED"]
        for result in body["results"]:
            assert result["serverEventId"].startswith("evt_")
            assert result["acknowledgementId"] == result["serverEventId"]
            assert "reason" not in result

    def test_the_results_keep_the_order_and_the_keys_of_the_request(self, api, as_tourist):
        response = sync(
            api,
            as_tourist,
            [
                entry("first", "POSITION", position_payload()),
                entry("second", "SOS", sos_payload()),
            ],
        )

        assert [result["dedupeKey"] for result in response.get_json()["results"]] == ["first", "second"]

    def test_an_empty_batch_is_refused(self, api, as_tourist):
        assert sync(api, as_tourist, []).status_code == 400

    def test_a_missing_events_key_is_refused(self, api, as_tourist):
        response = api.post("/api/v1/sync/events", headers=as_tourist, json={})

        assert response.status_code == 400
        assert "non-empty list" in response.get_json()["message"]

    def test_a_batch_beyond_the_ceiling_is_refused(self, api, as_tourist):
        events = [entry(f"position:{i}", "POSITION", position_payload()) for i in range(201)]

        response = sync(api, as_tourist, events)

        assert response.status_code == 400
        assert "at most 200" in response.get_json()["message"]

    def test_sync_requires_a_token(self, api):
        assert api.post("/api/v1/sync/events", json={"events": []}).status_code == 401


class TestIdempotency:
    def test_the_same_dedupe_key_twice_is_a_duplicate_with_the_same_server_id(self, api, as_tourist):
        first = sync(api, as_tourist, [entry("sos:2000", "SOS", sos_payload())]).get_json()["results"][0]
        second = sync(api, as_tourist, [entry("sos:2000", "SOS", sos_payload())]).get_json()["results"][0]

        assert first["status"] == "DELIVERED"
        assert second["status"] == "DUPLICATE"
        assert second["serverEventId"] == first["serverEventId"]
        assert second.get("acknowledgementId") is None

    def test_a_retried_sos_does_not_create_a_second_emergency(self, api, db, as_tourist):
        sync(api, as_tourist, [entry("sos:2000", "SOS", sos_payload())])
        sync(api, as_tourist, [entry("sos:2000", "SOS", sos_payload())])

        rows = db.query("SELECT * FROM sos_events WHERE tourist_id = ?", (TOURIST_ID,))
        assert len(rows) == 1

    def test_a_duplicate_does_not_append_a_second_audit_row(self, api, db, as_tourist):
        sync(api, as_tourist, [entry("alert:7:ENTRY:1000", "ZONE_ALERT", zone_alert_payload())])
        sync(api, as_tourist, [entry("alert:7:ENTRY:1000", "ZONE_ALERT", zone_alert_payload())])

        assert len(db.query("SELECT 1 FROM events WHERE tourist_id = ?", (TOURIST_ID,))) == 1

    def test_dedupe_keys_are_scoped_to_the_tourist(self, api, db, as_officer, as_tourist, tourist_token):
        """Two phones may legitimately pick the same key; they must not collide."""
        api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": "T-9002", "displayName": "Second Tourist", "password": "second-pass-1234"},
        )
        other = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": "T-9002", "password": "second-pass-1234"},
        ).get_json()["token"]

        first = sync(api, as_tourist, [entry("sos:2000", "SOS", sos_payload())]).get_json()["results"][0]
        second = sync(
            api,
            {"Authorization": f"Bearer {other}"},
            [entry("sos:2000", "SOS", sos_payload())],
        ).get_json()["results"][0]

        assert first["status"] == "DELIVERED"
        assert second["status"] == "DELIVERED", "a different tourist is not a duplicate"
        assert first["serverEventId"] != second["serverEventId"]


class TestRejections:
    def test_a_rejection_does_not_abort_the_rest_of_the_batch(self, api, as_tourist):
        response = sync(
            api,
            as_tourist,
            [
                entry("bad", "SOS", sos_payload(latitude=200)),
                entry("good", "SOS", sos_payload()),
            ],
        )

        results = response.get_json()["results"]
        assert [result["status"] for result in results] == ["REJECTED", "DELIVERED"]
        assert "latitude must be between -90 and 90" in results[0]["reason"]

    def test_a_rejected_event_stores_nothing(self, api, db, as_tourist):
        sync(api, as_tourist, [entry("bad", "SOS", sos_payload(latitude=200))])

        assert db.query("SELECT 1 FROM events WHERE tourist_id = ?", (TOURIST_ID,)) == []
        assert db.query("SELECT 1 FROM sos_events WHERE tourist_id = ?", (TOURIST_ID,)) == []

    def test_a_missing_dedupe_key_is_rejected(self, api, as_tourist):
        response = sync(api, as_tourist, [{"kind": "SOS", "payload": sos_payload()}])

        result = response.get_json()["results"][0]
        assert result["status"] == "REJECTED"
        assert result["dedupeKey"] is None
        assert result["reason"] == "dedupeKey is required"

    def test_a_missing_kind_is_rejected(self, api, as_tourist):
        response = sync(api, as_tourist, [{"dedupeKey": "x", "payload": sos_payload()}])

        result = response.get_json()["results"][0]
        assert result["status"] == "REJECTED"
        assert result["dedupeKey"] == "x"
        assert result["reason"] == "kind is required"

    def test_an_unknown_kind_is_rejected_by_name(self, api, as_tourist):
        response = sync(api, as_tourist, [entry("x", "TELEPORT", {})])

        assert "unsupported event kind" in response.get_json()["results"][0]["reason"]

    def test_an_entry_that_is_not_an_object_is_rejected(self, api, as_tourist):
        response = sync(api, as_tourist, ["not-an-object"])

        assert response.get_json()["results"][0]["reason"] == "each event must be an object"

    def test_a_string_payload_is_accepted_because_the_phone_may_send_either(self, api, as_tourist):
        import json

        response = sync(api, as_tourist, [entry("x", "SOS", json.dumps(sos_payload()))])

        assert response.get_json()["results"][0]["status"] == "DELIVERED"

    def test_a_corrupt_payload_string_is_rejected_with_a_reason(self, api, as_tourist):
        response = sync(api, as_tourist, [entry("x", "SOS", "{not json")])

        result = response.get_json()["results"][0]
        assert result["status"] == "REJECTED"
        assert "not valid JSON" in result["reason"]


class TestProjections:
    def test_a_zone_alert_lands_in_the_audit_trail_with_its_zone_fields(self, api, db, as_tourist):
        sync(api, as_tourist, [entry("alert:7:ENTRY:1000", "ZONE_ALERT", zone_alert_payload())])

        row = db.query_one("SELECT * FROM events WHERE tourist_id = ?", (TOURIST_ID,))
        assert row["kind"] == "ZONE_ALERT"
        assert row["zone_id"] == 7
        assert row["zone_name"] == "Cliff edge"
        assert row["zone_type"] == "DANGEROUS"
        assert row["event_type"] == "ENTRY"
        assert row["latitude"] == pytest.approx(18.52)
        assert row["simulated"] == 1

    def test_the_raw_payload_is_kept_verbatim_for_the_audit(self, api, db, as_tourist):
        sync(api, as_tourist, [entry("alert:7:ENTRY:1000", "ZONE_ALERT", zone_alert_payload(zoneName="Cliff edge"))])

        row = db.query_one("SELECT payload FROM events WHERE tourist_id = ?", (TOURIST_ID,))
        assert '"zoneName":"Cliff edge"' in row["payload"]

    def test_an_origin_that_is_not_text_is_stored_as_absent(self, api, db, as_tourist):
        sync(api, as_tourist, [entry("x", "SOS", sos_payload(), origin={"nested": True})])

        assert db.query_one("SELECT origin FROM events WHERE tourist_id = ?", (TOURIST_ID,))["origin"] is None

    def test_the_origin_is_recorded_when_it_is_text(self, api, db, as_tourist):
        sync(api, as_tourist, [entry("x", "SOS", sos_payload(), origin="GPS")])

        assert db.query_one("SELECT origin FROM events WHERE tourist_id = ?", (TOURIST_ID,))["origin"] == "GPS"

    def test_an_sos_opens_a_work_item_and_is_never_recorded_as_real(self, api, db, as_tourist):
        sync(api, as_tourist, [entry("sos:2000", "SOS", sos_payload())])

        sos = db.query_one("SELECT * FROM sos_events WHERE tourist_id = ?", (TOURIST_ID,))
        assert sos["status"] == "OPEN"
        assert sos["simulated"] == 1
        assert sos["raised_at"] == 2_000
        assert sos["acknowledged_at"] is None

    def test_a_real_sos_is_recorded_as_real(self, api, db, as_tourist):
        """The flag is the server's, not the client's polite default."""
        sync(api, as_tourist, [entry("sos:2000", "SOS", sos_payload(simulated=False))])

        assert db.query_one("SELECT simulated FROM sos_events WHERE tourist_id = ?", (TOURIST_ID,))["simulated"] == 0

    def test_a_position_report_updates_the_latest_known_position(self, api, db, as_tourist):
        sync(api, as_tourist, [entry("position:3000", "POSITION", position_payload(latitude=19.0))])

        position = db.query_one("SELECT * FROM latest_positions WHERE tourist_id = ?", (TOURIST_ID,))
        assert position["latitude"] == pytest.approx(19.0)
        assert position["recorded_at"] == 3_000

    def test_a_stale_position_does_not_overwrite_a_newer_one(self, api, db, as_tourist):
        """A phone that re-sends an old queued fix must not move the tourist backwards on the map."""
        sync(api, as_tourist, [entry("position:3000", "POSITION", position_payload(latitude=19.0, timestampMillis=3_000))])
        sync(api, as_tourist, [entry("position:1000", "POSITION", position_payload(latitude=11.0, timestampMillis=1_000))])

        position = db.query_one("SELECT * FROM latest_positions WHERE tourist_id = ?", (TOURIST_ID,))
        assert position["latitude"] == pytest.approx(19.0)
        assert position["recorded_at"] == 3_000

    def test_an_sos_position_also_updates_the_latest_position(self, api, db, as_tourist):
        # Otherwise the dashboard would show "last seen" from before the emergency.
        sync(api, as_tourist, [entry("sos:2000", "SOS", sos_payload(latitude=18.9))])

        position = db.query_one("SELECT * FROM latest_positions WHERE tourist_id = ?", (TOURIST_ID,))
        assert position["latitude"] == pytest.approx(18.9)

    def test_an_sos_without_a_position_leaves_the_last_known_one_alone(self, api, db, as_tourist):
        sync(api, as_tourist, [entry("position:3000", "POSITION", position_payload(latitude=19.0))])
        sync(api, as_tourist, [entry("sos:2000", "SOS", sos_payload(latitude=None, longitude=None))])

        position = db.query_one("SELECT * FROM latest_positions WHERE tourist_id = ?", (TOURIST_ID,))
        assert position["latitude"] == pytest.approx(19.0)


class TestMeReflectsDelivery:
    def test_the_delivered_count_and_latest_sos_come_back_on_me(self, api, as_tourist):
        sync(
            api,
            as_tourist,
            [
                entry("position:3000", "POSITION", position_payload()),
                entry("sos:2000", "SOS", sos_payload()),
            ],
        )

        body = api.get("/api/v1/me", headers=as_tourist).get_json()
        assert body["deliveredEventCount"] == 2
        assert body["latestSos"]["status"] == "OPEN"
        assert body["latestSos"]["simulated"] is True
        assert body["latestPosition"]["latitude"] == pytest.approx(18.52)
