"""Start the authority backend.

    python backend/run.py                       # http://127.0.0.1:8080
    python backend/run.py --host 0.0.0.0        # reachable from a phone on the same Wi-Fi

On the first start two accounts are created and their generated passwords are printed **once**.
See README.md in this directory for the full walkthrough.
"""

from __future__ import annotations

import argparse
import os
import socket
import sys

# Allow `python backend/run.py` from anywhere by putting the backend directory on the path.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from tsm import VERSION, create_app  # noqa: E402  (path set up above)


def local_ipv4() -> str | None:
    """Best-effort LAN address, used only to print a URL the phone can actually reach."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return None
    finally:
        sock.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="Tourist Safety authority backend")
    parser.add_argument("--host", default="127.0.0.1", help="bind address (0.0.0.0 for a phone)")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--database", default=None, help="override the SQLite file path")
    parser.add_argument("--quiet", action="store_true", help="suppress the start-up banner")
    args = parser.parse_args()

    config = {}
    if args.database:
        config["DATABASE_PATH"] = args.database

    app = create_app(config or None)
    report = app.extensions.get("tsm_seed_report", {"created": [], "credentials": []})

    if not args.quiet:
        lan = local_ipv4()
        print("=" * 72)
        print(f"  Tourist Safety authority backend {VERSION}")
        print(f"  Database : {app.config['DATABASE_PATH']}")
        print(f"  Dashboard: http://127.0.0.1:{args.port}/")
        if lan:
            print(f"  Dashboard (LAN)  : http://{lan}:{args.port}/")
            print(f"  Android base URL : http://{lan}:{args.port}/")
        print("  Phone on an emulator uses http://10.0.2.2:%d/ as the base URL." % args.port)
        print("-" * 72)

        if report["credentials"]:
            print("  Credentials generated on this first start (shown once):")
            for role, account_id, password in report["credentials"]:
                print(f"    {role:<10} id={account_id:<10} password={password}")
            print(f"  Also written to {app.config['CREDENTIALS_FILE']}")
        elif report["created"]:
            print("  Accounts created from TSM_*_PASSWORD environment variables.")
        else:
            print("  Accounts already existed; nothing was created or changed.")

        pins = [(entry["id"], entry["pin"]) for entry in report["created"] if entry.get("pin")]
        if pins:
            print("-" * 72)
            print("  Tourist sign-in PIN(s) generated on this first start (shown once):")
            for account_id, pin in pins:
                print(f"    tourist    id={account_id:<10} pin={pin}")
            print("  The PIN is stored only as a scrypt hash; a signed-in tourist can change it in-app.")
        print("=" * 72)
        print()

    # threaded=True: the dashboard polls while the phone uploads, and neither may block the other.
    app.run(host=args.host, port=args.port, threaded=True, debug=False, use_reloader=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
