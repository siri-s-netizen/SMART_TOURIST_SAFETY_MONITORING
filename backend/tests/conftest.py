"""Shared fixtures for the authority backend tests.

Everything here is in-process: the Flask test client, a temporary SQLite file per test and a **frozen
clock**. That combination is what makes the interesting behaviour testable at all — token expiry,
"events in the last 24 hours" and SOS acknowledgement timestamps are all functions of time, and
sleeping for them would make the suite slow and flaky.

The token lifetime is the *production* 30 days by default, so tests that advance the clock by hours
still have a valid token; the few tests that are about expiry build their own app with a short TTL via
the ``app_factory`` fixture.

`backend/verify_integration.py` is the complementary test: it starts the service as a real process
over a real socket and checks the whole journey. This suite checks the rules.
"""

from __future__ import annotations

import os
import sys
from typing import Any, NamedTuple

import pytest

# `from tsm import ...` must work when pytest is invoked from the repository root.
BACKEND_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from tsm import create_app  # noqa: E402  (path set up above)

TOURIST_ID = "T-9001"
TOURIST_PASSWORD = "tourist-pass-9f3a2c"
OFFICER_ID = "A-9001"
OFFICER_PASSWORD = "officer-pass-7b1c4d"

#: The production default. Long, so advancing the clock by a day does not sign anyone out.
TOKEN_TTL_MILLIS = 30 * 24 * 60 * 60 * 1000

#: Used only by the tests that are about expiry.
SHORT_TOKEN_TTL_MILLIS = 60_000


class FrozenClock:
    """A clock the test drives. Deterministic timestamps instead of `time.sleep`."""

    def __init__(self, start: int = 1_700_000_000_000) -> None:
        self.now = start

    def __call__(self) -> int:
        return self.now

    def advance(self, millis: int) -> int:
        self.now += millis
        return self.now


@pytest.fixture
def clock() -> FrozenClock:
    return FrozenClock()


def base_config(tmp_path, clock, **overrides: Any) -> dict:
    """Explicit passwords, so no test depends on a value printed at start-up."""
    config: dict[str, Any] = {
        "DATABASE_PATH": str(tmp_path / "authority.db"),
        "CREDENTIALS_FILE": str(tmp_path / "DEV_CREDENTIALS.txt"),
        "SEED_ACCOUNTS": True,
        "SEED_TIME_MILLIS": clock.now,
        "DEMO_TOURIST_ID": TOURIST_ID,
        "DEMO_TOURIST_NAME": "Test Tourist",
        "DEMO_TOURIST_PHONE": "+91 90000 00000",
        "DEMO_TOURIST_PASSWORD": TOURIST_PASSWORD,
        "AUTHORITY_ID": OFFICER_ID,
        "AUTHORITY_NAME": "Test Officer",
        "AUTHORITY_PASSWORD": OFFICER_PASSWORD,
        "TOKEN_TTL_MILLIS": TOKEN_TTL_MILLIS,
        "TESTING": True,
    }
    config.update(overrides)
    return config


@pytest.fixture
def config(tmp_path, clock) -> dict:
    return base_config(tmp_path, clock)


@pytest.fixture
def app_factory(tmp_path, clock):
    """Builds extra applications, each closed at the end of the test."""
    built: list = []

    def _make(**overrides: Any):
        application = create_app(base_config(tmp_path, clock, **overrides), clock=clock)
        built.append(application)
        return application

    yield _make

    for application in built:
        application.extensions["tsm_db"].close()


@pytest.fixture
def app(app_factory):
    return app_factory()


@pytest.fixture
def api(app):
    """The Flask test client. Real routing and real JSON, no socket."""
    return app.test_client()


@pytest.fixture
def db(app):
    return app.extensions["tsm_db"]


def login(api, role: str, subject_id: str, password: str) -> str:
    response = api.post(
        "/api/v1/auth/login",
        json={"role": role, "id": subject_id, "password": password},
    )
    assert response.status_code == 200, response.get_data(as_text=True)
    return response.get_json()["token"]


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def tourist_token(api) -> str:
    return login(api, "tourist", TOURIST_ID, TOURIST_PASSWORD)


@pytest.fixture
def officer_token(api) -> str:
    return login(api, "authority", OFFICER_ID, OFFICER_PASSWORD)


@pytest.fixture
def as_tourist(tourist_token):
    return bearer(tourist_token)


@pytest.fixture
def as_officer(officer_token):
    return bearer(officer_token)


class ShortTtlApp(NamedTuple):
    """An app whose tokens expire in [SHORT_TOKEN_TTL_MILLIS]."""

    client: Any
    token: str
    db: Any


@pytest.fixture
def short_ttl(tmp_path, app_factory) -> ShortTtlApp:
    application = app_factory(
        TOKEN_TTL_MILLIS=SHORT_TOKEN_TTL_MILLIS,
        DATABASE_PATH=str(tmp_path / "short-ttl.db"),
    )
    client = application.test_client()
    return ShortTtlApp(
        client=client,
        token=login(client, "tourist", TOURIST_ID, TOURIST_PASSWORD),
        db=application.extensions["tsm_db"],
    )
