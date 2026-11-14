"""Pairing (docs/CLIPBOARD.md, "Pairing").

`PairingServer` serves POST /pair on 47322 and accepts one device. `clipsync pair` wraps
it with a terminal QR; the daemon's StartPairing (the GNOME tile) wraps it with a PNG.
"""
from __future__ import annotations

import json
import logging
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import cloud, store as st
from .codec import ClipError, b64u_decode, b64u_encode, pair_open, pair_seal, qr_text
from .lan import LAN_PORT, local_ipv4_addrs

log = logging.getLogger("clipsync.pair")

PAIR_PORT = 47322
PAIR_WINDOW_S = 5 * 60
MAX_PAIR_BODY = 64 * 1024


class PairError(Exception):
    pass


def handle_pair_request(secret: bytes, body: bytes, store, cfg: dict, client_ip: str | None = None,
                        signin=cloud.sign_in_with_idp, save_cfg=st.save_config) -> tuple[bytes, dict]:
    """Decrypts one request, settles K_final and the account, returns (sealed reply, summary)."""
    try:
        req = json.loads(pair_open(secret, body).decode("utf-8"))
    except (ClipError, UnicodeDecodeError, json.JSONDecodeError) as e:
        raise PairError(f"bad pairing request: {e}") from e
    if not isinstance(req, dict) or not req.get("installId"):
        raise PairError("pairing request has no installId")

    key = st.load_key(store)
    if key is None and req.get("key"):
        offered = b64u_decode(req["key"])
        if len(offered) != 32:
            raise PairError("offered key is not 32 bytes")
        key = offered
    if key is None:
        key = os.urandom(32)
    st.save_key(store, key)

    account = st.load_account(store)
    signin_error = None
    if req.get("idToken") and account is None:
        api_key, project = req.get("apiKey"), req.get("projectId")
        if not api_key or not project:
            signin_error = "request has an idToken but no apiKey/projectId"
        else:
            try:
                resp = signin(api_key, req["idToken"])
                account = {
                    "refreshToken": resp["refreshToken"], "uid": resp["localId"],
                    "email": resp.get("email", ""), "apiKey": api_key, "projectId": project,
                }
                st.save_account(store, account)
            except (cloud.CloudError, KeyError) as e:
                signin_error = str(e)

    peers = cfg.setdefault("peers", {})
    peer = peers.setdefault(req["installId"], {})
    peer["name"] = req.get("name") or peer.get("name", "")
    if client_ip:
        peer["addrs"] = sorted(set(peer.get("addrs", [])) | {client_ip})
    peer["port"] = LAN_PORT
    save_cfg(cfg)

    reply = {
        "key": b64u_encode(key),
        "installId": cfg["installId"],
        "name": cfg["name"],
        "uid": account["uid"] if account else None,
    }
    sealed = pair_seal(secret, json.dumps(reply, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
    return sealed, {"peer": req.get("name") or req["installId"], "account": account,
                    "signin_error": signin_error}


def _print_qr(text: str) -> None:
    try:
        import qrcode
    except ImportError:
        raise SystemExit("python3-qrcode is not installed: sudo apt install python3-qrcode")
    qr = qrcode.QRCode(border=2, error_correction=qrcode.constants.ERROR_CORRECT_L)
    qr.add_data(text)
    qr.make(fit=True)
    qr.print_ascii(tty=sys.stdout.isatty(), invert=True)


def render_qr_png(text: str, path) -> str:
    """Writes `text` as a large black-on-white QR PNG with a 4-module quiet zone."""
    import qrcode
    from PIL import Image

    qr = qrcode.QRCode(border=4, box_size=1, error_correction=qrcode.constants.ERROR_CORRECT_M)
    qr.add_data(text)
    qr.make(fit=True)
    matrix = qr.get_matrix()  # includes the border
    n = len(matrix)
    scale = max(4, 600 // n)
    size = n * scale
    raw = bytes(
        0 if cell else 255
        for row in matrix
        for _ in range(scale)
        for cell in row
        for _ in range(scale)
    )
    img = Image.frombytes("L", (size, size), raw)
    path = os.fspath(path)
    tmp = f"{path}.tmp"
    img.save(tmp, format="PNG")
    os.replace(tmp, path)
    return path


class PairingServer:
    """One pairing window: serves POST /pair until ONE request succeeds.

    Shared by `clipsync pair` (CLI) and the daemon's StartPairing. `on_success(summary)`
    and `on_reject(client_ip, message)` run on the request thread.
    """

    def __init__(self, store, cfg: dict, secret: bytes | None = None, port: int = PAIR_PORT,
                 host: str = "0.0.0.0", signin=None, save_cfg=None, on_success=None, on_reject=None):
        self.secret = secret or os.urandom(32)
        self.done = threading.Event()
        self.outcome: dict = {}
        self._lock = threading.Lock()
        self._thread = None
        server = self
        signin = signin or cloud.sign_in_with_idp
        save_cfg = save_cfg or st.save_config

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt, *args):
                log.debug(fmt, *args)

            def _reply(self, status, body=b""):
                self.send_response(status)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Connection", "close")
                self.close_connection = True
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                if self.path != "/pair" or server.done.is_set():
                    self._reply(404)
                    return
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    length = -1
                if not 0 < length <= MAX_PAIR_BODY:
                    self._reply(413 if length > MAX_PAIR_BODY else 400)
                    return
                body = self.rfile.read(length)
                ip = self.client_address[0]
                with server._lock:  # one success only, even for two requests at once
                    if server.done.is_set():
                        self._reply(404)
                        return
                    try:
                        sealed, summary = handle_pair_request(server.secret, body, store, cfg, ip,
                                                              signin=signin, save_cfg=save_cfg)
                    except PairError as e:
                        log.warning("rejected a pairing attempt from %s: %s", ip, e)
                        self._reply(400)
                        if on_reject is not None:
                            on_reject(ip, str(e))
                        return
                    self._reply(200, sealed)
                    server.outcome.update(summary)
                    server.done.set()
                if on_success is not None:
                    on_success(summary)

        self.httpd = ThreadingHTTPServer((host, port), Handler)
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]

    def qr_text(self, addrs: list[str], name: str) -> str:
        return qr_text(addrs, self.port, self.secret, name)

    def start(self):
        self._thread = threading.Thread(target=self.httpd.serve_forever, name="pair-server", daemon=True)
        self._thread.start()
        return self

    def stop(self, flush_s: float = 0.0):
        if flush_s:
            time.sleep(flush_s)  # let the last reply flush
        self.httpd.shutdown()
        self.httpd.server_close()


def run_pair(store=None) -> int:
    store = store or st.KeyringStore()
    cfg = st.load_config()
    addrs = local_ipv4_addrs()
    if not addrs:
        print("No IPv4 address: connect to a network first.", file=sys.stderr)
        return 1

    def on_reject(ip, msg):
        print(f"Rejected a pairing attempt from {ip}: {msg}", file=sys.stderr)

    try:
        server = PairingServer(store, cfg, on_reject=on_reject)
    except OSError as e:
        print(f"Cannot listen on port {PAIR_PORT} ({e}). Is the daemon already pairing?", file=sys.stderr)
        return 1
    _print_qr(server.qr_text(addrs, cfg["name"]))
    print(f"\nScan with Euler's Lab: Settings -> Clipboard across devices -> Pair a computer.")
    print(f"Listening on {', '.join(addrs)} port {PAIR_PORT} for {PAIR_WINDOW_S // 60} minutes.")

    server.start()
    try:
        server.done.wait(PAIR_WINDOW_S)
    except KeyboardInterrupt:
        pass
    server.stop(flush_s=0.3)

    if not server.done.is_set():
        print("Pairing window closed without a device.", file=sys.stderr)
        return 1
    outcome = server.outcome
    print(f"Paired with {outcome['peer']}.")
    acct = outcome.get("account")
    if acct:
        print(f"Cloud fallback: signed in as {acct.get('email') or acct['uid']}.")
    elif outcome.get("signin_error"):
        print(f"Cloud sign-in FAILED ({outcome['signin_error']}); LAN only until you pair again.")
    else:
        print("No account was sent: LAN only.")
    print("The running daemon picks this up within 30 s (systemctl --user restart clipsync to hurry it).")
    return 0
