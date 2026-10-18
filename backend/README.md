# Tourist Safety — authority backend

The service the phone talks to and the duty officer works from. One Flask app, one SQLite file, no
container and no internet connection required.

It has two audiences:

* **the phone** — registers itself, signs in as a tourist, uploads its durable outbox (zone alerts, position
  reports, SOS, the on-device risk summary) and its emergency-contact list;
* **the authority dashboard** — a static page served at `/` that reads the same API, lists the registered
  tourists and works the SOS queue.

### Self-service tourist registration

A tourist can create their own account from the phone, with no officer involved:

1. On the phone, choose **I'm a tourist** on the Welcome screen, then **Create an account** (name, phone
   number, a 4–8 digit PIN and its confirmation, optional emergency contacts) → `POST /api/v1/auth/register`.
   The form never depends on a backend address being configured, and it names the field it wants rather
   than greying the button out.
2. The service mints the Tourist ID (`TR-2026-00124`) from a database sequence and returns it — with no
   token and no secret — and the phone shows a success screen with a **copy** button.
3. The tourist signs in with that id and the PIN they chose.
4. The account appears immediately under **Registered Tourists** on the dashboard: Tourist ID, name,
   registration date and *registration source* (`self-registered`), with **Registration**, **Tracking** and
   the safety signals in **separate columns** — a tourist who has not reported yet shows
   *Location not yet available* rather than a fabricated position.

