"""Application factory for the Tourist Safety authority backend.

    python backend/run.py --host 0.0.0.0 --port 8080

Everything configurable is a config key with a documented default, so the tests can build an app on a
temporary database and a frozen clock without touching the environment.
"""

from __future__ import annotations

import os
import time
from typing import Any, Callable

from flask import Flask, jsonify

from . import api
from .db import Database
from .seed import ensure_seed_accounts

VERSION = "1.0.0"

_BACKEND_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_VAR_DIR = os.path.join(_BACKEND_DIR, "var")

DEFAULTS: dict[str, Any] = {
    "TSM_VERSION": VERSION,
    "DATABASE_PATH": os.environ.get("TSM_DATABASE", os.path.join(_VAR_DIR, "authority.db")),
    # 30 days: a tourist should not be logged out in the middle of a trek, and the token is revocable.
    "TOKEN_TTL_MILLIS": 30 * 24 * 60 * 60 * 1000,
    "SEED_ACCOUNTS": True,
    "DEMO_TOURIST_ID": os.environ.get("TSM_DEMO_TOURIST_ID", "T-1001"),
    "DEMO_TOURIST_NAME": os.environ.get("TSM_DEMO_TOURIST_NAME", "Demo Tourist"),
    "DEMO_TOURIST_PHONE": os.environ.get("TSM_DEMO_TOURIST_PHONE"),
    # Never a literal: `None` means "generate one and print it once". See tsm/seed.py.
    "DEMO_TOURIST_PASSWORD": os.environ.get("TSM_DEMO_TOURIST_PASSWORD"),
    # Optional numeric PIN for the demo tourist; generated and shown once if not supplied.
    "DEMO_TOURIST_PIN": os.environ.get("TSM_DEMO_TOURIST_PIN"),
    "AUTHORITY_ID": os.environ.get("TSM_AUTHORITY_ID", "A-01"),
    "AUTHORITY_NAME": os.environ.get("TSM_AUTHORITY_NAME", "Duty Officer"),
    "AUTHORITY_PASSWORD": os.environ.get("TSM_AUTHORITY_PASSWORD"),
    "CREDENTIALS_FILE": os.environ.get(
        "TSM_CREDENTIALS_FILE", os.path.join(_VAR_DIR, "DEV_CREDENTIALS.txt")
    ),
    "JSON_SORT_KEYS": False,
}

CLOCK_TYPE = Callable[[], int]


def default_clock() -> int:
    return int(time.time() * 1000)


def create_app(
    config: dict[str, Any] | None = None,
    *,
    clock: CLOCK_TYPE | None = None,
) -> Flask:
    app = Flask(__name__, static_folder="static", static_url_path="/static")
    app.config.update(DEFAULTS)
    if config:
        app.config.update(config)

    database = Database(app.config["DATABASE_PATH"])
    database.connect()

    app.extensions["tsm_db"] = database
    app.extensions["tsm_clock"] = clock or default_clock

    if app.config["SEED_ACCOUNTS"]:
        app.extensions["tsm_seed_report"] = ensure_seed_accounts(database, app.config)
    else:
        app.extensions["tsm_seed_report"] = {"created": [], "credentials": []}

    app.register_blueprint(api.bp, url_prefix="/api/v1")

    @app.get("/")
    def dashboard():
        """The authority dashboard. A static page: it talks to the JSON API from the browser."""
        return app.send_static_file("index.html")

    @app.get("/healthz")
    def healthz():
        """Unversioned liveness probe for a process supervisor."""
        return jsonify({"status": "ok", "version": VERSION})

    # The phone and the dashboard both parse JSON. An HTML error page would surface as a
    # JSONDecodeError in the client and hide the real status code, so every error is JSON here.
    @app.errorhandler(404)
    def not_found(_error):
        return jsonify({"error": "not_found", "message": "No such endpoint."}), 404

    @app.errorhandler(405)
    def method_not_allowed(_error):
        return jsonify({"error": "method_not_allowed", "message": "Wrong HTTP method."}), 405

    @app.errorhandler(500)
    def server_error(error):  # pragma: no cover - only reached on a genuine bug
        return jsonify({"error": "server_error", "message": str(error)}), 500

    return app


__all__ = ["create_app", "default_clock", "VERSION"]
