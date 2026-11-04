"""WakeProof Clip wire format v1 (docs/CLIPBOARD.md, "The sealed clip").

Everything here is pure and byte-exact against the shared vectors in
app/src/test/resources/clip/vectors.json, which the Kotlin side reads too.
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import struct
from dataclasses import dataclass

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

VERSION = 1
NONCE_LEN = 12
CLIP_AAD_PREFIX = "wakeproof-clip-v1|"
PAIR_AAD = b"wakeproof-pair-v1"

MIME_TEXT = "text/plain"
MIME_PNG = "image/png"
MIME_JPEG = "image/jpeg"
MIMES = (MIME_TEXT, MIME_PNG, MIME_JPEG)

CHUNK_BYTES = 700 * 1024
CLOUD_JPEG_THRESHOLD = 2 * 1024 * 1024
CLOUD_JPEG_QUALITY = 90


class ClipError(Exception):
    """A sealed clip that does not decrypt, or whose header is not what it claims."""


def b64u_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64u_decode(text: str) -> bytes:
    text = text.strip()
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


@dataclass(frozen=True)
class Clip:
    id: str
    origin: str
    name: str
    created_at: int
    mime: str
    body: bytes

    def header_bytes(self) -> bytes:
        # Key order, separators and raw non-ASCII are all part of the contract.
        header = {
            "v": VERSION,
            "id": self.id,
            "origin": self.origin,
            "name": self.name,
            "createdAt": int(self.created_at),
            "mime": self.mime,
        }
        return json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def clip_aad(clip_id: str) -> bytes:
    return (CLIP_AAD_PREFIX + clip_id).encode("utf-8")


def seal(key: bytes, clip: Clip, nonce: bytes | None = None) -> bytes:
    """nonce || AES-256-GCM(K, nonce, u32be(len(header)) || header || body, aad)."""
    if len(key) != 32:
        raise ValueError("clip key must be 32 bytes")
    nonce = os.urandom(NONCE_LEN) if nonce is None else nonce
    if len(nonce) != NONCE_LEN:
        raise ValueError("nonce must be 12 bytes")
    header = clip.header_bytes()
    plain = struct.pack(">I", len(header)) + header + clip.body
    return nonce + AESGCM(key).encrypt(nonce, plain, clip_aad(clip.id))


def open_sealed(key: bytes, clip_id: str, sealed: bytes) -> Clip:
    """Decrypts a clip whose id came from the transport. Raises ClipError on any doubt."""
    if len(sealed) < NONCE_LEN + 16 + 4:
        raise ClipError("sealed clip too short")
    try:
        plain = AESGCM(key).decrypt(sealed[:NONCE_LEN], sealed[NONCE_LEN:], clip_aad(clip_id))
    except InvalidTag as e:
        raise ClipError("clip does not decrypt") from e
    (hlen,) = struct.unpack(">I", plain[:4])
    if 4 + hlen > len(plain):
        raise ClipError("header length out of range")
    try:
        header = json.loads(plain[4 : 4 + hlen].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise ClipError("header is not JSON") from e
    if not isinstance(header, dict) or header.get("v") != VERSION:
        raise ClipError("unsupported clip version")
    if header.get("id") != clip_id:
        raise ClipError("header id does not match transport id")
    mime = header.get("mime")
    if mime not in MIMES:
        raise ClipError(f"unsupported mime {mime!r}")
    try:
        return Clip(
            id=clip_id,
            origin=str(header["origin"]),
            name=str(header.get("name", "")),
            created_at=int(header["createdAt"]),
            mime=mime,
            body=plain[4 + hlen :],
        )
    except (KeyError, TypeError, ValueError) as e:
        raise ClipError("header is missing fields") from e


# --- pairing seal -----------------------------------------------------------

def pair_seal(secret: bytes, plain: bytes, nonce: bytes | None = None) -> bytes:
    nonce = os.urandom(NONCE_LEN) if nonce is None else nonce
    return nonce + AESGCM(secret).encrypt(nonce, plain, PAIR_AAD)


def pair_open(secret: bytes, sealed: bytes) -> bytes:
    if len(sealed) < NONCE_LEN + 16:
        raise ClipError("pairing message too short")
    try:
        return AESGCM(secret).decrypt(sealed[:NONCE_LEN], sealed[NONCE_LEN:], PAIR_AAD)
    except InvalidTag as e:
        raise ClipError("pairing message does not decrypt") from e


def qr_text(addrs: list[str], port: int, secret: bytes, name: str) -> str:
    payload = {"a": list(addrs), "p": port, "s": b64u_encode(secret), "n": name}
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return "wpclip1:" + b64u_encode(raw)


def parse_qr_text(text: str) -> dict:
    if not text.startswith("wpclip1:"):
        raise ValueError("not a WakeProof Clip pairing code")
    payload = json.loads(b64u_decode(text[len("wpclip1:") :]).decode("utf-8"))
    return {
        "a": list(payload["a"]),
        "p": int(payload["p"]),
        "s": b64u_decode(payload["s"]),
        "n": payload.get("n", ""),
    }


# --- chunks, cloud images, hashing -----------------------------------------

def split_chunks(sealed: bytes, size: int = CHUNK_BYTES) -> list[bytes]:
    if not sealed:
        return [b""]
    return [sealed[i : i + size] for i in range(0, len(sealed), size)]


def join_chunks(chunks: list[bytes]) -> bytes:
    return b"".join(chunks)


def cloud_body(mime: str, body: bytes) -> tuple[str, bytes]:
    """An image body over 2 MiB is re-encoded JPEG q90 before it goes to the cloud."""
    if not mime.startswith("image/") or len(body) <= CLOUD_JPEG_THRESHOLD:
        return mime, body
    from PIL import Image

    with Image.open(io.BytesIO(body)) as img:
        img.load()
        if img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info):
            rgba = img.convert("RGBA")
            flat = Image.new("RGB", rgba.size, (255, 255, 255))
            flat.paste(rgba, mask=rgba.getchannel("A"))
        else:
            flat = img.convert("RGB")
    out = io.BytesIO()
    flat.save(out, format="JPEG", quality=CLOUD_JPEG_QUALITY)
    return MIME_JPEG, out.getvalue()


def to_png(body: bytes) -> bytes:
    from PIL import Image

    with Image.open(io.BytesIO(body)) as img:
        out = io.BytesIO()
        img.save(out, format="PNG")
    return out.getvalue()


def clip_hash(mime: str, body: bytes) -> str:
    """SHA-256 of mime + body; local only (each device compares against itself)."""
    return hashlib.sha256(mime.encode("utf-8") + body).hexdigest()
