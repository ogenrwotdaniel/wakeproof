# WakeProof Clip — laptop side

One clipboard across this Ubuntu laptop and the WakeProof phone and tablet (text and
images). The wire protocol is `docs/CLIPBOARD.md`; it is the contract with the Android
half, and both test suites read `app/src/test/resources/clip/vectors.json`.

## Architecture

```
GNOME Shell extension  --Offer(s mime, ay data)-->   clipsync daemon  --LAN :47321-->  phone / tablet
(watches + sets the    <--Incoming(s mime, ay data)-- (key, crypto,    --Firestore--->  (fallback)
 clipboard; no network)        session D-Bus          LAN, cloud poll)
```

- GNOME Wayland lets nothing but the Shell watch the clipboard (`wl-paste --watch` fails
  here), so `extension/wakeproof-clip@ogenrwotdaniel` does only that: on each CLIPBOARD
  owner change it reads PNG/JPEG, else UTF-8 text, skips password-manager clips
  (`x-kde-passwordManagerHint`) and copied files, and calls `Offer`. It sets the clipboard
  on `Incoming` and skips the owner change that this causes. It also adds the
  **WakeProof Clip** Quick Settings tile (see below), which talks only to the daemon's
  local control methods.
- `clipsync/` (Python, stdlib + cryptography, Pillow, gi; zeroconf and qrcode optional):
  - `codec.py` seal/open (AES-256-GCM), chunking, JPEG re-encode for the cloud, hashes
  - `rules.py` never-send and receiving rules
  - `lan.py` HTTP server on 47321, parallel sender (1.5 s connect), mDNS
  - `cloud.py` Firestore REST under `users/{uid}/clips`, token refresh, presence, housekeeping
  - `pair.py` `PairingServer`: one-shot `POST /pair` on 47322, shared by `clipsync pair`
    (terminal QR) and the daemon's `StartPairing` (QR PNG for the tile)
  - `daemon.py` owns `com.ogenrwotdaniel.WakeProofClip`; polls the cloud every 4 s (30 s
    while the screen is locked). Received images are also saved to
    `~/Pictures/WakeProof Clip/`.
- Secrets (clip key, Firebase refresh token) live in GNOME Keyring, schema
  `com.ogenrwotdaniel.WakeProofClip`. `~/.config/wakeproof-clip/config.json` holds only the
  install id, the device name and the addresses of paired devices.

## Install

```
sudo apt install python3-zeroconf python3-qrcode   # optional but recommended
tools/clip/install.sh
```

It copies the extension to `~/.local/share/gnome-shell/extensions`, links
`systemd/clipsync.service` into `~/.config/systemd/user`, enables the service and the
extension (adding it to `org.gnome.shell enabled-extensions` if GNOME has not scanned it
yet). **A new extension on Wayland is only picked up after logging out and back in.**
After changing `extension.js`, rerun `install.sh` and log out and in again.
The unit assumes the repo is at `~/AndroidStudioProjects/wakeproof`.

## The Quick Settings tile (no terminal needed)

Top-right menu → **WakeProof Clip** tile (clipboard icon), next to GSConnect.

- **Tile** — click to switch sharing on/off. Off means nothing is sent, nothing received
  is applied, the cloud poll stops and the LAN server answers `503` (phones then fall
  back to the cloud, which this laptop ignores while off). Kept in `config.json`
  (`"enabled"`, default on) across restarts.
- **Subtitle** — `Paired · Galaxy S25 Edge 2 min ago`, `Not paired`, `Off`, or
  `Service not running`.
- **Arrow** — every known device with when it was last seen and whether it is on the LAN,
  the cloud account, then:
  - **Pair a device…** opens a QR on screen with a 5-minute countdown. On the phone or
    tablet: WakeProof → Settings → Clipboard across devices → Pair a computer, scan it.
    The dialog says "Paired with …" and closes itself; the daemon uses the new key at
    once (no restart). Cancel closes the pairing window. While it is open a clipboard
    icon shows in the top bar.
  - **Open received images** opens `~/Pictures/WakeProof Clip/`.
  - **Start service** (only when the daemon is not running) runs
    `systemctl --user start clipsync`.

Device "last seen" comes from presence docs and from LAN contacts. Presence is re-read
at most once a minute, and only when the tile asks (menu opened); sending still uses its
own 5-minute cache. The tile adds no other Firestore reads.

## Pair

Use the tile (above), or from a terminal:

```
cd tools/clip && python3 -m clipsync pair
```

On the phone: Settings → Clipboard across devices → Pair a computer, scan the QR. Pair the
tablet the same way (it then gets the same key). Both devices must be on one network for
this step only. `python3 -m clipsync status` shows the result; `forget` removes the key
and account from this laptop; `forget --cloud` first deletes every clip and device record the
account has in the cloud, to start over (then use "Forget pairing" on each phone or tablet). The terminal `pair` and the tile share port 47322, so only
one can be open at a time.

## Commands

`python3 -m clipsync {daemon,pair,status,forget [--cloud],send-test TEXT}` (`-v` for debug logs).
`send-test` goes through the running daemon if there is one, else sends in-process.

D-Bus control surface used by the tile (local to this laptop, not part of
`docs/CLIPBOARD.md`): `GetStatus() -> s` (JSON: `enabled`, `paired`, `signedIn`, `email`,
`name`, `imagesDir`, `peers[{installId,name,platform,lastSeenMs,lan}]`,
`pairing{active,qrText,expiresAtMs,result,warning}` with `result` `null` / `"ok:<device>"`
/ `"error:<message>"`), `SetEnabled(b)`, `StartPairing() -> s qrText` (returns the open
session if one is active), `CancelPairing()`, `RenderQr(s) -> s pngPath`
(`$XDG_RUNTIME_DIR/wakeproof-clip-qr.png`), signal `StatusChanged(s json)`.

```
gdbus call --session --dest com.ogenrwotdaniel.WakeProofClip \
  --object-path /com/ogenrwotdaniel/WakeProofClip \
  --method com.ogenrwotdaniel.WakeProofClip.GetStatus
```

## Troubleshooting

- Daemon logs: `journalctl --user -u clipsync -f` (`systemctl --user restart clipsync`).
- Is the daemon on the bus: `gdbus introspect --session --dest com.ogenrwotdaniel.WakeProofClip --object-path /com/ogenrwotdaniel/WakeProofClip`
- Extension: `gnome-extensions info wakeproof-clip@ogenrwotdaniel`; errors appear in
  Looking Glass (Alt+F2, `lg`, Extensions tab) and in `journalctl --user -f /usr/bin/gnome-shell`.
- Nothing arrives over LAN: `curl http://<phone-ip>:47321/ping` should answer
  `wakeproof-clip`. Campus Wi-Fi isolates clients; then the cloud carries it (needs the
  account sent at pairing — `status` says whether cloud is on).
- "not paired" in the log: run `pair`; the daemon re-reads the keyring every 30 s.

## Tests

`cd tools/clip && python3 -m pytest -q` (no network; the keyring is not touched).
