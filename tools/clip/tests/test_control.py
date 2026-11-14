"""The Quick Settings tile's control surface: GetStatus, SetEnabled, pairing, RenderQr."""
import http.client
import json
import os
import time

from clipsync import codec, lan, store as st
from clipsync.codec import Clip, b64u_decode, b64u_encode, parse_qr_text
from clipsync.daemon import DISABLED_STATUS, ClipEngine, render_qr

from test_transport import FakeFirestore, _request, make_engine, server  # noqa: F401 (fixture)


def _now():
    return int(time.time() * 1000)


def test_status_shape(tmp_path):
    fs = FakeFirestore()
    eng, key, _ = make_engine(tmp_path, fs=fs, peers={"phone-1": {"name": "Galaxy S25 Edge", "addrs": []}})
    fs.presence_docs = [
        {"installId": "phone-1", "name": "Galaxy S25 Edge", "platform": "android", "updatedAt": _now() - 120_000},
        {"installId": "laptop-1", "name": "Ubuntu laptop", "platform": "linux", "updatedAt": _now()},  # own
        {"installId": "tab-1", "name": "Galaxy Tab S9 FE+", "platform": "android", "updatedAt": _now() - 3_600_000},
    ]
    eng.presence(fs, force=True)
    c = Clip("x1", "phone-1", "Galaxy S25 Edge", _now(), "text/plain", b"hi")
    assert eng.receive("x1", codec.seal(key, c), "LAN", "10.0.0.5") == 204

    status = json.loads(eng.status_json())
    assert set(status) >= {"enabled", "paired", "signedIn", "email", "name", "peers", "pairing"}
    assert status["enabled"] is True and status["paired"] is True and status["signedIn"] is False
    assert status["name"] == "Ubuntu laptop"
    assert [p["name"] for p in status["peers"]] == ["Galaxy S25 Edge", "Galaxy Tab S9 FE+"]
    phone, tab = status["peers"]
    assert set(phone) >= {"name", "platform", "lastSeenMs", "lan"}
    assert phone["platform"] == "android" and phone["lan"] is True and phone["lastSeenMs"] >= _now() - 5000
    assert tab["lan"] is False and tab["lastSeenMs"] < _now() - 3_000_000
    assert status["pairing"] == {"active": False, "qrText": "", "expiresAtMs": 0, "result": None, "warning": None}


def test_status_not_paired(tmp_path):
    eng = ClipEngine(st.MemoryStore(), {"installId": "l", "name": "n", "peers": {}})
    eng.creds(force=True)
    status = eng.status()
    assert status["paired"] is False and status["signedIn"] is False and status["peers"] == []


def test_set_enabled_gates_send_and_receive_and_persists(tmp_path, server):
    peer_key, got, srv = server
    fs = FakeFirestore()
    fs.presence_docs = [{"installId": "phone", "updatedAt": _now()}]
    saved, signals = [], []
    eng, key, emitted = make_engine(tmp_path, fs=fs, peers={"phone": {"addrs": ["127.0.0.1"], "port": srv.port}})
    eng.save_cfg = lambda c: saved.append(dict(c))
    eng.on_status = lambda js: signals.append(json.loads(js))

    eng.set_enabled(False)
    assert saved[-1]["enabled"] is False and signals[-1]["enabled"] is False
    assert eng.offer("text/plain", b"while off").startswith("skipped")
    assert got == [] and fs.docs == {}  # nothing went over LAN or to the cloud
    c = Clip("in1", "phone", "Galaxy S25 Edge", _now(), "text/plain", b"incoming")
    assert eng.receive("in1", codec.seal(key, c), "cloud") == DISABLED_STATUS
    assert emitted == []

    # A peer posting over LAN gets 503 and does not count as acked.
    lan_srv = lan.ClipServer(lambda cid, b, ip: eng.receive(cid, b, "LAN", ip), port=0, host="127.0.0.1").start()
    try:
        sealed = codec.seal(key, c)
        conn = http.client.HTTPConnection("127.0.0.1", lan_srv.port, timeout=3)
        conn.request("POST", "/clip", body=sealed, headers={"X-Clip-Id": "in1"})
        assert conn.getresponse().status == 503
        conn.close()
        assert lan.send_to_peers({"laptop": [("127.0.0.1", lan_srv.port)]}, "in1", sealed) == set()
        assert emitted == []

        eng.set_enabled(True)
        assert saved[-1]["enabled"] is True and signals[-1]["enabled"] is True
        assert lan.send_to_peers({"laptop": [("127.0.0.1", lan_srv.port)]}, "in1", sealed) == {"laptop"}
        assert emitted == [("text/plain", b"incoming")]
    finally:
        lan_srv.stop()


def test_enabled_defaults_true_and_reads_config(tmp_path):
    assert ClipEngine(st.MemoryStore(), {"installId": "l", "name": "n"}).enabled is True
    assert ClipEngine(st.MemoryStore(), {"installId": "l", "name": "n", "enabled": False}).enabled is False


def _post_pair(port, body):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    conn.request("POST", "/pair", body=body, headers={"Content-Type": "application/octet-stream"})
    resp = conn.getresponse()
    out = resp.status, resp.read()
    conn.close()
    return out


