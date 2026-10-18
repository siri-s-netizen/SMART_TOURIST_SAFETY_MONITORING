"""Zone ownership and access control.

The property these tests exist to guarantee is a *security* one, not a rendering one: a tourist
account must not be able to create, edit, activate, deactivate or archive a restricted area by
talking to the backend directly. Removing the button from the app is not the control — the 403 from
``require_auth("authority")`` is.

Everything is exercised through the real Flask routing, so a route that was never registered fails
here rather than passing by accident.
"""

from __future__ import annotations

# A ~200 m square around a fixed point. Deliberately not at the origin, so a lat/lon swap in the
# validation or the storage would move the polygon somewhere obviously wrong.
SQUARE = [
    {"latitude": 12.9716, "longitude": 77.5946},
    {"latitude": 12.9734, "longitude": 77.5946},
    {"latitude": 12.9734, "longitude": 77.5974},
    {"latitude": 12.9716, "longitude": 77.5974},
]

TRIANGLE = [
    {"latitude": 12.9716, "longitude": 77.5946},
    {"latitude": 12.9734, "longitude": 77.5946},
    {"latitude": 12.9716, "longitude": 77.5974},
]


def create_zone(api, headers, **overrides):
    body = {
        "name": "Old quarry",
        "description": "Steep sides, no fencing.",
        "zoneType": "RESTRICTED",
        "vertices": SQUARE,
    }
    body.update(overrides)
    return api.post("/api/v1/authority/zones", json=body, headers=headers)


class TestOfficerCanManageZones:
    def test_an_officer_creates_a_zone_and_it_is_returned_with_its_geometry(self, api, as_officer):
        response = create_zone(api, as_officer)
        assert response.status_code == 201, response.get_data(as_text=True)
        zone = response.get_json()["zone"]
        assert zone["name"] == "Old quarry"
        assert zone["zoneType"] == "RESTRICTED"
        assert zone["status"] == "ACTIVE"
        assert zone["revision"] == 1
        assert zone["vertexCount"] == 4
        assert zone["vertices"] == SQUARE
        assert zone["createdBy"] == "A-9001"
        assert zone["publishedAtMillis"] is not None

    def test_a_created_zone_appears_in_the_officer_list(self, api, as_officer):
        create_zone(api, as_officer)
        response = api.get("/api/v1/authority/zones", headers=as_officer)
        assert response.status_code == 200
        assert [z["name"] for z in response.get_json()["zones"]] == ["Old quarry"]

    def test_editing_a_zone_bumps_the_revision_and_keeps_the_old_snapshot(self, api, as_officer):
        zone_id = create_zone(api, as_officer).get_json()["zone"]["zoneId"]
        updated = api.put(
            f"/api/v1/authority/zones/{zone_id}",
            json={"name": "Old quarry (closed)", "vertices": TRIANGLE},
            headers=as_officer,
        )
        assert updated.status_code == 200
        zone = updated.get_json()["zone"]
        assert zone["name"] == "Old quarry (closed)"
        assert zone["vertexCount"] == 3
        assert zone["revision"] == 2

        detail = api.get(f"/api/v1/authority/zones/{zone_id}", headers=as_officer).get_json()
        actions = [entry["action"] for entry in detail["history"]]
        assert actions[0] == "UPDATE"  # newest first
        assert "CREATE" in actions

    def test_deactivating_a_zone_removes_it_from_the_tourist_view(self, api, as_officer, as_tourist):
        zone_id = create_zone(api, as_officer).get_json()["zone"]["zoneId"]
        assert len(api.get("/api/v1/zones", headers=as_tourist).get_json()["zones"]) == 1

        paused = api.post(
            f"/api/v1/authority/zones/{zone_id}/status",
            json={"status": "INACTIVE"},
            headers=as_officer,
        )
        assert paused.status_code == 200
        assert paused.get_json()["zone"]["status"] == "INACTIVE"
        assert api.get("/api/v1/zones", headers=as_tourist).get_json()["zones"] == []

    def test_archiving_keeps_the_row_but_stops_evaluation(self, api, as_officer, as_tourist):
        zone_id = create_zone(api, as_officer).get_json()["zone"]["zoneId"]
        archived = api.delete(f"/api/v1/authority/zones/{zone_id}", headers=as_officer)
        assert archived.status_code == 200
        assert archived.get_json()["zone"]["status"] == "ARCHIVED"

        # Still visible to the authority (soft delete), gone from the tourist view.
        assert len(api.get("/api/v1/authority/zones", headers=as_officer).get_json()["zones"]) == 1
        assert api.get("/api/v1/zones", headers=as_tourist).get_json()["zones"] == []


