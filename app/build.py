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
import struct
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
COMPILE_SDK = "35"
MIN_SDK = "33"
TARGET_SDK = "35"
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
    # The synthetic-location writers are the only classes allowed to touch android.location: they
    # construct fake fixes and never read real coordinates from the platform.
    location_hook = app_prefix + "hook/LocationHooks;"
    location_hook_inner = app_prefix + "hook/LocationHooks$"
    sys_location_hook = app_prefix + "hook/SystemLocationHooks;"
    sys_location_hook_inner = app_prefix + "hook/SystemLocationHooks$"
    # WebView geolocation is granted here; coordinates still come from the location hooks.
    webview_hook = app_prefix + "hook/WebViewHooks;"
    webview_hook_inner = app_prefix + "hook/WebViewHooks$"
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
        if (ref.startswith("Landroid/location/")
                and owner != location_hook
                and not owner.startswith(location_hook_inner)
                and owner != sys_location_hook
                and not owner.startswith(sys_location_hook_inner)) or (
                ref.startswith("Landroid/webkit/")
                and owner != webview_hook
                and not owner.startswith(webview_hook_inner)) or (
                owner != shell_probe and ref.startswith(network)) or (
                ref.startswith(shell_probe + ".") and owner not in (bridge, shell_probe)):
            blocked.add(owner + " -> " + ref)
    return sorted(blocked)


# Explicit allowlist for API > MIN_SDK framework references that are strictly guarded at runtime
# by Build.VERSION.SDK_INT / HookContext.HOST_SDK_INT checks. Currently empty: all direct bytecode
# references stay <= MIN_SDK (33).
GUARDED_POST_MIN_SDK_APIS = set()


def post_min_sdk_refs(dexdump, api_versions_path, min_sdk, allowed=GUARDED_POST_MIN_SDK_APIS):
    """Static gate against unguarded Android framework references introduced after min_sdk."""
    tree = ET.parse(api_versions_path)
    root = tree.getroot()
    classes = {}
    for cls_el in root.findall("class"):
        name = cls_el.attrib["name"]
        cls_since = int(cls_el.attrib.get("since", "1"))
        parents = [el.attrib["name"] for el in cls_el.findall("extends")]
        parents += [el.attrib["name"] for el in cls_el.findall("implements")]
        methods = {}
        for m_el in cls_el.findall("method"):
            methods[m_el.attrib["name"]] = int(m_el.attrib.get("since", str(cls_since)))
        fields = {}
        for f_el in cls_el.findall("field"):
            fields[f_el.attrib["name"]] = int(f_el.attrib.get("since", str(cls_since)))
        classes[name] = {
            "since": cls_since,
            "parents": parents,
            "methods": methods,
            "fields": fields,
        }

    def lookup_member_since(cls_name, bucket, key):
        seen = set()
        stack = [cls_name]
        best = None
        while stack:
            cur = stack.pop()
            if cur in seen or cur not in classes:
                continue
            seen.add(cur)
            entry = classes[cur]
            if key in entry[bucket]:
                val = entry[bucket][key]
                best = val if best is None else min(best, val)
            stack.extend(entry["parents"])
        return best

    # Map app-defined classes to their superclasses so calls on app subclasses that inherit
    # post-minSdk framework methods (e.g. MainActivity -> Activity) cannot bypass the gate.
    app_supers = {}
    cur_cls = None
    for line in dexdump.splitlines():
        m_cls = re.search(r"Class descriptor\s*:\s*'L([^;]+);'", line)
        if m_cls:
            cur_cls = m_cls.group(1)
            continue
        if cur_cls and "Superclass" in line:
            m_sup = re.search(r"Superclass\s*:\s*'L([^;]+);'", line)
            if m_sup:
                app_supers[cur_cls] = m_sup.group(1)

    def resolve_framework_class(cls_name):
        seen = set()
        cur = cls_name
        while cur and cur not in seen:
            if cur in classes:
                return cur
            seen.add(cur)
            cur = app_supers.get(cur)
        return None

    owner = None
    violations = set()
    app_prefix = "Lcom/walter/claudewilson/"
    invoke_re = re.compile(r", L([^;]+);\.([^:]+):([^\s]+)")
    field_re = re.compile(r", L([^;]+);\.([^:]+):")
    type_re = re.compile(r", \[*L([^;]+);")

    for line in dexdump.splitlines():
        match = re.search(r"Class descriptor\s*:\s*'([^']+)'", line)
        if match:
            owner = match.group(1)
            continue
        if not owner or not owner.startswith(app_prefix):
            continue
        if "invoke-" in line:
            m = invoke_re.search(line)
            if not m:
                continue
            cls, name, desc = m.group(1), m.group(2), m.group(3)
            fw_cls = resolve_framework_class(cls)
            if fw_cls is None:
                continue
            ref = f"{fw_cls}.{name}{desc}"
            if ref in allowed:
                continue
            since = lookup_member_since(fw_cls, "methods", name + desc)
            cls_since = classes[fw_cls]["since"]
            eff = since if since is not None else cls_since
            if eff > min_sdk:
                violations.add(f"{owner} -> {ref} (API {eff})")
        elif any(op in line for op in ("sget", "iget", "sput", "iput")):
            m = field_re.search(line)
            if not m:
                continue
            cls, name = m.group(1), m.group(2)
            fw_cls = resolve_framework_class(cls)
            if fw_cls is None:
                continue
            ref = f"{fw_cls}.{name}"
            if ref in allowed:
                continue
            since = lookup_member_since(fw_cls, "fields", name)
            cls_since = classes[fw_cls]["since"]
            eff = since if since is not None else cls_since
            if eff > min_sdk:
                violations.add(f"{owner} -> {ref} (API {eff})")
        elif any(op in line for op in ("new-instance", "check-cast", "instance-of",
                                       "const-class", "new-array")):
            m = type_re.search(line)
            if not m:
                continue
            cls = m.group(1)
            if cls not in classes or cls in allowed:
                continue
            if classes[cls]["since"] > min_sdk:
                violations.add(f"{owner} -> {cls} (API {classes[cls]['since']})")
    return sorted(violations)


