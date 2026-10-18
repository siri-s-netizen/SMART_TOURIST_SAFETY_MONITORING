"""Validation and normalisation of the payloads the phone delivers.

This module is the **contract** with the Android side. The mirrored implementation lives in
``app/src/main/java/com/touristsafety/app/outbox/OutboxPayloads.kt``; the fixtures in
``backend/tests/test_payloads.py`` are byte-for-byte examples of what that Kotlin code emits, so a
change on either side that breaks the other is caught by a test rather than in the field.

Every payload is validated *before* anything is written. An event that cannot be understood is
reported back to the phone as ``REJECTED`` with a reason, which moves it to a terminal state in the
phone's outbox instead of leaving it to retry forever.
"""

from __future__ import annotations

import json
from typing import Any

#: Event kinds the backend understands. ``CONTACTS`` is accepted so a phone can upload the
#: emergency-contact list through the same durable queue as everything else. ``RISK`` carries a
#: **summary of a trained future-risk prediction**: what the phone's model forecast, not something
#: that happened.
KINDS = frozenset({"ZONE_ALERT", "SOS", "POSITION", "CONTACTS", "RISK"})

ZONE_TYPES = frozenset({"SAFE", "RESTRICTED", "DANGEROUS"})
ZONE_EVENT_TYPES = frozenset({"ENTRY", "EXIT"})

#: Risk bands the phone may report. ``UNAVAILABLE`` is deliberately **not** accepted: a prediction
#: the model could not make is not a prediction, and storing one would let the dashboard imply the
#: model made a call it did not make. The app does not send it either.
RISK_BANDS = frozenset({"LOW", "MODERATE", "HIGH"})

#: Hard ceilings so a hostile or buggy client cannot fill the disk with one absurd row.
MAX_TEXT_CHARS = 200
MAX_CONTACTS = 20
MAX_RISK_FACTORS = 5
#: Ten minutes: far longer than the model's one-second horizon, and short enough that a value beyond
#: it is a client fault rather than a long-range forecast.
MAX_RISK_HORIZON_SECONDS = 600.0


class PayloadError(ValueError):
    """The payload is structurally valid JSON but not a valid event of its kind."""


def _require_object(payload: Any) -> dict:
    if isinstance(payload, dict):
        return payload
    if isinstance(payload, str):
        try:
            decoded = json.loads(payload)
        except json.JSONDecodeError as error:
            raise PayloadError(f"payload is not valid JSON: {error.msg}") from error
        if not isinstance(decoded, dict):
            raise PayloadError("payload must be a JSON object")
        return decoded
    raise PayloadError("payload must be a JSON object or a JSON-encoded object")


