// Euler's Lab Clip — the clipboard's eyes and hands on GNOME Wayland.
//
// A background process cannot watch the clipboard on GNOME Wayland, so this
// extension does only that: on every CLIPBOARD owner change it reads the new
// content and hands it to the clipsync daemon over D-Bus (Offer), and when the
// daemon emits Incoming it sets the clipboard. No networking, no key.
// Wire contract: docs/CLIPBOARD.md.
//
// It also adds an "Euler's Lab Clip" tile to Quick Settings (TileController below):
// on/off, paired devices, and pairing with an on-screen QR, all through the
// daemon's local control methods (GetStatus, SetEnabled, StartPairing, ...).

import Clutter from 'gi://Clutter';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import GObject from 'gi://GObject';
import Meta from 'gi://Meta';
import St from 'gi://St';

import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as ModalDialog from 'resource:///org/gnome/shell/ui/modalDialog.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as QuickSettings from 'resource:///org/gnome/shell/ui/quickSettings.js';

const BUS_NAME = 'com.ogenrwotdaniel.WakeProofClip';
const OBJECT_PATH = '/com/ogenrwotdaniel/WakeProofClip';
const INTERFACE = 'com.ogenrwotdaniel.WakeProofClip';

const PASSWORD_HINT = 'x-kde-passwordManagerHint';
// Copied files arrive as a text path too; a path to this laptop's disk is no use elsewhere.
const FILE_COPY = 'x-special/gnome-copied-files';
const TEXT_TYPES = ['text/plain;charset=utf-8', 'UTF8_STRING', 'text/plain', 'STRING', 'TEXT'];
const IMAGE_TYPES = ['image/png', 'image/jpeg'];
const MAX_TEXT_BYTES = 256 * 1024;
const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
const DEBOUNCE_MS = 150;
const ECHO_WINDOW_US = 10 * GLib.USEC_PER_SEC;

const TITLE = "Euler's Lab Clip";
const ICON = 'edit-paste-symbolic';
const QR_SIZE = 280;
const PAIR_INSTRUCTIONS =
    "On your phone or tablet: Euler's Lab \u2192 Settings \u2192 Clipboard across devices \u2192 Pair a computer";
const CALL_TIMEOUT_MS = 10000;
const SUBTITLE_TICK_S = 30;
const PAIRED_CLOSE_MS = 2000;

function ago(ms) {
    if (!ms)
        return '';
    const s = Math.max(0, Math.floor((Date.now() - ms) / 1000));
    if (s < 60)
        return 'just now';
    const m = Math.floor(s / 60);
    if (m < 60)
        return `${m} min ago`;
    const h = Math.floor(m / 60);
    if (h < 24)
        return `${h} h ago`;
    return `${Math.floor(h / 24)} d ago`;
}

function errorText(e) {
    try {
        if (e instanceof GLib.Error && Gio.DBusError.is_remote_error(e))
            Gio.DBusError.strip_remote_error(e);
    } catch (_) {}
    return e?.message ?? String(e);
}

const ClipToggle = GObject.registerClass(
class ClipToggle extends QuickSettings.QuickMenuToggle {
    _init() {
        super._init({title: TITLE, subtitle: '', iconName: ICON, toggleMode: false});
        this.menu.setHeader(ICON, TITLE, '');
        this.section = new PopupMenu.PopupMenuSection();
        this.menu.addMenuItem(this.section);
    }
});

const ClipIndicator = GObject.registerClass(
class ClipIndicator extends QuickSettings.SystemIndicator {
    _init() {
        super._init();
        // Shown in the top bar only while a pairing window is open.
        this.pairingIcon = this._addIndicator();
        this.pairingIcon.iconName = ICON;
        this.pairingIcon.visible = false;
    }
});