def inspect_elf64(path):
    """Verify AArch64 ELF 16KB PT_LOAD alignment and GNU_PROPERTY_AARCH64_FEATURE_1_AND (BTI|PAC)."""
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise RuntimeError(f"not a 64-bit little-endian ELF: {path}")
    e_phoff, e_shoff = struct.unpack_from("<QQ", data, 32)
    e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHHHH", data, 54)
    load_aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        p_align = struct.unpack_from("<Q", data, off + 48)[0]
        if p_type == 1:  # PT_LOAD
            load_aligns.append(p_align)
    if not load_aligns or any(a < 0x4000 for a in load_aligns):
        raise RuntimeError(f"ELF PT_LOAD not 16KB-aligned ({load_aligns}): {path}")
    has_gnu_property = False
    aarch64_feature_1 = 0
    if e_shoff and e_shstrndx < e_shnum:
        str_off = struct.unpack_from("<Q", data, e_shoff + e_shstrndx * e_shentsize + 24)[0]
        for i in range(e_shnum):
            sh_off = e_shoff + i * e_shentsize
            sh_name = struct.unpack_from("<I", data, sh_off)[0]
            end = data.find(b"\x00", str_off + sh_name)
            if end != -1 and data[str_off + sh_name:end] == b".note.gnu.property":
                has_gnu_property = True
                sec_off, sec_size = struct.unpack_from("<QQ", data, sh_off + 24)
                pos = sec_off
                limit = sec_off + sec_size
                while pos + 12 <= limit:
                    namesz, descsz, ntype = struct.unpack_from("<III", data, pos)
                    pos += 12
                    name_bytes = data[pos:pos + namesz]
                    pos += (namesz + 3) & ~3
                    desc_end = min(pos + descsz, limit)
                    if ntype == 5 and name_bytes.rstrip(b"\x00") == b"GNU":
                        p = pos
                        while p + 8 <= desc_end:
                            pr_type, pr_datasz = struct.unpack_from("<II", data, p)
                            p += 8
                            if pr_type == 0xC0000000 and pr_datasz >= 4 and p + 4 <= desc_end:
                                aarch64_feature_1 |= struct.unpack_from("<I", data, p)[0]
                            p += (pr_datasz + 7) & ~7
                    pos += (descsz + 3) & ~3
                break
    bti_pac = (aarch64_feature_1 & 0x3) == 0x3
    if not has_gnu_property or not bti_pac:
        raise RuntimeError(
            f"ELF missing ARM64 BTI/PAC .note.gnu.property (feature_1=0x{aarch64_feature_1:x}): {path}")
    if b"com.termux" in data:
        raise RuntimeError(f"ELF embeds Termux path (strip DT_RUNPATH): {path}")
    return {
        "pt_load_align": min(load_aligns),
        "gnu_property_note": has_gnu_property,
        "aarch64_feature_1": aarch64_feature_1,
        "bti_pac": bti_pac,
    }


