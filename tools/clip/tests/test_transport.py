import base64
import json
import os
import socket
import time

import pytest

from clipsync import cloud, codec, lan, pair, store as st
from clipsync.codec import Clip, b64u_decode, b64u_encode
from clipsync.daemon import ClipEngine, image_filename


# --- LAN ---------------------------------------------------------------------

@pytest.fixture
def server():
    key = os.urandom(32)
    got = []

    def on_clip(clip_id, sealed, ip):
        try:
            got.append((codec.open_sealed(key, clip_id, sealed), ip))
            return 204
        except codec.ClipError:
            return 400

    srv = lan.ClipServer(on_clip, port=0, host="127.0.0.1").start()
    yield key, got, srv
    srv.stop()


def test_lan_round_trip(server):
    key, got, srv = server
    clip = Clip("id-1", "laptop", "Ubuntu laptop", 1, "text/plain", "héllo".encode())
    acked = lan.send_to_peers({"phone": [("127.0.0.1", srv.port)]}, clip.id, codec.seal(key, clip))
    assert acked == {"phone"}
    assert got[0][0] == clip and got[0][1] == "127.0.0.1"


def test_lan_wrong_key_is_400_not_acked(server):
    _, got, srv = server
    clip = Clip("id-2", "x", "x", 1, "text/plain", b"hi")
    acked = lan.send_to_peers({"phone": [("127.0.0.1", srv.port)]}, clip.id, codec.seal(os.urandom(32), clip))
    assert acked == set() and got == []


def test_lan_ping(server):
    assert lan.ping("127.0.0.1", server[2].port)


def test_lan_dead_address_fails_fast(server):
    key, got, srv = server
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        dead = s.getsockname()[1]  # closed once the with-block ends
    clip = Clip("id-3", "x", "x", 1, "text/plain", b"hi")
    t = time.monotonic()
    acked = lan.send_to_peers({"phone": [("127.0.0.1", dead), ("127.0.0.1", srv.port)],
                               "tablet": [("127.0.0.1", dead)]}, clip.id, codec.seal(key, clip))
    assert acked == {"phone"}
    assert time.monotonic() - t < 3


def test_lan_too_big_is_413(server):
    import http.client

    conn = http.client.HTTPConnection("127.0.0.1", server[2].port, timeout=3)
    conn.putrequest("POST", "/clip")
    conn.putheader("X-Clip-Id", "big")
    conn.putheader("Content-Length", str(lan.MAX_SEALED + 1))
    conn.endheaders()
    assert conn.getresponse().status == 413
    conn.close()


# --- Firestore encoding (no network) ------------------------------------------

def test_firestore_values():
    enc = cloud.encode_fields({"n": 1762200000000, "s": "é", "b": b"\xfb\xff", "a": ["1.2.3.4"], "t": True})
    assert enc["n"] == {"integerValue": "1762200000000"}
    assert enc["s"] == {"stringValue": "é"}
    # Standard base64 (with + / and padding), which the REST API requires.
    assert enc["b"] == {"bytesValue": base64.b64encode(b"\xfb\xff").decode()} == {"bytesValue": "+/8="}
    assert enc["a"] == {"arrayValue": {"values": [{"stringValue": "1.2.3.4"}]}}
    assert enc["t"] == {"booleanValue": True}
    assert cloud.decode_fields(enc) == {"n": 1762200000000, "s": "é", "b": b"\xfb\xff", "a": ["1.2.3.4"], "t": True}
    assert cloud.from_value({"arrayValue": {}}) == []


def test_clip_docs_shape_and_order():
    sealed = os.urandom(codec.CHUNK_BYTES + 5)
    docs = cloud.clip_docs("abc", "laptop", 1000, sealed)
    assert [d for d, _ in docs] == ["chunk_abc_0", "chunk_abc_1", "clip_abc"]
    header = docs[-1][1]
    assert header == {"id": "abc", "origin": "laptop", "createdAt": 1000, "chunks": 2,
                      "size": len(sealed), "expiresAt": 1000 + 3600_000}
    assert docs[1][1] == {"id": "abc", "index": 1, "data": sealed[codec.CHUNK_BYTES:], "expiresAt": 3601000}


def test_housekeeping_victims():
    now = 10_000_000
    heads = [{"id": str(i), "createdAt": i, "expiresAt": now + 1} for i in range(12)]
    heads.append({"id": "old", "createdAt": 50, "expiresAt": now - 1})
    victims = {h["id"] for h in cloud.housekeeping_victims(heads, now)}
    # 13 headers: keep the 10 newest by createdAt (old=50 is newest) minus the expired one.
    assert victims == {"0", "1", "2", "old"}


