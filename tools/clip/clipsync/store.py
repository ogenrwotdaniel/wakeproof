"""Secrets in GNOME Keyring (libsecret) and non-secret config in ~/.config.

Keyring schema `com.ogenrwotdaniel.WakeProofClip`, attribute `item`:
  item=clip-key  -> base64url K
  item=account   -> JSON {refreshToken, uid, email, apiKey, projectId}
"""
from __future__ import annotations

import json
import os
import tempfile
import uuid
from pathlib import Path

from .codec import b64u_decode, b64u_encode

SCHEMA_NAME = "com.ogenrwotdaniel.WakeProofClip"
ITEM_KEY = "clip-key"
ITEM_ACCOUNT = "account"
DEFAULT_NAME = "Ubuntu laptop"


class KeyringStore:
    """libsecret, imported lazily so the package loads without gi."""

    def __init__(self):
        import gi

        gi.require_version("Secret", "1")
        from gi.repository import Secret

        self._Secret = Secret
        self._schema = Secret.Schema.new(
            SCHEMA_NAME, Secret.SchemaFlags.NONE, {"item": Secret.SchemaAttributeType.STRING}
        )

    def get(self, item: str) -> str | None:
        return self._Secret.password_lookup_sync(self._schema, {"item": item}, None)

    def set(self, item: str, value: str) -> None:
        ok = self._Secret.password_store_sync(
            self._schema, {"item": item}, self._Secret.COLLECTION_DEFAULT,
            f"WakeProof Clip ({item})", value, None,
        )
        if not ok:
            raise RuntimeError(f"keyring refused to store {item}")

    def delete(self, item: str) -> None:
        self._Secret.password_clear_sync(self._schema, {"item": item}, None)


class MemoryStore:
    """Same interface as KeyringStore, for tests."""

    def __init__(self):
        self.items: dict[str, str] = {}

    def get(self, item):
        return self.items.get(item)

    def set(self, item, value):
        self.items[item] = value

    def delete(self, item):
        self.items.pop(item, None)


def load_key(store) -> bytes | None:
    raw = store.get(ITEM_KEY)
    if not raw:
        return None
    key = b64u_decode(raw)
    return key if len(key) == 32 else None


def save_key(store, key: bytes) -> None:
    store.set(ITEM_KEY, b64u_encode(key))


def load_account(store) -> dict | None:
    raw = store.get(ITEM_ACCOUNT)
    if not raw:
        return None
    try:
        acct = json.loads(raw)
    except json.JSONDecodeError:
        return None
    needed = ("refreshToken", "uid", "apiKey", "projectId")
    return acct if all(acct.get(k) for k in needed) else None


def save_account(store, account: dict) -> None:
    store.set(ITEM_ACCOUNT, json.dumps(account, separators=(",", ":")))


# --- config.json -------------------------------------------------------------

def config_path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return Path(base) / "wakeproof-clip" / "config.json"


def load_config(path: Path | None = None) -> dict:
    """Reads (and on first use creates) the config; always has installId and name."""
    path = path or config_path()
    try:
        cfg = json.loads(path.read_text("utf-8"))
        if not isinstance(cfg, dict):
            cfg = {}
    except (OSError, json.JSONDecodeError):
        cfg = {}
    changed = False
    if not cfg.get("installId"):
        cfg["installId"] = str(uuid.uuid4())
        changed = True
    if not cfg.get("name"):
        cfg["name"] = DEFAULT_NAME
        changed = True
    cfg.setdefault("peers", {})
    if changed:
        save_config(cfg, path)
    return cfg


def save_config(cfg: dict, path: Path | None = None) -> None:
    path = path or config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".config.")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    os.replace(tmp, path)