/** The modal with the pairing QR; follows the daemon's pairing state. */
class PairDialog {
    constructor(pngPath, expiresAtMs, onCancel, onClosed) {
        this._expiresAtMs = expiresAtMs;
        this._onCancel = onCancel;
        this._onClosed = onClosed;
        this._tickId = 0;
        this._closeId = 0;
        this._finished = false;

        this.dialog = new ModalDialog.ModalDialog({destroyOnClose: true});
        const box = new St.BoxLayout({
            orientation: Clutter.Orientation.VERTICAL,
            x_expand: true,
            style: 'spacing: 12px; padding: 8px 12px; max-width: 420px;',
        });
        const title = new St.Label({
            text: 'Pair a device',
            style: 'font-weight: bold; font-size: 1.3em; text-align: center;',
            x_align: Clutter.ActorAlign.CENTER,
        });
        const instructions = new St.Label({
            text: PAIR_INSTRUCTIONS,
            style: 'text-align: center;',
            x_align: Clutter.ActorAlign.CENTER,
        });
        instructions.clutter_text.line_wrap = true;
        const qrFrame = new St.Bin({
            style: 'background-color: white; padding: 8px; border-radius: 8px;',
            x_align: Clutter.ActorAlign.CENTER,
            child: new St.Icon({
                gicon: new Gio.FileIcon({file: Gio.File.new_for_path(pngPath)}),
                icon_size: QR_SIZE,
            }),
        });
        this._countdown = new St.Label({style: 'text-align: center; opacity: 0.8;',
            x_align: Clutter.ActorAlign.CENTER});
        this._result = new St.Label({style: 'text-align: center; font-weight: bold;',
            x_align: Clutter.ActorAlign.CENTER, visible: false});
        this._result.clutter_text.line_wrap = true;
        for (const a of [title, instructions, qrFrame, this._countdown, this._result])
            box.add_child(a);
        this.dialog.contentLayout.add_child(box);
        this._setButton('Cancel', true);

        this.dialog.connect('closed', () => this._closed());
        this._tick();
        this._tickId = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 1, () => {
            this._tick();
            return GLib.SOURCE_CONTINUE;
        });
        this.dialog.open();
    }

    _setButton(label, cancels) {
        this.dialog.setButtons([{
            label,
            key: Clutter.KEY_Escape,
            action: () => {
                if (cancels && !this._finished)
                    this._onCancel();
                this.close();
            },
        }]);
    }

    _tick() {
        if (this._finished)
            return;
        const left = Math.max(0, Math.round((this._expiresAtMs - Date.now()) / 1000));
        const m = Math.floor(left / 60);
        const s = String(left % 60).padStart(2, '0');
        this._countdown.text = left > 0 ? `Waiting for a device \u00b7 ${m}:${s} left` : 'Pairing window closed';
    }

    _show(text, isError) {
        this._result.text = text;
        this._result.style = `text-align: center; font-weight: bold; color: ${isError ? '#f66151' : '#57e389'};`;
        this._result.visible = true;
    }

    /** Applies the `pairing` object from GetStatus / StatusChanged. */
    update(pairing) {
        if (this._finished || !pairing)
            return;
        const result = pairing.result ?? null;
        if (result && result.startsWith('ok:')) {
            this._finish();
            let text = `Paired with ${result.slice(3)}`;
            if (pairing.warning)
                text += `\n(${pairing.warning})`;
            this._show(text, false);
            this._setButton('Done', false);
            this._closeId = GLib.timeout_add(GLib.PRIORITY_DEFAULT, PAIRED_CLOSE_MS, () => {
                this._closeId = 0;
                this.close();
                return GLib.SOURCE_REMOVE;
            });
        } else if (!pairing.active) {
            if (result && result.startsWith('error:')) {
                this._finish();
                this._show(result.slice(6), true);
                this._setButton('Close', false);
            } else {
                this.close(); // cancelled elsewhere
            }
        } else if (result && result.startsWith('error:')) {
            this._show(result.slice(6), true); // a refused attempt; the window stays open
        }
    }

    serviceStopped() {
        if (this._finished)
            return;
        this._finish();
        this._show("The Euler's Lab Clip service stopped.", true);
        this._setButton('Close', false);
    }

    _finish() {
        this._finished = true;
        this._countdown.visible = false;
    }

    close() {
        try {
            this.dialog?.close();
        } catch (e) {
            console.debug(`Euler's Lab Clip: closing the pairing dialog: ${e}`);
        }
    }

    _closed() {
        if (this._tickId) {
            GLib.source_remove(this._tickId);
            this._tickId = 0;
        }
        if (this._closeId) {
            GLib.source_remove(this._closeId);
            this._closeId = 0;
        }
        this.dialog = null;
        this._onClosed?.();
        this._onClosed = null;
    }

    destroy() {
        this._onClosed = null;
        const dialog = this.dialog;
        if (dialog) {
            this._closed();
            dialog.destroy();
        }
    }
}