class FakeFirestore:
    """Just enough of cloud.Firestore for the engine."""

    def __init__(self):
        self.docs = {}
        self.presence_docs = []

    def put(self, doc_id, fields):
        self.docs[doc_id] = fields

    def upload_clip(self, clip_id, origin, created_at, sealed):
        for d, f in cloud.clip_docs(clip_id, origin, created_at, sealed):
            self.put(d, f)

    def presence(self, since):
        return self.presence_docs

    def housekeep(self, install_id, now):
        return 0

    def headers_since(self, since):
        return sorted((f for d, f in self.docs.items() if d.startswith("clip_") and f["createdAt"] > since),
                      key=lambda f: f["createdAt"])

    def fetch_sealed(self, h):
        return codec.join_chunks([self.docs[cloud.chunk_doc_id(h["id"], i)]["data"] for i in range(h["chunks"])])


# --- engine ---------------------------------------------------------------------

def make_engine(tmp_path, install="laptop-1", fs=None, peers=None):
    store = st.MemoryStore()
    key = os.urandom(32)
    st.save_key(store, key)
    emitted = []
    eng = ClipEngine(store, {"installId": install, "name": "Ubuntu laptop", "peers": peers or {}},
                     emit=lambda m, b: emitted.append((m, b)), pictures=tmp_path / "pics")
    eng.creds(force=True)
    if fs is not None:
        eng._fs = fs
    return eng, key, emitted


def test_engine_receive_rules(tmp_path):
    eng, key, emitted = make_engine(tmp_path)
    now = int(time.time() * 1000)
    c = Clip("r1", "phone-1", "Galaxy S25 Edge", now, "text/plain", b"from phone")
    sealed = codec.seal(key, c)
    assert eng.receive("r1", sealed, "LAN", "10.0.0.5") == 204
    assert emitted == [("text/plain", b"from phone")]
    assert eng.receive("r1", sealed, "LAN") == 204  # duplicate id
    assert len(emitted) == 1
    assert eng.receive("r2", sealed, "LAN") == 400  # id mismatch with transport
    stale = Clip("r3", "phone-1", "P", now - 11 * 60_000, "text/plain", b"old")
    assert eng.receive("r3", codec.seal(key, stale), "cloud") == 204
    assert len(emitted) == 1
    # The clipboard change it caused is not sent back.
    assert eng.offer("text/plain", b"from phone").startswith("skipped")
    assert eng.learned == {"phone-1": {"10.0.0.5"}}


def test_engine_receive_image_saves_and_emits_png(tmp_path):
    from PIL import Image
    import io

    eng, key, emitted = make_engine(tmp_path)
    buf = io.BytesIO()
    Image.new("RGB", (8, 8), (200, 10, 10)).save(buf, format="JPEG")
    now = int(time.time() * 1000)
    c = Clip("img", "phone-1", "Galaxy S25 Edge", now, "image/jpeg", buf.getvalue())
    assert eng.receive("img", codec.seal(key, c), "cloud") == 204
    files = list((tmp_path / "pics").iterdir())
    assert len(files) == 1 and files[0].name.endswith(" from Galaxy S25 Edge.jpg")
    assert files[0].read_bytes() == buf.getvalue()
    assert emitted[0][0] == "image/png" and emitted[0][1][:4] == b"\x89PNG"


def test_image_filename():
    name = image_filename(1762200000000, "Tab/S9", "image/png")
    assert name.endswith(" from Tab_S9.png") and len(name.split(" from ")[0]) == 19


def test_engine_offer_lan_then_cloud(tmp_path, server):
    peer_key, got, srv = server
    fs = FakeFirestore()
    eng, key, _ = make_engine(tmp_path, fs=fs, peers={"phone": {"addrs": ["127.0.0.1"], "port": srv.port}})
    # The fixture server holds a different key: LAN answers 400, so the cloud must carry it.
    fs.presence_docs = [{"installId": "phone", "updatedAt": int(time.time() * 1000)}]
    out = eng.offer("text/plain", b"to everyone")
    assert "LAN 0/1" in out and "cloud" in out and "FAILED" not in out
    header = [f for d, f in fs.docs.items() if d.startswith("clip_")][0]
    opened = codec.open_sealed(key, header["id"], fs.fetch_sealed(header))
    assert opened.body == b"to everyone" and opened.origin == "laptop-1"
    # A second offer of the same text is a duplicate.
    assert eng.offer("text/plain", b"to everyone").startswith("skipped")


