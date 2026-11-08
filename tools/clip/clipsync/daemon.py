"""The clipsync daemon: D-Bus face for the GNOME extension, LAN + cloud behind it.

`ClipEngine` holds every rule and transport and knows nothing about D-Bus, so the
CLI (`send-test`) and the tests can drive it directly. `run_daemon` wraps it in
the session-bus service `com.ogenrwotdaniel.WakeProofClip`.

Besides Offer/Incoming (the clipboard, docs/CLIPBOARD.md) the service has a small
control surface for the GNOME Quick Settings tile: GetStatus, SetEnabled,
StartPairing/CancelPairing, RenderQr and the StatusChanged signal. Those are local to
this laptop and are not part of the cross-device protocol.
"""
from __future__ import annotations

import json
import logging
import os
import re
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path

from . import pair as pairing
from . import store as st
from .cloud import CloudError, firestore_for
from .codec import (MIME_JPEG, MIME_PNG, MIME_TEXT, Clip, ClipError, clip_hash, cloud_body,
                    open_sealed, seal, to_png)
from .lan import LAN_PORT, ClipServer, Mdns, local_ipv4_addrs, send_to_peers
from .rules import (PRESENCE_FRESH_MS, STALE_MS, SeenIds, cloud_needed, fresh_presence_peers, one_per_device,
                    receive_refusal, send_refusal)

log = logging.getLogger("clipsync")

BUS_NAME = "com.ogenrwotdaniel.WakeProofClip"
OBJECT_PATH = "/com/ogenrwotdaniel/WakeProofClip"
INTERFACE = "com.ogenrwotdaniel.WakeProofClip"
INTROSPECTION = f"""
<node>
  <interface name="{INTERFACE}">
    <method name="Offer">
      <arg type="s" name="mime" direction="in"/>
      <arg type="ay" name="data" direction="in"/>
    </method>
    <signal name="Incoming">
      <arg type="s" name="mime"/>
      <arg type="ay" name="data"/>
    </signal>
    <method name="GetStatus">
      <arg type="s" name="json" direction="out"/>
    </method>
    <method name="SetEnabled">
      <arg type="b" name="enabled" direction="in"/>
    </method>
    <method name="StartPairing">
      <arg type="s" name="qrText" direction="out"/>
    </method>
    <method name="CancelPairing"/>
    <method name="RenderQr">
      <arg type="s" name="text" direction="in"/>
      <arg type="s" name="pngPath" direction="out"/>
    </method>
    <signal name="StatusChanged">
      <arg type="s" name="json"/>
    </signal>
  </interface>
</node>
"""

CREDS_CACHE_S = 30
POLL_ACTIVE_S = 4
POLL_LOCKED_S = 30
POLL_ERROR_S = 30
POLL_LOOKBACK_MS = 30_000  # tolerate a peer clock a little behind ours
PRESENCE_EVERY_S = 30 * 60
PRESENCE_CACHE_S = 5 * 60
ADDR_CHECK_S = 60
HOUSEKEEP_EVERY_S = 3600
STATUS_PRESENCE_MAX_AGE_S = 60  # GetStatus refreshes presence at most this often
LAN_RECENT_MS = 30 * 60_000  # a LAN contact this recent marks a peer as "on LAN"
DISABLED_STATUS = 503  # LAN answer while the user has switched the tile off


QR_PNG_PREFIX = "wakeproof-clip-qr-"


def qr_png_path() -> Path:
    """A NEW file name per render. GNOME Shell's texture cache keys a Gio.FileIcon by
    its path, so reusing one name made every pairing window show the FIRST QR ever
    drawn — an old secret, and the phone's request then "does not decrypt"."""
    base = Path(os.environ.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}")
    for old in base.glob(f"{QR_PNG_PREFIX}*.png"):
        try:
            old.unlink()
        except OSError:
            pass
    legacy = base / "wakeproof-clip-qr.png"
    if legacy.exists():
        try:
            legacy.unlink()
        except OSError:
            pass
    return base / f"{QR_PNG_PREFIX}{uuid.uuid4().hex}.png"


