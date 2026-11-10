#!/usr/bin/env python3
"""Build the Java-only APK: validate -> link -> javac -> d8 -> sign -> verify."""
import datetime
import hashlib
import io
import json
import os
import re
import secrets
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
API = "35"
PACKAGE = "com.walter.claudewilson"
# (aar filename, class prefixes excluded from classes.jar)
AARS = [
    ("billing-8.0.0.aar",
     # Unused alternative-billing UI requires androidx.activity.
     ("com/android/billingclient/api/ProxyBillingActivityV2",
      "com/android/billingclient/api/zzct",
      "com/android/billingclient/api/zzcu",
      "com/android/billingclient/api/zzcv")),
    ("shizuku-api-13.1.5.aar", ()),
    ("shizuku-aidl-13.1.5.aar", ()),
    ("shizuku-shared-13.1.5.aar", ()),
    ("shizuku-provider-13.1.5.aar", ()),
]
# Compile-time only. Vector supplies these classes inside the target process; they must not be
# dexed into the app or they would shadow the framework's own implementation.
COMPILE_ONLY = ["xposed-api-82.jar"]
NATIVE_LIB = ROOT / "libs" / "arm64-v8a" / "libcwpersona.so"
EXPECTED_PERMISSIONS = sorted([
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.USE_BIOMETRIC",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.READ_PHONE_STATE",
    "com.android.vending.BILLING",
    "moe.shizuku.manager.permission.API_V23",
])


def environment_path(name):
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"set {name}; see HANDOFF.md section 5")
    return Path(value).expanduser().resolve()


