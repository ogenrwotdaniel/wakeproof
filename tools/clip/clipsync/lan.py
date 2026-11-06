"""LAN transport: HTTP on 47321, parallel sender, optional mDNS (docs/CLIPBOARD.md)."""
from __future__ import annotations

import http.client
import json
import logging
import socket
import subprocess
import threading
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .rules import MAX_IMAGE_BYTES

log = logging.getLogger("clipsync.lan")

LAN_PORT = 47321
SERVICE_TYPE = "_wakeproofclip._tcp.local."
CONNECT_TIMEOUT = 1.5
UPLOAD_TIMEOUT = 30.0
# Largest image plus header, length prefix, nonce and tag, with room to spare.
MAX_SEALED = MAX_IMAGE_BYTES + 64 * 1024
PING_BODY = b"wakeproof-clip"


def local_ipv4_addrs() -> list[str]:
    """Every non-loopback IPv4 address of an interface that is up."""
    addrs: list[str] = []
    try:
        out = subprocess.run(["ip", "-j", "-4", "addr", "show", "up"], capture_output=True,
                             text=True, timeout=3, check=True).stdout
        for iface in json.loads(out):
            for a in iface.get("addr_info", []):
                ip = a.get("local")
                if a.get("family") == "inet" and ip and not ip.startswith("127."):
                    addrs.append(ip)
    except (OSError, subprocess.SubprocessError, ValueError):
        pass
    if not addrs:
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("192.0.2.1", 9))  # no packet is sent for a UDP connect
                ip = s.getsockname()[0]
                if not ip.startswith("127."):
                    addrs.append(ip)
        except OSError:
            pass
    return sorted(set(addrs))


class ClipServer:
    """POST /clip and GET /ping. `on_clip(clip_id, sealed, client_ip) -> status`."""

    def __init__(self, on_clip, port: int = LAN_PORT, host: str = "0.0.0.0"):
        handler = self._make_handler(on_clip)
        ThreadingHTTPServer.allow_reuse_address = True
        self.httpd = ThreadingHTTPServer((host, port), handler)
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]
        self._thread = None

    @staticmethod
    def _make_handler(on_clip):
        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            server_version = "wakeproof-clip"
            # Anyone on the network can connect before any key is checked, so a
            # peer that goes quiet may hold its thread for this long and no longer.
            timeout = 15

            def log_message(self, fmt, *args):
                log.debug("%s %s", self.client_address[0], fmt % args)

            def _reply(self, status, body=b"", close=False):
                self.send_response(status)
                self.send_header("Content-Length", str(len(body)))
                if close:
                    self.send_header("Connection", "close")
                    self.close_connection = True
                self.end_headers()
                if body:
                    self.wfile.write(body)

            def do_GET(self):
                if self.path == "/ping":
                    self._reply(200, PING_BODY)
                else:
                    self._reply(404)

            def do_POST(self):
                if self.path != "/clip":
                    self._reply(404, close=True)
                    return
                try:
                    length = int(self.headers.get("Content-Length", ""))
                except ValueError:
                    self._reply(411, close=True)
                    return
                if length <= 0:
                    # A negative length would make read() run to the end of the
                    # stream, however long a stranger cares to keep sending.
                    self._reply(400, close=True)
                    return
                if length > MAX_SEALED:
                    self._reply(413, close=True)
                    return
                try:
                    body = self.rfile.read(length)
                except OSError:  # went quiet or reset partway through the body
                    self.close_connection = True
                    return
                clip_id = (self.headers.get("X-Clip-Id") or "").strip()
                if not clip_id or len(body) != length:
                    self._reply(400, close=True)
                    return
                try:
                    status = on_clip(clip_id, body, self.client_address[0])
                except Exception:
                    log.exception("handling a LAN clip failed")
                    status = 500
                self._reply(status)

        return Handler

    def start(self):
        self._thread = threading.Thread(target=self.httpd.serve_forever, name="lan-server", daemon=True)
        self._thread.start()
        return self

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def _post(host: str, port: int, clip_id: str, sealed: bytes) -> int | None:
    conn = http.client.HTTPConnection(host, port, timeout=CONNECT_TIMEOUT)
    try:
        conn.connect()
        conn.sock.settimeout(UPLOAD_TIMEOUT)
        conn.request("POST", "/clip", body=sealed, headers={
            "X-Clip-Id": clip_id,
            "Content-Type": "application/octet-stream",
        })
        resp = conn.getresponse()
        resp.read()
        return resp.status
    except (OSError, http.client.HTTPException):
        return None
    finally:
        conn.close()


