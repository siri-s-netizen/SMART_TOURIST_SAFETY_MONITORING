"""SQLite persistence for the Tourist Safety authority backend.

Plain stdlib ``sqlite3`` on purpose: this service has to start on a marker's laptop with no
network, no container and no migration tooling. WAL mode plus a single writer lock is enough for
the handful of tourists an authority office monitors, and it keeps the deployment story to
"python backend/run.py".

The schema is created idempotently at start-up. There is no destructive path anywhere: deleting a
tourist cascades only to *their* contacts and sessions, never to the audit trail of what they
reported.
"""

from __future__ import annotations

import os
import sqlite3
import threading
from contextlib import contextmanager
from typing import Any, Iterable, Iterator, Optional, Sequence

SCHEMA = """
CREATE TABLE IF NOT EXISTS tourists (
    id             TEXT PRIMARY KEY,
    display_name   TEXT NOT NULL,
    phone          TEXT,
    password_salt  TEXT NOT NULL,
    password_hash  TEXT NOT NULL,
    -- An optional numeric PIN, hashed with the same scrypt parameters as the password. NULL means
    -- "this tourist has no PIN"; a tourist may set one from the app (POST /me/pin).
    pin_salt       TEXT,
    pin_hash       TEXT,
    created_at     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS operators (
    id             TEXT PRIMARY KEY,
    display_name   TEXT NOT NULL,
    password_salt  TEXT NOT NULL,
    password_hash  TEXT NOT NULL,
    created_at     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    token       TEXT PRIMARY KEY,
    subject_id  TEXT NOT NULL,
    role        TEXT NOT NULL,
    device_id   TEXT,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions (expires_at);

CREATE TABLE IF NOT EXISTS emergency_contacts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    tourist_id   TEXT NOT NULL REFERENCES tourists (id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    phone        TEXT NOT NULL,
    relationship TEXT,
    priority     INTEGER NOT NULL DEFAULT 0,
    updated_at   INTEGER NOT NULL,
    UNIQUE (tourist_id, name, phone)
);
CREATE INDEX IF NOT EXISTS idx_contacts_tourist
    ON emergency_contacts (tourist_id, priority, name);

-- Every routine zone crossing, position report and SOS the phone delivers lands here first. It is the
-- append-only audit trail: nothing in this service updates or deletes a row in this table, so an
-- authority can always answer "what exactly did the device report, and when did we receive it?".
CREATE TABLE IF NOT EXISTS events (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    server_event_id   TEXT NOT NULL UNIQUE,
    tourist_id        TEXT NOT NULL,
    dedupe_key        TEXT NOT NULL,
    kind              TEXT NOT NULL,
    origin            TEXT,
    simulated         INTEGER NOT NULL DEFAULT 0,
    latitude          REAL,
    longitude         REAL,
    accuracy_meters   REAL,
    zone_id           INTEGER,
    zone_name         TEXT,
    zone_type         TEXT,
    event_type        TEXT,
    device_timestamp  INTEGER,
    received_at       INTEGER NOT NULL,
    payload           TEXT NOT NULL,
    UNIQUE (tourist_id, dedupe_key)
);
CREATE INDEX IF NOT EXISTS idx_events_received ON events (received_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_kind ON events (kind, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_tourist ON events (tourist_id, received_at DESC);

-- The SOS table is deliberately separate from the generic event log: an SOS has a *workflow*
-- (OPEN -> ACKNOWLEDGED) and an operator has to be able to work it, whereas a zone crossing is
-- merely recorded. ``event_id`` is UNIQUE, so re-delivering the same SOS cannot create a second
-- emergency.
CREATE TABLE IF NOT EXISTS sos_events (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id              INTEGER NOT NULL UNIQUE REFERENCES events (id) ON DELETE CASCADE,
    tourist_id            TEXT NOT NULL,
    latitude              REAL,
    longitude             REAL,
    accuracy_meters       REAL,
    raised_at             INTEGER NOT NULL,
    received_at           INTEGER NOT NULL,
    simulated             INTEGER NOT NULL DEFAULT 0,
    status                TEXT NOT NULL DEFAULT 'OPEN',
    acknowledged_at       INTEGER,
    acknowledged_by       TEXT,
    note                  TEXT
);
CREATE INDEX IF NOT EXISTS idx_sos_status ON sos_events (status, raised_at DESC);

CREATE TABLE IF NOT EXISTS latest_positions (
    tourist_id        TEXT PRIMARY KEY,
    latitude          REAL NOT NULL,
    longitude         REAL NOT NULL,
    accuracy_meters   REAL,
    recorded_at       INTEGER NOT NULL,
    received_at       INTEGER NOT NULL,
    simulated         INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS risk_assessments (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id          INTEGER NOT NULL UNIQUE REFERENCES events (id),
    tourist_id        TEXT NOT NULL,
    model_version     TEXT NOT NULL,
    horizon_seconds   REAL NOT NULL,
    risk_band         TEXT NOT NULL,
    -- NULL when the phone's model is not calibrated: a raw forest vote is not a probability and the
    -- schema refuses to pretend otherwise.
    probability       REAL,
    window_samples    INTEGER NOT NULL,
    observed_samples  INTEGER NOT NULL,
    data_ok           INTEGER NOT NULL DEFAULT 0,
    data_reason       TEXT,
    factors_json      TEXT NOT NULL DEFAULT '[]',
    assessed_at       INTEGER NOT NULL,
    received_at       INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_risk_tourist ON risk_assessments (tourist_id, assessed_at DESC);
CREATE INDEX IF NOT EXISTS idx_risk_received ON risk_assessments (received_at DESC);

-- The Tourist-ID ledger. `id` is SQLite's AUTOINCREMENT, so the number a self-registration consumes
-- can never repeat: the *database* issues it, not a client and not an in-process counter. The row is
-- only a sequence entry, so a rolled-back registration merely leaves a gap — it can never hand the
-- same number out twice. The finished text id is additionally protected by `tourists.id` being the
-- primary key, and `registration.mint_tourist_id` retries if that key is somehow already taken.
CREATE TABLE IF NOT EXISTS tourist_id_sequence (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    issued_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS schema_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Restricted/dangerous/safe areas are owned by the authority, not by a phone. A tourist may read the
-- active ones and be warned by them; only an authenticated officer may create, edit, deactivate or
-- archive one. The polygon ring is stored as JSON (an ordered list of {"latitude","longitude"},
-- closing edge implicit — the first vertex is never repeated), which keeps the geometry readable in
-- the audit trail without a second table.
--
-- `status`: ACTIVE (published — phones evaluate it), INACTIVE (draft/paused — no phone evaluates it),
-- ARCHIVED (soft-deleted — kept for history, never evaluated).
-- `revision` is bumped on every change so a phone can tell whether a zone it cached is still current.
CREATE TABLE IF NOT EXISTS zones (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    description   TEXT NOT NULL DEFAULT '',
    zone_type     TEXT NOT NULL,
    vertices      TEXT NOT NULL,
    vertex_count  INTEGER NOT NULL,
    status        TEXT NOT NULL DEFAULT 'ACTIVE',
    revision      INTEGER NOT NULL DEFAULT 1,
    created_by    TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    updated_by    TEXT,
    updated_at    INTEGER NOT NULL,
    published_at  INTEGER,
    archived_at   INTEGER
);
CREATE INDEX IF NOT EXISTS idx_zones_status ON zones (status, updated_at DESC);

-- One row per zone change (create, edit, status change, archive). Append-only, like `events`: the
-- authority can always answer "who changed this polygon, when, and what did it look like before".
CREATE TABLE IF NOT EXISTS zone_history (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    zone_id   INTEGER NOT NULL REFERENCES zones (id),
    revision  INTEGER NOT NULL,
    action    TEXT NOT NULL,
    actor     TEXT NOT NULL,
    at        INTEGER NOT NULL,
    snapshot  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_zone_history_zone ON zone_history (zone_id, at DESC);
"""


