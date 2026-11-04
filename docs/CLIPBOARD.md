# WakeProof Clip — wire protocol v1

One clipboard across the Ubuntu laptop, the phone and the tablet. Text and images only.
This file is the CONTRACT between the Kotlin side (`app/.../clip/`) and the Python /
GNOME side (`tools/clip/`). Change it here first, then both sides, then regenerate the
shared test vectors (`app/src/test/resources/clip/vectors.json`), which BOTH test suites read.

## Why it is shaped like this

- **GNOME Wayland does not let a background process watch the clipboard**
  (`wl-paste --watch` fails: no wlroots data-control protocol). Only a GNOME Shell
  extension can. So the laptop side is an extension (eyes and hands) + a Python
  daemon (crypto, network) talking over D-Bus.
- **Android lets an app READ the clipboard only while it is focused.** WakeProof is the
  HOME app, so every return to the home screen is a moment it may read. Writing the
  clipboard from the background is allowed.
- **Campus Wi-Fi isolates clients**, so LAN is tried first and Firestore is the
  fallback. Firestore docs hold ≤ 1 MiB; Cloud Storage would need the Blaze plan, so
  images are chunked across docs.
- **End-to-end encrypted.** Firestore sees ciphertext only. The clip key travels only
  inside the pairing exchange (QR + LAN), never through the cloud.

## Keys and identities

- `K` — the clip key, 32 random bytes, shared by every paired device. Stored on
  Android in EncryptedSharedPreferences, on Linux in GNOME Keyring (libsecret,
  schema `com.ogenrwotdaniel.WakeProofClip`, attribute `item` = `clip-key`).
- `installId` — each device's stable id (Android: `SyncPreferences.installId()`;
  Linux: uuid4 stored in `~/.config/wakeproof-clip/config.json`).
- `name` — human label ("Galaxy S25 Edge", "Tab S9 FE+", "Ubuntu laptop").
- base64 below is ALWAYS base64url without padding (RFC 4648 §5) unless it is a
  Firestore `bytesValue` (standard base64, which the REST API requires).

## The sealed clip (identical on LAN and in the cloud)

```
header  = UTF-8 JSON, keys in this order, no whitespace, non-ASCII written raw (not \u-escaped):
          {"v":1,"id":"<uuid4>","origin":"<installId>","name":"<name>",
           "createdAt":<epoch ms>,"mime":"<mime>"}
mime    ∈ "text/plain" (body is UTF-8) | "image/png" | "image/jpeg"
plain   = uint32 big-endian len(header) || header || body
nonce   = 12 random bytes
aad     = UTF-8 "wakeproof-clip-v1|" + id
sealed  = nonce || AES-256-GCM(K, nonce, plain, aad)   (ciphertext || 16-byte tag)
```

The receiver reads `id` from the transport (LAN header / Firestore doc), rebuilds the
aad, decrypts, and REJECTS the clip if decryption fails or the header's `id` differs.
Successful decryption is the only authentication — a sender without K cannot forge one.

Limits: text ≤ 256 KiB, image ≤ 20 MiB over LAN. Over the cloud an image whose body is
larger than 2 MiB is re-encoded as JPEG quality 90 (header `mime` becomes image/jpeg)
BEFORE sealing; LAN always carries the original.

Never sent: Android clips with `ClipDescription.EXTRA_IS_SENSITIVE`; Linux clips whose
mimetypes include `x-kde-passwordManagerHint` (value `secret`); empty/whitespace text;
a clip identical (SHA-256 of mime+body) to the last one this device sent or received.

## Receiving rules (both sides)