/** The Quick Settings tile and its menu. All D-Bus traffic is async. */
class TileController {
    constructor() {
        this._destroyed = false;
        this._running = false;
        this._status = null;
        this._dialog = null;
        this._pairBusy = false;
        this._cancellable = new Gio.Cancellable();

        this._toggle = new ClipToggle();
        this._toggle.connect('clicked', () => this._onClicked());
        this._menuOpenId = this._toggle.menu.connect('open-state-changed', (_m, open) => {
            if (open)
                this._refresh();
        });
        this._indicator = new ClipIndicator();
        this._indicator.quickSettingsItems.push(this._toggle);
        Main.panel.statusArea.quickSettings.addExternalIndicator(this._indicator);

        this._statusSub = Gio.DBus.session.signal_subscribe(
            BUS_NAME, INTERFACE, 'StatusChanged', OBJECT_PATH, null,
            Gio.DBusSignalFlags.NONE,
            (_conn, _sender, _path, _iface, _signal, params) => {
                try {
                    this._onStatus(params.get_child_value(0).get_string()[0]);
                } catch (e) {
                    console.debug(`WakeProof Clip: bad StatusChanged: ${e}`);
                }
            });
        this._watchId = Gio.bus_watch_name(Gio.BusType.SESSION, BUS_NAME,
            Gio.BusNameWatcherFlags.NONE,
            () => this._onAppeared(),
            () => this._onVanished());
        this._tickId = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, SUBTITLE_TICK_S, () => {
            this._render();
            return GLib.SOURCE_CONTINUE;
        });
        this._render();
    }

    destroy() {
        this._destroyed = true;
        this._cancellable.cancel();
        if (this._statusSub) {
            Gio.DBus.session.signal_unsubscribe(this._statusSub);
            this._statusSub = 0;
        }
        if (this._watchId) {
            Gio.bus_unwatch_name(this._watchId);
            this._watchId = 0;
        }
        if (this._tickId) {
            GLib.source_remove(this._tickId);
            this._tickId = 0;
        }
        this._dialog?.destroy();
        this._dialog = null;
        if (this._menuOpenId) {
            this._toggle.menu.disconnect(this._menuOpenId);
            this._menuOpenId = 0;
        }
        this._indicator.quickSettingsItems.forEach(item => item.destroy());
        this._indicator.quickSettingsItems = [];
        this._indicator.destroy();
        this._indicator = null;
        this._toggle = null;
    }

    // --- D-Bus ---------------------------------------------------------------

    _call(method, params, replyType, onOk, onError) {
        if (this._destroyed)
            return;
        try {
            Gio.DBus.session.call(BUS_NAME, OBJECT_PATH, INTERFACE, method, params,
                replyType ? new GLib.VariantType(replyType) : null,
                Gio.DBusCallFlags.NO_AUTO_START, CALL_TIMEOUT_MS, this._cancellable,
                (conn, res) => {
                    let reply;
                    try {
                        reply = conn.call_finish(res);
                    } catch (e) {
                        if (this._destroyed || e.matches?.(Gio.IOErrorEnum, Gio.IOErrorEnum.CANCELLED))
                            return;
                        console.debug(`WakeProof Clip: ${method} failed: ${e.message}`);
                        try {
                            onError?.(e);
                        } catch (e2) {
                            console.error(`WakeProof Clip: ${method} error handler: ${e2}`);
                        }
                        return;
                    }
                    if (this._destroyed)
                        return;
                    try {
                        onOk?.(reply);
                    } catch (e) {
                        console.error(`WakeProof Clip: ${method} reply handler: ${e}`);
                    }
                });
        } catch (e) {
            console.debug(`WakeProof Clip: ${method} not sent: ${e}`);
            onError?.(e);
        }
    }

    _refresh() {
        if (!this._running)
            return;
        this._call('GetStatus', null, '(s)', reply => this._onStatus(reply.get_child_value(0).get_string()[0]));
    }

    _onAppeared() {
        this._running = true;
        this._render();
        this._refresh();
    }

    _onVanished() {
        this._running = false;
        this._status = null;
        this._dialog?.serviceStopped();
        this._render();
    }

    _onStatus(json) {
        if (this._destroyed)
            return;
        let status;
        try {
            status = JSON.parse(json);
        } catch (e) {
            console.debug(`WakeProof Clip: unreadable status: ${e}`);
            return;
        }
        this._running = true;
        this._status = status;
        this._dialog?.update(status.pairing);
        this._render();
    }

    // --- actions -------------------------------------------------------------

    _onClicked() {
        if (!this._running) {
            this._startService();
            return;
        }
        const enabled = !(this._status?.enabled ?? true);
        this._call('SetEnabled', GLib.Variant.new_tuple([GLib.Variant.new_boolean(enabled)]), null,
            () => this._refresh(),
            e => Main.notify(TITLE, `Could not switch it ${enabled ? 'on' : 'off'}: ${errorText(e)}`));
    }

    _startService() {
        try {
            const proc = Gio.Subprocess.new(['systemctl', '--user', 'start', 'clipsync.service'],
                Gio.SubprocessFlags.STDOUT_SILENCE | Gio.SubprocessFlags.STDERR_PIPE);
            proc.communicate_utf8_async(null, this._cancellable, (p, res) => {
                try {
                    const [, , stderr] = p.communicate_utf8_finish(res);
                    if (!this._destroyed && !p.get_successful())
                        Main.notify(TITLE, `Could not start the service: ${(stderr ?? '').trim() || 'systemctl failed'}`);
                } catch (e) {
                    if (!this._destroyed)
                        console.debug(`WakeProof Clip: systemctl: ${e}`);
                }
            });
        } catch (e) {
            Main.notify(TITLE, `Could not start the service: ${e.message}`);
        }
    }

    _pair() {
        try {
            Main.panel.closeQuickSettings();
        } catch (_) {}
        if (this._pairBusy || this._dialog)
            return;
        this._pairBusy = true;
        const failed = e => {
            this._pairBusy = false;
            Main.notify(TITLE, `Could not start pairing: ${errorText(e)}`);
        };
        this._call('StartPairing', null, '(s)', reply => {
            const qrText = reply.get_child_value(0).get_string()[0];
            this._call('RenderQr', GLib.Variant.new_tuple([GLib.Variant.new_string(qrText)]), '(s)', r => {
                this._pairBusy = false;
                const path = r.get_child_value(0).get_string()[0];
                this._call('GetStatus', null, '(s)', s => {
                    let pairing = {};
                    try {
                        pairing = JSON.parse(s.get_child_value(0).get_string()[0]).pairing ?? {};
                    } catch (_) {}
                    if (this._dialog)
                        return;
                    const expires = pairing.expiresAtMs || Date.now() + 5 * 60 * 1000;
                    this._dialog = new PairDialog(path, expires,
                        () => this._call('CancelPairing', null, null),
                        () => {
                            this._dialog = null;
                        });
                    this._dialog.update(pairing);
                }, failed);
            }, failed);
        }, failed);
    }

    _openImages() {
        try {
            let dir = this._status?.imagesDir;
            if (!dir) {
                const pictures = GLib.get_user_special_dir(GLib.UserDirectory.DIRECTORY_PICTURES) ??
                    GLib.build_filenamev([GLib.get_home_dir(), 'Pictures']);
                dir = GLib.build_filenamev([pictures, "Euler's Lab Clip"]);
            }
            GLib.mkdir_with_parents(dir, 0o755);
            Gio.AppInfo.launch_default_for_uri(GLib.filename_to_uri(dir, null),
                global.create_app_launch_context(0, -1));
        } catch (e) {
            Main.notify(TITLE, `Could not open the folder: ${e.message}`);
        }
    }

    _forget() {
        this._call('ForgetPairing', null, null,
            () => {
                this._refresh();
                Main.notify(TITLE, 'Pairing revoked on this computer.');
            },
            e => Main.notify(TITLE, `Could not revoke pairing: ${errorText(e)}`));
    }

    // --- rendering -----------------------------------------------------------

    _subtitle() {
        const s = this._status;
        if (!this._running)
            return 'Service not running';
        if (!s)
            return '';
        if (!s.enabled)
            return 'Off';
        if (!s.paired)
            return 'Not paired';
        const last = (s.peers ?? []).find(p => p.lastSeenMs);
        return last ? `Paired \u00b7 ${last.name} ${ago(last.lastSeenMs)}` : 'Paired';
    }

    _render() {
        if (this._destroyed)
            return;
        const s = this._status;
        const subtitle = this._subtitle();
        this._toggle.subtitle = subtitle;
        this._toggle.checked = this._running && !!s?.enabled;
        this._toggle.menu.setHeader(ICON, TITLE, subtitle);
        this._indicator.pairingIcon.visible = !!(this._running && s?.pairing?.active);

        const section = this._toggle.section;
        section.removeAll();
        const info = text => section.addMenuItem(new PopupMenu.PopupMenuItem(text, {reactive: false, can_focus: false}));
        const action = (text, fn) => {
            const item = new PopupMenu.PopupMenuItem(text);
            item.connect('activate', () => fn());
            section.addMenuItem(item);
        };

        if (!this._running) {
            info('The clipsync service is not running.');
            action('Start service', () => this._startService());
            return;
        }
        if (s) {
            const peers = s.peers ?? [];
            if (!s.paired)
                info('No phone or tablet paired yet.');
            else if (peers.length === 0)
                info('No devices seen yet.');
            for (const p of peers) {
                const bits = [p.name];
                if (p.lastSeenMs)
                    bits.push(ago(p.lastSeenMs));
                if (p.lan)
                    bits.push('LAN');
                info(bits.join(' \u00b7 '));
            }
            if (s.paired)
                info(s.signedIn ? `Cloud fallback: ${s.email || 'on'}` : 'Cloud fallback off (LAN only)');
            if (!s.enabled)
                info('Sharing is off: nothing is sent or applied.');
        }
        section.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        action(s?.pairing?.active ? 'Show pairing code\u2026' : 'Pair a device\u2026', () => this._pair());
        if (s?.paired)
            action('Revoke pairing', () => this._forget());
        action('Open received images', () => this._openImages());
    }
}

