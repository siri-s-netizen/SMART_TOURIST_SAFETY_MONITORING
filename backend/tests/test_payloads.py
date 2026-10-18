"""The payload validators.

These are the rules the phone's outbox payloads must satisfy, so every assertion here is really a
statement about what a correct Android client may send. The whole-number rule in particular is why
`authority/Json.kt` writes `5` and not `5.0`.
"""

from __future__ import annotations

import json

import pytest

from tsm import payloads


def zone_alert(**overrides) -> dict:
    payload = {
        "type": "ZONE_ALERT",
        "zoneId": 7,
        "zoneName": "Cliff edge",
        "zoneType": "DANGEROUS",
        "eventType": "ENTRY",
        "latitude": 18.5,
        "longitude": 73.9,
        "accuracyMeters": 6.5,
        "timestampMillis": 1_700_000_000_000,
        "simulated": True,
    }
    payload.update(overrides)
    return payload


def sos(**overrides) -> dict:
    payload = {
        "type": "SOS",
        "latitude": 18.5,
        "longitude": 73.9,
        "accuracyMeters": 6.5,
        "timestampMillis": 1_700_000_000_000,
        "simulated": True,
    }
    payload.update(overrides)
    return payload


class TestKinds:
    def test_the_five_kinds_the_phone_can_send(self):
        assert payloads.KINDS == frozenset(
            {"ZONE_ALERT", "SOS", "POSITION", "CONTACTS", "RISK"}
        )

    def test_an_unknown_kind_is_a_payload_error(self):
        with pytest.raises(payloads.PayloadError, match="unsupported event kind"):
            payloads.normalise("TELEPORT", {})


class TestShape:
    def test_a_json_encoded_string_payload_is_accepted(self):
        # The backend accepts both a nested object and a JSON string, because an early client sent the
        # latter and dropping support for it would have been a silent data loss.
        columns = payloads.normalise("SOS", json.dumps(sos()))

        assert columns["latitude"] == 18.5

    def test_a_bare_list_is_not_a_payload(self):
        with pytest.raises(payloads.PayloadError, match="must be a JSON object"):
            payloads.normalise("SOS", "[1, 2, 3]")

    def test_malformed_json_text_says_so(self):
        with pytest.raises(payloads.PayloadError, match="not valid JSON"):
            payloads.normalise("SOS", "{not json")


class TestWholeNumbers:
    """`_integer()` is the contract the Kotlin JSON writer was written against."""

    def test_a_whole_number_is_accepted_as_int_and_as_float(self):
        assert payloads.normalise("SOS", sos(timestampMillis=5))["device_timestamp"] == 5
        assert payloads.normalise("SOS", sos(timestampMillis=5.0))["device_timestamp"] == 5

    def test_a_decimal_is_rejected_even_when_it_is_almost_whole(self):
        with pytest.raises(payloads.PayloadError, match="device timestamp|timestampMillis must be a whole number"):
            payloads.normalise("SOS", sos(timestampMillis=5.5))

    def test_a_boolean_is_not_a_number(self):
        # In Python `True == 1`, so a naive validator would accept `zoneId: true`.
        with pytest.raises(payloads.PayloadError, match="zoneId must be a number"):
            payloads.normalise("ZONE_ALERT", zone_alert(zoneId=True))


class TestStatistics:
    @pytest.mark.parametrize("latitude", [-90.0, 0.0, 90.0])
    def test_latitude_bounds_are_inclusive(self, latitude):
        assert payloads.normalise("SOS", sos(latitude=latitude))["latitude"] == latitude

    @pytest.mark.parametrize("latitude", [-90.5, 90.5, "north"])
    def test_a_bad_latitude_is_rejected(self, latitude):
        with pytest.raises(payloads.PayloadError):
            payloads.normalise("SOS", sos(latitude=latitude))

    @pytest.mark.parametrize("longitude", [-180.0, 180.0])
    def test_longitude_bounds_are_inclusive(self, longitude):
        assert payloads.normalise("SOS", sos(longitude=longitude))["longitude"] == longitude

    @pytest.mark.parametrize("longitude", [-180.5, 181.0])
    def test_a_bad_longitude_is_rejected(self, longitude):
        with pytest.raises(payloads.PayloadError, match="longitude must be between"):
            payloads.normalise("SOS", sos(longitude=longitude))

    def test_negative_accuracy_is_rejected(self):
        with pytest.raises(payloads.PayloadError, match="accuracyMeters must not be negative"):
            payloads.normalise("SOS", sos(accuracyMeters=-1))

    def test_a_missing_accuracy_is_allowed(self):
        assert payloads.normalise("SOS", sos(accuracyMeters=None))["accuracy_meters"] is None


class TestSimulatedFlag:
    def test_absent_means_real(self):
        payload = sos()
        payload.pop("simulated")

        assert payloads.normalise("SOS", payload)["simulated"] is False

    def test_a_truthy_non_boolean_is_rejected(self):
        with pytest.raises(payloads.PayloadError, match="simulated must be true or false"):
            payloads.normalise("SOS", sos(simulated="yes"))


