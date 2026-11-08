"""Extract T-1 private data (+ related system-side app data) via root.

Requires: device connected via adb AND su working (KernelSU re-enabled).
Read-only. Output under the PII quarantine dir (outside the repo) with
sha256 manifest update.

Usage: python pull_private.py [pkg]   (default T-1 package)
"""
import hashlib
import json
import os
import subprocess
import sys
import tarfile
import io
from datetime import datetime
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
# PII quarantine — private data must NOT land inside the repo (HANDOFF 附录B)
DEVICE_DIR = Path(os.environ.get("CW_DEVICE_DIR",
                                BASE.parents[1] / "private" / "ClaudeWilson" / "device"))
ADB = os.environ.get("EXPERTULTRA_ADB", "adb")
SERIAL = os.environ.get("EXPERTULTRA_SERIAL", "")
ADB_ENV = dict(os.environ, ADB_VENDOR_KEYS=os.path.expanduser("~/.android/adbkey"))
LOG = DEVICE_DIR / "commands.jsonl"
PKG = sys.argv[1] if len(sys.argv) > 1 else "com.anthropic.claude"


def run(args, binary=True):
    proc = subprocess.run([ADB, "-s", SERIAL] + args, capture_output=True, env=ADB_ENV)
    with LOG.open("a", encoding="utf-8") as f:
        f.write(json.dumps({
            "ts": datetime.now().astimezone().isoformat(),
            "cmd": [ADB, "-s", SERIAL] + args,
            "rc": proc.returncode,
            "out_sha256": hashlib.sha256(proc.stdout).hexdigest(),
            "err": proc.stderr.decode(errors="replace")[:300],
        }) + "\n")
    return proc.stdout if binary else proc.stdout.decode(errors="replace"), proc.returncode


def su(cmd, binary=True):
    return run(["exec-out", "su", "-c", cmd], binary=binary)


def save(rel, data):
    p = DEVICE_DIR / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data)
    return hashlib.sha256(data).hexdigest()


def main():
    if not SERIAL:
        sys.exit("EXPERTULTRA_SERIAL not set")
    out, _ = su("id")
    if b"uid=0" not in out:
        sys.exit("su not available: " + out.decode(errors="replace").strip())
    print("root:", out.decode().strip())

    manifest = json.loads((DEVICE_DIR / "manifest.json").read_text()) \
        if (DEVICE_DIR / "manifest.json").exists() else {"files": {}}
    manifest.setdefault("private_pull", {})["ts"] = datetime.now().astimezone().isoformat()

    targets = [
        ("data/data-%s.tar" % PKG, "/data/data", PKG),
        ("data/data-ce-%s.tar" % PKG, "/data/user/0", PKG),
        ("data/data-de-%s.tar" % PKG, "/data/user_de/0", PKG),
        ("data/profile-%s.tar" % PKG, "/data/misc/profiles/cur/0", PKG),
        ("data/apex-prof-%s.tar" % PKG, "/data/misc/profiles/ref", PKG),
    ]
    for rel, base, name in targets:
        data, rc = su(f"cd {base} 2>/dev/null && tar cf - {name} 2>/dev/null || true")
        if data and len(data) > 1024:
            manifest["files"][rel] = save(rel, data)
            try:
                names = tarfile.open(fileobj=io.BytesIO(data)).getnames()
                print(f"{rel}: {len(data)}B, {len(names)} entries, top: {[n for n in names[:12]]}")
            except Exception as e:
                print(f"{rel}: {len(data)}B (tar list failed: {e})")
        else:
            print(f"{rel}: empty/absent")

    # scattered system-side traces
    extras = {
        "system/packages-xml-entry.txt":
            f"grep -a -A30 'name=\"{PKG}\"' /data/system/packages.xml | head -60",
        "system/appops-xml.txt":
            f"grep -a -A40 '\"{PKG}\"' /data/system/appops.xml | head -80",
        "system/keystore-list.txt":
            f"ls /data/misc/keystore/user_0/ 2>/dev/null | grep -i claude; "
            f"ls /data/misc/keystore/persistent.sqlite 2>/dev/null && echo HAS_PERSISTENT_SQLITE",
        "system/notify-listeners.txt":
            f"dumpsys notification --noredact 2>/dev/null | grep -A3 {PKG} | head -20",
        "system/appwidgets.txt":
            f"ls /data/system_ce/0/ /data/system/users/0/ 2>/dev/null | head -40",
        "system/usagestats.txt":
            f"dumpsys usagestats 2>/dev/null | grep -i {PKG} | head -20",
        "system/accounts.txt":
            f"grep -a {PKG} /data/system_ce/0/accounts_ce.db 2>/dev/null | strings | head -20",
    }
    for rel, cmd in extras.items():
        out, _ = su(cmd, binary=False)
        manifest["files"][rel] = save(rel, out.encode() if isinstance(out, str) else out)
        print(f"{rel}: {len(out)}B")

    (DEVICE_DIR / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print("manifest updated,", len(manifest["files"]), "files total")


if __name__ == "__main__":
    main()