export default class WakeProofClipExtension extends Extension {
    enable() {
        this._clipboard = St.Clipboard.get_default();
        this._selection = global.display.get_selection();
        this._generation = 0;
        this._debounceId = 0;
        this._expect = null;
        this._daemonMissingLogged = false;
        this._encoder = new TextEncoder();
        this._decoder = new TextDecoder('utf-8');

        this._ownerChangedId = this._selection.connect('owner-changed',
            (_selection, type, source) => this._onOwnerChanged(type, source));

        this._incomingId = Gio.DBus.session.signal_subscribe(
            BUS_NAME, INTERFACE, 'Incoming', OBJECT_PATH, null,
            Gio.DBusSignalFlags.NONE,
            (_conn, _sender, _path, _iface, _signal, params) => this._onIncoming(params));

        try {
            this._tile = new TileController();
        } catch (e) {
            this._tile = null;
            console.error(`WakeProof Clip: could not add the Quick Settings tile: ${e}`);
        }
    }

    disable() {
        try {
            this._tile?.destroy();
        } catch (e) {
            console.error(`WakeProof Clip: removing the tile: ${e}`);
        }
        this._tile = null;
        if (this._ownerChangedId) {
            this._selection.disconnect(this._ownerChangedId);
            this._ownerChangedId = 0;
        }
        if (this._incomingId) {
            Gio.DBus.session.signal_unsubscribe(this._incomingId);
            this._incomingId = 0;
        }
        if (this._debounceId) {
            GLib.source_remove(this._debounceId);
            this._debounceId = 0;
        }
        this._generation++; // any read still in flight is dropped on arrival
        this._expect = null;
        this._selection = null;
        this._clipboard = null;
        this._encoder = null;
        this._decoder = null;
    }

