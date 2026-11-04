"""Never-send and receiving rules (docs/CLIPBOARD.md). Pure, unit-tested."""
from __future__ import annotations

from collections import OrderedDict

from .codec import MIME_JPEG, MIME_PNG, MIME_TEXT, Clip, clip_hash

MAX_TEXT_BYTES = 256 * 1024
MAX_IMAGE_BYTES = 20 * 1024 * 1024
STALE_MS = 10 * 60 * 1000
PRESENCE_FRESH_MS = 7 * 24 * 3600 * 1000
SEEN_IDS = 100
PASSWORD_HINT = "x-kde-passwordManagerHint"


class SeenIds:
    """The last N clip ids, oldest evicted first."""

    def __init__(self, limit: int = SEEN_IDS):
        self.limit = limit
        self._ids: OrderedDict[str, None] = OrderedDict()

    def __contains__(self, clip_id: str) -> bool:
        return clip_id in self._ids

    def add(self, clip_id: str) -> None:
        self._ids[clip_id] = None
        self._ids.move_to_end(clip_id)
        while len(self._ids) > self.limit:
            self._ids.popitem(last=False)

    def __len__(self) -> int:
        return len(self._ids)


def send_refusal(mime: str, body: bytes, last_hash: str | None, mimetypes=()) -> str | None:
    """Why this local clipboard change must NOT be sent, or None to send it."""
    if PASSWORD_HINT in tuple(mimetypes):
        return "password manager hint"
    if mime == MIME_TEXT:
        try:
            text = body.decode("utf-8")
        except UnicodeDecodeError:
            return "text is not UTF-8"
        if not text.strip():
            return "empty text"
        if len(body) > MAX_TEXT_BYTES:
            return "text larger than 256 KiB"
    elif mime in (MIME_PNG, MIME_JPEG):
        if not body:
            return "empty image"
        if len(body) > MAX_IMAGE_BYTES:
            return "image larger than 20 MiB"
    else:
        return f"unsupported mime {mime}"
    if last_hash is not None and clip_hash(mime, body) == last_hash:
        return "same as the last clip sent or received"
    return None


def receive_refusal(clip: Clip, own_install_id: str, seen: SeenIds, now_ms: int) -> str | None:
    """Why an authenticated incoming clip must be ignored, or None to apply it."""
    if clip.origin == own_install_id:
        return "own clip"
    if clip.id in seen:
        return "already seen"
    if now_ms - clip.created_at > STALE_MS:
        return "older than 10 minutes"
    return None


def fresh_presence_peers(presence: list[dict], own_install_id: str, now_ms: int) -> set[str]:
    return {
        p["installId"]
        for p in presence
        if p.get("installId") and p["installId"] != own_install_id
        and now_ms - int(p.get("updatedAt", 0)) <= PRESENCE_FRESH_MS
    }


def cloud_needed(presence_peers: set[str], acked: set[str]) -> bool:
    """Skip the cloud only when every fresh presence peer acknowledged over LAN.

    With no presence peer at all we cannot know nobody is listening, so the cloud
    is used (the vacuous "all of none acked" would otherwise never upload).
    """
    if not presence_peers:
        return True
    return not presence_peers <= acked
