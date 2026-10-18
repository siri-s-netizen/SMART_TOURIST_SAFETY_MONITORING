"""The authority dashboard's JSON API.

This is the surface `backend/tsm/static/app.js` reads. Every number the dashboard shows comes from
here, so "the dashboard works" is testable without a browser.
"""

from __future__ import annotations

import pytest

from conftest import OFFICER_ID, TOURIST_ID
from test_sync import entry, position_payload, sos_payload, sync, zone_alert_payload


def deliver(api, headers, events) -> None:
    response = sync(api, headers, events)
    assert response.status_code == 200, response.get_data(as_text=True)


class TestOverview:
    def test_a_fresh_service_reports_an_empty_but_valid_overview(self, api, as_officer):
        body = api.get("/api/v1/authority/overview", headers=as_officer).get_json()

        assert body["counts"]["tourists"] == 1
        assert body["counts"]["events"] == 0
        assert body["counts"]["sosTotal"] == 0
        assert body["counts"]["sosOpen"] == 0
        assert body["counts"]["sosAcknowledged"] == 0
        assert body["counts"]["zoneAlerts"] == 0
        assert body["counts"]["emergencyContacts"] == 0
        assert [tourist["touristId"] for tourist in body["tourists"]] == [TOURIST_ID]

    def test_every_kind_is_counted_in_its_own_column(self, api, as_tourist, as_officer):
        deliver(
            api,
            as_tourist,
            [
                entry("alert:1", "ZONE_ALERT", zone_alert_payload()),
                entry("position:1", "POSITION", position_payload()),
                entry("sos:1", "SOS", sos_payload()),
            ],
        )

        counts = api.get("/api/v1/authority/overview", headers=as_officer).get_json()["counts"]
        assert counts["events"] == 3
        assert counts["sosTotal"] == 1
        assert counts["sosOpen"] == 1
        assert counts["zoneAlerts"] == 1

    def test_events_last_24h_is_a_window_not_a_total(self, api, clock, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload())])
        clock.advance(25 * 60 * 60 * 1000)
        deliver(api, as_tourist, [entry("sos:2", "SOS", sos_payload())])

        counts = api.get("/api/v1/authority/overview", headers=as_officer).get_json()["counts"]
        assert counts["events"] == 2
        assert counts["eventsLast24h"] == 1

    def test_the_fleet_row_reports_the_tourist_state(self, api, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("position:1", "POSITION", position_payload(latitude=18.99))])

        tourist = api.get("/api/v1/authority/overview", headers=as_officer).get_json()["tourists"][0]
        assert tourist["state"] == "OK"
        assert tourist["latitude"] == pytest.approx(18.99)
        assert tourist["openSosCount"] == 0
        assert tourist["lastEventAtMillis"] is not None

    def test_a_tourist_with_an_open_sos_is_in_the_sos_state(self, api, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload())])

        tourist = api.get("/api/v1/authority/overview", headers=as_officer).get_json()["tourists"][0]
        assert tourist["state"] == "SOS"
        assert tourist["openSosCount"] == 1

    def test_a_tourist_who_has_never_reported_is_stale_not_ok(self, api, as_officer):
        tourist = api.get("/api/v1/authority/overview", headers=as_officer).get_json()["tourists"][0]

        assert tourist["state"] == "STALE"
        assert tourist["lastEventAtMillis"] is None


class TestSosQueue:
    def test_the_queue_lists_what_arrived_with_the_tourists_name(self, api, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload())])

        body = api.get("/api/v1/authority/sos", headers=as_officer).get_json()

        assert len(body["sos"]) == 1
        row = body["sos"][0]
        assert row["status"] == "OPEN"
        assert row["touristId"] == TOURIST_ID
        assert row["displayName"] == "Test Tourist"
        assert row["simulated"] is True
        assert row["raisedAtMillis"] == 2_000
        assert row["acknowledgedAtMillis"] is None

    def test_open_emergencies_sort_above_acknowledged_ones(self, api, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload(timestampMillis=1_000))])
        deliver(api, as_tourist, [entry("sos:2", "SOS", sos_payload(timestampMillis=2_000))])
        newest = api.get("/api/v1/authority/sos", headers=as_officer).get_json()["sos"][0]
        api.post(f"/api/v1/authority/sos/{newest['sosId']}/ack", headers=as_officer, json={})

        rows = api.get("/api/v1/authority/sos", headers=as_officer).get_json()["sos"]

        # The still-open one must be first even though it is older.
        assert [row["status"] for row in rows] == ["OPEN", "ACKNOWLEDGED"]

    def test_the_status_filter_selects_one_bucket(self, api, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload())])
        sos_id = api.get("/api/v1/authority/sos", headers=as_officer).get_json()["sos"][0]["sosId"]
        api.post(f"/api/v1/authority/sos/{sos_id}/ack", headers=as_officer, json={})

        open_rows = api.get("/api/v1/authority/sos?status=OPEN", headers=as_officer).get_json()["sos"]
        acknowledged = api.get("/api/v1/authority/sos?status=ACKNOWLEDGED", headers=as_officer).get_json()["sos"]

        assert open_rows == []
        assert len(acknowledged) == 1

    def test_the_status_filter_is_case_insensitive(self, api, as_officer):
        assert api.get("/api/v1/authority/sos?status=open", headers=as_officer).status_code == 200

    def test_an_unknown_status_filter_is_a_bad_request(self, api, as_officer):
        response = api.get("/api/v1/authority/sos?status=PENDING", headers=as_officer)

        assert response.status_code == 400
        assert "ALL, OPEN or ACKNOWLEDGED" in response.get_json()["message"]

    def test_the_queue_requires_an_officer(self, api, as_tourist):
        assert api.get("/api/v1/authority/sos", headers=as_tourist).status_code == 403


