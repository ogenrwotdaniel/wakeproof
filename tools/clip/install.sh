#!/usr/bin/env bash
# Installs the laptop half of Euler's Lab Clip for the current user:
# the GNOME Shell extension and the clipsync user service.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UUID="wakeproof-clip@ogenrwotdaniel"
EXT_SRC="$HERE/extension/$UUID"
EXT_DST="$HOME/.local/share/gnome-shell/extensions/$UUID"
UNIT_SRC="$HERE/systemd/clipsync.service"
UNIT_DIR="$HOME/.config/systemd/user"

if [[ "$HERE" != "$HOME/AndroidStudioProjects/wakeproof/tools/clip" ]]; then
    echo "NOTE: clipsync.service expects the repo at ~/AndroidStudioProjects/wakeproof;" >&2
    echo "      edit WorkingDirectory/PYTHONPATH in $UNIT_SRC if it lives elsewhere." >&2
fi

for mod in cryptography PIL gi; do
    python3 -c "import $mod" 2>/dev/null || { echo "missing python3 module: $mod" >&2; exit 1; }
done
python3 -c "import gi; gi.require_version('Secret', '1')" 2>/dev/null \
    || { echo "missing libsecret bindings: sudo apt install gir1.2-secret-1" >&2; exit 1; }
python3 -c "import zeroconf" 2>/dev/null || echo "note: python3-zeroconf missing, no mDNS (sudo apt install python3-zeroconf)"
python3 -c "import qrcode" 2>/dev/null || echo "note: python3-qrcode missing, needed for pairing (sudo apt install python3-qrcode)"

echo "Installing extension -> $EXT_DST"
mkdir -p "$EXT_DST"
cp -f "$EXT_SRC/metadata.json" "$EXT_SRC/extension.js" "$EXT_DST/"

echo "Linking user unit -> $UNIT_DIR/clipsync.service"
mkdir -p "$UNIT_DIR"
ln -sf "$UNIT_SRC" "$UNIT_DIR/clipsync.service"
systemctl --user daemon-reload
systemctl --user enable --now clipsync.service
systemctl --user --no-pager --lines=5 status clipsync.service || true

if gnome-extensions enable "$UUID" 2>/dev/null; then
    echo "Extension enabled."
else
    # GNOME has not scanned the new extension yet: put it on the enabled list
    # directly, so it loads at the next login with no further command.
    current="$(gsettings get org.gnome.shell enabled-extensions)"
    if [[ "$current" != *"'$UUID'"* ]]; then
        python3 - "$current" "$UUID" <<'PY'
import ast, subprocess, sys
raw, uuid = sys.argv[1], sys.argv[2]
items = [] if raw.strip() in ("@as []", "[]") else ast.literal_eval(raw.replace("@as ", ""))
items.append(uuid)
subprocess.run(["gsettings", "set", "org.gnome.shell", "enabled-extensions", str(items)], check=True)
PY
    fi
    echo "Extension added to the enabled list; GNOME loads it at the next login."
fi

cat <<MSG

Done. On Wayland GNOME Shell only discovers a NEW extension at login:
  1. log out and back in;
  2. if it is not active afterwards: gnome-extensions enable $UUID
Then pair once:  cd $HERE && python3 -m clipsync pair
Check:           python3 -m clipsync status ; journalctl --user -u clipsync -f
MSG