class Database:
    """A tiny thread-safe wrapper around one SQLite file.

    One connection guarded by a re-entrant lock: the Flask development server and the production
    ``waitress``/``gunicorn`` workers all funnel through here, and SQLite serialises writers anyway,
    so a pool would add failure modes without adding throughput.
    """

    def __init__(self, path: str) -> None:
        self.path = path
        self._lock = threading.RLock()
        self._conn: Optional[sqlite3.Connection] = None

    # ------------------------------------------------------------------ lifecycle

    def connect(self) -> sqlite3.Connection:
        with self._lock:
            if self._conn is None:
                directory = os.path.dirname(os.path.abspath(self.path))
                if directory:
                    os.makedirs(directory, exist_ok=True)
                connection = sqlite3.connect(self.path, check_same_thread=False)
                connection.row_factory = sqlite3.Row
                connection.execute("PRAGMA journal_mode=WAL")
                connection.execute("PRAGMA foreign_keys=ON")
                connection.execute("PRAGMA busy_timeout=5000")
                connection.executescript(SCHEMA)
                self._add_missing_columns(connection)
                connection.commit()
                self._conn = connection
            return self._conn

    def close(self) -> None:
        with self._lock:
            if self._conn is not None:
                self._conn.close()
                self._conn = None

    #: Columns added after the first release. ``CREATE TABLE IF NOT EXISTS`` cannot add a column to a
    #: table that already exists, so every later column is applied idempotently at start-up instead.
    #: All of them are nullable, so an existing database keeps working with no data migration.
    ADDED_COLUMNS: tuple[tuple[str, str, str], ...] = (
        ("tourists", "pin_salt", "TEXT"),
        ("tourists", "pin_hash", "TEXT"),
        # How the account came to exist: 'SELF' for an app registration, the operator's id for an
        # account an officer created, and NULL for anything older than this column (the seeded demo
        # tourist, for instance). Nullable so an existing database needs no data migration.
        ("tourists", "registration_source", "TEXT"),
    )

    def _add_missing_columns(self, connection: sqlite3.Connection) -> None:
        for table, column, declaration in self.ADDED_COLUMNS:
            existing = {row["name"] for row in connection.execute(f"PRAGMA table_info({table})")}
            if column not in existing:
                connection.execute(f"ALTER TABLE {table} ADD COLUMN {column} {declaration}")

    # ------------------------------------------------------------------ access

    @contextmanager
    def write(self) -> Iterator[sqlite3.Connection]:
        """Run a unit of work in a transaction, rolling back on any exception."""
        connection = self.connect()
        with self._lock:
            try:
                yield connection
                connection.commit()
            except Exception:
                connection.rollback()
                raise

    def query(self, sql: str, params: Sequence[Any] = ()) -> list[sqlite3.Row]:
        connection = self.connect()
        with self._lock:
            return list(connection.execute(sql, params))

    def query_one(self, sql: str, params: Sequence[Any] = ()) -> Optional[sqlite3.Row]:
        rows = self.query(sql, params)
        return rows[0] if rows else None

    def execute(self, sql: str, params: Sequence[Any] = ()) -> int:
        """Run one statement and return ``lastrowid``."""
        with self.write() as connection:
            cursor = connection.execute(sql, params)
            return int(cursor.lastrowid or 0)

    def execute_many(self, sql: str, rows: Iterable[Sequence[Any]]) -> None:
        with self.write() as connection:
            connection.executemany(sql, list(rows))

    def table_exists(self, name: str) -> bool:
        return self.query_one(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", (name,)
        ) is not None