class TestAcknowledge:
    def test_acknowledging_records_who_and_when(self, api, clock, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload())])
        sos_id = api.get("/api/v1/authority/sos", headers=as_officer).get_json()["sos"][0]["sosId"]
        clock.advance(1_234)

        response = api.post(
            f"/api/v1/authority/sos/{sos_id}/ack",
            headers=as_officer,
            json={"note": "Rang the tourist; safe."},
        )

        assert response.status_code == 200
        row = response.get_json()["sos"]
        assert row["status"] == "ACKNOWLEDGED"
        assert row["acknowledgedBy"] == OFFICER_ID
        assert row["acknowledgedAtMillis"] == clock.now
        assert row["note"] == "Rang the tourist; safe."

    def test_acknowledging_takes_it_out_of_the_open_bucket(self, api, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload())])
        sos_id = api.get("/api/v1/authority/sos", headers=as_officer).get_json()["sos"][0]["sosId"]

        api.post(f"/api/v1/authority/sos/{sos_id}/ack", headers=as_officer, json={})

        counts = api.get("/api/v1/authority/overview", headers=as_officer).get_json()["counts"]
        assert counts["sosOpen"] == 0
        assert counts["sosAcknowledged"] == 1

    def test_a_blank_note_is_stored_as_absent(self, api, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload())])
        sos_id = api.get("/api/v1/authority/sos", headers=as_officer).get_json()["sos"][0]["sosId"]

        row = api.post(
            f"/api/v1/authority/sos/{sos_id}/ack",
            headers=as_officer,
            json={"note": "   "},
        ).get_json()["sos"]

        assert row["note"] is None

    def test_acknowledging_an_unknown_emergency_is_a_404(self, api, as_officer):
        response = api.post("/api/v1/authority/sos/9999/ack", headers=as_officer, json={})

        assert response.status_code == 404
        assert response.get_json()["error"] == "not_found"

    def test_a_tourist_cannot_acknowledge_their_own_sos(self, api, as_tourist):
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload())])
        # The tourist cannot even read the queue, let alone work it.
        assert api.get("/api/v1/authority/sos", headers=as_tourist).status_code == 403
        assert api.post("/api/v1/authority/sos/1/ack", headers=as_tourist, json={}).status_code == 403

    def test_acknowledging_twice_is_not_an_error(self, api, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload())])
        sos_id = api.get("/api/v1/authority/sos", headers=as_officer).get_json()["sos"][0]["sosId"]

        first = api.post(f"/api/v1/authority/sos/{sos_id}/ack", headers=as_officer, json={})
        second = api.post(f"/api/v1/authority/sos/{sos_id}/ack", headers=as_officer, json={})

        assert first.status_code == second.status_code == 200
        assert second.get_json()["sos"]["status"] == "ACKNOWLEDGED"