    // --- local clipboard -> daemon ---------------------------------------------

    _onOwnerChanged(type, source) {
        if (type !== Meta.SelectionType.SELECTION_CLIPBOARD || !source)
            return;
        if (this._debounceId)
            GLib.source_remove(this._debounceId);
        this._debounceId = GLib.timeout_add(GLib.PRIORITY_DEFAULT, DEBOUNCE_MS, () => {
            this._debounceId = 0;
            this._readClipboard();
            return GLib.SOURCE_REMOVE;
        });
    }

    _readClipboard() {
        const generation = ++this._generation;
        const clipboard = this._clipboard;
        let mimetypes;
        try {
            mimetypes = clipboard.get_mimetypes(St.ClipboardType.CLIPBOARD) ?? [];
        } catch (e) {
            console.debug(`WakeProof Clip: cannot list clipboard types: ${e}`);
            return;
        }
        if (mimetypes.includes(PASSWORD_HINT) || mimetypes.includes(FILE_COPY))
            return;

        const image = IMAGE_TYPES.find(t => mimetypes.includes(t));
        if (image) {
            clipboard.get_content(St.ClipboardType.CLIPBOARD, image, (_cb, bytes) => {
                if (generation !== this._generation || !bytes)
                    return;
                const size = bytes.get_size();
                if (size === 0 || size > MAX_IMAGE_BYTES)
                    return;
                this._offer(image, bytes);
            });
            return;
        }
        if (!TEXT_TYPES.some(t => mimetypes.includes(t)))
            return;
        clipboard.get_text(St.ClipboardType.CLIPBOARD, (_cb, text) => {
            if (generation !== this._generation || !text || !text.trim())
                return;
            const data = this._encoder.encode(text);
            if (data.length > MAX_TEXT_BYTES)
                return;
            this._offer('text/plain', new GLib.Bytes(data));
        });
    }