The details, and what the endpoint refuses, are under [HTTP API](#self-service-registration).

## Running it

```powershell
python backend\run.py                      # 127.0.0.1:8080, for the dashboard on this computer
python backend\run.py --host 0.0.0.0       # reachable from a phone on the same network
python backend\run.py --port 9000 --database D:\tsm\authority.db
```

Then:

* **dashboard** — open <http://127.0.0.1:8080/>
* **health** — <http://127.0.0.1:8080/api/v1/health> (and `/healthz` for a process supervisor)

On the **first** start the server creates two accounts and does one of two things:

* if `TSM_DEMO_TOURIST_PASSWORD` / `TSM_AUTHORITY_PASSWORD` are set, those are used;
* otherwise a random password is generated, printed to the console **once**, and written to
  `backend/var/DEV_CREDENTIALS.txt` (tab-separated `role id password`, one per line).

There is no password anywhere in this repository — not in the source, not in a default, not in a
fixture. `backend/tests/test_security.py` asserts that.

Delete `backend/var/` to start over. Restarting the server never resets an existing password.

### Tourist PIN (id + PIN sign-in)

The same first start also generates a **6-digit PIN** for the demo tourist, prints it **once**, and — like
the password — stores only a scrypt hash (`pin_salt` / `pin_hash`). The PIN is deliberately **not** written
to `DEV_CREDENTIALS.txt`, which stays a three-field `role/id/password` table. A tourist can change it
in-app or with `PUT /api/v1/me/pin` (4–8 digits). Set `TSM_DEMO_TOURIST_PIN` to choose your own.

`POST /auth/login` accepts **either** a `password` **or** a `pin`:

```json
{"role":"tourist","id":"T-1001","pin":"4821","deviceId":"Pixel"}
```

An empty or missing secret is a `400`; a wrong one is a `401` with the same
`unknown id or wrong password` body whether a password or a PIN was offered, so the endpoint cannot be
used to tell them apart. An operator account can never use a PIN. See `backend/tests/test_pin.py`.

### Pointing the phone at it

A debug build can bake a default in, so the app is often already pointed at the right place on first
launch: build with `-PAUTHORITY_URL=http://192.168.1.20:8080` (or set `TSM_AUTHORITY_URL`, or
`AUTHORITY_URL=…` in `local.properties`). It is written to the session store **once** and never overwrites
an address that has already been chosen.

To change it on the phone, open **Settings → Backend connection (advanced)** — the address deliberately
does not sit in the tourist's normal path. Press **Test connection** to confirm it: that performs a real
`GET /api/v1/health` and reports *Connected* only when something answered, *Offline* otherwise.

| Where the phone is | Address to enter |
| --- | --- |
| Android emulator on this computer | `10.0.2.2:8080` |
| Physical phone on the same Wi-Fi | the LAN address `run.py` prints on start, e.g. `192.168.1.20:8080` |

Then use **I'm a tourist** to sign in with the tourist id and password (or the id and PIN) — or to create
an account, if you have none. **I'm an authority officer** leads to a separate officer console (the duty
officer's id and password) that lists the registered tourists; tourist credentials are refused there by the
server with `403`. Until an address is set *and* a tourist is signed in, nothing is delivered: every event
stays queued on the phone and the app says so.

## Tests

```powershell
python -m pytest backend\tests -q        # 265 tests, in-process, ~110 s
python backend\verify_integration.py     # 41 checks, end-to-end against a real process on a real socket
```

`verify_integration.py` starts its own server on a free port, signs in as both roles, self-registers a
tourist and signs in with the id and PIN it minted, delivers an outbox batch, checks idempotency and SOS
acknowledgement, restarts the server to prove persistence, and fetches the dashboard. It prints the
generated credentials and a pass/fail line per check. It is the acceptance test for "the phone's contract
works"; `backend/tests/` is the acceptance test for "the rules are right".

The registration rules specifically live in `backend/tests/test_registration.py` (45 tests): the id is
minted and unique, the PIN is hashed and the digest is never returned, duplicate phones are `409`, invalid
bodies are `400`, a `role` of `authority` is refused, the new account appears in the authenticated register
with registration separate from tracking, and a tourist token is still `403` on the authority routes.

The Android live test drives the *production Kotlin client* against a server you start yourself:

```powershell
python backend\run.py --port 8080        # terminal 1
# terminal 2, from the repository root:
.\build.ps1 ":app:testDebugUnitTest" "--tests" "com.touristsafety.app.authority.AuthorityLiveBackendTest" `
  "-Dtsm.e2e.baseUrl=http://127.0.0.1:8080" `
  "-Dtsm.e2e.credentials=D:\TouristSafetyMonitoring-src\backend\var\DEV_CREDENTIALS.txt"
```

Without `-Dtsm.e2e.baseUrl` those seven tests skip, so an ordinary test run never needs a server. As well
as the tourist journeys, they cover the **officer console**: an officer signs in, reads
`GET /api/v1/authority/tourists` through the app's own client method, and a **tourist** token presented to
the same route is refused with `403`.

## Configuration

Every key has a default and can be overridden by an environment variable, so nothing has to be edited
to deploy this.

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `TSM_DATABASE` | `backend/var/authority.db` | SQLite file |
| `TSM_CREDENTIALS_FILE` | `backend/var/DEV_CREDENTIALS.txt` | where generated passwords are written once |
| `TSM_DEMO_TOURIST_ID` | `T-1001` | the starting tourist |
| `TSM_DEMO_TOURIST_NAME` | `Demo Tourist` | displayed name |
| `TSM_DEMO_TOURIST_PHONE` | *(none)* | optional |
| `TSM_DEMO_TOURIST_PASSWORD` | *(generated)* | set to avoid generation |
| `TSM_DEMO_TOURIST_PIN` | *(generated)* | choose the tourist's 4–8 digit sign-in PIN |
| `TSM_AUTHORITY_ID` | `A-01` | the starting duty officer |
| `TSM_AUTHORITY_NAME` | `Duty Officer` | displayed name |
| `TSM_AUTHORITY_PASSWORD` | *(generated)* | set to avoid generation |

Token lifetime and the batch/page ceilings are config keys with defaults in `tsm/__init__.py`
(`TOKEN_TTL_MILLIS` = 30 days) and `tsm/api.py` (`MAX_EVENTS_PER_BATCH` = 200, `DEFAULT_PAGE` = 100,
`MAX_PAGE` = 500).

## HTTP API

All routes are under `/api/v1`. Authentication is a bearer token from `/auth/login`, held in the
`sessions` table so it can expire and be revoked server-side. Errors are always JSON:
`{"error": "<code>", "message": "<human readable>"}`.

| Method | Path | Role | Purpose |
| --- | --- | --- | --- |
| `GET` | `/health` | — | status, version, database path, server time |
| `POST` | `/auth/register` | — | self-service tourist registration; returns the minted Tourist ID and **no secret** |
| `POST` | `/auth/login` | — | `{role, id, password, deviceId?}` → token (or `{role, id, pin}`) |
| `POST` | `/auth/logout` | any | revoke the presented token |
| `GET` | `/me` | tourist | own record, contacts, delivery totals, latest SOS/position |
| `PUT` | `/me/contacts` | tourist | replace the whole contact list |
| `PUT` | `/me/pin` | tourist | set/change the 4–8 digit sign-in PIN |
| `POST` | `/sync/events` | any | ingest an outbox batch (idempotent per `dedupeKey`) |
| `GET` | `/authority/overview` | authority | counts and the tourist fleet |
| `GET` | `/authority/sos` | authority | the SOS queue (`?status=ALL\|OPEN\|ACKNOWLEDGED`) |
| `POST` | `/authority/sos/<id>/ack` | authority | work an emergency (`{note?}`) |
| `GET` | `/authority/events` | authority | the audit trail (`?kind=`, `?touristId=`, `?limit=`) |
| `GET` | `/authority/tourists` | authority | the registered-tourist register: registration, tracking status and latest position-or-none, newest first |
| `GET` | `/authority/tourists/<id>` | authority | everything known about one tourist |
| `POST` | `/authority/tourists` | authority | register a tourist *at the desk*; returns the password **once** |

### Self-service registration

`POST /auth/register` is the one public write, and it exists because a tourist has no credential *until*
they register. It is fenced by three rules:

* it can create exactly one thing — a tourist. It reads **no role** from the request, so it cannot mint an
  operator; a body carrying `"role": "authority"` is refused with `400`. Unknown fields have nowhere to go.
* the Tourist ID is **minted by the database**, never by the client. Each registration consumes one
  `AUTOINCREMENT` row in `tourist_id_sequence`, and `tourists.id` is the primary key, so two registrations
  cannot collide — `TR-<year>-<5 digits>`, e.g. `TR-2026-00124`. The counter is never reset, and if the
  resulting text id is somehow already taken the next number is tried.
* it answers with the id and the account's own non-secret fields only: **no token**, no password, no PIN,
  no hash. Signing in stays a separate step. (The `password` column is `NOT NULL`, so a random secret is
  generated and immediately discarded — the PIN the tourist chose is the credential.)

```json
POST /api/v1/auth/register
{"displayName": "Nadia Qureshi", "phone": "+91 90000 11111", "pin": "4821",
 "pinConfirmation": "4821", "contacts": [{"name": "Asha", "phone": "112", "relationship": "Sister"}]}
→ 201
{"tourist": {"touristId": "TR-2026-00124", "displayName": "Nadia Qureshi", "phone": "+91 90000 11111",
             "registeredAtMillis": 1730000000000, "registrationSource": "SELF", "emergencyContactCount": 1},
 "note": "Registration complete. Sign in with this Tourist ID and the PIN you chose. …",
 "serverTimeMillis": 1730000000000}
```

`displayName`, `phone` and a 4–8 digit `pin` are required; `pinConfirmation`, when sent, must match the
PIN; a phone number that is already registered is `409`. Emergency contacts, if supplied, are validated by
the same rule the outbox uses and are stored against the new account. The PIN is hashed with the project's
scrypt parameters before it is written, and neither the salt nor the digest is ever returned.

### The sync contract the phone speaks

Request — one entry per queued event, in the order the phone wants them handled:

```json
{
  "events": [
    {
      "dedupeKey": "sos:1730000000000",
      "kind": "SOS",
      "origin": "SIMULATED_WATCH",
      "payload": {"type": "SOS", "latitude": 18.52, "longitude": 73.85, "timestampMillis": 1730000000000, "simulated": true},
      "createdAtMillis": 1730000000000,
      "attemptCount": 1
    }
  ]
}
```

`kind` is one of `ZONE_ALERT`, `SOS`, `POSITION`, `CONTACTS` or `RISK`. `payload` may be a nested object
or a JSON-encoded string.

Response — one verdict per entry, in the same order:

```json
{
  "results": [
    {"dedupeKey": "sos:1730000000000", "status": "DELIVERED", "serverEventId": "evt_…", "acknowledgementId": "evt_…"}
  ],
  "serverTimeMillis": 1730000000001
}
```

* `DELIVERED` — stored; `serverEventId` is the authority's id for it.
* `DUPLICATE` — this `dedupeKey` was already received; the original `serverEventId` is returned. The
  phone counts this as success, because that is what idempotency means.
* `REJECTED` — permanently invalid, with a `reason` naming the field. The phone moves the row to a
  terminal state and shows the reason; retrying the same bytes cannot help.

One rejected entry never aborts the batch.

### Rules the payloads must satisfy

| Kind | Requires | Notes |
| --- | --- | --- |
| `ZONE_ALERT` | `zoneId`, `zoneName`, `zoneType` ∈ {SAFE, RESTRICTED, DANGEROUS}, `eventType` ∈ {ENTRY, EXIT}, `latitude`, `longitude`, `timestampMillis` | a crossing with no position is meaningless |
| `SOS` | `timestampMillis` | **position is optional**: a tourist with no fix must still be able to call for help |
| `POSITION` | `latitude`, `longitude` | an older `timestampMillis` than the current latest does not move the tourist backwards |
| `CONTACTS` | `contacts` (a list, ≤ 20) | each entry needs `name` and `phone`; omitted `priority` defaults to its index |
| `RISK` | `modelVersion`, `horizonSeconds`, `riskBand` ∈ {LOW, MODERATE, HIGH}, `probability` | a **summary of an on-device forecast**, not an incident: it is stored in `risk_assessments`, is labelled `PREDICTION`, and can never create an SOS |

Whole-number fields reject a decimal (`5.5` is an error; `5` and `5.0` are accepted), latitude and
longitude are range-checked, and `simulated` must be a real boolean rather than a truthy string.

## Data model

One SQLite file, created idempotently, never migrated destructively.

* `tourists`, `operators` — ids, display names, per-user salt and scrypt digest. No recoverable
  password is stored anywhere. `tourists.registration_source` records how the account came to exist
  (`SELF` for an app registration, the operator's id for a desk account, `NULL` for anything older).
* `tourist_id_sequence` — the Tourist-ID ledger. `id` is `AUTOINCREMENT`, and it is the **database** that
  issues each number: a restart, a crash or two registrations in the same millisecond cannot repeat one.
  The resulting text id is additionally protected by `tourists.id` being the primary key.
* `sessions` — one row per sign-in, with an expiry and the device label, so a token can be revoked.
* `emergency_contacts` — the whole list per tourist, replaced in one transaction.
* `events` — **append-only** audit trail. Nothing in this service updates or deletes a row here, so
  the authority can always answer "what exactly did the device report, and when did we receive it?".
  A `UNIQUE (tourist_id, dedupe_key)` index is what makes re-delivery harmless.
* `sos_events` — a separate table because an SOS has a *workflow* (OPEN → ACKNOWLEDGED) that a routine
  zone crossing does not. `event_id` is UNIQUE, so a re-delivered SOS cannot create a second
  emergency.
* `latest_positions` — one row per tourist, updated only by a fresher fix.
* `risk_assessments` — the on-device forecasts, in their **own** table so a prediction can never be
  counted as an event that happened or leak into the SOS queue.

## What this service deliberately is not

* **No position on an SOS is not a failure.** The dashboard renders "position unknown" rather than
  inventing coordinates.
* **A simulated event is always marked as simulated.** The `simulated` flag travels from the payload
  into `events`, `sos_events` and `latest_positions`, and the dashboard badges it. Nothing here
  presents a simulated SOS as a real one.
* **No outbound calls.** The service never rings a phone, sends a message or contacts a third party.
  It records what arrived and lets the officer decide. That is a deliberate scope line, not an
  omission.
* **No user management.** There is no way to change a password or list sessions over HTTP. Deleting
  the row (or the database) is the supported reset. Self-registration adds exactly one public *write*
  (creating a tourist, with no privilege field of its own), so the reachable surface is still small and
  the two privileged routes remain the two privileged routes.
