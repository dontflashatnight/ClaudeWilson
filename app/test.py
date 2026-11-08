#!/usr/bin/env python3
"""Verify the rule layer off-device: layering rules, compile without Android, run self-tests.

The compile step deliberately puts no android.jar on the classpath. A stray platform import in
model/ or rule/ therefore fails the build here instead of in review.
"""
import os
import re
import secrets
import subprocess
import sys
import tempfile
from pathlib import Path

APP = Path(__file__).resolve().parent
PACKAGE = APP / "src" / "com" / "walter" / "claudewilson"
SELFTEST = APP / "selftest"
# Kept out of out/ so a test run never mixes with build records (and never inherits their ACLs).
# mkdtemp() is deliberately not used: it creates the directory with mode 0700, which under a
# restricted Windows token (DSH's sandbox) also cuts off the token's own package grant, and javac
# then cannot create package directories inside it. mkdir() keeps the inherited permissions.
OUT = Path(tempfile.gettempdir()) / ("ClaudeWilson-selftest-" + secrets.token_hex(4))
OUT.mkdir()

ANDROID_IMPORT = re.compile(r"^\s*import\s+android[.\w]*;", re.MULTILINE)
CJK = re.compile(r"[\u4e00-\u9fff]")


def tool(name):
    """Prefer the toolchain build.py uses, fall back to PATH."""
    jbr = os.environ.get("CW_JBR")
    if jbr:
        candidate = Path(jbr) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if candidate.is_file():
            return str(candidate)
    return name


def layering_problems():
    problems = []
    for layer in ("model", "rule"):
        for path in sorted((PACKAGE / layer).rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            relative = path.relative_to(APP).as_posix()
            if ANDROID_IMPORT.search(text):
                problems.append(f"{relative}: imports android.*")
            if CJK.search(text):
                problems.append(f"{relative}: contains user-facing copy")
    return problems


def main():
    problems = layering_problems()
    if problems:
        print("layering violations:", *problems, sep="\n  ")
        return 1

    sources = (sorted((PACKAGE / "model").rglob("*.java"))
               + sorted((PACKAGE / "rule").rglob("*.java"))
               + sorted(SELFTEST.rglob("*.java")))
    compile_args = [tool("javac"), "-source", "8", "-target", "8",
                    "-encoding", "UTF-8", "-Xlint:all", "-Xlint:-options",
                    "-d", str(OUT)] + [str(path) for path in sources]
    compiled = subprocess.run(compile_args, capture_output=True, text=True)
    if compiled.stdout or compiled.stderr:
        print(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        return compiled.returncode

    run = subprocess.run([tool("java"), "-cp", str(OUT),
                          "com.walter.claudewilson.selftest.SelfTest"],
                         capture_output=True, text=True)
    print(run.stdout.rstrip())
    if run.returncode:
        print(run.stderr.rstrip(), file=sys.stderr)
    return run.returncode


if __name__ == "__main__":
    sys.exit(main())