    _offer(mime, bytes) {
        try {
            if (this._isEcho(bytes))
                return;
            const params = GLib.Variant.new_tuple([
                GLib.Variant.new_string(mime),
                GLib.Variant.new_from_bytes(new GLib.VariantType('ay'), bytes, true),
            ]);
            Gio.DBus.session.call(BUS_NAME, OBJECT_PATH, INTERFACE, 'Offer', params, null,
                Gio.DBusCallFlags.NO_AUTO_START, 10000, null, (conn, res) => {
                    try {
                        conn.call_finish(res);
                        this._daemonMissingLogged = false;
                    } catch (e) {
                        // The daemon not running is normal (not installed, restarting).
                        if (!this._daemonMissingLogged) {
                            this._daemonMissingLogged = true;
                            console.debug(`WakeProof Clip: daemon unavailable: ${e.message}`);
                        }
                    }
                });
        } catch (e) {
            console.debug(`WakeProof Clip: offer failed: ${e}`);
        }
    }

    _isEcho(bytes) {
        const expect = this._expect;
        if (!expect)
            return false;
        if (GLib.get_monotonic_time() > expect.until) {
            this._expect = null;
            return false;
        }
        if (GLib.compute_checksum_for_bytes(GLib.ChecksumType.SHA256, bytes) !== expect.hash)
            return false;
        this._expect = null;
        return true;
    }

    // --- daemon -> local clipboard ---------------------------------------------

    _onIncoming(params) {
        try {
            const [mime] = params.get_child_value(0).get_string();
            const bytes = params.get_child_value(1).get_data_as_bytes();
            if (!bytes || bytes.get_size() === 0)
                return;
            this._expect = {
                hash: GLib.compute_checksum_for_bytes(GLib.ChecksumType.SHA256, bytes),
                until: GLib.get_monotonic_time() + ECHO_WINDOW_US,
            };
            if (mime === 'text/plain') {
                this._clipboard.set_text(St.ClipboardType.CLIPBOARD,
                    this._decoder.decode(bytes.toArray()));
            } else if (IMAGE_TYPES.includes(mime)) {
                this._clipboard.set_content(St.ClipboardType.CLIPBOARD, mime, bytes);
            } else {
                this._expect = null;
            }
        } catch (e) {
            this._expect = null;
            console.debug(`WakeProof Clip: could not set the clipboard: ${e}`);
        }
    }
}