def _wait_for(pred, timeout=3.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if pred():
            return True
        time.sleep(0.02)
    return pred()


def test_in_daemon_pairing_round_trip_reloads_key(tmp_path):
    store = st.MemoryStore()
    signals, saved = [], []
    eng = ClipEngine(store, {"installId": "laptop-1", "name": "Ubuntu laptop", "peers": {}},
                     save_cfg=lambda c: saved.append(json.loads(json.dumps(c))),
                     on_status=lambda js: signals.append(json.loads(js)))
    eng.creds(force=True)
    assert eng.status()["paired"] is False

    def signin(api_key, token):
        return {"refreshToken": "rt", "localId": "uid-1", "email": "a@b.c"}

    text = eng.start_pairing(port=0, host="127.0.0.1", signin=signin, addrs=["127.0.0.1"])
    qr = parse_qr_text(text)
    port = eng.pairing.server.port
    assert qr["a"] == ["127.0.0.1"] and qr["p"] == port and qr["n"] == "Ubuntu laptop"
    status = eng.status()["pairing"]
    assert status["active"] is True and status["qrText"] == text and status["expiresAtMs"] > _now()

    # A stale QR (wrong secret) is refused but the window stays open.
    assert _post_pair(port, _request(os.urandom(32)))[0] == 400
    assert eng.status()["pairing"]["active"] is True
    assert eng.status()["pairing"]["result"].startswith("error:")

    k = os.urandom(32)
    code, sealed = _post_pair(port, _request(qr["s"], key=b64u_encode(k), idToken="tok",
                                             apiKey="AIza", projectId="proj"))
    assert code == 200
    assert b64u_decode(json.loads(codec.pair_open(qr["s"], sealed))["key"]) == k
    assert _wait_for(lambda: eng.status()["pairing"]["result"] == "ok:Galaxy S25 Edge")

    # The engine uses the new key and account without a restart (no forced creds() here).
    status = eng.status()
    assert status["paired"] is True and status["signedIn"] is True and status["email"] == "a@b.c"
    assert status["pairing"]["active"] is False
    assert eng._key == k
    assert eng.cfg["peers"]["phone-1"]["name"] == "Galaxy S25 Edge"
    assert saved[-1]["peers"]["phone-1"]["addrs"] == ["127.0.0.1"]
    assert _wait_for(lambda: any(s["pairing"]["result"] == "ok:Galaxy S25 Edge" and s["paired"]
                                 for s in list(signals)))

    # Single success: the window is closed now.
    assert _wait_for(lambda: _pair_refused(port))


def _pair_refused(port):
    try:
        return _post_pair(port, b"x")[0] != 200
    except OSError:
        return True


def test_start_pairing_twice_returns_same_session_and_cancel(tmp_path):
    eng, _, _ = make_engine(tmp_path)
    first = eng.start_pairing(port=0, host="127.0.0.1", addrs=["127.0.0.1"])
    session = eng.pairing
    try:
        assert eng.start_pairing(port=0, host="127.0.0.1", addrs=["127.0.0.1"]) == first
        assert eng.pairing is session
    finally:
        eng.cancel_pairing()
    assert eng.status()["pairing"]["active"] is False and eng.status()["pairing"]["result"] is None
    second = eng.start_pairing(port=0, host="127.0.0.1", addrs=["127.0.0.1"])
    try:
        assert second != first  # a new secret
    finally:
        eng.cancel_pairing()


def test_pairing_window_expires(tmp_path):
    eng, _, _ = make_engine(tmp_path)
    eng.start_pairing(port=0, host="127.0.0.1", addrs=["127.0.0.1"], window_s=0.2)
    assert _wait_for(lambda: not eng.status()["pairing"]["active"])
    assert eng.status()["pairing"]["result"].startswith("error:")


def test_render_qr_writes_png(tmp_path):
    from PIL import Image

    text = codec.qr_text(["192.168.0.10"], 47322, os.urandom(32), "Ubuntu laptop")
    path = render_qr(text, tmp_path / "qr.png")
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    img = Image.open(path)
    assert img.size[0] == img.size[1] >= 400
    # Quiet zone: the corners are white.
    assert img.getpixel((0, 0)) == 255 and img.getpixel((img.size[0] - 1, img.size[1] - 1)) == 255


def test_forget_pairing_clears_key_account_peers_and_emits_status(tmp_path):
    fs = FakeFirestore()
    saved, signals = [], []
    eng, _, _ = make_engine(tmp_path, fs=fs, peers={"phone-1": {"name": "Galaxy S25 Edge", "addrs": ["10.0.0.5"]}})
    st.save_account(eng.store, {"refreshToken": "r", "uid": "u", "email": "a@b.c", "apiKey": "k", "projectId": "p"})
    eng.creds(force=True)
    eng.save_cfg = lambda c: saved.append(dict(c))
    eng.on_status = lambda js: signals.append(json.loads(js))

    eng.forget_pairing()
    status = eng.status()
    assert status["paired"] is False and status["signedIn"] is False
    assert status["peers"] == [] and eng.cfg["peers"] == {}
    assert st.load_key(eng.store) is None and st.load_account(eng.store) is None
    assert saved[-1]["peers"] == {}
    assert signals[-1]["paired"] is False
