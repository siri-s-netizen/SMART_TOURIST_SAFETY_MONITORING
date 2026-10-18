"""Self-service tourist registration: validating the form and minting Tourist IDs.

Two rules this module exists to keep:

1. **The Tourist ID is issued by the database.** The number comes from an ``AUTOINCREMENT`` row in
   ``tourist_id_sequence`` — never from a client and never from an in-process counter — and the
   finished text id is then protected by the ``tourists.id`` primary key. A restart, a crash, or two
   registrations in the same millisecond therefore cannot produce the same id.
2. **A registration creates a tourist and nothing else.** Only the named fields are read, so a
   request cannot smuggle in a role; the endpoint that uses this returns the id and nothing secret.

The validation here mirrors the field rules the phone applies in
``app/src/main/java/com/touristsafety/app/authority/RegistrationForm.kt``. The phone's copy exists to
give a fast, specific message; *this* copy is the one that decides what is accepted.
"""

from __future__ import annotations

import datetime
from typing import Any

#: The shape the requirements ask for, e.g. ``TR-2026-00124``.
ID_PREFIX = "TR"
#: Zero-padded width of the sequence number. ``TR-2026-00124`` is five digits; a sixth would simply
#: widen the id, which is harmless because uniqueness comes from the database, not the format.
ID_DIGITS = 5

MAX_NAME_CHARS = 100
MAX_PHONE_CHARS = 40
MIN_PIN_DIGITS = 4
MAX_PIN_DIGITS = 8

#: The source recorded for an account created by the tourist themselves.
SOURCE_SELF = "SELF"


class RegistrationError(ValueError):
    """The request is well-formed JSON but not a valid registration."""


def format_tourist_id(sequence: int, year: int) -> str:
    """``TR-<year>-<sequence>``, zero-padded to [ID_DIGITS] digits."""
    return f"{ID_PREFIX}-{year:04d}-{sequence:0{ID_DIGITS}d}"


def year_of(millis: int) -> int:
    """The calendar year an instant falls in, in UTC.

    UTC so that two servers (or a server and its test) agree on the year regardless of the machine's
    local timezone — an id that changed shape across a New Year boundary would be a confusing thing
    to debug.
    """
    return datetime.datetime.fromtimestamp(millis / 1000, tz=datetime.timezone.utc).year


def validate(body: Any) -> dict[str, str]:
    """Return the cleaned registration fields, or raise [RegistrationError].

    Deliberately narrow: only ``displayName``, ``phone`` and ``pin`` are read from the body. A
    ``role`` or ``authority`` key has nowhere to go, so a registration cannot assign a privilege.
    """
    if not isinstance(body, dict):
        raise RegistrationError("the request body must be a JSON object")

    role = body.get("role", "tourist")
    if role != "tourist":
        # Explicit rather than silently ignored: a client that *tries* to register an operator
        # should be told no, and the refusal is what the test asserts.
        raise RegistrationError("a registration can only create a tourist account")

    display_name = body.get("displayName")
    if not isinstance(display_name, str) or not display_name.strip():
        raise RegistrationError("displayName is required")
    display_name = display_name.strip()
    if len(display_name) > MAX_NAME_CHARS:
        raise RegistrationError(f"displayName must be at most {MAX_NAME_CHARS} characters")

    phone = body.get("phone")
    if not isinstance(phone, str) or not phone.strip():
        raise RegistrationError("phone is required")
    phone = phone.strip()
    if len(phone) > MAX_PHONE_CHARS:
        raise RegistrationError(f"phone must be at most {MAX_PHONE_CHARS} characters")

    pin = body.get("pin")
    if not isinstance(pin, str) or not pin.isdigit() or not MIN_PIN_DIGITS <= len(pin) <= MAX_PIN_DIGITS:
        raise RegistrationError(f"pin must be {MIN_PIN_DIGITS} to {MAX_PIN_DIGITS} digits")

    # The confirmation field is a client-side convenience, so it is optional on the wire — but when it
    # *is* sent it must match. That way a UI that forgot to compare cannot create an account whose PIN
    # is not the one the tourist believes they typed.
    confirmation = body.get("pinConfirmation")
    if confirmation is not None and confirmation != pin:
        raise RegistrationError("pinConfirmation does not match pin")

    return {"displayName": display_name, "phone": phone, "pin": pin}


def mint_tourist_id(connection, issued_at_millis: int) -> str:
    """Mint an unused Tourist ID, using the database as the sequence.

    Each attempt consumes exactly one ``AUTOINCREMENT`` number, so the count itself is the source of
    uniqueness; the loop exists only for the pathological case where the resulting *text* id is
    already in use (an operator who created a matching id by hand, say). When that happens the next
    number is tried, which is why the counter is never reset.
    """
    year = year_of(issued_at_millis)
    for _ in range(10_000):  # pragma: no cover - 10k collisions would mean a corrupt database
        cursor = connection.execute(
            "INSERT INTO tourist_id_sequence (issued_at) VALUES (?)",
            (issued_at_millis,),
        )
        candidate = format_tourist_id(int(cursor.lastrowid or 0), year)
        taken = connection.execute(
            "SELECT 1 FROM tourists WHERE id = ?", (candidate,)
        ).fetchone()
        if taken is None:
            return candidate
    raise RuntimeError("could not mint a unique Tourist ID")  # pragma: no cover
