"""Self-service tourist registration: the public write, and the register the officer reads.

Two properties matter most here and are asserted directly:

* the Tourist ID is *minted by the database* — never by the client — and two registrations can never
  collide, even when the next id has already been taken;
* a registration creates a tourist and nothing else. It cannot mint an operator, it returns no secret,
  and it hands back no session token.

`test_sync.entry`/`sync` are reused so a delivered position report is shaped exactly as the phone
shapes it.
"""

from __future__ import annotations

import re

import pytest

from conftest import OFFICER_ID, TOURIST_ID, bearer
from test_sync import entry, position_payload, sync
from tsm.registration import format_tourist_id, year_of

#: `TR-2026-00124` — the shape the requirements name.
TOURIST_ID_PATTERN = re.compile(r"^TR-\d{4}-\d{5}$")

VALID_REGISTRATION = {
    "displayName": "Nadia Qureshi",
    "phone": "+91 90000 11111",
    "pin": "4821",
    "pinConfirmation": "4821",
}


def register(api, **overrides):
    body = dict(VALID_REGISTRATION)
    body.update(overrides)
    for key, value in list(body.items()):
        if value is None:
            del body[key]
    return api.post("/api/v1/auth/register", json=body)


def registered_id(response) -> str:
    return response.get_json()["tourist"]["touristId"]


class TestSuccessfulRegistration:
    def test_a_tourist_can_register_and_receives_a_generated_id(self, api):
        response = register(api)

        assert response.status_code == 201, response.get_data(as_text=True)
        body = response.get_json()
        tourist_id = body["tourist"]["touristId"]
        assert TOURIST_ID_PATTERN.match(tourist_id), tourist_id
        assert body["tourist"]["displayName"] == "Nadia Qureshi"
        assert body["tourist"]["phone"] == "+91 90000 11111"
        assert body["tourist"]["registrationSource"] == "SELF"
        assert isinstance(body["tourist"]["registeredAtMillis"], int)
        assert "PIN you chose" in body["note"]

    def test_registration_needs_no_credential(self, api):
        """A tourist has no credential until they register, so the route cannot require one."""
        assert register(api).status_code == 201

    def test_the_identifying_id_uses_the_registration_year(self, api, clock):
        response = register(api)

        assert registered_id(response).startswith(f"TR-{year_of(clock.now):04d}-")

    def test_the_returned_id_and_pin_sign_the_tourist_in(self, api):
        tourist_id = registered_id(register(api))

        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": tourist_id, "pin": "4821"},
        )

        assert response.status_code == 200
        assert response.get_json()["subjectId"] == tourist_id

    def test_the_response_never_carries_a_session_token(self, api):
        """Signing in is a separate, deliberate step — registration must not log anyone in."""
        body = register(api).get_json()

        assert "token" not in body
        assert "token" not in body["tourist"]

    def test_neither_the_salt_nor_the_digest_is_ever_returned(self, api, db):
        response = register(api)
        tourist_id = registered_id(response)

        row = db.query_one(
            "SELECT password_salt, password_hash, pin_salt, pin_hash FROM tourists WHERE id = ?",
            (tourist_id,),
        )
        body = response.get_data(as_text=True)
        for secret in (row["password_salt"], row["password_hash"], row["pin_salt"], row["pin_hash"]):
            assert secret and secret not in body

    def test_the_chosen_pin_is_stored_only_as_a_hash(self, api, db):
        from tsm.security import verify_password

        tourist_id = registered_id(register(api))

        row = db.query_one("SELECT pin_salt, pin_hash FROM tourists WHERE id = ?", (tourist_id,))
        assert row["pin_hash"] != "4821"
        assert verify_password("4821", row["pin_salt"], row["pin_hash"])

    def test_the_password_column_is_a_discarded_secret_not_the_pin(self, api):
        """The PIN is the credential; the NOT NULL password column must not double as it."""
        tourist_id = registered_id(register(api))

        # If the password had been set to the PIN, this would sign in.
        response = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": tourist_id, "password": "4821"},
        )

        assert response.status_code == 401

    def test_missing_pin_confirmation_is_accepted_but_a_mismatch_is_not(self, api):
        assert register(api, pinConfirmation=None, phone="+91 90000 22222").status_code == 201
        assert register(api, pinConfirmation="0000", phone="+91 90000 33333").status_code == 400

    def test_whitespace_around_the_name_and_phone_is_trimmed(self, api):
        body = register(api, displayName="  Nadia  ", phone="  +91 90000 44444  ").get_json()

        assert body["tourist"]["displayName"] == "Nadia"
        assert body["tourist"]["phone"] == "+91 90000 44444"


