"""End-to-end integration check for the authority backend.

This is the script that answers "is the integration *actually* working?", and it is deliberately not
a mock: it

1. starts the real server as a separate process on a free port, against a throwaway SQLite file;
2. reads the credentials the server *generated* on first start (proving the documented first-run flow);
3. signs in as the duty officer, registers a tourist, and signs in as that tourist;
4. uploads the emergency-contact list;
5. delivers an outbox batch exactly as the phone's `HttpSyncTransport` shapes it — a simulated
   SOS, a zone alert, a position report **and an on-device future-risk prediction summary** — and
   re-delivers the SOS to prove server-side idempotency;
6. reads the SOS back from the authority API and acknowledges it, and reads the risk prediction back
   from `/authority/risk` and the tourist detail, checking it is labelled as a prediction and that it
   raised no SOS;
7. **kills the server, restarts it against the same database, and checks the SOS and the prediction are
   still there and still acknowledged** — the persistence claim;
8. prints one PASS/FAIL line per step and exits non-zero if anything failed.

Run it with the backend dependencies available:

    python backend/verify_integration.py
"""

from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

BACKEND_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(BACKEND_DIR)
PYTHON = sys.executable


class Step:
    def __init__(self) -> None:
        self.failures = 0
        self.passes = 0

    def check(self, label: str, condition: bool, detail: str = "") -> bool:
        if condition:
            self.passes += 1
            print(f"  PASS  {label}")
        else:
            self.failures += 1
            print(f"  FAIL  {label}" + (f"  <- {detail}" if detail else ""))
        return condition

    def summary(self) -> int:
        print("-" * 72)
        print(f"  {self.passes} checks passed, {self.failures} failed")
        return 0 if self.failures == 0 else 1


step = Step()


# ---------------------------------------------------------------------------- HTTP helpers


def request(base: str, method: str, path: str, body=None, token: str | None = None):
    """Return (status, decoded_json_or_none). Raises only on transport failure."""
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            raw = response.read().decode("utf-8")
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            return error.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return error.code, None


def free_port() -> int:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def wait_for_health(base: str, timeout_seconds: float = 25.0) -> bool:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        try:
            status, payload = request(base, "GET", "/api/v1/health")
            if status == 200 and payload and payload.get("status") == "ok":
                return True
        except Exception:  # noqa: BLE001 - the server may not be listening yet
            pass
        time.sleep(0.25)
    return False


# ---------------------------------------------------------------------------- server lifecycle