def _number(value: Any, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise PayloadError(f"{field} must be a number")
    return float(value)


def _optional_number(value: Any, field: str) -> float | None:
    if value is None:
        return None
    return _number(value, field)


def _integer(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise PayloadError(f"{field} must be a number")
    if float(value) != int(value):
        raise PayloadError(f"{field} must be a whole number")
    return int(value)


def _text(value: Any, field: str, *, required: bool = True, maximum: int = MAX_TEXT_CHARS) -> str | None:
    if value is None:
        if required:
            raise PayloadError(f"{field} is required")
        return None
    if not isinstance(value, str):
        raise PayloadError(f"{field} must be text")
    if required and not value.strip():
        raise PayloadError(f"{field} must not be empty")
    if len(value) > maximum:
        raise PayloadError(f"{field} must be at most {maximum} characters")
    return value


def _latitude(payload: dict) -> float | None:
    if payload.get("latitude") is None:
        return None
    latitude = _number(payload["latitude"], "latitude")
    if not -90.0 <= latitude <= 90.0:
        raise PayloadError("latitude must be between -90 and 90")
    return latitude


def _longitude(payload: dict) -> float | None:
    if payload.get("longitude") is None:
        return None
    longitude = _number(payload["longitude"], "longitude")
    if not -180.0 <= longitude <= 180.0:
        raise PayloadError("longitude must be between -180 and 180")
    return longitude


def _accuracy(payload: dict) -> float | None:
    accuracy = _optional_number(payload.get("accuracyMeters"), "accuracyMeters")
    if accuracy is not None and accuracy < 0:
        raise PayloadError("accuracyMeters must not be negative")
    return accuracy


def _simulated(payload: dict) -> bool:
    value = payload.get("simulated", False)
    if not isinstance(value, bool):
        raise PayloadError("simulated must be true or false")
    return value


def normalise(kind: str, payload: Any) -> dict:
    """Validate ``payload`` for ``kind`` and return the columns to store.

    The returned mapping always contains ``payload_json`` (canonical, compact JSON text) plus the
    typed projection the dashboard reads without having to re-parse anything.
    """
    if kind not in KINDS:
        raise PayloadError(f"unsupported event kind {kind!r}")

    body = _require_object(payload)

    columns: dict[str, Any] = {
        "latitude": None,
        "longitude": None,
        "accuracy_meters": None,
        "zone_id": None,
        "zone_name": None,
        "zone_type": None,
        "event_type": None,
        "device_timestamp": None,
        "simulated": False,
        # Present on every row so callers can read the same keys regardless of kind; only RISK fills
        # them in. They project the *typed* fields of a prediction so the dashboard never has to
        # re-parse the payload JSON to render a risk row.
        "risk_band": None,
        "risk_model_version": None,
        "risk_horizon_seconds": None,
        "risk_probability": None,
        "risk_window_samples": None,
        "risk_observed_samples": None,
        "risk_data_ok": None,
        "risk_data_reason": None,
        "risk_factors_json": None,
    }

    if kind == "ZONE_ALERT":
        zone_id = _integer(body.get("zoneId"), "zoneId")
        zone_type = _text(body.get("zoneType"), "zoneType")
        if zone_type not in ZONE_TYPES:
            raise PayloadError(f"zoneType must be one of {sorted(ZONE_TYPES)}")
        event_type = _text(body.get("eventType"), "eventType")
        if event_type not in ZONE_EVENT_TYPES:
            raise PayloadError(f"eventType must be one of {sorted(ZONE_EVENT_TYPES)}")

        columns.update(
            latitude=_latitude(body),
            longitude=_longitude(body),
            accuracy_meters=_accuracy(body),
            zone_id=zone_id,
            zone_name=_text(body.get("zoneName"), "zoneName"),
            zone_type=zone_type,
            event_type=event_type,
            device_timestamp=_integer(body.get("timestampMillis"), "timestampMillis"),
            simulated=_simulated(body),
        )
        if columns["latitude"] is None or columns["longitude"] is None:
            raise PayloadError("a zone alert must carry the position it was raised at")

    elif kind == "SOS":
        # An SOS is the one event that is still meaningful without a position: a tourist in a
        # building with no fix must still be able to call for help, so latitude/longitude are
        # allowed to be null and the dashboard renders "position unknown".
        columns.update(
            latitude=_latitude(body),
            longitude=_longitude(body),
            accuracy_meters=_accuracy(body),
            device_timestamp=_integer(body.get("timestampMillis"), "timestampMillis"),
            simulated=_simulated(body),
        )

    elif kind == "POSITION":
        latitude = _latitude(body)
        longitude = _longitude(body)
        if latitude is None or longitude is None:
            raise PayloadError("a position report must carry latitude and longitude")
        columns.update(
            latitude=latitude,
            longitude=longitude,
            accuracy_meters=_accuracy(body),
            device_timestamp=_integer(body.get("timestampMillis"), "timestampMillis"),
            simulated=_simulated(body),
        )

    elif kind == "RISK":
        band = _text(body.get("riskBand"), "riskBand", maximum=20)
        if band not in RISK_BANDS:
            raise PayloadError(f"riskBand must be one of {sorted(RISK_BANDS)}")
        horizon = _number(body.get("horizonSeconds"), "horizonSeconds")
        if not 0 < horizon <= MAX_RISK_HORIZON_SECONDS:
            raise PayloadError(
                f"horizonSeconds must be greater than 0 and at most {MAX_RISK_HORIZON_SECONDS:g}"
            )
        probability = _optional_number(body.get("probability"), "probability")
        if probability is not None and not 0.0 <= probability <= 1.0:
            raise PayloadError("probability must be between 0 and 1, or null for an uncalibrated model")

        window_samples = _integer(body.get("windowSamples"), "windowSamples")
        if window_samples < 1:
            raise PayloadError("windowSamples must be at least 1")
        observed_samples = _integer(body.get("observedSamples", window_samples), "observedSamples")
        if observed_samples < 0:
            raise PayloadError("observedSamples must not be negative")

        quality = body.get("dataQuality")
        if not isinstance(quality, dict):
            raise PayloadError("dataQuality must be an object")
        data_ok = quality.get("ok")
        if not isinstance(data_ok, bool):
            raise PayloadError("dataQuality.ok must be true or false")
        data_reason = _text(quality.get("reason"), "dataQuality.reason", required=False)

        factors = body.get("factors", [])
        if not isinstance(factors, list):
            raise PayloadError("factors must be a list")
        if len(factors) > MAX_RISK_FACTORS:
            raise PayloadError(f"factors must contain at most {MAX_RISK_FACTORS} entries")
        cleaned_factors = []
        for index, entry in enumerate(factors):
            if not isinstance(entry, dict):
                raise PayloadError(f"factors[{index}] must be an object")
            cleaned_factors.append(
                {
                    "label": _text(entry.get("label"), f"factors[{index}].label", maximum=80),
                    "detail": _text(
                        entry.get("detail"), f"factors[{index}].detail",
                        required=False, maximum=MAX_TEXT_CHARS,
                    ),
                }
            )

        columns.update(
            device_timestamp=_integer(body.get("timestampMillis"), "timestampMillis"),
            risk_band=band,
            risk_model_version=_text(body.get("modelVersion"), "modelVersion", maximum=64),
            risk_horizon_seconds=horizon,
            risk_probability=probability,
            risk_window_samples=window_samples,
            risk_observed_samples=observed_samples,
            risk_data_ok=data_ok,
            risk_data_reason=data_reason,
            risk_factors_json=json.dumps(cleaned_factors, separators=(",", ":")),
        )
        body = dict(body)
        body["factors"] = cleaned_factors

    else:  # CONTACTS
        contacts = body.get("contacts")
        if not isinstance(contacts, list):
            raise PayloadError("contacts must be a list")
        if len(contacts) > MAX_CONTACTS:
            raise PayloadError(f"contacts must contain at most {MAX_CONTACTS} entries")
        cleaned = []
        for index, entry in enumerate(contacts):
            if not isinstance(entry, dict):
                raise PayloadError(f"contacts[{index}] must be an object")
            cleaned.append(
                {
                    "name": _text(entry.get("name"), f"contacts[{index}].name", maximum=80),
                    "phone": _text(entry.get("phone"), f"contacts[{index}].phone", maximum=40),
                    "relationship": _text(
                        entry.get("relationship"), f"contacts[{index}].relationship",
                        required=False, maximum=60,
                    ),
                    "priority": _integer(entry.get("priority", index), f"contacts[{index}].priority"),
                }
            )
        columns["device_timestamp"] = _integer(body.get("timestampMillis", 0), "timestampMillis")
        body = dict(body)
        body["contacts"] = cleaned

    columns["payload_json"] = json.dumps(body, separators=(",", ":"), sort_keys=True)
    return columns
