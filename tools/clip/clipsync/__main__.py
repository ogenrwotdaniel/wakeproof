"""python3 -m clipsync {daemon,pair,status,forget,send-test TEXT}"""
from __future__ import annotations

import argparse
import hashlib
import logging
import sys


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="clipsync", description="WakeProof Clip, laptop side")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("daemon", help="run the D-Bus/LAN/cloud daemon (systemd runs this)")
    sub.add_parser("pair", help="show a QR and pair one phone or tablet")
    sub.add_parser("status", help="show pairing and daemon state")
    sub.add_parser("forget", help="delete the clip key and account from this laptop")
    p = sub.add_parser("send-test", help="send TEXT as if it had been copied")
    p.add_argument("text")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, stream=sys.stderr,
                        format="%(levelname)s %(name)s: %(message)s")
    logging.getLogger("zeroconf").setLevel(logging.WARNING)

    if args.cmd == "daemon":
        from .daemon import run_daemon

        return run_daemon()
    if args.cmd == "pair":
        from .pair import run_pair

        return run_pair()
    if args.cmd == "status":
        return status()
    if args.cmd == "forget":
        return forget()
    if args.cmd == "send-test":
        return send_test(args.text)
    return 2


def _optional(mod: str) -> str:
    try:
        __import__(mod)
        return "yes"
    except ImportError:
        return "NO"


def status() -> int:
    from . import store as st
    from .daemon import daemon_running
    from .lan import local_ipv4_addrs

    cfg = st.load_config()
    print(f"device:       {cfg['name']} ({cfg['installId']})")
    print(f"config:       {st.config_path()}")
    try:
        store = st.KeyringStore()
        key, account = st.load_key(store), st.load_account(store)
    except Exception as e:
        print(f"keyring:      UNAVAILABLE ({e})")
        key = account = None
    if key:
        print(f"clip key:     paired (fingerprint {hashlib.sha256(key).hexdigest()[:12]})")
    else:
        print("clip key:     not paired (run: python3 -m clipsync pair)")
    if account:
        print(f"cloud:        {account.get('email') or '?'} uid {account['uid']} project {account['projectId']}")
    else:
        print("cloud:        not signed in (LAN only)")
    peers = cfg.get("peers", {})
    for pid, p in peers.items():
        print(f"peer:         {p.get('name') or '?'} ({pid}) at {', '.join(p.get('addrs', [])) or '?'}")
    print(f"addresses:    {', '.join(local_ipv4_addrs()) or 'none'}")
    print(f"daemon:       {'running' if daemon_running() else 'not running'}")
    print(f"zeroconf:     {_optional('zeroconf')}   qrcode: {_optional('qrcode')}")
    return 0


def forget() -> int:
    from . import store as st
    from .cloud import CloudError, firestore_for

    store = st.KeyringStore()
    cfg = st.load_config()
    account = st.load_account(store)
    if account:
        try:
            firestore_for(account).delete_presence(cfg["installId"])
        except CloudError as e:
            print(f"(could not delete the presence doc: {e})", file=sys.stderr)
    store.delete(st.ITEM_KEY)
    store.delete(st.ITEM_ACCOUNT)
    cfg["peers"] = {}
    st.save_config(cfg)
    print("Forgotten: clip key and account removed from this laptop. Other devices are unchanged.")
    return 0


def send_test(text: str) -> int:
    from .daemon import daemon_running, offer_via_dbus

    data = text.encode("utf-8")
    if daemon_running():
        offer_via_dbus("text/plain", data)
        print("Offered to the running daemon; see: journalctl --user -u clipsync -f")
        return 0
    from . import store as st
    from .daemon import ClipEngine

    engine = ClipEngine(st.KeyringStore(), st.load_config())
    print(engine.offer("text/plain", data))
    return 0


if __name__ == "__main__":
    sys.exit(main())
