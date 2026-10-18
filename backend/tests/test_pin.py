"""Tourist id + PIN sign-in.

The PIN is an optional, numeric second credential for the same account. These tests pin down the two
properties that matter: a PIN is a real credential (it signs you in), and it changes nothing about the
password flow (which keeps working, and stays indistinguishable from a wrong PIN at the wire level).
"""

from __future__ import annotations

from conftest import OFFICER_ID, OFFICER_PASSWORD, TOURIST_ID, TOURIST_PASSWORD, bearer


def seeded_pin(app, tourist_id: str = TOURIST_ID) -> str:
    """The PIN the seeder generated for a demo tourist (reported once in the seed report)."""
    for entry in app.extensions["tsm_seed_report"]["created"]:
        if entry["role"] == "tourist" and entry["id"] == tourist_id and entry.get("pin"):
            return entry["pin"]
    raise AssertionError("the seed report did not include a generated tourist PIN")


class TestPinSignIn:
    def test_the_seeded_tourist_can_sign_in_with_the_generated_pin(self, api, app):
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "pin": seeded_pin(app)},
        )

        assert response.status_code == 200
        body = response.get_json()
        assert body["role"] == "tourist"
        assert body["subjectId"] == TOURIST_ID
        assert body["token"]

    def test_the_password_still_works_alongside_the_pin(self, api):
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": TOURIST_PASSWORD},
        )

        assert response.status_code == 200

    def test_a_wrong_pin_is_indistinguishable_from_a_wrong_password(self, api):
        wrong_pin = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "pin": "000000"},
        )
        wrong_password = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": "wrong"},
        )

        assert wrong_pin.status_code == wrong_password.status_code == 401
        assert wrong_pin.get_json() == wrong_password.get_json()

    def test_a_pin_is_never_echoed_back(self, api, app):
        pin = seeded_pin(app)
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "pin": pin},
        )

        assert pin not in response.get_data(as_text=True)

    def test_missing_both_a_password_and_a_pin_is_a_bad_request(self, api):
        assert api.post("/api/v1/auth/login", json={"id": TOURIST_ID}).status_code == 400

    def test_an_empty_pin_with_no_password_is_a_bad_request(self, api):
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "pin": ""},
        )

        assert response.status_code == 400

    def test_a_wrong_pin_of_any_shape_is_just_wrong(self, api):
        # The server does not shape-check a PIN at login: a non-empty PIN is a credential and is
        # simply wrong. Only an *empty* PIN (with no password) is a 400, handled above.
        for bad in ("123", "123456789", "abcd", "12 34"):
            response = api.post(
                "/api/v1/auth/login",
                json={"role": "tourist", "id": TOURIST_ID, "pin": bad},
            )
            assert response.status_code == 401, bad

    def test_an_operator_cannot_use_a_pin(self, api):
        # Operators have no PIN columns at all, so a PIN can never be a way in at the desk.
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "authority", "id": OFFICER_ID, "pin": "123456"},
        )

        assert response.status_code == 401

    def test_a_tourist_with_no_pin_cannot_use_a_pin(self, api, db):
        db.execute(
            "UPDATE tourists SET pin_salt = NULL, pin_hash = NULL WHERE id = ?", (TOURIST_ID,)
        )

        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "pin": "123456"},
        )

        assert response.status_code == 401
        # ...and the password is unaffected.
        assert api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": TOURIST_PASSWORD},
        ).status_code == 200


class TestSetPin:
    def test_a_signed_in_tourist_can_change_their_pin(self, api, as_tourist):
        assert api.put("/api/v1/me/pin", headers=as_tourist, json={"pin": "4321"}).status_code == 200

        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "pin": "4321"},
        )
        assert response.status_code == 200

    def test_changing_the_pin_leaves_the_password_valid(self, api, as_tourist):
        api.put("/api/v1/me/pin", headers=as_tourist, json={"pin": "4321"})

        assert api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": TOURIST_ID, "password": TOURIST_PASSWORD},
        ).status_code == 200

    def test_an_invalid_new_pin_is_rejected(self, api, as_tourist):
        for bad in ("12", "abcdef", "123456789"):
            response = api.put("/api/v1/me/pin", headers=as_tourist, json={"pin": bad})
            assert response.status_code == 400, bad

    def test_setting_a_pin_requires_a_tourist_token(self, api, as_officer):
        assert api.put("/api/v1/me/pin", json={"pin": "4321"}).status_code == 401
        assert api.put("/api/v1/me/pin", headers=as_officer, json={"pin": "4321"}).status_code == 403


class TestCreatedTouristPin:
    def test_the_officer_gets_a_pin_for_a_new_tourist_and_it_signs_in(self, api, as_officer):
        created = api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": "T-7100", "displayName": "Nadia"},
        ).get_json()

        pin = created["pin"]
        assert pin.isdigit() and len(pin) == 6

        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": "T-7100", "pin": pin},
        )
        assert response.status_code == 200

    def test_the_officer_can_choose_the_pin(self, api, as_officer):
        created = api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": "T-7101", "displayName": "Nadia", "pin": "2468"},
        ).get_json()

        assert created["pin"] == "2468"
        assert api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": "T-7101", "pin": "2468"},
        ).status_code == 200

    def test_the_created_pin_is_never_returned_again(self, api, as_officer, db):
        created = api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": "T-7102", "displayName": "Nadia"},
        ).get_json()

        row = db.query_one(
            "SELECT pin_salt, pin_hash FROM tourists WHERE id = ?", ("T-7102",)
        )
        assert created["pin"] not in (row["pin_salt"], row["pin_hash"])


class TestBearerHelper:
    def test_the_shared_bearer_helper_still_shapes_a_header(self):
        assert bearer("abc") == {"Authorization": "Bearer abc"}

    def test_officer_password_is_unchanged(self, api):
        assert api.post(
            "/api/v1/auth/login",
            json={"role": "authority", "id": OFFICER_ID, "password": OFFICER_PASSWORD},
        ).status_code == 200
