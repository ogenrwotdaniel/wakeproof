"""Firestore REST transport, `users/{uid}/clips/{docId}` (docs/CLIPBOARD.md, "Cloud transport").

Only stdlib urllib. The REST API has no listen call, so the daemon polls runQuery.
"""
from __future__ import annotations

import base64
import json
import logging
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from .codec import join_chunks, split_chunks

log = logging.getLogger("clipsync.cloud")

FIRESTORE = "https://firestore.googleapis.com/v1"
IDP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key="
TOKEN_URL = "https://securetoken.googleapis.com/v1/token?key="
CLIP_TTL_MS = 3600 * 1000
KEEP_OWN_CLIPS = 10
LAN_PORT = 47321


class CloudError(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def _http(method: str, url: str, body=None, headers=None, timeout=30.0):
    data = None
    headers = dict(headers or {})
    if body is not None:
        if isinstance(body, (bytes, bytearray)):
            data = bytes(body)
        else:
            data = json.dumps(body).encode("utf-8")
            headers.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as e:
        detail = e.read()[:500].decode("utf-8", "replace")
        raise CloudError(f"{method} {url.split('?')[0]} -> {e.code}: {detail}", e.code) from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise CloudError(f"{method} {url.split('?')[0]} failed: {e}") from e
    return json.loads(raw) if raw else None


# --- Firestore value encoding -------------------------------------------------

def to_value(v) -> dict:
    if v is None:
        return {"nullValue": None}
    if isinstance(v, bool):
        return {"booleanValue": v}
    if isinstance(v, int):
        return {"integerValue": str(v)}
    if isinstance(v, float):
        return {"doubleValue": v}
    if isinstance(v, str):
        return {"stringValue": v}
    if isinstance(v, (bytes, bytearray)):
        return {"bytesValue": base64.b64encode(bytes(v)).decode("ascii")}
    if isinstance(v, (list, tuple)):
        return {"arrayValue": {"values": [to_value(x) for x in v]}}
    if isinstance(v, dict):
        return {"mapValue": {"fields": encode_fields(v)}}
    raise TypeError(f"cannot encode {type(v).__name__} for Firestore")


def from_value(v: dict):
    if "nullValue" in v:
        return None
    if "booleanValue" in v:
        return bool(v["booleanValue"])
    if "integerValue" in v:
        return int(v["integerValue"])
    if "doubleValue" in v:
        return float(v["doubleValue"])
    if "stringValue" in v:
        return v["stringValue"]
    if "bytesValue" in v:
        return base64.b64decode(v["bytesValue"])
    if "timestampValue" in v:
        return v["timestampValue"]
    if "arrayValue" in v:
        return [from_value(x) for x in v["arrayValue"].get("values", [])]
    if "mapValue" in v:
        return decode_fields(v["mapValue"].get("fields", {}))
    raise ValueError(f"unknown Firestore value {list(v)}")


def encode_fields(d: dict) -> dict:
    return {k: to_value(v) for k, v in d.items()}


def decode_fields(fields: dict) -> dict:
    return {k: from_value(v) for k, v in (fields or {}).items()}


# --- doc shapes ---------------------------------------------------------------

def header_doc_id(clip_id: str) -> str:
    return f"clip_{clip_id}"


def chunk_doc_id(clip_id: str, index: int) -> str:
    return f"chunk_{clip_id}_{index}"


def presence_doc_id(install_id: str) -> str:
    return f"presence_{install_id}"


def clip_docs(clip_id: str, origin: str, created_at: int, sealed: bytes):
    """[(docId, fields)] — every chunk first, the header last (write order matters)."""
    expires = created_at + CLIP_TTL_MS
    chunks = split_chunks(sealed)
    docs = [
        (chunk_doc_id(clip_id, i), {"id": clip_id, "index": i, "data": c, "expiresAt": expires})
        for i, c in enumerate(chunks)
    ]
    docs.append((header_doc_id(clip_id), {
        "id": clip_id, "origin": origin, "createdAt": created_at,
        "chunks": len(chunks), "size": len(sealed), "expiresAt": expires,
    }))
    return docs


def housekeeping_victims(own_headers: list[dict], now_ms: int, keep: int = KEEP_OWN_CLIPS) -> list[dict]:
    """Own headers that are expired, or beyond the newest `keep`."""
    ordered = sorted(own_headers, key=lambda h: int(h.get("createdAt", 0)), reverse=True)
    return [h for i, h in enumerate(ordered) if i >= keep or int(h.get("expiresAt", 0)) < now_ms]


# --- auth -----------------------------------------------------------------------

def sign_in_with_idp(api_key: str, google_id_token: str) -> dict:
    body = {
        "postBody": "id_token=" + urllib.parse.quote(google_id_token, safe="") + "&providerId=google.com",
        "requestUri": "http://localhost",
        "returnSecureToken": True,
        "returnIdpCredential": True,
    }
    return _http("POST", IDP_URL + urllib.parse.quote(api_key, safe=""), body)


class Auth:
    """Turns the stored refresh token into short-lived ID tokens."""

    def __init__(self, api_key: str, refresh_token: str):
        self.api_key = api_key
        self.refresh_token = refresh_token
        self._token = None
        self._expires = 0.0
        self._lock = threading.Lock()

    def id_token(self) -> str:
        with self._lock:
            if self._token and time.time() < self._expires - 120:
                return self._token
            form = urllib.parse.urlencode(
                {"grant_type": "refresh_token", "refresh_token": self.refresh_token}
            ).encode("ascii")
            resp = _http("POST", TOKEN_URL + urllib.parse.quote(self.api_key, safe=""), form,
                         {"Content-Type": "application/x-www-form-urlencoded"})
            self._token = resp["id_token"]
            self._expires = time.time() + int(resp.get("expires_in", 3600))
            if resp.get("refresh_token"):
                self.refresh_token = resp["refresh_token"]
            return self._token

    def invalidate(self):
        with self._lock:
            self._token = None


class Firestore:
    def __init__(self, auth: Auth, project_id: str, uid: str):
        self.auth = auth
        self.project = project_id
        self.uid = uid
        self.root = f"projects/{project_id}/databases/(default)/documents"
        self.parent = f"{self.root}/users/{urllib.parse.quote(uid, safe='')}"

    def _name(self, doc_id: str) -> str:
        return f"{self.parent}/clips/{urllib.parse.quote(doc_id, safe='')}"

    def _call(self, method, path, body=None):
        url = f"{FIRESTORE}/{path}"
        for attempt in (0, 1):
            headers = {"Authorization": "Bearer " + self.auth.id_token()}
            try:
                return _http(method, url, body, headers)
            except CloudError as e:
                if e.status == 401 and attempt == 0:
                    self.auth.invalidate()
                    continue
                raise

    def put(self, doc_id: str, fields: dict) -> None:
        self._call("PATCH", self._name(doc_id), {"fields": encode_fields(fields)})

    def delete(self, doc_id: str) -> None:
        try:
            self._call("DELETE", self._name(doc_id))
        except CloudError as e:
            if e.status != 404:
                raise

    def query(self, structured: dict) -> list[tuple[str, dict]]:
        resp = self._call("POST", f"{self.parent}:runQuery", {"structuredQuery": structured}) or []
        out = []
        for row in resp:
            doc = row.get("document")
            if doc:
                out.append((doc["name"].rsplit("/", 1)[-1], decode_fields(doc.get("fields", {}))))
        return out

    def batch_get(self, doc_ids: list[str]) -> dict[str, dict]:
        if not doc_ids:
            return {}
        resp = self._call("POST", f"{self.root}:batchGet",
                          {"documents": [self._name(d) for d in doc_ids]}) or []
        out = {}
        for row in resp:
            found = row.get("found")
            if found:
                out[urllib.parse.unquote(found["name"].rsplit("/", 1)[-1])] = decode_fields(found.get("fields", {}))
        return out

    # --- clip operations ---

    def upload_clip(self, clip_id: str, origin: str, created_at: int, sealed: bytes) -> None:
        for doc_id, fields in clip_docs(clip_id, origin, created_at, sealed):
            self.put(doc_id, fields)

    def headers_since(self, since_ms: int) -> list[dict]:
        rows = self.query({
            "from": [{"collectionId": "clips"}],
            "where": {"fieldFilter": {"field": {"fieldPath": "createdAt"}, "op": "GREATER_THAN",
                                      "value": to_value(int(since_ms))}},
            "orderBy": [{"field": {"fieldPath": "createdAt"}, "direction": "ASCENDING"}],
        })
        return [f for doc_id, f in rows if doc_id.startswith("clip_")]

    def fetch_sealed(self, header: dict) -> bytes:
        clip_id, n = header["id"], int(header["chunks"])
        ids = [chunk_doc_id(clip_id, i) for i in range(n)]
        docs = self.batch_get(ids)
        missing = [d for d in ids if d not in docs]
        if missing:
            raise CloudError(f"clip {clip_id}: missing chunks {missing}")
        sealed = join_chunks([docs[d]["data"] for d in ids])
        if len(sealed) != int(header["size"]):
            raise CloudError(f"clip {clip_id}: size {len(sealed)} != {header['size']}")
        return sealed

    def own_headers(self, install_id: str) -> list[dict]:
        # Equality only, no orderBy: needs no composite index. Sorted locally.
        rows = self.query({
            "from": [{"collectionId": "clips"}],
            "where": {"fieldFilter": {"field": {"fieldPath": "origin"}, "op": "EQUAL",
                                      "value": to_value(install_id)}},
        })
        return [f for doc_id, f in rows if doc_id.startswith("clip_")]

    def housekeep(self, install_id: str, now_ms: int) -> int:
        victims = housekeeping_victims(self.own_headers(install_id), now_ms)
        for h in victims:
            # Header first, so no reader ever sees a header whose chunks are gone.
            self.delete(header_doc_id(h["id"]))
            for i in range(int(h.get("chunks", 0))):
                self.delete(chunk_doc_id(h["id"], i))
        return len(victims)

    def put_presence(self, install_id: str, name: str, addrs: list[str], now_ms: int) -> None:
        self.put(presence_doc_id(install_id), {
            "installId": install_id, "name": name, "platform": "linux",
            "lanAddrs": list(addrs), "port": LAN_PORT, "updatedAt": now_ms,
        })

    def delete_presence(self, install_id: str) -> None:
        self.delete(presence_doc_id(install_id))

    def presence(self, since_ms: int) -> list[dict]:
        # Only presence docs carry updatedAt.
        rows = self.query({
            "from": [{"collectionId": "clips"}],
            "where": {"fieldFilter": {"field": {"fieldPath": "updatedAt"}, "op": "GREATER_THAN",
                                      "value": to_value(int(since_ms))}},
        })
        return [f for doc_id, f in rows if doc_id.startswith("presence_")]


def firestore_for(account: dict) -> Firestore:
    return Firestore(Auth(account["apiKey"], account["refreshToken"]), account["projectId"], account["uid"])