- Ignore `origin == own installId`.
- Dedupe by `id` (remember the last 100 ids).
- Ignore clips with `createdAt` older than 10 minutes on arrival (stale backlog after a
  device was off must not overwrite today's clipboard).
- Text → clipboard. Image → clipboard AND, on Linux, also saved to
  `~/Pictures/WakeProof Clip/<yyyy-MM-dd HH-mm-ss> from <name>.<png|jpg>`.
- Remember the received clip's hash so the clipboard change it causes is NOT sent back.

## LAN transport

- TCP port **47321**, plain HTTP/1.1 (the payload is already sealed).
- `POST /clip` with headers `X-Clip-Id: <id>`, `Content-Type: application/octet-stream`,
  body = `sealed`. Reply `204` on success, `400` if it does not decrypt, `413` if too big.
- `GET /ping` → `200` body `wakeproof-clip` (reachability probe).
- Discovery: mDNS/DNS-SD service type `_wakeproofclip._tcp`, TXT `id=<installId>`,
  `name=<name>`; PLUS the `lanAddrs` in each peer's presence doc (mDNS is often blocked).
- Sender: try every known peer address with a 1.5 s connect timeout, in parallel.

## Cloud transport — `users/{uid}/clips/{docId}` (flat; existing rules cover it)

| docId | fields |
| --- | --- |
| `clip_<id>` | `id` (string), `origin` (string), `createdAt` (integer ms), `chunks` (integer), `size` (integer, total sealed bytes), `expiresAt` (integer ms = createdAt + 1 h) |
| `chunk_<id>_<i>` | `id`, `index` (integer, 0-based), `data` (bytes, ≤ 700 KiB slice of `sealed`), `expiresAt` |
| `presence_<installId>` | `installId`, `name`, `platform` ("android" / "linux"), `lanAddrs` (array of strings), `port` (47321), `updatedAt` (integer ms) |

- Write ALL chunks first, then the `clip_` header doc. A reader that sees the header can
  therefore always fetch every chunk.
- Only `clip_` docs carry `createdAt`, so the query `where createdAt > since orderBy
  createdAt` returns headers only and needs no composite index.
- Sender skips the cloud when every peer with a presence doc updated in the last 7 days
  acknowledged over LAN (204).
- Housekeeping: on each send, the sender deletes its OWN header+chunks with
  `expiresAt < now` and all but its 10 newest clips.
- Presence is rewritten on start, on network change, and at most every 30 min.
- Android listens with a snapshot listener (inside `AppBlockService`). The laptop has no
  listen API over REST, so it polls `runQuery` every 4 s while the session is active and
  every 30 s while the screen is locked (an empty query still costs one read; ~20k reads
  a day worst case, inside the free 50k).

## Pairing (once per device, needs one shared network)

1. Laptop: `clipsync pair` prints a QR (terminal) whose text is
   `wpclip1:` + base64url(JSON `{"a":["<ipv4>",...],"p":47322,"s":"<b64url 32 bytes>","n":"<name>"}`).
   It listens on TCP **47322** for 5 minutes, one successful pairing, then exits.
2. Phone/tablet: Settings → Clipboard across devices → Pair a computer → scans the QR
   (ML Kit barcode scanning), then `POST http://<a>:47322/pair` with body
   `nonce(12) || AES-256-GCM(S, nonce, request_json, aad="wakeproof-pair-v1")`, where
   request_json =
   `{"installId","name","key":"<b64url K or null>","idToken":"<Google ID token or null>",
     "apiKey":"<Firebase web API key>","projectId":"<Firebase project id>"}`.
3. Laptop: `K_final` = its existing K if it has one, else the request's K, else 32 new
   random bytes. If `idToken` is present and the laptop is not signed in, it calls
   `POST https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=<apiKey>`
   `{"postBody":"id_token=<idToken>&providerId=google.com","requestUri":"http://localhost",
     "returnSecureToken":true,"returnIdpCredential":true}`
   and stores `refreshToken`, `localId` (= uid), `email`, apiKey and projectId. Fresh ID
   tokens come from `POST https://securetoken.googleapis.com/v1/token?key=<apiKey>`
   (`grant_type=refresh_token`).
4. Laptop replies `200` with `nonce || AES-256-GCM(S, nonce, {"key":"<b64url K_final>",
   "installId","name","uid"}, aad="wakeproof-pair-v1")`. The device adopts K_final.
   The tablet pairs by scanning the laptop's QR the same way and so gets the same K.
5. Forget (either side) deletes the local K and stops the feature on that device only.

## D-Bus between the GNOME extension and the daemon

Session bus name `com.ogenrwotdaniel.WakeProofClip`, object `/com/ogenrwotdaniel/WakeProofClip`,
interface `com.ogenrwotdaniel.WakeProofClip`, owned by the DAEMON:

- method `Offer(s mime, ay data)` — the extension calls it on every clipboard owner change.
- signal `Incoming(s mime, ay data)` — the extension sets the clipboard on it.

The extension does no networking and holds no key.
