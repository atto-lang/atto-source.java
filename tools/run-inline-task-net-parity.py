#!/usr/bin/env python3
"""Execute exact .NET-proven guest bytes with the actual Java compiler."""
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "tests/fixtures/inline-task-net-parity/manifest.json"

def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()

def blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()

def invoke(command, args):
    try:
        p = subprocess.run([*command, *args], cwd=ROOT, capture_output=True,
                           text=True, timeout=90)
        return {"exit": p.returncode, "stdout": p.stdout.strip(), "stderr": p.stderr}
    except subprocess.TimeoutExpired:
        return {"exit": None, "stdout": "", "stderr": "TIMEOUT (90s)"}

def main():
    mode = sys.argv[1]
    if mode not in ("jvm", "native"):
        raise SystemExit("Unknown mode")
    head = os.environ["EXPECTED_HEAD"]
    if git("rev-parse", "HEAD") != head:
        raise SystemExit("Wrong checkout")
    tree = git("rev-parse", "HEAD^{tree}")
    manifest = json.loads(MANIFEST.read_text())
    if len(manifest["cases"]) != 4 or len({x["name"] for x in manifest["cases"]}) != 4:
        raise SystemExit("Missing or duplicated case")
    if mode == "jvm":
        entry = ROOT / "target/classes/dev/oreslang/launcher/OresMain.class"
        classpath = (ROOT / "target/conformance-classpath.txt").read_text().strip()
        command = ["java", "-XX:+UnlockExperimentalVMOptions", "-XX:+EnableJVMCI",
                   "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED",
                   "-cp", str(ROOT / "target/classes") + os.pathsep + classpath,
                   "dev.oreslang.launcher.OresMain"]
    else:
        entry = ROOT / "target/ores-aot"
        if not os.access(entry, os.X_OK):
            raise SystemExit("Missing Native Image")
        command = [str(entry)]
    if not entry.is_file() or entry.is_symlink():
        raise SystemExit("Missing actual compiler entrypoint")
    digest = hashlib.sha256(entry.read_bytes()).hexdigest()
    failures = 0
    extension_failures = 0
    # New shared-source candidate, not covered by the historical .NET receipt.
    extension = {
        "name": "inline-local-loops-do",
        "path": "tests/fixtures/inline-task-net-parity/inline-local-loops-do.atto",
        "blob": "c502b65bd859fdfc2fb8b9221b9af997447c22e1",
        "expected": "42",
    }
    early = {
        "name": "inline-early-conditional",
        "path": "tests/fixtures/inline-task-net-parity/inline-early-conditional.atto",
        "blob": "678821cc705b2637b755f4d8a04e5aa81df80c98",
        "expected": "42",
    }
    early_failures = 0
    for case in [*manifest["cases"], extension, early]:
        src = ROOT / case["path"]
        if not src.is_file() or src.is_symlink():
            raise SystemExit("Missing regular source fixture")
        before = src.read_bytes()
        if blob(before) != case["blob"] or git("rev-parse", "HEAD:" + case["path"]) != case["blob"]:
            raise SystemExit("Fixture drift: " + case["name"])
        check = invoke(command, ["--no-prompt", "--check", str(src)])
        run = None
        if check["exit"] == 0:
            run = invoke(command, ["--no-prompt", str(src)])
        passed = (run is not None and run["exit"] == 0 and
                  run["stdout"] == case["expected"] and src.read_bytes() == before)
        if case is extension:
            extension_failures += not passed
        elif case is early:
            early_failures += not passed
        else:
            failures += not passed
        print(json.dumps({"case": case["name"], "head": head, "tree": tree,
                          "source_blob": case["blob"], "mode": mode,
                          "platform": platform.platform(), "entry_sha256": digest,
                          "check": check, "run": run, "passed": passed}), flush=True)
    if not failures:
        print("EXACT_INLINE_TASK_JAVA_PASS 4/4", flush=True)
    if not extension_failures:
        print("INLINE_TASK_DO_JAVA_PASS 1/1", flush=True)
    if not early_failures:
        print("INLINE_TASK_EARLY_CONDITIONAL_JAVA_PASS 1/1", flush=True)
    if failures or extension_failures or early_failures:
        raise SystemExit(f"PARITY_NOT_PROVEN: historical={failures}/4 new_do={extension_failures}/1 early={early_failures}/1 failed")

if __name__ == "__main__":
    main()
