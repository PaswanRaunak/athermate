#!/usr/bin/env python3
"""Desktop Ather API lab. Credentials and captures live outside the checkout."""

import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

STORE = Path.home() / ".local/share/atherpro-lab"
BASE = "https://cerberus.ather.io"
JWT = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
PRIVATE_KEYS = re.compile(
    r"token|authorization|secret|password|otp|contact|phone|email|uuid|"
    r"registration|reg_no|bike_id|scooterid|gps|latitude|longitude|address|"
    r"display_name|user_name|full_name|recents|"
    r"(?:^|[._])(?:vin|lat|lng|lon|city)(?:$|[._])",
    re.I,
)
PATHS = ["telemetry.bike", "telemetry.charging", "telemetry.tpms",
         "scooters.remote_charging", "scooters.properties", "scooters.bike",
         "app.ather_stack_features"]


class LabError(Exception):
    pass


def private_directory():
    checkout = Path(__file__).resolve().parent.parent
    if STORE.resolve().is_relative_to(checkout):
        raise LabError("Private storage resolves inside the repository; refusing.")
    # Refuse symlinks anywhere along the private storage path.
    for parent in (STORE, *STORE.parents):
        if parent.is_symlink():
            raise LabError("Private storage path contains a symlink; refusing.")
    STORE.mkdir(mode=0o700, parents=True, exist_ok=True)
    STORE.chmod(0o700)
    return STORE


def save_private(name, value):
    directory = private_directory()
    fd, temporary = tempfile.mkstemp(prefix=".pending-", dir=directory)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(value, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, directory / name)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_private(name):
    path = private_directory() / name
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        raise LabError("No local session. Run login first.") from None
    with os.fdopen(fd) as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            raise LabError("Private file must be a regular file owned by you.")
        os.fchmod(stream.fileno(), 0o600)
        try:
            return json.load(stream)
        except (ValueError, TypeError):
            raise LabError("Invalid local session file; log in again.") from None


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward Authorization to a redirected host.
        return None


def headers(token=None):
    result = {"Accept": "application/json", "Accept-Charset": "UTF-8",
              "Content-Type": "application/json", "Source": "ATHER_APP/13.2.0",
              "X-Platform": "Android", "X-Platform-Version": "14",
              "User-Agent": "ktor-client"}
    if token:
        result["Authorization"] = "Bearer " + token
    return result


def request(path, token=None, payload=None, query=None):
    url = BASE + path + ("?" + urlencode(query) if query else "")
    body = json.dumps(payload).encode() if payload is not None else None
    request_headers = headers(token)
    request_headers["X-Request-Source"] = "ATHER_APP"
    req = Request(url, data=body, headers=request_headers)
    try:
        with build_opener(NoRedirect()).open(req, timeout=30) as response:
            raw = response.read()
    except HTTPError as error:
        # Response bodies may echo credentials; never print them.
        if error.code in (401, 403):
            raise LabError(f"HTTP {error.code}: sign-in expired or access denied.") from None
        raise LabError(f"Ather API returned HTTP {error.code}.") from None
    except (URLError, TimeoutError, OSError):
        raise LabError("Ather API connection failed or timed out.") from None
    try:
        return json.loads(raw) if raw.strip() else {}
    except ValueError:
        raise LabError("API returned an unexpected non-JSON response.") from None


def extract_token(root):
    for candidate in (root, root.get("token"), root.get("data")):
        if isinstance(candidate, dict):
            for key in ("token", "access_token"):
                if isinstance(candidate.get(key), str) and candidate[key]:
                    return candidate[key]
    raise LabError("Login response contained no token.")


def session():
    data = read_private("session.json")
    if not isinstance(data, dict) or not isinstance(data.get("token"), str):
        raise LabError("Invalid session; log in again.")
    token = data["token"]
    try:
        segment = token.split(".")[1]
        expiry = json.loads(base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))).get("exp")
    except (ValueError, IndexError, TypeError):
        expiry = None
    if isinstance(expiry, (int, float)) and expiry <= time.time():
        raise LabError("Local token expired; log in again.")
    return data


def payload_for(action, uuid, timestamp=None):
    return {"state": {"desired": {"remote_charging": {
        "state": 1, "action": action, "error": "0",
        "timestamp": int(time.time() * 1000) if timestamp is None else timestamp,
    }}}, "request_id": "ma_" + uuid}