class TestIdUniqueness:
    def test_two_registrations_get_two_different_ids(self, api):
        first = registered_id(register(api, phone="+91 1"))
        second = registered_id(register(api, phone="+91 2"))

        assert first != second
        assert TOURIST_ID_PATTERN.match(first) and TOURIST_ID_PATTERN.match(second)

    def test_the_sequence_is_issued_by_the_database_and_advances(self, api, db):
        ids = [registered_id(register(api, phone=f"+91 5000 {index}")) for index in range(4)]

        assert len(set(ids)) == 4
        numbers = [int(tourist_id.rsplit("-", 1)[1]) for tourist_id in ids]
        assert numbers == sorted(numbers)
        assert numbers[-1] - numbers[0] == 3
        # The ledger is a real table with one row per minted id, not an in-process counter.
        count = db.query_one("SELECT COUNT(*) AS n FROM tourist_id_sequence")["n"]
        assert count >= 4

    def test_an_id_already_taken_by_hand_is_stepped_over(self, api, clock, db):
        """The database, not the format, is what guarantees uniqueness."""
        next_number = (db.query_one("SELECT COALESCE(MAX(id), 0) AS n FROM tourist_id_sequence")["n"]) + 1
        year = year_of(clock.now)
        planted = format_tourist_id(next_number, year)
        db.execute(
            "INSERT INTO tourists (id, display_name, phone, password_salt, password_hash, created_at) "
            "VALUES (?, 'Planted', NULL, 'salt', 'hash', ?)",
            (planted, clock.now),
        )

        tourist_id = registered_id(register(api, phone="+91 3"))

        assert tourist_id == format_tourist_id(next_number + 1, year)
        assert tourist_id != planted


class TestRegistrationValidation:
    @pytest.mark.parametrize(
        "override",
        [
            {"displayName": None},
            {"displayName": "   "},
            {"phone": None},
            {"phone": "   "},
            {"pin": None},
            {"pin": "123"},
            {"pin": "123456789"},
            {"pin": "12ab"},
            {"pinConfirmation": "9999"},
        ],
    )
    def test_an_invalid_field_is_a_bad_request(self, api, override):
        response = register(api, **override)

        assert response.status_code == 400
        assert response.get_json()["error"] == "bad_request"
        assert response.get_json()["message"]

    def test_a_name_that_is_too_long_is_rejected(self, api):
        assert register(api, displayName="x" * 101).status_code == 400

    def test_a_duplicate_phone_number_is_a_conflict(self, api):
        assert register(api).status_code == 201

        response = register(api, displayName="Someone Else")

        assert response.status_code == 409
        assert response.get_json()["error"] == "conflict"

    def test_a_blank_body_is_a_bad_request_not_a_crash(self, api):
        assert api.post("/api/v1/auth/register", json={}).status_code == 400

    def test_a_non_json_body_is_a_bad_request_not_a_crash(self, api):
        response = api.post("/api/v1/auth/register", data="not json", content_type="text/plain")

        assert response.status_code == 400

    def test_an_empty_registration_creates_nothing(self, api, db):
        before = db.query_one("SELECT COUNT(*) AS n FROM tourists")["n"]

        register(api, pin="1")

        assert db.query_one("SELECT COUNT(*) AS n FROM tourists")["n"] == before


