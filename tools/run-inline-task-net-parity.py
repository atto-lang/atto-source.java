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
    # New candidates have their own markers. Historical receipts apply only
    # to the four original manifest cases, never to an extension.
    additions = [
        ("inline-local-loops-do", "c502b65bd859fdfc2fb8b9221b9af997447c22e1",
         "INLINE_TASK_DO_JAVA_PASS"),
        ("inline-early-conditional", "678821cc705b2637b755f4d8a04e5aa81df80c98",
         "INLINE_TASK_EARLY_CONDITIONAL_JAVA_PASS"),
        ("inline-multi-conditional", "9511cefaafedaf820375912ac2c58c7e1136055a",
         "INLINE_TASK_MULTI_CONDITIONAL_JAVA_PASS"),
        ("inline-shortcircuit-writeback", "adea3e544fd92d4b79387f7dc9881e36ecae65f3",
         "INLINE_TASK_SHORTCIRCUIT_JAVA_PASS"),
    ]
    extras = [{"name": name, "path": f"tests/fixtures/inline-task-net-parity/{name}.atto",
               "blob": sha, "expected": "42"} for name, sha, _ in additions]
    markers = {name: marker for name, _, marker in additions}
    historical = {case["name"] for case in manifest["cases"]}
    if historical.intersection(markers) or len(markers) != len(additions):
        raise SystemExit("Duplicated historical/extension fixture")
    failures = 0
    extra_failures = []
    for case in [*manifest["cases"], *extras]:
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
        if case["name"] in markers:
            if passed:
                print(markers[case["name"]] + " 1/1", flush=True)
            else:
                extra_failures.append(case["name"])
        else:
            failures += not passed
        print(json.dumps({"case": case["name"], "head": head, "tree": tree,
                          "source_blob": case["blob"], "mode": mode,
                          "platform": platform.platform(), "entry_sha256": digest,
                          "check": check, "run": run, "passed": passed}), flush=True)
    if not failures:
        print("EXACT_INLINE_TASK_JAVA_PASS 4/4", flush=True)
    if failures or extra_failures:
        raise SystemExit(f"PARITY_NOT_PROVEN: historical={failures}/4 extensions={extra_failures}")

if __name__ == "__main__":
    main()