class TestTouristCannotManageZones:
    def test_a_tourist_cannot_create_a_zone(self, api, as_tourist, db):
        response = create_zone(api, as_tourist)
        assert response.status_code == 403, response.get_data(as_text=True)
        assert response.get_json()["error"] == "forbidden"
        # And nothing was written.
        assert db.query_one("SELECT COUNT(*) AS n FROM zones")["n"] == 0

    def test_a_tourist_cannot_edit_a_zone(self, api, as_officer, as_tourist):
        zone_id = create_zone(api, as_officer).get_json()["zone"]["zoneId"]
        response = api.put(
            f"/api/v1/authority/zones/{zone_id}",
            json={"name": "hijacked"},
            headers=as_tourist,
        )
        assert response.status_code == 403

    def test_a_tourist_cannot_change_a_zone_status(self, api, as_officer, as_tourist):
        zone_id = create_zone(api, as_officer).get_json()["zone"]["zoneId"]
        response = api.post(
            f"/api/v1/authority/zones/{zone_id}/status",
            json={"status": "ARCHIVED"},
            headers=as_tourist,
        )
        assert response.status_code == 403

    def test_a_tourist_cannot_delete_a_zone(self, api, as_officer, as_tourist, db):
        zone_id = create_zone(api, as_officer).get_json()["zone"]["zoneId"]
        response = api.delete(f"/api/v1/authority/zones/{zone_id}", headers=as_tourist)
        assert response.status_code == 403
        assert db.query_one("SELECT status FROM zones WHERE id = ?", (zone_id,))["status"] == "ACTIVE"

    def test_no_token_is_unauthorised(self, api):
        assert api.post("/api/v1/authority/zones", json={"name": "x", "vertices": SQUARE}).status_code == 401
        assert api.delete("/api/v1/authority/zones/1").status_code == 401

    def test_a_tourist_may_read_the_active_zones(self, api, as_officer, as_tourist):
        """Reading is deliberately allowed: a phone needs the geometry to warn its user."""
        create_zone(api, as_officer)
        response = api.get("/api/v1/zones", headers=as_tourist)
        assert response.status_code == 200
        assert response.get_json()["zones"][0]["vertices"] == SQUARE


class TestPolygonValidation:
    def test_a_polygon_with_fewer_than_three_vertices_is_rejected(self, api, as_officer):
        response = create_zone(api, as_officer, vertices=SQUARE[:2])
        assert response.status_code == 400
        assert "at least 3" in response.get_json()["message"]

    def test_an_out_of_range_latitude_is_rejected(self, api, as_officer):
        bad = [dict(v) for v in SQUARE]
        bad[0]["latitude"] = 123.0
        response = create_zone(api, as_officer, vertices=bad)
        assert response.status_code == 400
        assert "latitude" in response.get_json()["message"]

    def test_a_degenerate_polygon_is_rejected(self, api, as_officer):
        flat = [
            {"latitude": 12.9716, "longitude": 77.5946},
            {"latitude": 12.9716, "longitude": 77.5956},
            {"latitude": 12.9716, "longitude": 77.5966},
        ]
        response = create_zone(api, as_officer, vertices=flat)
        assert response.status_code == 400
        assert "degenerate" in response.get_json()["message"]

    def test_a_non_numeric_coordinate_is_rejected(self, api, as_officer):
        bad = [dict(v) for v in SQUARE]
        bad[2]["longitude"] = "east"
        response = create_zone(api, as_officer, vertices=bad)
        assert response.status_code == 400

    def test_an_unknown_zone_type_is_rejected(self, api, as_officer):
        response = create_zone(api, as_officer, zoneType="FORBIDDEN")
        assert response.status_code == 400
        assert "zoneType" in response.get_json()["message"]

    def test_a_blank_name_is_rejected(self, api, as_officer):
        response = create_zone(api, as_officer, name="   ")
        assert response.status_code == 400


class TestPropagation:
    def test_an_officer_edit_is_what_the_tourist_next_reads(self, api, as_officer, as_tourist):
        """The backend is the single source of truth: after an edit the tourist read sees the edit."""
        zone_id = create_zone(api, as_officer).get_json()["zone"]["zoneId"]
        api.put(
            f"/api/v1/authority/zones/{zone_id}",
            json={"name": "Old quarry (updated)", "vertices": TRIANGLE},
            headers=as_officer,
        )
        zones = api.get("/api/v1/zones", headers=as_tourist).get_json()["zones"]
        assert zones[0]["name"] == "Old quarry (updated)"
        assert zones[0]["vertexCount"] == 3
        assert zones[0]["revision"] == 2

    def test_a_draft_zone_is_not_published_to_tourists(self, api, as_officer, as_tourist):
        create_zone(api, as_officer, status="INACTIVE")
        assert api.get("/api/v1/zones", headers=as_tourist).get_json()["zones"] == []
        # ...but the officer can still see it, with its status.
        drafts = api.get("/api/v1/authority/zones?status=INACTIVE", headers=as_officer).get_json()
        assert drafts["zones"][0]["status"] == "INACTIVE"

    def test_existing_events_are_untouched_by_zone_changes(self, api, as_officer, as_tourist, db):
        """Zone management must never disturb the audit trail of what a phone already reported."""
        before = db.query_one("SELECT COUNT(*) AS n FROM events")["n"]
        create_zone(api, as_officer)
        after = db.query_one("SELECT COUNT(*) AS n FROM events")["n"]
        assert before == after