def now_ms() -> int:
    return int(time.time() * 1000)


def pictures_dir() -> Path:
    try:
        from gi.repository import GLib

        d = GLib.get_user_special_dir(GLib.UserDirectory.DIRECTORY_PICTURES)
        if d:
            return Path(d) / "WakeProof Clip"
    except Exception:
        pass
    return Path.home() / "Pictures" / "WakeProof Clip"


def image_filename(created_at_ms: int, sender: str, mime: str) -> str:
    stamp = datetime.fromtimestamp(created_at_ms / 1000).strftime("%Y-%m-%d %H-%M-%S")
    safe = re.sub(r"[/\\\0]", "_", sender).strip() or "unknown device"
    ext = "jpg" if mime == MIME_JPEG else "png"
    return f"{stamp} from {safe}.{ext}"


def screensaver_active() -> bool:
    try:
        from gi.repository import Gio, GLib

        bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        res = bus.call_sync("org.gnome.ScreenSaver", "/org/gnome/ScreenSaver", "org.gnome.ScreenSaver",
                            "GetActive", None, GLib.VariantType("(b)"), Gio.DBusCallFlags.NONE, 1000, None)
        return bool(res.unpack()[0])
    except Exception:
        return False


class PairingSession:
    """State of one StartPairing window, as GetStatus reports it."""

    def __init__(self, server: pairing.PairingServer, qr_text: str, expires_at_ms: int):
        self.server = server
        self.qr_text = qr_text
        self.expires_at_ms = expires_at_ms
        self.active = True
        self.result: str | None = None  # None | "ok:<device name>" | "error:<message>"
        self.warning: str | None = None  # e.g. cloud sign-in failed although pairing worked
        self.timer: threading.Timer | None = None


