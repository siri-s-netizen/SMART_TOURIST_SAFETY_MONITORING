"""The application itself: health, the dashboard, JSON error envelopes and first-run seeding."""

from __future__ import annotations

import os

import pytest

from tsm import VERSION, create_app
from tsm.security import verify_password


class TestHealth:
    def test_the_versioned_health_route_describes_the_service(self, api):
        body = api.get("/api/v1/health").get_json()

        assert body["status"] == "ok"
        assert body["service"] == "tourist-safety-authority"
        assert body["version"] == VERSION
        assert isinstance(body["serverTimeMillis"], int)
        assert body["database"]

    def test_the_liveness_probe_is_unversioned_and_needs_no_token(self, api):
        assert api.get("/healthz").get_json() == {"status": "ok", "version": VERSION}


class TestDashboard:
    def test_the_dashboard_is_served_at_the_root(self, api):
        response = api.get("/")

        assert response.status_code == 200
        html = response.get_data(as_text=True)
        assert "Authority Dashboard" in html
        assert "signin-form" in html

    @pytest.mark.parametrize("asset", ["style.css", "app.js"])
    def test_the_dashboard_assets_are_served(self, api, asset):
        response = api.get(f"/static/{asset}")

        assert response.status_code == 200
        assert response.get_data(as_text=True).strip()

    def test_the_dashboard_client_only_ever_reads_the_authority_api(self, api):
        """No fabricated data: the page's own source names the one API it uses."""
        client = api.get("/static/app.js").get_data(as_text=True)

        assert "const API = '/api/v1'" in client
        # And it says out loud that a simulated event is badged as such.
        assert "simulated" in client


class TestErrorEnvelopes:
    """The phone parses JSON; an HTML error page would hide the status code as a decode error."""

    def test_an_unknown_endpoint_is_json(self, api):
        response = api.get("/api/v1/nope")

        assert response.status_code == 404
        assert response.get_json()["error"] == "not_found"

    def test_a_wrong_method_is_json(self, api):
        response = api.get("/api/v1/auth/login")

        assert response.status_code == 405
        assert response.get_json()["error"] == "method_not_allowed"

    def test_a_bad_content_type_is_not_a_crash(self, api):
        response = api.post("/api/v1/auth/login", data="not json", content_type="text/plain")

        assert response.status_code == 400


class TestSeeding:
    def test_the_seed_report_names_the_accounts_it_created(self, app):
        report = app.extensions["tsm_seed_report"]

        assert {entry["role"] for entry in report["created"]} == {"tourist", "authority"}
        # Nothing was generated, because the test supplied both passwords.
        assert report["credentials"] == []

    def test_a_supplied_password_is_never_written_to_disk(self, app, config):
        assert not os.path.exists(config["CREDENTIALS_FILE"])

    def test_a_generated_password_is_written_once_in_a_readable_format(self, tmp_path, clock):
        path = str(tmp_path / "DEV_CREDENTIALS.txt")
        app = create_app(
            {
                "DATABASE_PATH": str(tmp_path / "authority.db"),
                "CREDENTIALS_FILE": path,
                "SEED_ACCOUNTS": True,
                "SEED_TIME_MILLIS": clock.now,
                "DEMO_TOURIST_ID": "T-8001",
                "AUTHORITY_ID": "A-8001",
                "TESTING": True,
            },
            clock=clock,
        )
        try:
            assert app.extensions["tsm_seed_report"]["created"], "both accounts should have been created"
            with open(path, encoding="utf-8") as handle:
                lines = [line.strip() for line in handle if line.strip() and not line.startswith("#")]
            parsed = {line.split("\t")[0]: line.split("\t") for line in lines}
            assert set(parsed) == {"tourist", "authority"}
            for parts in parsed.values():
                assert len(parts) == 3, parts

            # And the generated password really is the account's password.
            tourist_id, password = parsed["tourist"][1], parsed["tourist"][2]
            row = app.extensions["tsm_db"].query_one(
                "SELECT password_salt, password_hash FROM tourists WHERE id = ?",
                (tourist_id,),
            )
            assert verify_password(password, row["password_salt"], row["password_hash"])
        finally:
            app.extensions["tsm_db"].close()

    def test_restarting_never_resets_a_password(self, tmp_path, clock, api):
        database = str(tmp_path / "authority.db")
        base = {
            "DATABASE_PATH": database,
            "CREDENTIALS_FILE": str(tmp_path / "creds.txt"),
            "SEED_ACCOUNTS": True,
            "SEED_TIME_MILLIS": clock.now,
            "DEMO_TOURIST_ID": "T-8001",
            "AUTHORITY_ID": "A-8001",
            "TESTING": True,
        }
        first = create_app({**base, "DEMO_TOURIST_PASSWORD": "chosen-first"}, clock=clock)
        try:
            assert first.extensions["tsm_seed_report"]["created"]
        finally:
            first.extensions["tsm_db"].close()

        # A second start with a *different* password must not overwrite the existing account.
        second = create_app({**base, "DEMO_TOURIST_PASSWORD": "chosen-second"}, clock=clock)
        try:
            assert second.extensions["tsm_seed_report"]["created"] == []
            client = second.test_client()
            assert client.post(
                "/api/v1/auth/login",
                json={"role": "tourist", "id": "T-8001", "password": "chosen-first"},
            ).status_code == 200
            assert client.post(
                "/api/v1/auth/login",
                json={"role": "tourist", "id": "T-8001", "password": "chosen-second"},
            ).status_code == 401
        finally:
            second.extensions["tsm_db"].close()

    def test_seeding_can_be_turned_off_entirely(self, tmp_path, clock):
        app = create_app(
            {
                "DATABASE_PATH": str(tmp_path / "authority.db"),
                "CREDENTIALS_FILE": str(tmp_path / "creds.txt"),
                "SEED_ACCOUNTS": False,
                "TESTING": True,
            },
            clock=clock,
        )
        try:
            assert app.extensions["tsm_seed_report"] == {"created": [], "credentials": []}
            client = app.test_client()
            assert client.post(
                "/api/v1/auth/login",
                json={"role": "tourist", "id": "T-1001", "password": "anything"},
            ).status_code == 401
        finally:
            app.extensions["tsm_db"].close()


class TestSchema:
    def test_every_table_the_service_needs_exists(self, db):
        for table in ("tourists", "operators", "sessions", "events", "sos_events", "latest_positions",
                      "emergency_contacts", "risk_assessments", "tourist_id_sequence"):
            assert db.table_exists(table), table

    def test_the_audit_trail_is_append_only_by_design(self, app):
        """There is no update or delete of `events` anywhere in the service."""
        import inspect

        from tsm import api

        source = inspect.getsource(api)
        assert "UPDATE events" not in source
        assert "DELETE FROM events" not in source