class TestEvents:
    def test_the_audit_trail_is_newest_first(self, api, clock, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("position:1", "POSITION", position_payload())])
        clock.advance(1_000)
        deliver(api, as_tourist, [entry("sos:1", "SOS", sos_payload())])

        events = api.get("/api/v1/authority/events", headers=as_officer).get_json()["events"]

        assert [event["kind"] for event in events] == ["SOS", "POSITION"]

    def test_the_kind_filter_works(self, api, as_tourist, as_officer):
        deliver(
            api,
            as_tourist,
            [
                entry("position:1", "POSITION", position_payload()),
                entry("sos:1", "SOS", sos_payload()),
            ],
        )

        events = api.get("/api/v1/authority/events?kind=SOS", headers=as_officer).get_json()["events"]

        assert len(events) == 1
        assert events[0]["kind"] == "SOS"

    def test_the_tourist_filter_works(self, api, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("position:1", "POSITION", position_payload())])

        matching = api.get(f"/api/v1/authority/events?touristId={TOURIST_ID}", headers=as_officer).get_json()["events"]
        other = api.get("/api/v1/authority/events?touristId=T-nobody", headers=as_officer).get_json()["events"]

        assert len(matching) == 1
        assert other == []

    def test_the_page_limit_is_clamped_rather_than_trusted(self, api, as_officer):
        for limit in ("0", "-5", "100000", "abc", ""):
            response = api.get(f"/api/v1/authority/events?limit={limit}", headers=as_officer)
            assert response.status_code == 200, limit

    def test_a_zone_alert_carries_its_zone_into_the_trail(self, api, as_tourist, as_officer):
        deliver(api, as_tourist, [entry("alert:1", "ZONE_ALERT", zone_alert_payload())])

        event = api.get("/api/v1/authority/events", headers=as_officer).get_json()["events"][0]
        assert event["zoneId"] == 7
        assert event["zoneType"] == "DANGEROUS"
        assert event["eventType"] == "ENTRY"
        assert event["simulated"] is True


class TestTourists:
    def test_the_register_lists_registered_tourists(self, api, as_officer):
        body = api.get("/api/v1/authority/tourists", headers=as_officer).get_json()

        assert [tourist["touristId"] for tourist in body["tourists"]] == [TOURIST_ID]
        assert body["tourists"][0]["displayName"] == "Test Tourist"

    def test_the_detail_view_gathers_everything_known_about_one_tourist(self, api, as_tourist, as_officer):
        deliver(
            api,
            as_tourist,
            [
                entry("alert:1", "ZONE_ALERT", zone_alert_payload()),
                entry("sos:1", "SOS", sos_payload()),
            ],
        )
        api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={"contacts": [{"name": "Asha", "phone": "+91"}]},
        )

        detail = api.get(f"/api/v1/authority/tourists/{TOURIST_ID}", headers=as_officer).get_json()

        assert detail["tourist"]["touristId"] == TOURIST_ID
        assert len(detail["events"]) == 2
        assert len(detail["sos"]) == 1
        assert len(detail["contacts"]) == 1
        assert detail["latestPosition"]["latitude"] == pytest.approx(18.52)

    def test_an_unknown_tourist_is_a_404(self, api, as_officer):
        response = api.get("/api/v1/authority/tourists/T-nobody", headers=as_officer)

        assert response.status_code == 404


class TestRegisterTourist:
    def test_a_new_tourist_is_created_and_the_password_is_returned_once(self, api, as_officer):
        response = api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": "T-7001", "displayName": "Nadia", "phone": "+91 90000 00009"},
        )

        assert response.status_code == 201
        body = response.get_json()
        assert body["tourist"]["touristId"] == "T-7001"
        assert body["password"]
        assert "Store these now" in body["note"]
        # The PIN is generated alongside the password and returned exactly once too.
        assert body["pin"].isdigit() and len(body["pin"]) == 6

    def test_the_returned_password_actually_signs_the_tourist_in(self, api, as_officer):
        created = api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": "T-7001", "displayName": "Nadia"},
        ).get_json()

        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": "T-7001", "password": created["password"]},
        )

        assert response.status_code == 200

    def test_a_supplied_password_is_used_and_can_sign_in(self, api, as_officer):
        response = api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": "T-7001", "displayName": "Nadia", "password": "chosen-by-the-officer"},
        )

        assert response.status_code == 201
        assert api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": "T-7001", "password": "chosen-by-the-officer"},
        ).status_code == 200

    def test_neither_the_salt_nor_the_digest_is_ever_returned(self, api, as_officer, db):
        response = api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": "T-7001", "displayName": "Nadia"},
        )

        row = db.query_one("SELECT password_salt, password_hash FROM tourists WHERE id = 'T-7001'")
        body = response.get_data(as_text=True)
        assert row["password_salt"] not in body
        assert row["password_hash"] not in body

    def test_a_duplicate_id_is_a_conflict(self, api, as_officer):
        response = api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": TOURIST_ID, "displayName": "Impostor"},
        )

        assert response.status_code == 409
        assert response.get_json()["error"] == "conflict"

    def test_missing_fields_are_called_out(self, api, as_officer):
        assert api.post("/api/v1/authority/tourists", headers=as_officer, json={}).status_code == 400
        assert api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": "T-7001"},
        ).status_code == 400

    def test_a_tourist_cannot_register_tourists(self, api, as_tourist):
        response = api.post(
            "/api/v1/authority/tourists",
            headers=as_tourist,
            json={"touristId": "T-7001", "displayName": "Sneaky"},
        )

        assert response.status_code == 403