def sha(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def forbidden_api_refs(dexdump):
    """Audit calls by owning class; only the shell probe may use network APIs."""
    owner = None
    blocked = set()
    app_prefix = "Lcom/walter/claudewilson/"
    shell_probe = app_prefix + "ShellNetworkProbe;"
    bridge = app_prefix + "Bridge;"
    # The synthetic-location writer is the only class allowed to touch android.location: it
    # constructs a fake fix and never calls a reader (getLastKnownLocation/requestLocationUpdates
    # on the real manager are hooked, not invoked).
    location_hook = app_prefix + "hook/LocationHooks;"
    location_hook_inner = app_prefix + "hook/LocationHooks$"
    network = ("Ljava/net/Socket", "Ljava/net/URL", "Ljava/net/HttpURLConnection",
               "Ljava/net/DatagramSocket", "Ljavax/net/SocketFactory",
               "Ljavax/net/ssl/SSLSocket", "Ljava/net/InetAddress;.getByName",
               "Ljava/net/InetAddress;.getAllByName", "Lokhttp3/")
    forbidden = ("Landroid/webkit/",)
    for line in dexdump.splitlines():
        match = re.search(r"Class descriptor\s*:\s*'([^']+)'", line)
        if match:
            owner = match.group(1)
        if not owner or not owner.startswith(app_prefix) or "invoke-" not in line:
            continue
        match = re.search(r", (L[^;]+;\.[^:]+):", line)
        if not match:
            continue
        ref = match.group(1)
        if ref.startswith(forbidden) or (
                ref.startswith("Landroid/location/")
                and owner != location_hook
                and not owner.startswith(location_hook_inner)) or (
                owner != shell_probe and ref.startswith(network)) or (
                ref.startswith(shell_probe + ".") and owner not in (bridge, shell_probe)):
            blocked.add(owner + " -> " + ref)
    return sorted(blocked)


class Build:
    def __init__(self):
        workspace = environment_path("CW_WORKSPACE")
        self.jbr = environment_path("CW_JBR")
        sdk = workspace / "tools/android-sdk"
        self.platform = sdk / f"platforms/android-{API}/android.jar"
        tools = sdk / "build-tools/35.0.0"
        self.keystore = Path(os.environ.get(
            "ClaudeWilson_KEYSTORE", str(workspace / "keys/debug.keystore"))).expanduser().resolve()
        suffix = ".exe" if os.name == "nt" else ""
        script = ".bat" if os.name == "nt" else ""
        self.tools = {
            "javac": self.jbr / "bin" / ("javac" + suffix),
            "aapt2": tools / ("aapt2" + suffix),
            "d8": tools / ("d8" + script),
            "zipalign": tools / ("zipalign" + suffix),
            "apksigner": tools / ("apksigner" + script),
            "dexdump": tools / ("dexdump" + suffix),
        }
        self.stubs = tools / "core-lambda-stubs.jar"
        self.sources = sorted((ROOT / "src").rglob("*.java")) \
            + sorted((ROOT / "stubs-src").rglob("*.java"))
        self.manifest = ROOT / "AndroidManifest.xml"
        self.dependencies = [(ROOT / "libs" / name, drops) for name, drops in AARS]
        self.compile_only = [ROOT / "libs" / name for name in COMPILE_ONLY]
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S")
        self.out = ROOT / "out" / ("build-" + stamp + "-" + secrets.token_hex(3))
        self.apk = self.out / "ClaudeWilson.apk"
        self.tmp = self.out / "tmp"

    def validate(self):
        required = [self.platform, self.keystore, self.stubs, self.manifest,
                    *self.tools.values(), *(a for a, _ in self.dependencies),
                    *self.compile_only, NATIVE_LIB]
        missing = [str(path) for path in required if not path.is_file()]
        if not list((ROOT / "src").rglob("*.java")):
            missing.append(str(ROOT / "src") + " (Java sources)")
        if not (ROOT / "assets").is_dir():
            missing.append(str(ROOT / "assets"))
        if missing:
            raise RuntimeError("missing build inputs:\n  " + "\n  ".join(missing))

    def run(self, tool, *args):
        argv = [str(self.tools[tool]), *(str(arg) for arg in args)]
        if argv[0].lower().endswith(".bat"):
            argv = ["cmd.exe", "/c", *argv]
        env = os.environ.copy()
        env.update(TEMP=str(self.tmp), TMP=str(self.tmp), TMPDIR=str(self.tmp),
                   JAVA_HOME=str(self.jbr),
                   PATH=str(self.jbr / "bin") + os.pathsep + env.get("PATH", ""))
        result = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True,
                                text=True, encoding="utf-8", errors="replace")
        output = result.stdout + result.stderr
        # Keep compiler/dex warnings visible in the per-build record.
        with (self.out / "build.log").open("a", encoding="utf-8") as log:
            log.write(f"[{tool}] exit={result.returncode}\n{output}\n")
        if result.returncode:
            raise RuntimeError(f"{tool} failed:\n{output[-4000:]}\nLog: {self.out / 'build.log'}")
        return output

    def link(self):
        base = self.out / "base.apk"
        args = ["aapt2", "link", "-o", base, "-I", self.platform,
                "--manifest", self.manifest, "-A", ROOT / "assets",
                "--min-sdk-version", API, "--target-sdk-version", API]
        # Resources are optional: the UI is drawn in code, but the launcher icon needs a real
        # resource entry, so a res/ directory is compiled and linked when present.
        res = ROOT / "res"
        if res.is_dir():
            compiled = self.out / "res.zip"
            self.run("aapt2", "compile", "--dir", res, "-o", compiled)
            args += ["-R", compiled]
        self.run(*args)
        return base

    def extract_dependencies(self):
        jars = []
        for aar_path, drops in self.dependencies:
            with zipfile.ZipFile(aar_path) as aar:
                raw = aar.read("classes.jar")
            out_jar = self.out / (aar_path.stem + "-filtered.jar")
            with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as zout, \
                    zipfile.ZipFile(io.BytesIO(raw)) as zin:
                for item in zin.infolist():
                    if not any(item.filename.startswith(p) for p in drops):
                        zout.writestr(item, zin.read(item.filename))
            jars.append(out_jar)
        return jars

    def compile_java(self, jars):
        classes = self.out / "classes"
        classes.mkdir()
        # A response file avoids the Windows command-line length limit as modules grow.
        args = ["-source", "8", "-target", "8", "-bootclasspath",
                str(self.platform) + os.pathsep + str(self.stubs), "-classpath",
                os.pathsep.join(str(p) for p in [self.platform, *jars, *self.compile_only]),
                "-encoding", "UTF-8", "-Xlint:all", "-Xlint:-options",
                "-d", classes, *self.sources]
        argfile = self.out / "javac.args"
        argfile.write_text("\n".join(json.dumps(str(a), ensure_ascii=False) for a in args),
                           encoding="utf-8")
        self.run("javac", "@" + str(argfile))
        jar = self.out / "classes.jar"
        with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(classes.rglob("*.class")):
                archive.write(file, file.relative_to(classes).as_posix())
        return jar

    def dex(self, jar, dependencies):
        dex_dir = self.out / "dex"
        dex_dir.mkdir()
        d8_args = ["d8", "--release", "--min-api", API, "--lib", self.platform]
        for library in self.compile_only:
            d8_args += ["--lib", library]
        self.run(*d8_args, "--output", dex_dir, jar, *dependencies)
        dexes = sorted(dex_dir.glob("*.dex"))
        if [p.name for p in dexes] != ["classes.dex"]:
            raise RuntimeError("expected single classes.dex: " + repr(dexes))
        return dexes[0]

    def package_and_sign(self, base, dex):
        unsigned = self.out / "unsigned.apk"
        unsigned.write_bytes(base.read_bytes())
        with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
            archive.write(dex, "classes.dex")
            archive.write(NATIVE_LIB, "lib/arm64-v8a/libcwpersona.so")
        aligned = self.out / "aligned.apk"
        self.run("zipalign", "-p", "4", unsigned, aligned)
        self.run("apksigner", "sign", "--ks", self.keystore,
                 "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
                 "--key-pass", "pass:android", "--out", self.apk, aligned)

    def verify(self, dex):
        verification = self.run("apksigner", "verify", "-v", "--print-certs", self.apk)
        if not any("Verified using v" in line and ": true" in line
                   for line in verification.splitlines() if "v2" in line or "v3" in line):
            raise RuntimeError("v2/v3 signature not verified")
        self.run("zipalign", "-c", "-p", "4", self.apk)
        badging = self.run("aapt2", "dump", "badging", self.apk)
        permissions = re.findall(r"^uses-permission: name='([^']+)'", badging, re.MULTILINE)
        if sorted(permissions) != EXPECTED_PERMISSIONS:
            raise RuntimeError("unexpected permissions: " + repr(permissions))
        if f"package: name='{PACKAGE}'" not in badging:
            raise RuntimeError("unexpected package name")
        if "application-debuggable" in badging:
            raise RuntimeError("debuggable must stay false")
        dexdump = self.run("dexdump", "-d", dex)
        (self.out / "dexdump.txt").write_text(dexdump, encoding="utf-8")
        blocked = forbidden_api_refs(dexdump)
        if blocked:
            raise RuntimeError("network/location API outside shell boundary: " + repr(blocked))
        return permissions, blocked

    def record(self, versions, dex, permissions, blocked):
        assets = [f for f in sorted((ROOT / "assets").rglob("*")) if f.is_file()]
        record = {
            "local_build": str(self.out), "tools": versions,
            "deps": [{"path": str(a.relative_to(ROOT)), "sha256": sha(a), "dropped": list(d)}
                     for a, d in self.dependencies],
            "sources": [{"path": str(f.relative_to(ROOT)), "sha256": sha(f)}
                        for f in [self.manifest, ROOT / "build.py", *self.sources, *assets]],
            "dex_sha256": sha(dex),
            "native": {"path": str(NATIVE_LIB.relative_to(ROOT)), "sha256": sha(NATIVE_LIB)},
            "apk": {"path": str(self.apk), "bytes": self.apk.stat().st_size,
                    "sha256": sha(self.apk)},
            "permissions": permissions, "forbidden_api_refs": blocked,
        }
        (self.out / "build-manifest.json").write_text(
            json.dumps(record, indent=2, ensure_ascii=False), encoding="utf-8")
        print("Build: " + str(self.apk))
        print("SHA-256: " + record["apk"]["sha256"])
        print("Permissions: " + ", ".join(permissions))

    def build(self):
        self.validate()
        self.tmp.mkdir(parents=True)
        versions = {tool: self.run(tool, flag).strip() for tool, flag in (
            ("aapt2", "version"), ("d8", "--version"),
            ("apksigner", "--version"), ("javac", "-version"))}
        base = self.link()
        dependencies = self.extract_dependencies()
        dex = self.dex(self.compile_java(dependencies), dependencies)
        self.package_and_sign(base, dex)
        permissions, blocked = self.verify(dex)
        self.record(versions, dex, permissions, blocked)


def main():
    Build().build()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("Build failed: " + str(exc), file=sys.stderr)
        sys.exit(1)