class ClipEngine:
    def __init__(self, store, cfg: dict, emit=None, pictures: Path | None = None,
                 save_cfg=None, on_status=None):
        self.store = store
        self.cfg = cfg
        self.save_cfg = save_cfg  # save_cfg(cfg); None = do not persist (tests, CLI)
        self.on_status = on_status  # on_status(json) -> emits StatusChanged
        self.install_id = cfg["installId"]
        self.name = cfg["name"]
        self.emit = emit  # emit(mime, bytes) -> sets the desktop clipboard
        self.pictures = pictures
        self.lock = threading.RLock()
        self.seen = SeenIds()
        self.last_hash: str | None = None
        self.learned: dict[str, set[str]] = {}  # installId -> client IPs seen over LAN
        self.mdns: Mdns | None = None
        self._creds_lock = threading.Lock()
        self._creds_at = 0.0
        self._key = None
        self._account = None
        self._fs = None
        self._paired_state = None
        self._presence: list[dict] = []
        self._presence_at = 0.0
        self._presence_refreshing = False
        self._housekept_at = 0.0
        self.lan_seen: dict[str, int] = {}  # installId -> last LAN contact (ms)
        self.lan_names: dict[str, str] = {}  # installId -> name seen in a LAN clip
        self.wake = threading.Event()  # cuts the background loop's wait short
        self.pairing: PairingSession | None = None
        self._pair_lock = threading.Lock()
        self._last_status: str | None = None

    # --- on/off ---

    @property
    def enabled(self) -> bool:
        return self.cfg.get("enabled", True) is not False

    def set_enabled(self, enabled: bool) -> None:
        with self.lock:
            changed = self.enabled != bool(enabled)
            self.cfg["enabled"] = bool(enabled)
            if changed and self.save_cfg is not None:
                self.save_cfg(self.cfg)
        if changed:
            log.info("clipboard sharing switched %s", "on" if enabled else "off")
            self.wake.set()
        self.check_status()

    def adopt_peers(self, paired_cfg: dict) -> None:
        """save_cfg for an in-daemon pairing: takes only the peers it added."""
        with self.lock:
            self.cfg["peers"] = paired_cfg.get("peers", {})
            if self.save_cfg is not None:
                self.save_cfg(self.cfg)

    # --- credentials ---

    def creds(self, force: bool = False):
        """(K or None, Firestore or None), re-read from the keyring every 30 s."""
        changed = False
        with self._creds_lock:
            if force or time.monotonic() - self._creds_at > CREDS_CACHE_S:
                try:
                    key, account = st.load_key(self.store), st.load_account(self.store)
                except Exception as e:
                    log.warning("keyring unreadable: %s", e)
                    key, account = self._key, self._account
                if account != self._account:
                    self._fs = firestore_for(account) if account else None
                    self._presence, self._presence_at = [], 0.0
                self._key, self._account = key, account
                self._creds_at = time.monotonic()
                state = (key is not None, account is not None)
                if state != self._paired_state:
                    self._paired_state = state
                    if key is None:
                        log.info("not paired: run `python3 -m clipsync pair` (idle until then)")
                    else:
                        log.info("paired; cloud fallback %s",
                                 f"as {account.get('email') or account['uid']}" if account else "off (LAN only)")
                    changed = True
            key, fs = self._key, (self._fs if self._key is not None else None)
        if changed:
            self.wake.set()
            self.check_status()
        return key, fs

    # --- peers ---

    def presence(self, fs, force: bool = False) -> list[dict]:
        if fs is None:
            return []
        if force or time.monotonic() - self._presence_at > PRESENCE_CACHE_S:
            try:
                self._presence = fs.presence(now_ms() - PRESENCE_FRESH_MS)
                self._presence_at = time.monotonic()
            except CloudError as e:
                log.warning("presence read failed: %s", e)
        return self._presence

    def lan_targets(self, fs) -> dict[str, list[tuple[str, int]]]:
        targets: dict[str, set[tuple[str, int]]] = {}

        def add(pid, ip, port):
            if pid and pid != self.install_id and ip:
                targets.setdefault(pid, set()).add((ip, int(port or LAN_PORT)))

        with self.lock:
            paired = list(self.cfg.get("peers", {}).items())
        for pid, p in paired:
            for ip in p.get("addrs", []):
                add(pid, ip, p.get("port"))
        for p in self.presence(fs):
            for ip in p.get("lanAddrs") or []:
                add(p.get("installId"), ip, p.get("port"))
        if self.mdns is not None:
            for pid, p in self.mdns.snapshot().items():
                for ip, port in p["addrs"]:
                    add(pid, ip, port)
        with self.lock:
            for pid, ips in self.learned.items():
                for ip in ips:
                    add(pid, ip, LAN_PORT)
        return {pid: sorted(a) for pid, a in targets.items()}

    # --- sending ---

    def offer(self, mime: str, body: bytes) -> str:
        """Handles one local clipboard change. Returns a short outcome for logs/CLI."""
        if not self.enabled:
            return "skipped: switched off"
        key, fs = self.creds()
        if key is None:
            return "not paired"
        with self.lock:
            reason = send_refusal(mime, body, self.last_hash)
            if reason:
                log.debug("not sending: %s", reason)
                return f"skipped: {reason}"
            self.last_hash = clip_hash(mime, body)
            clip = Clip(str(uuid.uuid4()), self.install_id, self.name, now_ms(), mime, body)
            self.seen.add(clip.id)
        sealed = seal(key, clip)
        targets = self.lan_targets(fs)
        acked = send_to_peers(targets, clip.id, sealed)
        if acked:
            with self.lock:
                for pid in acked:
                    self.lan_seen[pid] = now_ms()
            self.check_status()
        outcome = f"{mime} {len(body)} B: LAN {len(acked)}/{len(targets)}"
        if fs is not None:
            # Every fresh presence doc, old installs included: a reinstall that
            # shares the Clip key but not this laptop's pairing looks just like
            # one, and skipping the cloud would leave it without the clip.
            peers = fresh_presence_peers(self.presence(fs), self.install_id, now_ms())
            if cloud_needed(peers, acked):
                try:
                    cmime, cbody = cloud_body(mime, body)
                    csealed = sealed if cmime == mime else seal(key, Clip(
                        clip.id, clip.origin, clip.name, clip.created_at, cmime, cbody))
                    fs.upload_clip(clip.id, self.install_id, clip.created_at, csealed)
                    outcome += f", cloud {len(csealed)} B" + (" (as JPEG)" if cmime != mime else "")
                    self.housekeep(fs, force=True)
                except CloudError as e:
                    log.warning("cloud upload failed: %s", e)
                    outcome += ", cloud FAILED"
            else:
                outcome += ", cloud skipped (every peer acked)"
                self.housekeep(fs)
        log.info("sent %s", outcome)
        return outcome

    def housekeep(self, fs, force: bool = False):
        if not force and time.monotonic() - self._housekept_at < HOUSEKEEP_EVERY_S:
            return
        self._housekept_at = time.monotonic()
        try:
            n = fs.housekeep(self.install_id, now_ms())
            if n:
                log.info("housekeeping deleted %d old clip(s)", n)
        except CloudError as e:
            log.warning("housekeeping failed: %s", e)

    # --- receiving ---

    def receive(self, clip_id: str, sealed: bytes, source: str, client_ip: str | None = None) -> int:
        """Applies one sealed clip. Returns the LAN status: 204, 400 if it does not open,
        503 while switched off (the sender then falls back to the cloud)."""
        if not self.enabled:
            return DISABLED_STATUS
        key, _ = self.creds()
        if key is None:
            return 400
        try:
            clip = open_sealed(key, clip_id, sealed)
        except ClipError as e:
            log.warning("rejected %s clip %s: %s", source, clip_id, e)
            return 400
        with self.lock:
            if client_ip and clip.origin != self.install_id:
                self.learned.setdefault(clip.origin, set()).add(client_ip)
                self.lan_seen[clip.origin] = now_ms()
                if clip.name:
                    self.lan_names[clip.origin] = clip.name
            reason = receive_refusal(clip, self.install_id, self.seen, now_ms())
            if reason:
                log.debug("ignoring %s clip %s: %s", source, clip_id, reason)
            else:
                self.seen.add(clip.id)
                self._apply(clip, source)
        if client_ip:
            self.check_status()
        return 204

    def _apply(self, clip: Clip, source: str):
        if clip.mime == MIME_TEXT:
            out_mime, out_body = MIME_TEXT, clip.body
        else:
            self._save_image(clip)
            out_mime = MIME_PNG
            try:
                out_body = clip.body if clip.mime == MIME_PNG else to_png(clip.body)
            except Exception as e:
                log.warning("could not convert %s to PNG: %s", clip.mime, e)
                out_mime, out_body = clip.mime, clip.body
        # What lands on the clipboard is what the extension will offer back: remember it.
        self.last_hash = clip_hash(out_mime, out_body)
        log.info("received %s %d B from %s over %s", clip.mime, len(clip.body), clip.name, source)
        if self.emit is not None:
            self.emit(out_mime, out_body)

    def _save_image(self, clip: Clip):
        if self.pictures is None:
            return
        try:
            self.pictures.mkdir(parents=True, exist_ok=True)
            path = self.pictures / image_filename(clip.created_at, clip.name, clip.mime)
            n = 2
            while path.exists():
                path = path.with_name(f"{path.stem.rsplit(' (', 1)[0]} ({n}){path.suffix}")
                n += 1
            path.write_bytes(clip.body)
            log.info("saved %s", path)
        except OSError as e:
            log.warning("could not save image: %s", e)

    # --- cloud poll + presence upkeep ---

    def run_background(self, stop: threading.Event):
        since = now_ms() - STALE_MS
        presence_at = 0.0
        addrs_at = 0.0
        addrs = local_ipv4_addrs()
        last_fs = None
        was_enabled = self.enabled
        while not stop.is_set():
            key, fs = self.creds()
            wait = POLL_ACTIVE_S
            if not self.enabled:
                was_enabled = False
                self.check_status()
                self._wait(stop, CREDS_CACHE_S)
                continue
            if not was_enabled:
                # Clips that came while switched off are not applied after the fact.
                was_enabled, since = True, now_ms()
            if time.monotonic() - addrs_at > ADDR_CHECK_S:
                addrs_at = time.monotonic()
                fresh = local_ipv4_addrs()
                if fresh != addrs:
                    log.info("network changed: %s", fresh)
                    addrs, presence_at = fresh, 0.0
                    if self.mdns is not None:
                        self.mdns.update_addrs(addrs)
            if fs is not None:
                if fs is not last_fs:
                    last_fs, presence_at = fs, 0.0
                if time.monotonic() - presence_at > PRESENCE_EVERY_S:
                    try:
                        fs.put_presence(self.install_id, self.name, addrs, now_ms())
                        presence_at = time.monotonic()
                        log.info("presence written (%s)", ", ".join(addrs) or "no addresses")
                    except CloudError as e:
                        log.warning("presence write failed: %s", e)
                try:
                    since = self.poll_once(fs, since)
                except CloudError as e:
                    log.warning("cloud poll failed: %s", e)
                    wait = POLL_ERROR_S
                if wait == POLL_ACTIVE_S and screensaver_active():
                    wait = POLL_LOCKED_S
            else:
                wait = CREDS_CACHE_S
            self.check_status()
            self._wait(stop, wait)

    def _wait(self, stop: threading.Event, seconds: float):
        if stop.is_set():
            return
        self.wake.wait(seconds)
        self.wake.clear()

    def poll_once(self, fs, since: int) -> int:
        now = now_ms()
        for h in fs.headers_since(since - POLL_LOOKBACK_MS):
            created = int(h.get("createdAt", 0))
            # Clamped to now: a peer clock running ahead must not push the cursor
            # past clips that are still to come.
            since = max(since, min(created, now))
            clip_id = h.get("id")
            if not clip_id or h.get("origin") == self.install_id:
                continue
            with self.lock:
                if clip_id in self.seen:
                    continue
            if now - created > STALE_MS:
                continue
            self.receive(clip_id, fs.fetch_sealed(h), "cloud")
        return since


    # --- status for the Quick Settings tile ---

    def peers(self) -> list[dict]:
        """Every device this laptop knows of: paired, presence docs, mDNS and LAN contacts.

        Uses only cached data; never touches the network.
        """
        now = now_ms()
        with self.lock:
            paired = {pid: dict(p) for pid, p in self.cfg.get("peers", {}).items()}
            lan_seen = dict(self.lan_seen)
            lan_names = dict(self.lan_names)
            presence = list(self._presence) if self._fs is not None else []
        mdns = self.mdns.snapshot() if self.mdns is not None else {}
        out: dict[str, dict] = {}

        def entry(pid):
            return out.setdefault(pid, {"installId": pid, "name": "", "platform": "",
                                        "lastSeenMs": 0, "lan": False})

        for pid, p in paired.items():
            entry(pid)["name"] = p.get("name") or ""
        for pid, name in lan_names.items():
            e = entry(pid)
            e["name"] = e["name"] or name
        for p in presence:
            pid = p.get("installId")
            if not pid or pid == self.install_id:
                continue
            e = entry(pid)
            e["name"] = p.get("name") or e["name"]
            e["platform"] = p.get("platform") or e["platform"]
            e["lastSeenMs"] = max(e["lastSeenMs"], int(p.get("updatedAt") or 0))
        for pid, p in mdns.items():
            e = entry(pid)
            e["name"] = e["name"] or p.get("name") or ""
            e["lan"] = True
        for pid, ms in lan_seen.items():
            e = entry(pid)
            e["lastSeenMs"] = max(e["lastSeenMs"], ms)
            if now - ms <= LAN_RECENT_MS:
                e["lan"] = True
        out.pop(self.install_id, None)
        rows = one_per_device(list(out.values()), set(paired))
        for e in rows:
            e["name"] = e["name"] or "unknown device"
        return rows

    def status(self) -> dict:
        with self.lock:
            account = self._account
            paired = self._key is not None
        with self._pair_lock:
            ps = self.pairing
            pairing_state = {
                "active": bool(ps and ps.active),
                "qrText": ps.qr_text if ps and ps.active else "",
                "expiresAtMs": ps.expires_at_ms if ps and ps.active else 0,
                "result": ps.result if ps else None,
                "warning": ps.warning if ps else None,
            }
        return {
            "enabled": self.enabled,
            "paired": paired,
            "signedIn": account is not None,
            "email": (account or {}).get("email", "") if paired else "",
            "name": self.name,
            "imagesDir": str(self.pictures) if self.pictures else "",
            "peers": self.peers(),
            "pairing": pairing_state,
        }

    def status_json(self) -> str:
        return json.dumps(self.status(), separators=(",", ":"), ensure_ascii=False)

    def check_status(self) -> None:
        """Emits StatusChanged when anything GetStatus reports has changed."""
        try:
            js = self.status_json()
        except Exception:
            log.exception("status failed")
            return
        with self._pair_lock:
            if js == self._last_status:
                return
            self._last_status = js
        if self.on_status is not None:
            self.on_status(js)

    def refresh_presence_soon(self) -> None:
        """Re-reads presence docs in the background if the cache is over 60 s old."""
        fs = self._fs if self._key is not None else None
        if fs is None or time.monotonic() - self._presence_at <= STATUS_PRESENCE_MAX_AGE_S:
            return
        with self.lock:
            if self._presence_refreshing:
                return
            self._presence_refreshing = True

        def run():
            try:
                self.presence(fs, force=True)
            finally:
                with self.lock:
                    self._presence_refreshing = False
            self.check_status()

        threading.Thread(target=run, name="presence", daemon=True).start()

    # --- pairing from the tile ---

    def start_pairing(self, port: int = pairing.PAIR_PORT, host: str = "0.0.0.0", signin=None,
                      addrs: list[str] | None = None, window_s: float = pairing.PAIR_WINDOW_S) -> str:
        """Opens (or returns the already open) pairing window; returns the QR text."""
        import copy

        with self._pair_lock:
            if self.pairing is not None and self.pairing.active:
                return self.pairing.qr_text
            addrs = local_ipv4_addrs() if addrs is None else addrs
            if not addrs:
                raise pairing.PairError("no IPv4 address: connect to a network first")
            holder: dict = {}
            try:
                server = pairing.PairingServer(
                    self.store, copy.deepcopy(self.cfg), port=port, host=host, signin=signin,
                    save_cfg=self.adopt_peers,
                    on_success=lambda summary: self._pairing_succeeded(holder["s"], summary),
                    on_reject=lambda ip, msg: self._pairing_rejected(holder["s"], ip, msg))
            except OSError as e:
                raise pairing.PairError(
                    f"port {port} is busy ({e.strerror}); is `clipsync pair` running in a terminal?") from e
            session = PairingSession(server, server.qr_text(addrs, self.name), now_ms() + int(window_s * 1000))
            holder["s"] = session
            session.timer = threading.Timer(window_s, self._pairing_expired, args=(session,))
            session.timer.daemon = True
            self.pairing = session
            server.start()
            session.timer.start()
        log.info("pairing window open on %s port %d", ", ".join(addrs), server.port)
        self.check_status()
        return session.qr_text

    def cancel_pairing(self) -> None:
        with self._pair_lock:
            session = self.pairing
            if session is None or not session.active:
                return
            session.active = False
            session.result = None
        self._close_session(session)
        log.info("pairing cancelled")
        self.check_status()

    def _close_session(self, session: PairingSession, flush_s: float = 0.0):
        if session.timer is not None:
            session.timer.cancel()

        def stop():
            try:
                session.server.stop(flush_s)
            except Exception as e:
                log.debug("stopping the pairing server: %s", e)

        # Never stop the server on its own request thread (shutdown() would deadlock).
        threading.Thread(target=stop, name="pair-stop", daemon=True).start()

    def _pairing_succeeded(self, session: PairingSession, summary: dict):
        # The new key and account, without a restart; loaded BEFORE the result is
        # published, so whoever sees "ok:" also sees paired = true.
        self.creds(force=True)
        with self._pair_lock:
            if self.pairing is not session or not session.active:
                return
            session.active = False
            session.result = f"ok:{summary.get('peer') or 'a device'}"
            if summary.get("signin_error"):
                session.warning = f"cloud sign-in failed ({summary['signin_error']}); LAN only"
        self._close_session(session, flush_s=0.3)
        log.info("paired with %s", summary.get("peer"))
        self.check_status()

    def _pairing_rejected(self, session: PairingSession, ip: str, msg: str):
        with self._pair_lock:
            if self.pairing is not session or not session.active:
                return
            session.result = f"error:rejected a pairing attempt from {ip}: {msg}"
        self.check_status()

    def _pairing_expired(self, session: PairingSession):
        with self._pair_lock:
            if self.pairing is not session or not session.active:
                return
            session.active = False
            session.result = "error:the pairing window closed without a device"
        self._close_session(session)
        log.info("pairing window closed without a device")
        self.check_status()