class TestNoPrivilegeEscalation:
    def test_a_registration_cannot_create_an_authority_account(self, api, db):
        operators_before = db.query_one("SELECT COUNT(*) AS n FROM operators")["n"]

        response = register(api, role="authority")

        assert response.status_code == 400
        assert db.query_one("SELECT COUNT(*) AS n FROM operators")["n"] == operators_before

    def test_an_explicit_tourist_role_is_accepted(self, api):
        assert register(api, role="tourist").status_code == 201

    def test_unknown_fields_have_nowhere_to_go(self, api, db):
        response = register(api, isAuthority=True, roles=["authority"], pinHash="x")

        assert response.status_code == 201
        tourist_id = registered_id(response)
        row = db.query_one("SELECT registration_source FROM tourists WHERE id = ?", (tourist_id,))
        assert row["registration_source"] == "SELF"
        assert db.query_one(
            "SELECT COUNT(*) AS n FROM sessions WHERE subject_id = ?", (tourist_id,)
        )["n"] == 0

    def test_a_registered_tourist_cannot_reach_the_authority_routes(self, api):
        tourist_id = registered_id(register(api))
        token = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": tourist_id, "pin": "4821"},
        ).get_json()["token"]

        assert api.get("/api/v1/authority/tourists", headers=bearer(token)).status_code == 403


class TestRegistrationContacts:
    def test_contacts_supplied_at_registration_reach_the_tourist_record(self, api):
        response = register(
            api,
            contacts=[
                {"name": "Asha", "phone": "+91 90000 55555", "relationship": "Sister"},
                {"name": "Control Room", "phone": "112"},
            ],
        )
        tourist_id = registered_id(response)

        token = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": tourist_id, "pin": "4821"},
        ).get_json()["token"]
        body = api.get("/api/v1/me", headers=bearer(token)).get_json()

        assert response.get_json()["tourist"]["emergencyContactCount"] == 2
        assert [contact["name"] for contact in body["contacts"]] == ["Asha", "Control Room"]
        assert body["contacts"][0]["relationship"] == "Sister"

    def test_the_authority_sees_the_contacts_too(self, api, as_officer):
        tourist_id = registered_id(register(api, contacts=[{"name": "Asha", "phone": "112"}]))

        detail = api.get(f"/api/v1/authority/tourists/{tourist_id}", headers=as_officer).get_json()

        assert len(detail["contacts"]) == 1
        assert detail["contacts"][0]["name"] == "Asha"

    def test_a_registration_without_contacts_is_fine(self, api):
        response = register(api)

        assert response.status_code == 201
        assert response.get_json()["tourist"]["emergencyContactCount"] == 0

    def test_too_many_contacts_are_rejected(self, api):
        contacts = [{"name": f"C{index}", "phone": str(index)} for index in range(21)]

        assert register(api, contacts=contacts).status_code == 400

    def test_a_contact_without_a_phone_is_rejected(self, api):
        assert register(api, contacts=[{"name": "Asha"}]).status_code == 400
        # And nothing was half-created: the phone is still free to register with.
        assert register(api).status_code == 201


