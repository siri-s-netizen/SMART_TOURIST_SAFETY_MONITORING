"""Start the authority backend.

    python backend/run.py                       # http://0.0.0.0:8080, reachable from a phone
    python backend/run.py --host 127.0.0.1      # this computer only

The default bind address is ``0.0.0.0`` on purpose. This service exists so that *phones* on the same
Wi-Fi can reach it, and a server bound to loopback cannot be reached by any of them: the phone gets a
connection timeout and the sign-in screen can only say "not answering", which is indistinguishable
from a wrong address, a stopped server, or a firewall. Binding the LAN interface by default removes
that whole class of dead end. It prints the address to type into the app on start-up.

Bind to loopback explicitly (``--host 127.0.0.1``) when working on this computer alone, or when the
Wi-Fi network is not trusted, since ``0.0.0.0`` exposes this port to everyone on the network.

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
    parser.add_argument(
        "--host",
        default="0.0.0.0",
        help="bind address; 0.0.0.0 (default) is reachable from a phone on the same Wi-Fi, "
        "127.0.0.1 is this computer only",
    )
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
        print(f"  Bind     : {args.host}:{args.port}")
        print(f"  Dashboard: http://127.0.0.1:{args.port}/")
        if lan and args.host != "127.0.0.1":
            print("-" * 72)
            print(f"  Type this into the Android app (Settings -> Authority backend):")
            print(f"      {lan}:{args.port}")
            print(f"  Phone on an emulator uses 10.0.2.2:{args.port} instead of {lan}.")
        elif args.host == "127.0.0.1":
            print("-" * 72)
            print("  Bound to loopback: only this computer can reach it. Re-run without --host to")
            print("  let a phone on the same Wi-Fi connect.")
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
