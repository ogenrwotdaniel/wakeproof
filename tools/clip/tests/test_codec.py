import io
import json
import os

import pytest
from PIL import Image

from clipsync import codec
from clipsync.codec import Clip, ClipError, b64u_decode, b64u_encode


def case_clip(c):
    return Clip(c["id"], c["origin"], c["name"], c["createdAt"], c["mime"], b64u_decode(c["body"]))


def test_vectors_seal_byte_for_byte(vectors):
    key = b64u_decode(vectors["key"])
    assert len(vectors["cases"]) >= 2
    for c in vectors["cases"]:
        clip = case_clip(c)
        assert clip.header_bytes() == c["header"].encode("utf-8")
        sealed = codec.seal(key, clip, nonce=b64u_decode(c["nonce"]))
        assert b64u_encode(sealed) == c["sealed"], c["id"]


def test_vectors_open(vectors):
    key = b64u_decode(vectors["key"])
    for c in vectors["cases"]:
        got = codec.open_sealed(key, c["id"], b64u_decode(c["sealed"]))
        assert got == case_clip(c)


def test_non_ascii_header_is_raw_utf8(vectors):
    clip = Clip("x", "o", "Tab S9 FE+ 세상", 1, "text/plain", b"")
    assert "세상".encode("utf-8") in clip.header_bytes()
    assert b"\\u" not in clip.header_bytes()


def test_header_id_mismatch_rejected():
    key = os.urandom(32)
    # Sealed under aad for "good" but the header claims "evil": aad matches transport, header does not.
    clip = Clip("evil", "o", "n", 1, "text/plain", b"hi")
    header = clip.header_bytes()
    import struct
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    nonce = os.urandom(12)
    plain = struct.pack(">I", len(header)) + header + b"hi"
    sealed = nonce + AESGCM(key).encrypt(nonce, plain, codec.clip_aad("good"))
    with pytest.raises(ClipError, match="id"):
        codec.open_sealed(key, "good", sealed)


def test_transport_id_mismatch_fails_aad():
    key = os.urandom(32)
    sealed = codec.seal(key, Clip("a", "o", "n", 1, "text/plain", b"hi"))
    with pytest.raises(ClipError):
        codec.open_sealed(key, "b", sealed)


def test_wrong_key_rejected(vectors):
    c = vectors["cases"][0]
    with pytest.raises(ClipError):
        codec.open_sealed(os.urandom(32), c["id"], b64u_decode(c["sealed"]))


def test_tampered_rejected(vectors):
    key = b64u_decode(vectors["key"])
    c = vectors["cases"][0]
    sealed = bytearray(b64u_decode(c["sealed"]))
    sealed[20] ^= 1
    with pytest.raises(ClipError):
        codec.open_sealed(key, c["id"], bytes(sealed))


def test_pair_vector(vectors):
    p = vectors["pair"]
    secret = b64u_decode(p["secret"])
    sealed = codec.pair_seal(secret, p["plain"].encode(), nonce=b64u_decode(p["nonce"]))
    assert b64u_encode(sealed) == p["sealed"]
    assert codec.pair_open(secret, b64u_decode(p["sealed"])).decode() == p["plain"]


def test_qr_round_trip(vectors):
    text = vectors["qr"]["text"]
    parsed = codec.parse_qr_text(text)
    assert codec.qr_text(parsed["a"], parsed["p"], parsed["s"], parsed["n"]) == text
    assert parsed["p"] == 47322
    assert len(parsed["s"]) == 32
    assert parsed["s"] == b64u_decode(vectors["pair"]["secret"])


def test_b64u_no_padding():
    for n in range(0, 10):
        data = os.urandom(n)
        enc = b64u_encode(data)
        assert "=" not in enc and "+" not in enc and "/" not in enc
        assert b64u_decode(enc) == data


def test_chunk_split_join():
    data = os.urandom(codec.CHUNK_BYTES * 2 + 123)
    chunks = codec.split_chunks(data)
    assert [len(c) for c in chunks] == [codec.CHUNK_BYTES, codec.CHUNK_BYTES, 123]
    assert codec.join_chunks(chunks) == data
    assert codec.split_chunks(b"x" * codec.CHUNK_BYTES) == [b"x" * codec.CHUNK_BYTES]
    assert codec.CHUNK_BYTES == 700 * 1024


def _png(w, h, noise=True):
    img = Image.frombytes("RGB", (w, h), os.urandom(w * h * 3)) if noise else Image.new("RGBA", (w, h), (1, 2, 3, 128))
    out = io.BytesIO()
    img.save(out, format="PNG")
    return out.getvalue()


def test_jpeg_reencode_above_threshold():
    big = _png(1000, 1000)
    assert len(big) > codec.CLOUD_JPEG_THRESHOLD
    mime, body = codec.cloud_body("image/png", big)
    assert mime == "image/jpeg"
    assert body[:3] == b"\xff\xd8\xff"
    with Image.open(io.BytesIO(body)) as img:
        assert img.size == (1000, 1000)


def test_no_reencode_at_or_below_threshold():
    small = _png(64, 64)
    assert codec.cloud_body("image/png", small) == ("image/png", small)
    text = b"x" * (codec.CLOUD_JPEG_THRESHOLD + 10)
    assert codec.cloud_body("text/plain", text) == ("text/plain", text)


def test_reencode_flattens_alpha(monkeypatch):
    monkeypatch.setattr(codec, "CLOUD_JPEG_THRESHOLD", 10)
    mime, body = codec.cloud_body("image/png", _png(50, 50, noise=False))
    assert mime == "image/jpeg"


def test_clip_hash_depends_on_mime_and_body():
    assert codec.clip_hash("text/plain", b"a") == codec.clip_hash("text/plain", b"a")
    assert codec.clip_hash("text/plain", b"a") != codec.clip_hash("image/png", b"a")
    assert codec.clip_hash("text/plain", b"a") != codec.clip_hash("text/plain", b"b")