class Build:
    def __init__(self):
        workspace = environment_path("CW_WORKSPACE")
        self.jbr = environment_path("CW_JBR")
        sdk_override = os.environ.get("CW_ANDROID_SDK", "").strip() or os.environ.get("ANDROID_HOME", "").strip()
        sdk = Path(sdk_override).expanduser().resolve() if sdk_override else (workspace / "tools/android-sdk")
        self.platform = sdk / f"platforms/android-{COMPILE_SDK}/android.jar"
        self.api_versions = sdk / f"platforms/android-{COMPILE_SDK}/data/api-versions.xml"
        bt_root = sdk / "build-tools"
        tools = bt_root / "35.0.0"
        if not tools.is_dir() and bt_root.is_dir():
            versions = sorted((p for p in bt_root.iterdir() if p.is_dir()), reverse=True)
            if versions:
                tools = versions[0]
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
        out_env = os.environ.get("CW_OUT_DIR", "").strip()
        out_root = Path(out_env).expanduser().resolve() if out_env else (ROOT / "out")
        self.out = out_root / ("build-" + stamp + "-" + secrets.token_hex(3))
        self.apk = self.out / "ClaudeWilson.apk"
        self.tmp = self.out / "tmp"

    def validate(self):
        required = [self.platform, self.api_versions, self.keystore, self.stubs, self.manifest,
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

    def sync_audited_targets(self):
        baselines_dir = ROOT.parent / "tools" / "target_watch" / "baselines"
        if not baselines_dir.is_dir():
            return
        lines = [
            "# Auto-generated by build.py from tools/target_watch/baselines/*/latest.json",
            "# Format: <target_id>=<versionCode>|<versionName>",
        ]
        found = 0
        for child in sorted(baselines_dir.iterdir()):
            latest = child / "latest.json"
            if not latest.is_file():
                continue
            try:
                snap = json.loads(latest.read_text(encoding="utf-8"))
                meta = snap.get("meta", {})
                vc = int(meta.get("version_code", 0))
                vn = str(meta.get("version_name", "")).strip()
                if vc > 0:
                    lines.append(f"{child.name}={vc}|{vn}")
                    found += 1
            except Exception:
                continue
        if found:
            target = ROOT / "assets" / "catalog" / "audited-targets.txt"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def link(self):
        self.sync_audited_targets()
        base = self.out / "base.apk"
        args = ["aapt2", "link", "-o", base, "-I", self.platform,
                "--manifest", self.manifest, "-A", ROOT / "assets",
                "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK]
        # Resources are optional: the UI is drawn in code, but the launcher icon needs a real
        # resource entry, so a res/ directory is compiled and linked when present.
        res = ROOT / "res"
        if res.is_dir():
            compiled = self.out / "res.zip"
            self.run("aapt2", "compile", "--dir", res, "-o", compiled)
            args += ["--auto-add-overlay", "-R", compiled]
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
        d8_args = ["d8", "--release", "--min-api", MIN_SDK, "--lib", self.platform]
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
            archive.write(dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
            archive.write(NATIVE_LIB, "lib/arm64-v8a/libcwpersona.so",
                          compress_type=zipfile.ZIP_STORED)
        aligned = self.out / "aligned.apk"
        self.run("zipalign", "-P", "16", "4", unsigned, aligned)
        self.run("apksigner", "sign", "--ks", self.keystore,
                 "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
                 "--key-pass", "pass:android", "--out", self.apk, aligned)

    def verify(self, dex):
        verification = self.run("apksigner", "verify", "-v", "--print-certs", self.apk)
        if not any("Verified using v" in line and ": true" in line
                   for line in verification.splitlines() if "v2" in line or "v3" in line):
            raise RuntimeError("v2/v3 signature not verified")
        self.run("zipalign", "-c", "-P", "16", "4", self.apk)
        with zipfile.ZipFile(self.apk) as archive:
            so_info = archive.getinfo("lib/arm64-v8a/libcwpersona.so")
            if so_info.compress_type != zipfile.ZIP_STORED:
                raise RuntimeError("libcwpersona.so must be stored uncompressed for 16KB mmap")
        elf_info = inspect_elf64(NATIVE_LIB)
        badging = self.run("aapt2", "dump", "badging", self.apk)
        permissions = re.findall(r"^uses-permission: name='([^']+)'", badging, re.MULTILINE)
        if sorted(permissions) != EXPECTED_PERMISSIONS:
            raise RuntimeError("unexpected permissions: " + repr(permissions))
        if f"package: name='{PACKAGE}'" not in badging:
            raise RuntimeError("unexpected package name")
        if f"minSdkVersion:'{MIN_SDK}'" not in badging and f"sdkVersion:'{MIN_SDK}'" not in badging:
            raise RuntimeError(f"unexpected minSdkVersion (expected {MIN_SDK})")
        if f"targetSdkVersion:'{TARGET_SDK}'" not in badging:
            raise RuntimeError(f"unexpected targetSdkVersion (expected {TARGET_SDK})")
        if "application-debuggable" in badging:
            raise RuntimeError("debuggable must stay false")
        dexdump = self.run("dexdump", "-d", dex)
        (self.out / "dexdump.txt").write_text(dexdump, encoding="utf-8")
        blocked = forbidden_api_refs(dexdump)
        if blocked:
            raise RuntimeError("network/location API outside shell boundary: " + repr(blocked))
        api_violations = post_min_sdk_refs(dexdump, self.api_versions, int(MIN_SDK))
        if api_violations:
            raise RuntimeError(
                f"unguarded API > {MIN_SDK} references:\n  " + "\n  ".join(api_violations))
        return permissions, blocked, elf_info

    def record(self, versions, dex, permissions, blocked, elf_info):
        assets = [f for f in sorted((ROOT / "assets").rglob("*")) if f.is_file()]
        record = {
            "local_build": str(self.out), "tools": versions,
            "sdk": {"compile": int(COMPILE_SDK), "min": int(MIN_SDK), "target": int(TARGET_SDK)},
            "deps": [{"path": str(a.relative_to(ROOT)), "sha256": sha(a), "dropped": list(d)}
                     for a, d in self.dependencies],
            "sources": [{"path": str(f.relative_to(ROOT)), "sha256": sha(f)}
                        for f in [self.manifest, ROOT / "build.py", *self.sources, *assets]],
            "dex_sha256": sha(dex),
            "native": {"path": str(NATIVE_LIB.relative_to(ROOT)), "sha256": sha(NATIVE_LIB),
                       **elf_info},
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
        permissions, blocked, elf_info = self.verify(dex)
        self.record(versions, dex, permissions, blocked, elf_info)


def main():
    Build().build()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("Build failed: " + str(exc), file=sys.stderr)
        sys.exit(1)