def test_engine_offer_skips_cloud_when_all_acked(tmp_path):
    peer_eng, _, peer_emitted = make_engine(tmp_path / "peer", install="phone")
    fs = FakeFirestore()
    eng, key, _ = make_engine(tmp_path, fs=fs)
    st.save_key(peer_eng.store, key)
    peer_eng.creds(force=True)
    srv = lan.ClipServer(lambda cid, b, ip: peer_eng.receive(cid, b, "LAN", ip), port=0, host="127.0.0.1").start()
    try:
        eng.cfg["peers"] = {"phone": {"addrs": ["127.0.0.1"], "port": srv.port}}
        fs.presence_docs = [{"installId": "phone", "updatedAt": int(time.time() * 1000)}]
        out = eng.offer("text/plain", b"lan only")
        assert "LAN 1/1" in out and "cloud skipped" in out
        assert fs.docs == {}
        assert peer_emitted == [("text/plain", b"lan only")]
    finally:
        srv.stop()


def test_engine_cloud_poll(tmp_path):
    fs = FakeFirestore()
    eng, key, emitted = make_engine(tmp_path, fs=fs)
    now = int(time.time() * 1000)
    for cid, origin, body in (("a", "phone-1", b"one"), ("b", "laptop-1", b"own"), ("c", "tablet", b"two")):
        c = Clip(cid, origin, "x", now + len(cid), "text/plain", body)
        fs.upload_clip(cid, origin, c.created_at, codec.seal(key, c))
    since = eng.poll_once(fs, now - 1000)
    assert [b for _, b in emitted] == [b"one", b"two"]
    assert since >= now
    eng.poll_once(fs, since)
    assert len(emitted) == 2


def test_engine_not_paired(tmp_path):
    eng = ClipEngine(st.MemoryStore(), {"installId": "l", "name": "n", "peers": {}})
    assert eng.offer("text/plain", b"x") == "not paired"
    assert eng.receive("id", b"\0" * 40, "LAN") == 400


# --- pairing ---------------------------------------------------------------------

def _request(secret, **fields):
    body = {"installId": "phone-1", "name": "Galaxy S25 Edge", "key": None, "idToken": None,
            "apiKey": None, "projectId": None}
    body.update(fields)
    return codec.pair_seal(secret, json.dumps(body).encode())


def test_pair_adopts_offered_key_and_signs_in():
    secret, k = os.urandom(32), os.urandom(32)
    store = st.MemoryStore()
    cfg = {"installId": "laptop-1", "name": "Ubuntu laptop", "peers": {}}
    calls = []

    def signin(api_key, token):
        calls.append((api_key, token))
        return {"refreshToken": "rt", "localId": "uid-1", "email": "a@b.c"}

    sealed, summary = pair.handle_pair_request(
        secret, _request(secret, key=b64u_encode(k), idToken="tok", apiKey="AIza", projectId="proj"),
        store, cfg, "10.0.0.9", signin=signin, save_cfg=lambda c: None)
    reply = json.loads(codec.pair_open(secret, sealed))
    assert list(reply) == ["key", "installId", "name", "uid"]
    assert b64u_decode(reply["key"]) == k == st.load_key(store)
    assert reply["uid"] == "uid-1" and reply["installId"] == "laptop-1"
    assert calls == [("AIza", "tok")]
    assert st.load_account(store)["projectId"] == "proj"
    assert cfg["peers"]["phone-1"]["addrs"] == ["10.0.0.9"]


def test_pair_keeps_existing_key_and_account():
    secret, existing = os.urandom(32), os.urandom(32)
    store = st.MemoryStore()
    st.save_key(store, existing)
    st.save_account(store, {"refreshToken": "r", "uid": "u", "apiKey": "a", "projectId": "p"})
    cfg = {"installId": "l", "name": "n", "peers": {}}
    sealed, _ = pair.handle_pair_request(
        secret, _request(secret, key=b64u_encode(os.urandom(32)), idToken="tok", apiKey="a", projectId="p"),
        store, cfg, signin=lambda *a: pytest.fail("must not sign in again"), save_cfg=lambda c: None)
    assert b64u_decode(json.loads(codec.pair_open(secret, sealed))["key"]) == existing


def test_pair_mints_key_when_none_offered():
    secret = os.urandom(32)
    store = st.MemoryStore()
    sealed, summary = pair.handle_pair_request(secret, _request(secret), store,
                                               {"installId": "l", "name": "n"}, save_cfg=lambda c: None)
    reply = json.loads(codec.pair_open(secret, sealed))
    assert len(b64u_decode(reply["key"])) == 32 and reply["uid"] is None


def test_pair_wrong_secret_rejected():
    with pytest.raises(pair.PairError):
        pair.handle_pair_request(os.urandom(32), _request(os.urandom(32)), st.MemoryStore(),
                                 {"installId": "l", "name": "n"}, save_cfg=lambda c: None)
