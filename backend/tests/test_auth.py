"""Sign-in, sign-out, token lifetime and role separation.

Role separation is the security property that matters most here: a tourist token must not be able to
read another tourist's data, and must not reach the authority routes at all.
"""

from __future__ import annotations

from conftest import (
    OFFICER_ID,
    OFFICER_PASSWORD,
    SHORT_TOKEN_TTL_MILLIS,
    TOURIST_ID,
    TOURIST_PASSWORD,
    TOKEN_TTL_MILLIS,
    bearer,
)


class TestLogin:
    def test_a_tourist_sign_in_returns_a_usable_token(self, api):
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": TOURIST_PASSWORD, "deviceId": "Pixel 7"},
        )

        assert response.status_code == 200
        body = response.get_json()
        assert body["role"] == "tourist"
        assert body["subjectId"] == TOURIST_ID
        assert body["displayName"] == "Test Tourist"
        assert body["token"]
        assert body["expiresAtMillis"] == body["issuedAtMillis"] + TOKEN_TTL_MILLIS

    def test_the_device_label_is_recorded_on_the_session(self, api, db):
        api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": TOURIST_PASSWORD, "deviceId": "Pixel 7"},
        )

        row = db.query_one("SELECT device_id FROM sessions WHERE subject_id = ?", (TOURIST_ID,))
        assert row["device_id"] == "Pixel 7"

    def test_the_password_is_never_echoed_back(self, api):
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": TOURIST_PASSWORD},
        )

        assert TOURIST_PASSWORD not in response.get_data(as_text=True)

    def test_role_defaults_to_tourist(self, api):
        response = api.post(
            "/api/v1/auth/login",
            json={"id": TOURIST_ID, "password": TOURIST_PASSWORD},
        )

        assert response.status_code == 200
        assert response.get_json()["role"] == "tourist"

    def test_an_unknown_role_is_a_bad_request(self, api):
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "admin", "id": TOURIST_ID, "password": TOURIST_PASSWORD},
        )

        assert response.status_code == 400
        assert response.get_json()["error"] == "bad_request"

    def test_an_unknown_id_and_a_wrong_password_are_indistinguishable(self, api):
        unknown = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": "T-nobody", "password": TOURIST_PASSWORD},
        )
        wrong = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": "not-the-password"},
        )

        assert unknown.status_code == wrong.status_code == 401
        assert unknown.get_json() == wrong.get_json()

    def test_a_tourist_cannot_sign_in_at_the_authority_desk(self, api):
        # Same id, wrong table: a tourist id is not an operator.
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "authority", "id": TOURIST_ID, "password": TOURIST_PASSWORD},
        )

        assert response.status_code == 401

    def test_missing_fields_are_called_out(self, api):
        assert api.post("/api/v1/auth/login", json={}).status_code == 400
        assert api.post("/api/v1/auth/login", json={"id": TOURIST_ID}).status_code == 400
        assert api.post("/api/v1/auth/login", json={"password": TOURIST_PASSWORD}).status_code == 400

    def test_a_whitespace_password_is_a_wrong_password_not_a_missing_one(self, api):
        # "   " is a non-empty string, so it passes the shape check and fails the credential check.
        # The distinction matters: a client with a broken password field should see 401, not 400.
        response = api.post(
            "/api/v1/auth/login",
            json={"id": TOURIST_ID, "password": "   "},
        )

        assert response.status_code == 401
        assert response.get_json()["error"] == "invalid_credentials"

    def test_an_empty_password_is_a_bad_request(self, api):
        response = api.post("/api/v1/auth/login", json={"id": TOURIST_ID, "password": ""})

        assert response.status_code == 400

    def test_a_blank_id_is_not_treated_as_a_real_one(self, api):
        response = api.post(
            "/api/v1/auth/login",
            json={"id": "   ", "password": TOURIST_PASSWORD},
        )

        assert response.status_code == 400