class TestZoneAlert:
    def test_a_complete_zone_alert_is_projected_into_columns(self):
        columns = payloads.normalise("ZONE_ALERT", zone_alert())

        assert columns["zone_id"] == 7
        assert columns["zone_name"] == "Cliff edge"
        assert columns["zone_type"] == "DANGEROUS"
        assert columns["event_type"] == "ENTRY"
        assert columns["latitude"] == 18.5
        assert columns["device_timestamp"] == 1_700_000_000_000

    def test_a_zone_alert_without_a_position_is_rejected(self):
        # Unlike an SOS: a crossing that happened nowhere is meaningless.
        with pytest.raises(payloads.PayloadError, match="must carry the position"):
            payloads.normalise("ZONE_ALERT", zone_alert(latitude=None))

    def test_an_unknown_zone_type_is_rejected(self):
        with pytest.raises(payloads.PayloadError, match="zoneType must be one of"):
            payloads.normalise("ZONE_ALERT", zone_alert(zoneType="HAUNTED"))

    def test_an_unknown_event_type_is_rejected(self):
        with pytest.raises(payloads.PayloadError, match="eventType must be one of"):
            payloads.normalise("ZONE_ALERT", zone_alert(eventType="LOITERING"))

    def test_an_empty_zone_name_is_rejected(self):
        with pytest.raises(payloads.PayloadError, match="zoneName must not be empty"):
            payloads.normalise("ZONE_ALERT", zone_alert(zoneName="   "))

    def test_an_absurdly_long_zone_name_is_rejected(self):
        with pytest.raises(payloads.PayloadError, match="at most 200 characters"):
            payloads.normalise("ZONE_ALERT", zone_alert(zoneName="x" * 201))


class TestSos:
    def test_an_sos_without_a_position_is_still_accepted(self):
        # A tourist in a building with no fix must still be able to call for help.
        columns = payloads.normalise("SOS", sos(latitude=None, longitude=None))

        assert columns["latitude"] is None
        assert columns["longitude"] is None

    def test_an_sos_has_no_zone_fields(self):
        columns = payloads.normalise("SOS", sos())

        assert columns["zone_id"] is None
        assert columns["event_type"] is None


class TestPosition:
    def test_a_position_report_requires_both_coordinates(self):
        with pytest.raises(payloads.PayloadError, match="must carry latitude and longitude"):
            payloads.normalise("POSITION", {"timestampMillis": 1, "latitude": 18.5})

    def test_a_complete_position_report_is_accepted(self):
        columns = payloads.normalise(
            "POSITION",
            {"latitude": 18.5, "longitude": 73.9, "accuracyMeters": 4.0, "timestampMillis": 9},
        )

        assert columns["latitude"] == 18.5
        assert columns["device_timestamp"] == 9


class TestContacts:
    def contacts(self, *entries: dict) -> dict:
        return {"contacts": list(entries), "timestampMillis": 42}

    def test_a_whole_list_is_cleaned_and_kept_in_order(self):
        columns = payloads.normalise(
            "CONTACTS",
            self.contacts(
                {"name": "Asha", "phone": "+91", "relationship": "Sister", "priority": 0},
                {"name": "Ravi", "phone": "+92"},
            ),
        )

        stored = json.loads(columns["payload_json"])["contacts"]
        assert [c["name"] for c in stored] == ["Asha", "Ravi"]
        # An omitted relationship becomes null rather than the string "None".
        assert stored[1]["relationship"] is None
        # An omitted priority defaults to the position in the list.
        assert stored[1]["priority"] == 1
        assert columns["device_timestamp"] == 42

    def test_contacts_must_be_a_list(self):
        with pytest.raises(payloads.PayloadError, match="contacts must be a list"):
            payloads.normalise("CONTACTS", {"contacts": "Asha"})

    def test_more_than_twenty_contacts_is_refused(self):
        too_many = [{"name": f"P{i}", "phone": "1"} for i in range(payloads.MAX_CONTACTS + 1)]

        with pytest.raises(payloads.PayloadError, match="at most 20 entries"):
            payloads.normalise("CONTACTS", self.contacts(*too_many))

    def test_an_entry_that_is_not_an_object_names_its_index(self):
        with pytest.raises(payloads.PayloadError, match=r"contacts\[1\] must be an object"):
            payloads.normalise("CONTACTS", self.contacts({"name": "Asha", "phone": "1"}, "Ravi"))

    @pytest.mark.parametrize("field", ["name", "phone"])
    def test_name_and_phone_are_required(self, field):
        entry = {"name": "Asha", "phone": "+91"}
        entry[field] = "  "

        with pytest.raises(payloads.PayloadError, match=rf"contacts\[0\]\.{field} must not be empty"):
            payloads.normalise("CONTACTS", self.contacts(entry))

    def test_an_empty_contact_list_is_valid_because_it_clears_the_stored_list(self):
        columns = payloads.normalise("CONTACTS", self.contacts())

        assert json.loads(columns["payload_json"])["contacts"] == []

    def test_an_over_long_name_is_rejected(self):
        with pytest.raises(payloads.PayloadError, match="at most 80 characters"):
            payloads.normalise("CONTACTS", self.contacts({"name": "x" * 81, "phone": "1"}))


class TestCanonicalJson:
    def test_the_stored_payload_is_compact_and_key_sorted(self):
        columns = payloads.normalise("SOS", sos())

        canonical = columns["payload_json"]
        assert ", " not in canonical
        assert '": ' not in canonical
        # sort_keys=True, so the order is stable however the client ordered its fields.
        assert canonical == json.dumps(json.loads(canonical), separators=(",", ":"), sort_keys=True)

    def test_two_spellings_of_the_same_payload_canonicalise_identically(self):
        first = payloads.normalise("SOS", sos())
        second = payloads.normalise("SOS", dict(reversed(list(sos().items()))))

        assert first["payload_json"] == second["payload_json"]