def redact(value, secrets=()):
    if isinstance(value, dict):
        return {key: "[redacted]" if PRIVATE_KEYS.search(key) else redact(item, secrets)
                for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item, secrets) for item in value]
    if isinstance(value, str):
        for secret in secrets:
            if secret:
                value = value.replace(secret, "[redacted]")
        return JWT.sub("[redacted]", value)
    return value


def display(value, data):
    safe = redact(value, (data.get("token"), data.get("uuid")))
    print(json.dumps(safe, indent=2))
    save_private("last-response.json", safe)


def discover(data):
    try:
        root = request("/api/v1/me", data["token"])
    except LabError as error:
        if "401" in str(error) or "403" in str(error):
            raise
        root = {}
    nested = root.get("data", {})
    nested = nested if isinstance(nested, dict) else {}
    vehicles = root.get("vehicles") or nested.get("vehicles") or root.get("scooters") or []
    if not vehicles:
        root = request("/api/v2/auth/user/scooters/firebase-dbs", data["token"])
        nested = root.get("data", {})
        nested = nested if isinstance(nested, dict) else {}
        vehicles = root.get("shardDetails") or nested.get("shardDetails") or []
    found = []
    for vehicle in vehicles:
        if not isinstance(vehicle, dict):
            continue
        uuid = next((vehicle.get(k) for k in ("uuid", "vehicle_uuid", "scooter_uuid", "id")
                     if vehicle.get(k)), None)
        if uuid:
            found.append({"uuid": uuid, "name": vehicle.get("display_name") or
                          vehicle.get("scooter") or vehicle.get("name") or "Scooter"})
    data["vehicles"] = found
    if len(found) == 1:
        data["uuid"] = found[0]["uuid"]
    save_private("session.json", data)
    for index, vehicle in enumerate(found, 1):
        print(f"{index}. {redact(vehicle['name'], (data['token'], vehicle['uuid']))}"
              + (" (selected)" if vehicle["uuid"] == data.get("uuid") else ""))
    if not found:
        print("No scooters found for this account.")
    elif len(found) > 1:
        print("Select one with: python3 scripts/ather-lab.py select NUMBER")
    return found


def require_vehicle(data):
    if not data.get("uuid"):
        raise LabError("No selected scooter. Run vehicles, then select NUMBER.")
    return data["uuid"]


def request_otp():
    if not sys.stdin.isatty():
        raise LabError("Login needs an interactive terminal so inputs remain hidden.")
    phone = getpass.getpass("Registered mobile number (hidden): ").strip()
    if not phone.isdigit() or not 8 <= len(phone) <= 15:
        raise LabError("Enter the mobile number as digits, without the country prefix for IN.")
    payload = {"email": "", "contact_no": phone, "country_code": "IN"}
    request("/auth/v2/generate-login-otp", payload=payload)
    save_private("pending-login.json", payload)
    print("OTP requested. Use verify-otp when the SMS arrives.")


def verify_otp():
    if not sys.stdin.isatty():
        raise LabError("Login needs an interactive terminal so inputs remain hidden.")
    payload = read_private("pending-login.json")
    otp = getpass.getpass("SMS OTP (hidden): ").strip()
    if not otp.isdigit() or not 4 <= len(otp) <= 8:
        raise LabError("Enter the 4–8 digit SMS OTP.")
    payload.update(userOtp=otp, is_mobile_login="true")
    root = request("/auth/v2/verify-login-otp", payload=payload)
    data = {"token": extract_token(root)}
    save_private("session.json", data)
    (private_directory() / "pending-login.json").unlink(missing_ok=True)
    print("Logged in. Token saved privately outside the repository; never displayed.")
    discover(data)