class TestAuthorisation:
    def test_no_token_is_unauthorised(self, api):
        response = api.get("/api/v1/me")

        assert response.status_code == 401
        assert response.get_json()["error"] == "unauthorized"

    def test_a_garbage_token_is_unauthorised(self, api):
        response = api.get("/api/v1/me", headers={"Authorization": "Bearer not-a-token"})

        assert response.status_code == 401

    def test_a_token_without_the_bearer_scheme_is_unauthorised(self, api, tourist_token):
        response = api.get("/api/v1/me", headers={"Authorization": tourist_token})

        assert response.status_code == 401

    def test_a_tourist_cannot_reach_the_authority_routes(self, api, as_tourist):
        for path in (
            "/api/v1/authority/overview",
            "/api/v1/authority/sos",
            "/api/v1/authority/events",
            "/api/v1/authority/tourists",
        ):
            response = api.get(path, headers=as_tourist)
            assert response.status_code == 403, path
            assert response.get_json()["error"] == "forbidden"

    def test_an_officer_cannot_read_a_tourist_profile(self, api, as_officer):
        response = api.get("/api/v1/me", headers=as_officer)

        assert response.status_code == 403

    def test_an_officer_cannot_read_a_tourist_token(self, api, as_officer):
        """A token is a capability for one role, not a licence to act as the other."""
        assert api.get("/api/v1/me", headers=as_officer).status_code == 403


class TestTokenLifetime:
    """Uses its own app with a one-minute TTL (`short_ttl`)."""

    def test_a_token_works_up_to_its_expiry(self, clock, short_ttl):
        clock.advance(SHORT_TOKEN_TTL_MILLIS - 1)

        assert short_ttl.client.get("/api/v1/me", headers=bearer(short_ttl.token)).status_code == 200

    def test_an_expired_token_is_refused_and_deleted(self, clock, short_ttl):
        clock.advance(SHORT_TOKEN_TTL_MILLIS + 1)

        response = short_ttl.client.get("/api/v1/me", headers=bearer(short_ttl.token))

        assert response.status_code == 401
        # Deleted on sight: an expired capability should not linger in the table.
        assert short_ttl.db.query_one("SELECT 1 FROM sessions WHERE token = ?", (short_ttl.token,)) is None

    def test_signing_in_again_after_expiry_works(self, clock, short_ttl):
        clock.advance(SHORT_TOKEN_TTL_MILLIS + 1)
        short_ttl.client.get("/api/v1/me", headers=bearer(short_ttl.token))

        fresh = short_ttl.client.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": TOURIST_PASSWORD},
        )

        assert fresh.status_code == 200
        assert fresh.get_json()["token"] != short_ttl.token


class TestLogout:
    def test_logout_revokes_the_token_immediately(self, api, as_tourist):
        assert api.post("/api/v1/auth/logout", headers=as_tourist).get_json() == {"revoked": True}

        assert api.get("/api/v1/me", headers=as_tourist).status_code == 401

    def test_logout_requires_a_token(self, api):
        assert api.post("/api/v1/auth/logout").status_code == 401

    def test_logging_out_twice_is_harmless(self, api, as_tourist):
        api.post("/api/v1/auth/logout", headers=as_tourist)

        # The second attempt has no valid token, so it is refused rather than crashing.
        assert api.post("/api/v1/auth/logout", headers=as_tourist).status_code == 401

    def test_signing_out_one_phone_does_not_touch_another(self, api):
        first = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": TOURIST_PASSWORD, "deviceId": "phone-1"},
        ).get_json()["token"]
        second = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": TOURIST_PASSWORD, "deviceId": "phone-2"},
        ).get_json()["token"]

        api.post("/api/v1/auth/logout", headers={"Authorization": f"Bearer {first}"})

        assert api.get("/api/v1/me", headers={"Authorization": f"Bearer {first}"}).status_code == 401
        assert api.get("/api/v1/me", headers={"Authorization": f"Bearer {second}"}).status_code == 200


class TestMe:
    def test_a_fresh_tourist_has_no_history(self, api, as_tourist):
        body = api.get("/api/v1/me", headers=as_tourist).get_json()

        assert body["tourist"]["touristId"] == TOURIST_ID
        assert body["tourist"]["displayName"] == "Test Tourist"
        assert body["deliveredEventCount"] == 0
        assert body["lastDeliveredAtMillis"] is None
        assert body["latestSos"] is None
        assert body["latestPosition"] is None
        assert body["contacts"] == []
        assert isinstance(body["serverTimeMillis"], int)


class TestOfficerIdentity:
    def test_the_officer_token_identifies_the_operator(self, api):
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "authority", "id": OFFICER_ID, "password": OFFICER_PASSWORD},
        )

        body = response.get_json()
        assert body["role"] == "authority"
        assert body["subjectId"] == OFFICER_ID
        assert body["displayName"] == "Test Officer"