def render_qr(text: str, path: Path | None = None) -> str:
    path = path or qr_png_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    return pairing.render_qr_png(text, path)


def run_daemon() -> int:
    import signal

    from gi.repository import Gio, GLib

    cfg = st.load_config()
    try:
        store = st.KeyringStore()
    except Exception as e:
        log.error("libsecret unavailable: %s", e)
        return 1

    loop = GLib.MainLoop()
    state = {"conn": None}

    def emit(mime: str, data: bytes):
        def _do():
            conn = state["conn"]
            if conn is not None:
                params = GLib.Variant.new_tuple(
                    GLib.Variant.new_string(mime),
                    GLib.Variant.new_from_bytes(GLib.VariantType.new("ay"), GLib.Bytes.new(data), True),
                )
                conn.emit_signal(None, OBJECT_PATH, INTERFACE, "Incoming", params)
            return False

        GLib.idle_add(_do)

    def emit_status(js: str):
        def _do():
            conn = state["conn"]
            if conn is not None:
                conn.emit_signal(None, OBJECT_PATH, INTERFACE, "StatusChanged",
                                 GLib.Variant("(s)", (js,)))
            return False

        GLib.idle_add(_do)

    engine = ClipEngine(store, cfg, emit=emit, pictures=pictures_dir(),
                        save_cfg=st.save_config, on_status=emit_status)
    engine.creds(force=True)
    control = ThreadPoolExecutor(max_workers=2, thread_name_prefix="control")

    def reply_later(invocation, fn, signature):
        """Runs fn off the main loop; returns its result (or a D-Bus error) when done."""
        def work():
            try:
                result = fn()
                value = GLib.Variant(signature, (result,)) if signature else None
                GLib.idle_add(lambda: (invocation.return_value(value), False)[1])
            except Exception as e:
                log.warning("%s failed: %s", invocation.get_method_name(), e)
                msg = str(e) or e.__class__.__name__
                GLib.idle_add(lambda: (invocation.return_dbus_error(
                    "com.ogenrwotdaniel.WakeProofClip.Error", msg), False)[1])

        control.submit(work)
    offers = ThreadPoolExecutor(max_workers=1, thread_name_prefix="offer")

    def safe_offer(mime, data):
        try:
            engine.offer(mime, data)
        except Exception:
            log.exception("offer failed")

    def on_method(conn, sender, path, iface, method, params, invocation):
        if method == "Offer":
            mime = params.get_child_value(0).get_string()
            data = params.get_child_value(1).get_data_as_bytes().get_data() or b""
            invocation.return_value(None)
            offers.submit(safe_offer, mime, bytes(data))
        elif method == "GetStatus":
            invocation.return_value(GLib.Variant("(s)", (engine.status_json(),)))
            engine.refresh_presence_soon()
        elif method == "SetEnabled":
            enabled = params.get_child_value(0).get_boolean()
            reply_later(invocation, lambda: engine.set_enabled(enabled), None)
        elif method == "StartPairing":
            reply_later(invocation, engine.start_pairing, "(s)")
        elif method == "CancelPairing":
            reply_later(invocation, engine.cancel_pairing, None)
        elif method == "RenderQr":
            text = params.get_child_value(0).get_string()
            reply_later(invocation, lambda: render_qr(text), "(s)")
        else:
            invocation.return_dbus_error("org.freedesktop.DBus.Error.UnknownMethod", method)

    node = Gio.DBusNodeInfo.new_for_xml(INTROSPECTION)

    def on_bus(conn, name):
        state["conn"] = conn
        conn.register_object(OBJECT_PATH, node.interfaces[0], on_method, None, None)

    def on_lost(conn, name):
        log.error("could not own %s (another clipsync running?)", BUS_NAME)
        state["exit"] = 1
        loop.quit()

    owner = Gio.bus_own_name(Gio.BusType.SESSION, BUS_NAME, Gio.BusNameOwnerFlags.NONE,
                             on_bus, lambda c, n: log.info("owning %s", n), on_lost)

    try:
        server = ClipServer(lambda cid, body, ip: engine.receive(cid, body, "LAN", ip), LAN_PORT).start()
        log.info("LAN server on port %d", server.port)
    except OSError as e:
        log.error("cannot listen on %d: %s", LAN_PORT, e)
        return 1

    engine.mdns = Mdns(engine.install_id, engine.name)
    if not engine.mdns.start(local_ipv4_addrs()):
        engine.mdns = None

    stop = threading.Event()
    threading.Thread(target=engine.run_background, args=(stop,), name="cloud", daemon=True).start()

    for sig in (signal.SIGTERM, signal.SIGINT):
        GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, sig, lambda: (loop.quit(), False)[1])
    log.info("clipsync daemon started (install %s, \"%s\")", engine.install_id, engine.name)
    loop.run()

    stop.set()
    engine.wake.set()
    engine.cancel_pairing()
    Gio.bus_unown_name(owner)
    server.stop()
    if engine.mdns is not None:
        engine.mdns.stop()
    offers.shutdown(wait=False, cancel_futures=True)
    control.shutdown(wait=False, cancel_futures=True)
    log.info("clipsync daemon stopped")
    return state.get("exit", 0)


def daemon_running() -> bool:
    try:
        from gi.repository import Gio, GLib

        bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        res = bus.call_sync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                            "NameHasOwner", GLib.Variant("(s)", (BUS_NAME,)), GLib.VariantType("(b)"),
                            Gio.DBusCallFlags.NONE, 1000, None)
        return bool(res.unpack()[0])
    except Exception:
        return False


def offer_via_dbus(mime: str, data: bytes) -> None:
    from gi.repository import Gio, GLib

    bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    params = GLib.Variant.new_tuple(
        GLib.Variant.new_string(mime),
        GLib.Variant.new_from_bytes(GLib.VariantType.new("ay"), GLib.Bytes.new(data), True),
    )
    bus.call_sync(BUS_NAME, OBJECT_PATH, INTERFACE, "Offer", params, None,
                  Gio.DBusCallFlags.NO_AUTO_START, 5000, None)
