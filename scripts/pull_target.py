"""Extract T-1 app APKs + scattered system files from the selected Android device.

Read-only device side. Everything lands under the PII quarantine dir
(outside the repo) with per-file sha256 in manifest.json and a
commands.jsonl audit log.

Usage:
  set EXPERTULTRA_SERIAL=<serial>
  python pull_target.py [package-name]      # default: auto-detect T-1 package
"""
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
# PII quarantine — device dumps must NOT land inside the repo (HANDOFF 附录B)
DEVICE_DIR = Path(os.environ.get("CW_DEVICE_DIR",
                                BASE.parents[1] / "private" / "ClaudeWilson" / "device"))
ADB = os.environ.get("EXPERTULTRA_ADB", "adb")
SERIAL = os.environ.get("EXPERTULTRA_SERIAL", "")
ADB_ENV = dict(os.environ, ADB_VENDOR_KEYS=os.path.expanduser("~/.android/adbkey"))
LOG = DEVICE_DIR / "commands.jsonl"


def adb(args, root=False, binary=False):
    cmd = [ADB, "-s", SERIAL] + args
    proc = subprocess.run(cmd, capture_output=True, env=ADB_ENV)
    rec = {
        "ts": datetime.now().astimezone().isoformat(),
        "cmd": cmd,
        "rc": proc.returncode,
        "out_sha256": hashlib.sha256(proc.stdout).hexdigest(),
        "err": proc.stderr.decode(errors="replace")[:500],
    }
    with LOG.open("a", encoding="utf-8") as f:
        f.write(json.dumps(rec) + "\n")
    return proc.stdout if binary else proc.stdout.decode(errors="replace"), proc.returncode


def shell(cmd_str, root=False, binary=False):
    if root:
        return adb(["exec-out", "su", "-c", cmd_str], binary=binary)
    return adb(["exec-out", "sh", "-c", cmd_str], binary=binary)


def save(rel, data):
    p = DEVICE_DIR / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data if isinstance(data, bytes) else data.encode())
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    pkg_arg = sys.argv[1] if len(sys.argv) > 1 else None
    if not SERIAL:
        sys.exit("EXPERTULTRA_SERIAL not set")
    DEVICE_DIR.mkdir(parents=True, exist_ok=True)

    out, rc = adb(["shell", "id"])
    print("shell:", out.strip())
    out_root, _ = adb(["exec-out", "su", "-c", "id"], binary=True)
    print("su   :", out_root.decode(errors="replace").strip()[:120])
    have_root = b"uid=0" in out_root

    # locate package
    pkgs, _ = shell("pm list packages")
    cands = [l.split(":", 1)[1].strip() for l in pkgs.splitlines()
             if ":" in l and ("claude" in l.lower() or "anthropic" in l.lower())]
    pkg = pkg_arg or (cands[0] if cands else None)
    print("candidates:", cands, "-> using:", pkg)
    if not pkg:
        sys.exit("no T-1 package found")

    manifest = {"pkg": pkg, "serial": SERIAL, "root": have_root, "files": {}}

    # system-side metadata
    for label, cmd in [
        ("system/dumpsys-package.txt", f"dumpsys package {pkg}"),
        ("system/pm-dump.txt", f"pm dump {pkg}"),
        ("system/appops.txt", f"appops get {pkg}"),
        ("system/install-source.txt",
         f"pm get-install-location; cmd package get-installer-info {pkg} 2>/dev/null; "
         f"pm list packages -i {pkg}"),
    ]:
        out, _ = shell(cmd)
        manifest["files"][label] = save(label, out)

    # APK set (base + splits)
    paths, _ = shell(f"pm path {pkg}")
    apk_paths = [l.split(":", 1)[1].strip() for l in paths.splitlines() if l.startswith("package:")]
    for ap in apk_paths:
        rel = "apk/" + ap.split("/")[-1]
        data, _ = shell(f"cat '{ap}'", binary=True)
        manifest["files"][rel] = save(rel, data)
        print("apk:", ap, len(data), "bytes")

    # /data/app dir listing (libs, oat)
    out, _ = shell(f"ls -laR /data/app/*/ 2>/dev/null | grep -A40 '{pkg}'", root=have_root)
    manifest["files"]["system/data-app-listing.txt"] = save("system/data-app-listing.txt", out)

    # private data via root tar
    if have_root:
        for name, cmd in [
            (f"data/data-{pkg}.tar", f"tar cf - -C /data/data {pkg} 2>/dev/null"),
            (f"data/data-ce-{pkg}.tar", f"tar cf - -C /data/user_de/0 {pkg} 2>/dev/null"),
            (f"data/profile-{pkg}.tar", f"tar cf - -C /data/misc/profiles/cur/0 {pkg} 2>/dev/null"),
            (f"data/misc-{pkg}.tar",
             f"for d in /data/misc/*/profiles/cur/0/{pkg}; do :; done; true"),
        ]:
            data, _ = shell(cmd, root=True, binary=True)
            if data and len(data) > 1024:
                manifest["files"][name] = save(name, data)
                print("tar:", name, len(data), "bytes")
        # packages.xml entry + keystore/user cert traces (grep only)
        out, _ = shell(
            f"grep -o '<package[^>]*name=\"{pkg}\"[^/]*</package>\\|<package[^>]*name=\"{pkg}\"[^>]*/>' "
            f"/data/system/packages.xml | head -5; "
            f"ls /data/misc/keystore/user_0/ 2>/dev/null | grep -i {pkg.replace('.', '_')}",
            root=True)
        manifest["files"]["system/packages-xml-entry.txt"] = save("system/packages-xml-entry.txt", out)

    # shared/external storage (no root needed)
    for name, cmd in [
        (f"data/sdcard-android-data-{pkg}.tar",
         f"tar cf - -C /sdcard/Android/data {pkg} 2>/dev/null"),
        (f"data/sdcard-android-media-{pkg}.tar",
         f"tar cf - -C /sdcard/Android/media {pkg} 2>/dev/null"),
        (f"data/sdcard-android-obb-{pkg}.tar",
         f"tar cf - -C /sdcard/Android/obb {pkg} 2>/dev/null"),
    ]:
        data, _ = shell(cmd, binary=True)
        if data and len(data) > 1024:
            manifest["files"][name] = save(name, data)
            print("tar:", name, len(data), "bytes")

    save("manifest.json", json.dumps(manifest, indent=2))
    print("done. files:", len(manifest["files"]))


if __name__ == "__main__":
    main()