def send_to_peers(targets: dict[str, list[tuple[str, int]]], clip_id: str, sealed: bytes) -> set[str]:
    """POSTs to every address of every peer at once; returns the peer ids that answered 204.

    A peer reached on two addresses gets the clip twice; it dedupes by id.
    """
    jobs = [(peer, host, port) for peer, addrs in targets.items() for host, port in addrs]
    if not jobs:
        return set()
    acked: set[str] = set()
    with ThreadPoolExecutor(max_workers=min(16, len(jobs))) as pool:
        results = pool.map(lambda j: (j, _post(j[1], j[2], clip_id, sealed)), jobs)
        for (peer, host, port), status in results:
            if status == 204:
                acked.add(peer)
            else:
                log.debug("LAN %s at %s:%s -> %s", peer, host, port, status)
    return acked


def ping(host: str, port: int = LAN_PORT, timeout: float = CONNECT_TIMEOUT) -> bool:
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        conn.request("GET", "/ping")
        resp = conn.getresponse()
        return resp.status == 200 and resp.read() == PING_BODY
    except (OSError, http.client.HTTPException):
        return False
    finally:
        conn.close()


class Mdns:
    """Registers this device and browses for peers when python3-zeroconf is installed."""

    def __init__(self, install_id: str, name: str, port: int = LAN_PORT):
        self.install_id = install_id
        self.name = name
        self.port = port
        self.peers: dict[str, dict] = {}  # installId -> {"name", "addrs": [(ip, port)]}
        self._zc = None
        self._info = None
        self._lock = threading.Lock()

    def start(self, addrs: list[str]) -> bool:
        try:
            from zeroconf import ServiceBrowser, ServiceInfo, Zeroconf
        except ImportError:
            log.info("python3-zeroconf not installed: no mDNS, relying on presence docs")
            return False
        try:
            self._zc = Zeroconf()
            self._ServiceInfo = ServiceInfo
            self._register(addrs)
            self._browser = ServiceBrowser(self._zc, SERVICE_TYPE, handlers=[self._on_change])
            return True
        except Exception as e:  # zeroconf raises many kinds when multicast is blocked
            log.warning("mDNS unavailable: %s", e)
            self._zc = None
            return False

    def _register(self, addrs):
        if not addrs:
            return
        info = self._ServiceInfo(
            SERVICE_TYPE,
            f"wakeproof-{self.install_id[:12]}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(a) for a in addrs],
            port=self.port,
            properties={"id": self.install_id, "name": self.name},
        )
        if self._info is not None:
            self._zc.update_service(info)
        else:
            self._zc.register_service(info)
        self._info = info

    def update_addrs(self, addrs):
        if self._zc is None:
            return
        try:
            self._register(addrs)
        except Exception as e:
            log.warning("mDNS re-register failed: %s", e)

    def _on_change(self, zeroconf, service_type, name, state_change):
        try:
            from zeroconf import ServiceStateChange

            if state_change == ServiceStateChange.Removed:
                with self._lock:
                    for pid, p in list(self.peers.items()):
                        if p.get("service") == name:
                            del self.peers[pid]
                return
            info = zeroconf.get_service_info(service_type, name, timeout=1500)
            if not info:
                return
            props = {k.decode(): (v or b"").decode() for k, v in (info.properties or {}).items()}
            pid = props.get("id")
            if not pid or pid == self.install_id:
                return
            addrs = [(a, info.port) for a in info.parsed_addresses() if ":" not in a]
            with self._lock:
                self.peers[pid] = {"name": props.get("name", ""), "addrs": addrs, "service": name}
            log.info("mDNS peer %s (%s) at %s", props.get("name"), pid, addrs)
        except Exception as e:
            log.debug("mDNS change handling failed: %s", e)

    def snapshot(self) -> dict[str, dict]:
        with self._lock:
            return {k: dict(v) for k, v in self.peers.items()}

    def stop(self):
        if self._zc is not None:
            try:
                if self._info is not None:
                    self._zc.unregister_service(self._info)
                self._zc.close()
            except Exception:
                pass
