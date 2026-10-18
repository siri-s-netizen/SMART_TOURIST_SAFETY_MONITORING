"""Emergency contacts, through both routes that can store them.

The phone can reach the authority two ways for contacts: a direct `PUT /me/contacts`, and the
`CONTACTS` event through the durable outbox. Both must land in the same place, because the Android
app uses the outbox path (so an edit made offline is not lost) while the dashboard's own fixtures use
the `PUT` path.
"""

from __future__ import annotations

from conftest import TOURIST_ID


def contacts(*entries: dict) -> list[dict]:
    return list(entries)


class TestPutContacts:
    def test_a_list_is_stored_and_echoed_back(self, api, as_tourist):
        response = api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={
                "contacts": contacts(
                    {"name": "Asha", "phone": "+91 90000 00001", "relationship": "Sister", "priority": 0},
                    {"name": "Ravi", "phone": "+91 90000 00002", "priority": 1},
                )
            },
        )

        assert response.status_code == 200
        body = response.get_json()
        assert body["count"] == 2
        assert [contact["name"] for contact in body["contacts"]] == ["Asha", "Ravi"]
        assert body["contacts"][1]["relationship"] is None

    def test_the_list_is_ordered_by_priority_then_name(self, api, as_tourist):
        api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={
                "contacts": contacts(
                    {"name": "Zoya", "phone": "3", "priority": 1},
                    {"name": "Asha", "phone": "1", "priority": 0},
                    {"name": "Bilal", "phone": "2", "priority": 1},
                )
            },
        )

        stored = api.get("/api/v1/me", headers=as_tourist).get_json()["contacts"]
        assert [contact["name"] for contact in stored] == ["Asha", "Bilal", "Zoya"]

    def test_the_timestamp_the_server_uses_is_its_own(self, api, clock, as_tourist):
        api.put("/api/v1/me/contacts", headers=as_tourist, json={"contacts": contacts({"name": "A", "phone": "1"})})

        body = api.get("/api/v1/me", headers=as_tourist).get_json()
        # The response has no timestamp field of its own; what matters is that the call succeeded at
        # the frozen instant rather than at an unmodelled real time.
        assert body["serverTimeMillis"] == clock.now

    def test_a_second_put_replaces_rather_than_merges(self, api, as_tourist):
        api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={"contacts": contacts({"name": "Asha", "phone": "1"}, {"name": "Ravi", "phone": "2"})},
        )

        api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={"contacts": contacts({"name": "Meera", "phone": "3"})},
        )

        stored = api.get("/api/v1/me", headers=as_tourist).get_json()["contacts"]
        assert [contact["name"] for contact in stored] == ["Meera"]

    def test_an_empty_list_clears_the_stored_contacts(self, api, as_tourist):
        api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={"contacts": contacts({"name": "Asha", "phone": "1"})},
        )

        response = api.put("/api/v1/me/contacts", headers=as_tourist, json={"contacts": []})

        assert response.status_code == 200
        assert response.get_json()["count"] == 0

    def test_a_blank_name_is_a_bad_request_naming_the_field(self, api, as_tourist):
        response = api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={"contacts": contacts({"name": "  ", "phone": "1"})},
        )

        assert response.status_code == 400
        assert "name must not be empty" in response.get_json()["message"]

    def test_more_than_twenty_contacts_is_refused(self, api, as_tourist):
        response = api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={"contacts": contacts(*[{"name": f"P{i}", "phone": "1"} for i in range(21)])},
        )

        assert response.status_code == 400

    def test_a_missing_contacts_key_is_refused(self, api, as_tourist):
        assert api.put("/api/v1/me/contacts", headers=as_tourist, json={}).status_code == 400

    def test_the_endpoint_requires_a_tourist_token(self, api, as_officer):
        response = api.put("/api/v1/me/contacts", headers=as_officer, json={"contacts": []})

        assert response.status_code == 403


