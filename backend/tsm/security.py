"""Credential handling: hashing, verification and bearer tokens.

Two rules this module exists to enforce:

1. **No password is ever hard-coded in this repository.** A fresh checkout has no usable
   credentials at all until the operator supplies them (environment variables) or explicitly runs
   the seeding step, which *generates* a random one and prints it once. See ``tsm/seed.py``.
2. **No password is ever stored recoverably.** Only a per-user random salt and a scrypt digest are
   persisted, and verification is a constant-time comparison.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets

# scrypt parameters. n=2**14 with r=8 costs ~16 MiB and a few tens of milliseconds, which is the
# usual interactive-login sweet spot: far too slow to brute-force, far too fast to annoy a user.
_SCRYPT_N = 2 ** 14
_SCRYPT_R = 8
_SCRYPT_P = 1
_SCRYPT_DKLEN = 32
_SCRYPT_MAXMEM = 64 * 1024 * 1024

# Ambiguous glyphs (0/O, 1/l/I) are excluded so a generated password can be read off a console and
# typed into a phone without a transcription mistake.
_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"


def hash_password(password: str, salt: str | None = None) -> tuple[str, str]:
    """Return ``(salt_hex, hash_hex)`` for ``password``."""
    salt_hex = salt or secrets.token_hex(16)
    digest = hashlib.scrypt(
        password.encode("utf-8"),
        salt=bytes.fromhex(salt_hex),
        n=_SCRYPT_N,
        r=_SCRYPT_R,
        p=_SCRYPT_P,
        dklen=_SCRYPT_DKLEN,
        maxmem=_SCRYPT_MAXMEM,
    )
    return salt_hex, digest.hex()


def verify_password(password: str, salt_hex: str, hash_hex: str) -> bool:
    """Constant-time check of ``password`` against a stored salt and digest."""
    try:
        _, candidate = hash_password(password, salt_hex)
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(candidate, hash_hex)


def dummy_verify() -> None:
    """Burn the same time as a real verification.

    Called when the requested account does not exist, so that an attacker cannot tell "no such
    tourist" from "wrong password" by measuring the response.
    """
    hash_password("not-a-real-password", "00" * 16)


def new_token() -> str:
    """A 256-bit URL-safe bearer token. Stored hashed-at-rest? No: opaque and revocable is enough.

    The token is a random capability, not a password: it is issued per sign-in, expires, and can be
    revoked server-side, so storing it verbatim lets the operator see and revoke live sessions.
    """
    return secrets.token_urlsafe(32)


def new_password(length: int = 16) -> str:
    """A readable, high-entropy password for first-run seeding."""
    return "".join(secrets.choice(_PASSWORD_ALPHABET) for _ in range(length))


def new_pin(length: int = 6) -> str:
    """A numeric PIN, for the tourist's quick sign-in.

    Deliberately digits only: a PIN is the credential a tourist types on a phone keypad after a long
    day, and the search space (10**6) is only safe because login is rate-limited by scrypt's cost and
    the server runs on a private LAN. It is hashed with exactly the same scrypt parameters as a
    password, so it is never stored recoverably.
    """
    return "".join(secrets.choice("0123456789") for _ in range(length))

