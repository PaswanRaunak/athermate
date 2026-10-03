#!/usr/bin/env python3
"""One-shot local charge cutoff. No automatic start or stop retries."""

import argparse
from decimal import Decimal, InvalidOperation
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import signal
import time
from urllib.parse import urlencode

spec = importlib.util.spec_from_file_location("ather_lab", Path(__file__).with_name("ather-lab.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)

STOPPED = {"paused", "pause", "stopped", "stop", "completed", "complete",
           "disconnected", "idle", "not charging"}
SNAPSHOT_REFRESH_SECONDS = 5


def section(root, path):
    if isinstance(root.get(path), dict):
        return root[path]
    nested = root
    for part in path.split("."):
        nested = nested.get(part, {}) if isinstance(nested, dict) else {}
    result = dict(nested) if isinstance(nested, dict) else {}
    prefix = path + "."
    result.update({key[len(prefix):]: value for key, value in root.items() if key.startswith(prefix)})
    return result


def fields(frame):
    state = frame.get("state", {})
    root = state.get("reported") or state.get("delta") or frame.get("state.reported") or frame.get("state.delta") or frame
    return section(root, "telemetry.bike"), section(root, "telemetry.charging")


def fresh_battery(bike, initial, now):
    try:
        soc = Decimal(str(bike["battery_soc"]))
        if not soc.is_finite() or not 0 <= soc <= 100:
            return None
        source = bike.get("last_synced_time")
        if source is not None:
            age = now - float(source) / 1000
            if not -30 <= age <= 120:
                return None
        elif initial:
            # An initial cloud snapshot without a source timestamp can be old.
            return None
        return soc
    except (KeyError, InvalidOperation, ValueError, TypeError):
        return None


def stopped(charge):
    status = str(charge.get("chargingStatus", "")).strip().lower()
    connected = str(charge.get("chargerConnected", "")).lower()
    return connected in {"off", "false", "0", "disconnected"} or status in STOPPED


class Watcher:
    def __init__(self, limit):
        self.limit = limit
        self.status = {"pid": os.getpid(), "limit_percent": str(limit), "phase": "connecting",
                       "stop_attempted": False, "battery_percent": None}
        self.sent_at = None
        self.deadline = None
        self.cancelled = False

    def save(self, phase=None, **extra):
        if phase:
            self.status["phase"] = phase
        self.status.update(extra)
        self.status["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        lab.save_private("charge-limit-state.json", self.status)

    def observe(self, frame, initial, data):
        bike, charge = fields(frame)
        now = time.time()
        fresh = fresh_battery(bike, initial, now)
        if "battery_soc" in bike:
            # Store only validated battery data; never log raw frames or identifiers.
            try:
                value = Decimal(str(bike["battery_soc"]))
                if value.is_finite() and 0 <= value <= 100:
                    self.status["battery_percent"] = str(value)
            except (InvalidOperation, ValueError):
                pass
            self.status["battery_reading_fresh"] = fresh is not None
            self.status["battery_source_time_ms"] = bike.get("last_synced_time")
        for key in ("chargerConnected", "chargingStatus", "chargingHeartBeat"):
            if key in charge:
                self.status[key] = charge[key]
        self.save()
        if self.sent_at is not None:
            # Require a subsequent physical charging update, never the desired action.
            source = bike.get("last_synced_time")
            try:
                source_new = (not initial) if source is None else float(source) > self.sent_at * 1000
            except (ValueError, TypeError):
                source_new = False
            if source_new and stopped(charge):
                self.save("stop_confirmed", confirmed_at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
                print("Charging stopped: confirmed by a subsequent charging update.", flush=True)
                return True
        elif fresh is not None and fresh >= self.limit:
            # Persist the attempt before dispatch so a network error never causes a retry.
            self.sent_at = now
            self.deadline = time.monotonic() + 60
            self.save("stop_sending", stop_attempted=True, trigger_percent=str(fresh), command_time_ms=int(now * 1000))
            try:
                lab.request("/api/v1/devices/shadows/scooters", data["token"],
                            lab.payload_for("stop", data["uuid"], int(now * 1000)), {"uuid": data["uuid"]})
            except lab.LabError:
                self.save("stop_unconfirmed", detail="Stop request failed or timed out; it will not be retried automatically.")
                print("Stop request unconfirmed. Check scooter; automatic retries are disabled.", flush=True)
                return True
            self.save("stop_accepted")
            print(f"Battery {fresh}%: stop accepted by API; waiting for charging confirmation.", flush=True)
        return False

    def run(self):
        import websocket
        directory = lab.private_directory()
        lock = os.open(directory / "charge-limit.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            os.close(lock)
            raise lab.LabError("A charge-limit watcher is already running.")
        def cancel(signum, frame):
            self.cancelled = True
        signal.signal(signal.SIGTERM, cancel)
        signal.signal(signal.SIGINT, cancel)
        self.save()
        delay = 2
        try:
            while not self.cancelled:
                if self.deadline is not None and time.monotonic() >= self.deadline:
                    self.save("stop_unconfirmed", detail="API accepted stop, but no physical confirmation arrived within 60 seconds.")
                    return
                socket = None
                refresh_due = False
                try:
                    data = lab.session()
                    uuid = lab.require_vehicle(data)
                    h = lab.headers(data["token"])
                    h.update({"X-Device-Info": "Google Pixel 8 Pro", "User-Agent": "Ather/13.2.0 android/14 (Google Pixel 8 Pro)"})
                    socket = websocket.create_connection(
                        "wss://cerberus.ather.io/api/v1/ws/devices/shadows/onchange?" + urlencode({"uuid": uuid}),
                        header=[f"{k}: {v}" for k, v in h.items()], timeout=15, redirect_limit=0)
                    if socket.getstatus() != 101:
                        raise lab.LabError("WebSocket handshake incomplete.")
                    socket.send(json.dumps({"paths": lab.PATHS}))
                    socket.settimeout(1)
                    self.save("stop_accepted" if self.sent_at else "monitoring")
                    print(f"Connected. Cutoff armed at {self.limit}%.", flush=True)
                    delay = 2
                    initial = True
                    heartbeat = time.monotonic()
                    last_ping = heartbeat
                    refresh_at = heartbeat + SNAPSHOT_REFRESH_SECONDS
                    while not self.cancelled:
                        if self.deadline is not None and time.monotonic() >= self.deadline:
                            self.save("stop_unconfirmed", detail="No physical stop confirmation received within 60 seconds.")
                            return
                        if time.monotonic() >= refresh_at:
                            # Cerberus may send an initial snapshot without subsequent
                            # events. Re-subscribe on a new socket to refresh the snapshot.
                            refresh_due = True
                            break
                        if time.monotonic() - last_ping >= 25:
                            socket.ping()
                            last_ping = time.monotonic()
                        if time.monotonic() - heartbeat >= 10:
                            self.save()
                            heartbeat = time.monotonic()
                        try:
                            raw = socket.recv()
                        except websocket.WebSocketTimeoutException:
                            continue
                        if not raw:
                            raise OSError("Connection closed")
                        try:
                            frame = json.loads(raw)
                        except ValueError:
                            continue
                        if not isinstance(frame, dict):
                            continue
                        if self.observe(frame, initial, data):
                            return
                        initial = False
                except lab.LabError:
                    self.save("error", detail="Session expired, missing, or WebSocket handshake failed.")
                    return
                except websocket.WebSocketBadStatusException as error:
                    if error.status_code in (401, 403):
                        self.save("error", detail="Ather session expired or access denied.")
                        return
                    self.save("reconnecting")
                except (websocket.WebSocketException, OSError, ValueError):
                    self.save("reconnecting")
                finally:
                    if socket is not None:
                        socket.close()
                if refresh_due:
                    continue
                for _ in range(delay):
                    if self.cancelled:
                        break
                    time.sleep(1)
                delay = min(delay * 2, 30)
            self.save("cancelled")
        except Exception:
            self.save("error", detail="Watcher failed; cutoff is no longer active.")
            raise
        finally:
            os.close(lock)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("percent", type=Decimal)
    args = parser.parse_args()
    if not args.percent.is_finite() or not 0 < args.percent <= 100:
        parser.error("Percentage must be between 0 and 100.")
    try:
        Watcher(args.percent).run()
    except Exception:
        print("Charge-limit watcher failed; no credentials were printed.", flush=True)
        raise SystemExit(1)