class TestContactsThroughTheOutbox:
    """The path the Android app actually uses."""

    def test_a_contacts_event_stores_the_list(self, api, as_tourist):
        response = api.post(
            "/api/v1/sync/events",
            headers=as_tourist,
            json={
                "events": [
                    {
                        "dedupeKey": "contacts:5000",
                        "kind": "CONTACTS",
                        "origin": "GPS",
                        "payload": {
                            "type": "CONTACTS",
                            "timestampMillis": 5_000,
                            "contacts": [
                                {"name": "Asha", "phone": "+91", "relationship": "Sister", "priority": 0},
                                {"name": "Ravi", "phone": "+92", "relationship": None, "priority": 1},
                            ],
                        },
                    }
                ]
            },
        )

        assert response.get_json()["results"][0]["status"] == "DELIVERED"
        stored = api.get("/api/v1/me", headers=as_tourist).get_json()["contacts"]
        assert [contact["name"] for contact in stored] == ["Asha", "Ravi"]

    def test_a_contacts_event_is_not_stored_twice(self, api, as_tourist):
        event = {
            "dedupeKey": "contacts:5000",
            "kind": "CONTACTS",
            "payload": {"contacts": [{"name": "Asha", "phone": "+91"}]},
        }
        api.post("/api/v1/sync/events", headers=as_tourist, json={"events": [event]})
        api.post("/api/v1/sync/events", headers=as_tourist, json={"events": [event]})

        stored = api.get("/api/v1/me", headers=as_tourist).get_json()["contacts"]
        assert len(stored) == 1

    def test_a_later_contacts_event_replaces_an_earlier_one(self, api, as_tourist):
        def push(key: str, *names: str) -> None:
            api.post(
                "/api/v1/sync/events",
                headers=as_tourist,
                json={
                    "events": [
                        {
                            "dedupeKey": key,
                            "kind": "CONTACTS",
                            "payload": {"contacts": [{"name": name, "phone": "1"} for name in names]},
                        }
                    ]
                },
            )

        push("contacts:1", "Asha", "Ravi")
        push("contacts:2", "Meera")

        stored = api.get("/api/v1/me", headers=as_tourist).get_json()["contacts"]
        assert [contact["name"] for contact in stored] == ["Meera"]

    def test_the_outbox_path_and_the_put_path_agree(self, api, as_tourist):
        api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={"contacts": [{"name": "Asha", "phone": "+91", "relationship": "Sister", "priority": 0}]},
        )
        after_put = api.get("/api/v1/me", headers=as_tourist).get_json()["contacts"]

        api.post(
            "/api/v1/sync/events",
            headers=as_tourist,
            json={
                "events": [
                    {
                        "dedupeKey": "contacts:1",
                        "kind": "CONTACTS",
                        "payload": {"contacts": [{"name": "Asha", "phone": "+91", "relationship": "Sister", "priority": 0}]},
                    }
                ]
            },
        )
        after_sync = api.get("/api/v1/me", headers=as_tourist).get_json()["contacts"]

        assert after_put == after_sync


class TestOfficerViewOfContacts:
    def test_the_officer_sees_the_tourists_contacts(self, api, as_tourist, as_officer):
        api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={
                "contacts": [
                    {"name": "Asha", "phone": "+91 90000 00001", "relationship": "Sister", "priority": 0},
                ]
            },
        )

        detail = api.get(f"/api/v1/authority/tourists/{TOURIST_ID}", headers=as_officer).get_json()

        assert len(detail["contacts"]) == 1
        assert detail["contacts"][0]["name"] == "Asha"
        assert detail["contacts"][0]["phone"] == "+91 90000 00001"
        assert detail["contacts"][0]["relationship"] == "Sister"

    def test_the_overview_counts_stored_contacts(self, api, as_tourist, as_officer):
        api.put(
            "/api/v1/me/contacts",
            headers=as_tourist,
            json={"contacts": [{"name": "Asha", "phone": "1"}, {"name": "Ravi", "phone": "2"}]},
        )

        overview = api.get("/api/v1/authority/overview", headers=as_officer).get_json()

        assert overview["counts"]["emergencyContacts"] == 2