def monitor(data, seconds, capture):
    try:
        import websocket
    except ImportError:
        raise LabError("Monitoring requires websocket-client; see docs/LOCAL-API-LAB.md.") from None
    uuid = require_vehicle(data)
    ws_headers = headers(data["token"])
    ws_headers.update({"X-Device-Info": "Google Pixel 8 Pro",
                       "User-Agent": "Ather/13.2.0 android/14 (Google Pixel 8 Pro)"})
    socket = None
    fd = None
    if capture:
        fd = os.open(private_directory() / "monitor.jsonl",
                     os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW, 0o600)
        os.fchmod(fd, 0o600)
    try:
        socket = websocket.create_connection(
            "wss://cerberus.ather.io/api/v1/ws/devices/shadows/onchange?" + urlencode({"uuid": uuid}),
            header=[f"{k}: {v}" for k, v in ws_headers.items()], timeout=30,
            redirect_limit=0,
        )
        if socket.getstatus() != 101:
            raise LabError("Telemetry server did not complete the WebSocket handshake.")
        socket.send(json.dumps({"paths": PATHS}))
        print("Connected. Showing reported updates; Ctrl+C stops monitoring.")
        deadline = time.monotonic() + seconds if seconds else None
        last_ping = time.monotonic()
        while deadline is None or time.monotonic() < deadline:
            socket.settimeout(min(5, max(0.1, deadline - time.monotonic())) if deadline else 5)
            if time.monotonic() - last_ping >= 30:
                socket.ping()
                last_ping = time.monotonic()
            try:
                frame = socket.recv()
            except websocket.WebSocketTimeoutException:
                continue
            if not frame:
                raise LabError("Telemetry connection closed.")
            try:
                value = json.loads(frame)
            except ValueError:
                print("Skipped a non-JSON telemetry frame.")
                continue
            safe = redact(value, (data["token"], uuid))
            record = {"received_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                      "frame": safe}
            print(json.dumps(record), flush=True)
            if fd is not None:
                os.write(fd, (json.dumps(record) + "\n").encode())
    except (websocket.WebSocketException, OSError, ValueError):
        # Library exceptions may contain headers or response bodies.
        raise LabError("Telemetry connection failed. Check login and connectivity.") from None
    finally:
        if socket is not None:
            socket.close()
        if fd is not None:
            os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("login", "request-otp", "verify-otp", "vehicles", "profile", "rides", "health", "logout"):
        commands.add_parser(name)
    select = commands.add_parser("select")
    select.add_argument("number", type=int)
    watch = commands.add_parser("monitor")
    watch.add_argument("--seconds", type=int, default=60, help="0 runs until Ctrl+C")
    watch.add_argument("--capture", action="store_true", help="Save redacted frames outside Git")
    for action in ("start", "stop"):
        command = commands.add_parser(action)
        command.add_argument("--execute", action="store_true", help="Send the real scooter command")
    args = parser.parse_args()
    if args.command == "logout":
        for name in ("session.json", "pending-login.json", "last-response.json", "monitor.jsonl"):
            (private_directory() / name).unlink(missing_ok=True)
        print("Local credentials and captures deleted. This does not revoke the token on Ather's server.")
        return
    if args.command in ("login", "request-otp"):
        request_otp()
        if args.command == "login":
            verify_otp()
        return
    if args.command == "verify-otp":
        verify_otp()
        return
    data = session()
    if args.command == "vehicles":
        discover(data)
    elif args.command == "select":
        vehicles = data.get("vehicles", [])
        if not 1 <= args.number <= len(vehicles):
            raise LabError("Invalid scooter number; run vehicles first.")
        data["uuid"] = vehicles[args.number - 1]["uuid"]
        save_private("session.json", data)
        print(f"Selected scooter {args.number}.")
    elif args.command == "monitor":
        if args.seconds < 0:
            raise LabError("--seconds must be zero or positive.")
        monitor(data, args.seconds, args.capture)
    elif args.command in ("start", "stop"):
        uuid = require_vehicle(data)
        payload = payload_for(args.command, uuid)
        if not args.execute:
            print("Preview only. Add --execute to send this command to your scooter.")
            print(json.dumps(redact(payload, (uuid,)), indent=2))
            return
        request("/api/v1/devices/shadows/scooters", data["token"], payload, {"uuid": uuid})
        print(f"{args.command.capitalize()} request accepted by API. Scooter execution is unconfirmed; check monitor.")
    elif args.command in ("profile", "health"):
        path = "/api/v1/devices/shadows/scooters/properties" if args.command == "profile" else "/api/v1/vehicle-health/report"
        query = {"uuid": require_vehicle(data)}
        if args.command == "profile":
            query["state"] = "reported"
        display(request(path, data["token"], query=query), data)
    elif args.command == "rides":
        profile = request("/api/v1/devices/shadows/scooters/properties", data["token"],
                          query={"uuid": require_vehicle(data), "state": "reported"})
        bike_id = profile.get("data", {}).get("bike_id")
        if not bike_id:
            raise LabError("Scooter properties contained no bike_id for ride history.")
        display(request("/api/v1/rides", data["token"],
                        query={"scooterid": bike_id, "limit": 100, "page": 1}), data)


if __name__ == "__main__":
    try:
        main()
    except LabError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (KeyboardInterrupt, EOFError):
        print("Stopped.", file=sys.stderr)
        sys.exit(130)
    except Exception:
        # Do not leak credentials through unexpected tracebacks.
        print("Local lab failed. No credentials were printed.", file=sys.stderr)
        sys.exit(1)
