"""First-run account seeding.

The project rule is *no hard-coded credentials*, so this module has no default password anywhere.
Instead:

* if ``TSM_AUTHORITY_PASSWORD`` / ``TSM_DEMO_TOURIST_PASSWORD`` are set in the environment, those are
  used;
* otherwise a high-entropy password is **generated**, printed to the console exactly once, and written
  to ``backend/var/DEV_CREDENTIALS.txt`` (git-ignored) so the operator can read it back;
* if the account already exists, nothing is overwritten — restarting the server never resets a
  password someone has already changed by deleting the row.

Real deployments are expected to either set the environment variables or create accounts through
``POST /api/v1/authority/tourists`` and then delete the seeded ones.
"""

from __future__ import annotations

import os
from typing import Any

from .db import Database
from .security import hash_password, new_password, new_pin


def _create(
    database: Database,
    table: str,
    account_id: str,
    display_name: str,
    password: str,
    at_millis: int,
    phone: str | None = None,
    pin: str | None = None,
) -> None:
    salt, digest = hash_password(password)
    if table == "tourists":
        # The PIN is hashed with the same scrypt parameters as the password and is never stored in
        # clear. A tourist with no PIN simply has NULL pin columns.
        pin_salt, pin_hash = hash_password(pin) if pin else (None, None)
        database.execute(
            "INSERT INTO tourists "
            "(id, display_name, phone, password_salt, password_hash, pin_salt, pin_hash, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (account_id, display_name, phone, salt, digest, pin_salt, pin_hash, at_millis),
        )
    else:
        database.execute(
            "INSERT INTO operators (id, display_name, password_salt, password_hash, created_at) "
            "VALUES (?, ?, ?, ?, ?)",
            (account_id, display_name, salt, digest, at_millis),
        )


def ensure_seed_accounts(database: Database, config: dict[str, Any]) -> dict[str, Any]:
    """Create the two starting accounts if they are missing.

    Returns a summary the caller can print: which accounts were created, and the plaintext password
    for any that was *generated* (never for one supplied through the environment, because the operator
    already knows that one).
    """
    database.connect()
    at_millis = int(config.get("SEED_TIME_MILLIS") or 0) or _now(config)

    report: dict[str, Any] = {"created": [], "credentials": []}

    tourist_id = config["DEMO_TOURIST_ID"]
    if database.query_one("SELECT 1 FROM tourists WHERE id = ?", (tourist_id,)) is None:
        password = config.get("DEMO_TOURIST_PASSWORD") or new_password()
        generated = not config.get("DEMO_TOURIST_PASSWORD")
        # A PIN is always set for a seeded tourist so the "tourist id + PIN" sign-in works out of the
        # box. It is reported only when *generated* (a supplied one is already known to the operator),
        # and it is deliberately kept out of DEV_CREDENTIALS.txt so that file stays a simple
        # role/id/password table.
        pin = config.get("DEMO_TOURIST_PIN") or new_pin()
        pin_generated = not config.get("DEMO_TOURIST_PIN")
        _create(
            database,
            "tourists",
            tourist_id,
            config["DEMO_TOURIST_NAME"],
            password,
            at_millis,
            config.get("DEMO_TOURIST_PHONE"),
            pin=pin,
        )
        entry: dict[str, Any] = {"role": "tourist", "id": tourist_id}
        if pin_generated:
            entry["pin"] = pin
        report["created"].append(entry)
        if generated:
            report["credentials"].append(("tourist", tourist_id, password))

    authority_id = config["AUTHORITY_ID"]
    if database.query_one("SELECT 1 FROM operators WHERE id = ?", (authority_id,)) is None:
        password = config.get("AUTHORITY_PASSWORD") or new_password()
        generated = not config.get("AUTHORITY_PASSWORD")
        _create(database, "operators", authority_id, config["AUTHORITY_NAME"], password, at_millis)
        report["created"].append({"role": "authority", "id": authority_id})
        if generated:
            report["credentials"].append(("authority", authority_id, password))

    if report["credentials"]:
        _write_credentials_file(config, report["credentials"])

    return report


def _now(config: dict[str, Any]) -> int:
    import time

    return int(time.time() * 1000)


def _write_credentials_file(config: dict[str, Any], credentials: list[tuple[str, str, str]]) -> None:
    path = config.get("CREDENTIALS_FILE")
    if not path:
        return
    try:
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(
                "# Local development credentials generated on first start.\n"
                "# These identify a local, single-machine instance only. Delete this file (and the\n"
                "# account row) before deploying anywhere real, or set TSM_AUTHORITY_PASSWORD /\n"
                "# TSM_DEMO_TOURIST_PASSWORD instead of letting the server generate them.\n\n"
            )
            for role, account_id, password in credentials:
                handle.write(f"{role}\t{account_id}\t{password}\n")
    except OSError:
        # A read-only deployment directory must not stop the service from starting.
        pass