class TestRegisteredTouristsListing:
    def test_the_register_requires_an_officer(self, api):
        assert api.get("/api/v1/authority/tourists").status_code == 401

    def test_a_tourist_token_is_forbidden(self, api, as_tourist):
        assert api.get("/api/v1/authority/tourists", headers=as_tourist).status_code == 403

    def test_a_newly_registered_tourist_appears_with_its_registration_date(self, api, clock, as_officer):
        tourist_id = registered_id(register(api))

        rows = api.get("/api/v1/authority/tourists", headers=as_officer).get_json()["tourists"]
        row = next(entry for entry in rows if entry["touristId"] == tourist_id)

        assert row["registeredAtMillis"] == clock.now
        assert row["registrationStatus"] == "REGISTERED"
        assert row["registrationSource"] == "SELF"
        assert row["displayName"] == "Nadia Qureshi"

    def test_a_tourist_who_has_not_reported_has_no_position(self, api, as_officer):
        tourist_id = registered_id(register(api))

        rows = api.get("/api/v1/authority/tourists", headers=as_officer).get_json()["tourists"]
        row = next(entry for entry in rows if entry["touristId"] == tourist_id)

        # Registered, but nothing has arrived: the dashboard says so rather than drawing them at 0,0.
        assert row["hasPosition"] is False
        assert row["latestPosition"] is None
        assert row["trackingStatus"] == "AWAITING_FIRST_REPORT"
        assert row["lastSeenMillis"] is None

    def test_a_position_report_gives_the_tourist_a_position(self, api, as_officer):
        tourist_id = registered_id(register(api))
        token = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": tourist_id, "pin": "4821"},
        ).get_json()["token"]
        delivered = sync(
            api,
            bearer(token),
            [entry("position:1", "POSITION", position_payload(latitude=18.99, longitude=73.5))],
        )
        assert delivered.status_code == 200, delivered.get_data(as_text=True)

        rows = api.get("/api/v1/authority/tourists", headers=as_officer).get_json()["tourists"]
        row = next(entry for entry in rows if entry["touristId"] == tourist_id)

        assert row["hasPosition"] is True
        assert row["trackingStatus"] == "OK"
        assert row["latestPosition"]["latitude"] == pytest.approx(18.99)
        assert row["latestPosition"]["simulated"] is False
        assert row["lastSeenMillis"] is not None

    def test_the_desk_records_itself_as_the_source(self, api, as_officer):
        api.post(
            "/api/v1/authority/tourists",
            headers=as_officer,
            json={"touristId": "T-DESK", "displayName": "Desk Made"},
        )

        rows = api.get("/api/v1/authority/tourists", headers=as_officer).get_json()["tourists"]
        row = next(entry for entry in rows if entry["touristId"] == "T-DESK")

        assert row["registrationSource"] == OFFICER_ID

    def test_a_pre_existing_tourist_still_lists_with_a_null_source(self, api, as_officer):
        rows = api.get("/api/v1/authority/tourists", headers=as_officer).get_json()["tourists"]
        row = next(entry for entry in rows if entry["touristId"] == TOURIST_ID)

        assert row["registrationSource"] is None

    def test_new_registrations_come_first(self, api, clock, as_officer):
        first = registered_id(register(api, phone="+91 7000"))
        clock.advance(60_000)
        second = registered_id(register(api, phone="+91 7001"))

        rows = api.get("/api/v1/authority/tourists", headers=as_officer).get_json()["tourists"]

        assert rows[0]["touristId"] == second
        assert rows[1]["touristId"] == first

    def test_registration_is_separate_from_tracking_and_from_alerts(self, api, as_officer):
        """A registered tourist in trouble must still read as registered *and* tracking *and* SOS."""
        from test_sync import sos_payload

        tourist_id = registered_id(register(api))
        token = api.post(
            "/api/v1/auth/login",
            json={"role": "tourist", "id": tourist_id, "pin": "4821"},
        ).get_json()["token"]
        sync(
            api,
            bearer(token),
            [
                entry("position:1", "POSITION", position_payload()),
                entry("sos:1", "SOS", sos_payload()),
            ],
        )

        rows = api.get("/api/v1/authority/tourists", headers=as_officer).get_json()["tourists"]
        row = next(entry for entry in rows if entry["touristId"] == tourist_id)

        assert row["registrationStatus"] == "REGISTERED"
        assert row["trackingStatus"] == "OK"
        assert row["openSosCount"] == 1
        assert row["state"] == "SOS"