class Server:
    """A real `python backend/run.py` process, on a throwaway database."""

    def __init__(self, database_path: str, credentials_path: str) -> None:
        self.database_path = database_path
        self.credentials_path = credentials_path
        self.port = free_port()
        self.process: subprocess.Popen | None = None

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def start(self) -> bool:
        environment = dict(os.environ)
        environment["TSM_DATABASE"] = self.database_path
        environment["TSM_CREDENTIALS_FILE"] = self.credentials_path
        # Deliberately NOT setting TSM_AUTHORITY_PASSWORD: the point of this script is to prove the
        # generated-credentials first-run flow works, not to short-circuit it.
        environment.pop("TSM_AUTHORITY_PASSWORD", None)
        environment.pop("TSM_DEMO_TOURIST_PASSWORD", None)
        environment["PYTHONIOENCODING"] = "utf-8"

        self.process = subprocess.Popen(
            [PYTHON, os.path.join(BACKEND_DIR, "run.py"), "--host", "127.0.0.1",
             "--port", str(self.port), "--quiet"],
            cwd=REPO_ROOT,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        return wait_for_health(self.base)

    def stop(self) -> None:
        if self.process is None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:  # pragma: no cover
            self.process.kill()
            self.process.wait(timeout=10)
        self.process = None

    def restart(self) -> bool:
        self.stop()
        self.port = free_port()
        return self.start()


def read_credentials(path: str) -> dict[str, tuple[str, str]]:
    """Parse the first-run credentials file into {role: (id, password)}."""
    found: dict[str, tuple[str, str]] = {}
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3:
                found[parts[0]] = (parts[1], parts[2])
    return found


# ---------------------------------------------------------------------------- the run


def main() -> int:
    workdir = tempfile.mkdtemp(prefix="tsm-verify-")
    database_path = os.path.join(workdir, "authority.db")
    credentials_path = os.path.join(workdir, "DEV_CREDENTIALS.txt")

    print("=" * 72)
    print("  Tourist Safety — backend integration verification")
    print(f"  throwaway database : {database_path}")
    print("=" * 72)

    server = Server(database_path, credentials_path)
    if not step.check("server starts and answers /api/v1/health", server.start()):
        server.stop()
        return step.summary()

    try:
        status, health = request(server.base, "GET", "/api/v1/health")
        step.check("health reports ok", status == 200 and health["status"] == "ok",
                   f"status={status} body={health}")

        # ------------------------------------------------------------ first-run credentials
        credentials = read_credentials(credentials_path) if os.path.exists(credentials_path) else {}
        step.check(
            "first start generated an authority account (not a hard-coded password)",
            "authority" in credentials,
            f"file={credentials_path} exists={os.path.exists(credentials_path)}",
        )
        if "authority" not in credentials:
            server.stop()
            return step.summary()
        authority_id, authority_password = credentials["authority"]
        tourist_seed_id, tourist_seed_password = credentials.get("tourist", ("", ""))

        # ------------------------------------------------------------ auth
        status, payload = request(server.base, "GET", "/api/v1/authority/overview")
        step.check("authority routes reject an unauthenticated request", status == 401,
                   f"status={status}")

        status, payload = request(server.base, "POST", "/api/v1/auth/login",
                                  {"role": "authority", "id": authority_id, "password": "wrong"})
        step.check("wrong password is rejected", status == 401, f"status={status}")

        status, officer = request(server.base, "POST", "/api/v1/auth/login",
                                  {"role": "authority", "id": authority_id,
                                   "password": authority_password, "deviceId": "verify"})
        officer_token = officer["token"] if status == 200 and officer else None
        step.check("duty officer signs in with the generated password",
                   status == 200 and bool(officer_token), f"status={status} body={officer}")

        # ------------------------------------------------------------ register a tourist
        tourist_id = "T-VERIFY"
        status, created = request(server.base, "POST", "/api/v1/authority/tourists",
                                  {"touristId": tourist_id, "displayName": "Verification Walker",
                                   "phone": "+91-00000-00000"}, token=officer_token)
        tourist_password = created["password"] if status == 201 and created else None
        step.check("officer registers a tourist and gets a one-time password",
                   status == 201 and bool(tourist_password), f"status={status} body={created}")

        status, walker = request(server.base, "POST", "/api/v1/auth/login",
                                 {"role": "tourist", "id": tourist_id,
                                  "password": tourist_password, "deviceId": "verify-phone"})
        walker_token = walker["token"] if status == 200 and walker else None
        step.check("tourist signs in from 'the phone'", status == 200 and bool(walker_token),
                   f"status={status} body={walker}")

        step.check("a tourist token cannot reach the authority API",
                   request(server.base, "GET", "/api/v1/authority/overview",
                           token=walker_token)[0] == 403)

        # ------------------------------------------------------------ self-service registration
        # A tourist with no credential at all creates their own account from the phone. The *service*
        # mints the id, and the new account must then be visible to the officer without the officer
        # having done anything.
        registration_phone = f"+91-80000-{int(time.time() * 1000)}"
        status, registration = request(
            server.base, "POST", "/api/v1/auth/register",
            {
                "role": "tourist",
                "displayName": "Self Registered Walker",
                "phone": registration_phone,
                "pin": "4821",
                "pinConfirmation": "4821",
                "deviceId": "verify-self",
                "contacts": [
                    {"name": "Asha Rao", "phone": "+91-90000-11111", "relationship": "Sister"},
                ],
            },
        )
        self_registered_id = registration["tourist"]["touristId"] if status == 201 and registration else None
        step.check(
            "a tourist self-registers and the database mints a TR-<year>-<n> id",
            status == 201 and bool(self_registered_id)
            and re.match(r"^TR-\d{4}-\d{5}$", self_registered_id or "") is not None,
            f"status={status} body={registration}",
        )
        step.check(
            "the registration response carries the id but no token and no secret",
            bool(registration) and "token" not in registration
            and "password" not in json.dumps(registration)
            and registration["tourist"]["registrationSource"] == "SELF",
            f"body={registration}",
        )

        status, self_login = request(server.base, "POST", "/api/v1/auth/login",
                                     {"role": "tourist", "id": self_registered_id, "pin": "4821",
                                      "deviceId": "verify-self"})
        self_token = self_login["token"] if status == 200 and self_login else None
        step.check("the self-registered tourist signs in with the id and the PIN they chose",
                   status == 200 and bool(self_token)
                   and self_login["subjectId"] == self_registered_id,
                   f"status={status} body={self_login}")

        status, duplicate = request(server.base, "POST", "/api/v1/auth/register",
                                    {"displayName": "Copy Cat", "phone": registration_phone,
                                     "pin": "4821"})
        step.check("registering the same phone number twice is a 409, not a second account",
                   status == 409, f"status={status} body={duplicate}")

        status, escalated = request(server.base, "POST", "/api/v1/auth/register",
                                    {"role": "authority", "displayName": "Sneaky",
                                     "phone": registration_phone + "9", "pin": "4821"})
        step.check("a registration cannot mint an authority account", status == 400,
                   f"status={status} body={escalated}")

        status, register_list = request(server.base, "GET", "/api/v1/authority/tourists",
                                        token=officer_token)
        register_rows = register_list["tourists"] if status == 200 else []
        self_row = next((row for row in register_rows if row["touristId"] == self_registered_id), None)
        step.check(
            "the self-registered tourist appears on the officer's dashboard, with a registration date",
            self_row is not None and self_row["displayName"] == "Self Registered Walker"
            and self_row["emergencyContactCount"] == 1,
            f"rows={[row['touristId'] for row in register_rows]}",
        )
        step.check(
            "registration and tracking are separate: registered, with no position yet",
            self_row is not None and self_row["registrationStatus"] == "REGISTERED"
            and self_row["trackingStatus"] == "AWAITING_FIRST_REPORT"
            and self_row["hasPosition"] is False
            and self_row["latestPosition"] is None
            and self_row["registeredAtMillis"] > 0,
            f"row={self_row}",
        )

        # ------------------------------------------------------------ emergency contacts
        status, contacts = request(
            server.base, "PUT", "/api/v1/me/contacts",
            {"contacts": [
                {"name": "Asha Rao", "phone": "+91-90000-11111", "relationship": "Sister", "priority": 0},
                {"name": "Control Room", "phone": "112", "relationship": "Emergency services",
                 "priority": 1},
            ]},
            token=walker_token,
        )
        step.check("emergency contacts upload and read back",
                   status == 200 and contacts and contacts["count"] == 2,
                   f"status={status} body={contacts}")

        # ------------------------------------------------------------ outbox delivery
        # Shaped exactly as app/src/main/java/.../outbox/OutboxPayloads.kt builds it.
        now_millis = int(time.time() * 1000)
        sos_payload = {
            "type": "SOS", "simulated": True, "timestampMillis": now_millis,
            "latitude": 13.174338, "longitude": 77.563211, "accuracyMeters": 15.0,
            "locationSource": "GPS",
        }
        alert_payload = {
            "type": "ZONE_ALERT", "zoneId": 7, "zoneName": "Cliff edge",
            "zoneType": "RESTRICTED", "eventType": "ENTRY",
            "latitude": 13.174338, "longitude": 77.563211, "accuracyMeters": 15.0,
            "timestampMillis": now_millis - 5000, "locationSource": "GPS", "simulated": False,
        }
        position_payload = {
            "type": "POSITION", "latitude": 13.1744, "longitude": 77.5633,
            "accuracyMeters": 12.0, "timestampMillis": now_millis, "simulated": False,
        }
        # Shaped exactly as app/src/main/java/.../outbox/OutboxPayloads.futureRisk() builds it: a
        # *summary* of the on-device forecast — no coordinates and no raw sensor windows travel in it.
        risk_payload = {
            "type": "RISK", "timestampMillis": now_millis,
            "modelVersion": "future-risk-unimib-v1", "horizonSeconds": 1.0,
            "riskBand": "HIGH", "probability": 0.62, "windowSamples": 50, "observedSamples": 50,
            "dataQuality": {"ok": True, "samples": 50, "reason": None},
            "factors": [
                {"label": "Acceleration magnitude", "detail": "peak 4.2 g"},
                {"label": "Free-fall fraction", "detail": "0.18 of the window"},
            ],
        }
        batch = {
            "deviceId": "verify-phone",
            "events": [
                {"dedupeKey": f"sos:{now_millis}", "kind": "SOS", "payload": sos_payload,
                 "createdAtMillis": now_millis, "attemptCount": 1, "origin": "SIMULATED_WATCH"},
                {"dedupeKey": f"alert:7:ENTRY:{now_millis - 5000}", "kind": "ZONE_ALERT",
                 "payload": alert_payload, "createdAtMillis": now_millis - 5000,
                 "attemptCount": 1, "origin": "GPS"},
                {"dedupeKey": f"position:{tourist_id}:{now_millis // 60000}", "kind": "POSITION",
                 "payload": position_payload, "createdAtMillis": now_millis,
                 "attemptCount": 1, "origin": "GPS"},
                {"dedupeKey": f"risk:{now_millis}", "kind": "RISK", "payload": risk_payload,
                 "createdAtMillis": now_millis, "attemptCount": 1, "origin": "ON_DEVICE_MODEL"},
            ],
        }

        status, delivered = request(server.base, "POST", "/api/v1/sync/events", batch,
                                    token=walker_token)
        statuses = [r["status"] for r in delivered["results"]] if status == 200 and delivered else []
        step.check("the outbox batch is accepted and every event is DELIVERED",
                   status == 200 and statuses == ["DELIVERED"] * 4,
                   f"status={status} results={delivered}")

        # The same batch again: a lost acknowledgement must not create a second SOS.
        status, again = request(server.base, "POST", "/api/v1/sync/events", batch,
                                token=walker_token)
        step.check("re-delivering the same batch is reported DUPLICATE, never duplicated",
                   status == 200 and all(r["status"] == "DUPLICATE" for r in again["results"]),
                   f"results={again}")

        status, overview = request(server.base, "GET", "/api/v1/authority/overview",
                                  token=officer_token)
        counts = overview["counts"] if status == 200 else {}
        step.check("SOS is counted exactly once after the duplicate delivery",
                   counts.get("sosTotal") == 1, f"counts={counts}")
        step.check("the zone alert and position report arrived",
                   counts.get("zoneAlerts") == 1 and counts.get("events") == 4, f"counts={counts}")
        step.check("emergency contacts reached the authority (2 uploaded + 1 from self-registration)",
                   counts.get("emergencyContacts") == 3, f"counts={counts}")

        # ------------------------------------------------------------ future-risk prediction
        status, risk_list = request(server.base, "GET", "/api/v1/authority/risk?limit=50",
                                    token=officer_token)
        risk_rows = risk_list["risk"] if status == 200 else []
        step.check("the on-device future-risk summary reaches the authority dashboard",
                   len(risk_rows) == 1 and risk_rows[0]["riskBand"] == "HIGH",
                   f"status={status} rows={risk_rows}")
        step.check(
            "the prediction is labelled PREDICTION on the wire, with its model version and horizon",
            bool(risk_rows) and risk_rows[0]["kind"] == "PREDICTION"
            and risk_rows[0]["modelVersion"] == "future-risk-unimib-v1"
            and risk_rows[0]["horizonSeconds"] == 1.0,
            f"row={risk_rows[0] if risk_rows else None}",
        )
        step.check("the prediction carries its calibrated probability and contributing factors",
                   bool(risk_rows) and risk_rows[0]["probability"] == 0.62
                   and len(risk_rows[0]["factors"]) == 2
                   and risk_rows[0]["dataOk"] is True,
                   f"row={risk_rows[0] if risk_rows else None}")
        step.check("a HIGH forecast is counted as elevated risk and raises NO incident or SOS",
                   counts.get("riskAssessments") == 1 and counts.get("riskElevated") == 1
                   and counts.get("sosTotal") == 1,
                   f"counts={counts}")
        step.check("the prediction appears in the tourist's own detail view",
                   request(server.base, "GET", f"/api/v1/authority/tourists/{tourist_id}",
                           token=officer_token)[1]["latestRisk"]["riskBand"] == "HIGH")

        # ------------------------------------------------------------ SOS workflow
        status, sos_list = request(server.base, "GET", "/api/v1/authority/sos?status=OPEN",
                                  token=officer_token)
        sos_rows = sos_list["sos"] if status == 200 else []
        step.check("the SOS appears in the officer's open queue",
                   len(sos_rows) == 1 and sos_rows[0]["status"] == "OPEN", f"rows={sos_rows}")
        sos_id = sos_rows[0]["sosId"] if sos_rows else None
        step.check("the SOS is marked SIMULATED, so it cannot be mistaken for a real one",
                   bool(sos_rows) and sos_rows[0]["simulated"] is True, f"rows={sos_rows}")

        status, acked = request(server.base, "POST", f"/api/v1/authority/sos/{sos_id}/ack",
                                {"note": "Called the tourist and dispatched patrol."},
                                token=officer_token)
        step.check("the officer acknowledges the SOS",
                   status == 200 and acked["sos"]["status"] == "ACKNOWLEDGED"
                   and acked["sos"]["acknowledgedBy"] == authority_id,
                   f"status={status} body={acked}")

        # ------------------------------------------------------------ persistence across restart
        if not step.check("server restarts against the same database", server.restart()):
            return step.summary()

        status, officer = request(server.base, "POST", "/api/v1/auth/login",
                                  {"role": "authority", "id": authority_id,
                                   "password": authority_password})
        step.check("the seeded account survives a restart",
                   status == 200 and bool(officer), f"status={status} body={officer}")
        token_after = officer["token"]

        status, sos_after = request(server.base, "GET", "/api/v1/authority/sos?status=ALL",
                                    token=token_after)
        rows_after = sos_after["sos"] if status == 200 else []
        step.check("the SOS survived the restart", len(rows_after) == 1, f"rows={rows_after}")
        step.check(
            "the acknowledgement survived the restart",
            bool(rows_after) and rows_after[0]["status"] == "ACKNOWLEDGED"
            and rows_after[0]["note"] == "Called the tourist and dispatched patrol.",
            f"rows={rows_after}",
        )

        status, events_after = request(server.base, "GET", "/api/v1/authority/events?limit=50",
                                       token=token_after)
        step.check("the whole event log survived the restart",
                   status == 200 and len(events_after["events"]) == 4,
                   f"count={len(events_after['events']) if status == 200 else status}")

        status, risk_after = request(server.base, "GET", "/api/v1/authority/risk?limit=50",
                                     token=token_after)
        rows_risk_after = risk_after["risk"] if status == 200 else []
        step.check("the future-risk prediction survived the restart, and is still not an SOS",
                   len(rows_risk_after) == 1 and rows_risk_after[0]["riskBand"] == "HIGH"
                   and request(server.base, "GET", "/api/v1/authority/overview",
                               token=token_after)[1]["counts"]["sosTotal"] == 1,
                   f"rows={rows_risk_after}")

        status, register_after = request(server.base, "GET", "/api/v1/authority/tourists",
                                         token=token_after)
        register_rows_after = register_after["tourists"] if status == 200 else []
        step.check(
            "the self-registered account survived the restart, still marked SELF",
            any(row["touristId"] == self_registered_id and row["registrationSource"] == "SELF"
                for row in register_rows_after),
            f"rows={[row['touristId'] for row in register_rows_after]}",
        )

        status, detail = request(server.base,
                                 "GET", f"/api/v1/authority/tourists/{tourist_id}",
                                 token=token_after)
        step.check("the tourist detail view still lists contacts and SOS history",
                   status == 200 and len(detail["contacts"]) == 2 and len(detail["sos"]) == 1,
                   f"status={status}")
        step.check("the tourist detail view still shows the latest prediction",
                   status == 200 and detail["latestRisk"] is not None
                   and detail["latestRisk"]["riskBand"] == "HIGH"
                   and len(detail["risk"]) == 1,
                   f"latestRisk={detail.get('latestRisk') if status == 200 else status}")

        # ------------------------------------------------------------ dashboard
        req = urllib.request.Request(server.base + "/")
        with urllib.request.urlopen(req, timeout=10) as response:
            html = response.read().decode("utf-8")
        step.check("the dashboard is served and wires up its client script",
                   response.status == 200 and "Authority Dashboard" in html and "/static/app.js" in html)

        # ------------------------------------------------------------ demo account
        if tourist_seed_id:
            status, seeded = request(server.base, "POST", "/api/v1/auth/login",
                                     {"role": "tourist", "id": tourist_seed_id,
                                      "password": tourist_seed_password})
            step.check("the seeded demo tourist can sign in with its generated password",
                       status == 200 and bool(seeded), f"status={status}")

    finally:
        server.stop()

    return step.summary()


if __name__ == "__main__":
    raise SystemExit(main())
