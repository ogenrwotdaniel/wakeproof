from clipsync.codec import Clip, clip_hash
from clipsync.rules import (MAX_IMAGE_BYTES, MAX_TEXT_BYTES, PASSWORD_HINT, STALE_MS, SeenIds,
                            cloud_needed, fresh_presence_peers, receive_refusal, send_refusal)

NOW = 1_762_200_000_000


def test_send_ok():
    assert send_refusal("text/plain", b"hello", None) is None
    assert send_refusal("image/png", b"\x89PNG", None) is None


def test_never_send_password_hint():
    assert send_refusal("text/plain", b"hunter2", None, mimetypes=["text/plain", PASSWORD_HINT])


def test_never_send_blank_text():
    assert send_refusal("text/plain", b"", None)
    assert send_refusal("text/plain", b" \n\t ", None)


def test_never_send_duplicate_of_last():
    h = clip_hash("text/plain", b"same")
    assert send_refusal("text/plain", b"same", h)
    assert send_refusal("text/plain", b"other", h) is None
    # Same bytes, different mime: not the same clip.
    assert send_refusal("image/png", b"same", h) is None


def test_size_limits_and_mime():
    assert send_refusal("text/plain", b"a" * MAX_TEXT_BYTES, None) is None
    assert send_refusal("text/plain", b"a" * (MAX_TEXT_BYTES + 1), None)
    assert send_refusal("image/png", b"a" * (MAX_IMAGE_BYTES + 1), None)
    assert send_refusal("application/pdf", b"%PDF", None)
    assert send_refusal("text/plain", b"\xff\xfe", None)


def clip(origin="phone-1", created=NOW, cid="c1"):
    return Clip(cid, origin, "Phone", created, "text/plain", b"x")


def test_receive_own_origin_ignored():
    assert receive_refusal(clip(origin="me"), "me", SeenIds(), NOW)


def test_receive_seen_ignored():
    seen = SeenIds()
    seen.add("c1")
    assert receive_refusal(clip(), "me", seen, NOW)


def test_receive_stale_rule():
    assert receive_refusal(clip(created=NOW - STALE_MS), "me", SeenIds(), NOW) is None
    assert receive_refusal(clip(created=NOW - STALE_MS - 1), "me", SeenIds(), NOW)
    # A sender clock ahead of ours is not stale.
    assert receive_refusal(clip(created=NOW + 60_000), "me", SeenIds(), NOW) is None


def test_seen_ids_keeps_last_100():
    seen = SeenIds()
    for i in range(150):
        seen.add(f"id{i}")
    assert len(seen) == 100
    assert "id49" not in seen and "id50" in seen and "id149" in seen


def test_cloud_decision():
    presence = [
        {"installId": "phone", "updatedAt": NOW - 1000},
        {"installId": "tablet", "updatedAt": NOW - 8 * 24 * 3600 * 1000},  # stale: ignored
        {"installId": "me", "updatedAt": NOW},
    ]
    peers = fresh_presence_peers(presence, "me", NOW)
    assert peers == {"phone"}
    assert cloud_needed(peers, {"phone"}) is False
    assert cloud_needed(peers, set()) is True
    assert cloud_needed(set(), set()) is True  # nobody known: use the cloud
