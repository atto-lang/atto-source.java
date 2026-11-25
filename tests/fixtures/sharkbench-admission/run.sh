#!/usr/bin/env bash
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
cd "$root"
here="$root/tests/fixtures/sharkbench-admission"
python3 - "$here/inputs.lock.json" <<'PY'
import json, subprocess, sys
lock=json.load(open(sys.argv[1]))
for path, expected in lock["blobs"].items():
    actual=subprocess.check_output(["git","hash-object",path],text=True).strip()
    if actual != expected: raise SystemExit(f"fixture mismatch: {path}: {actual} != {expected}")
    print(f"FIXTURE_BLOB={actual} {path}")
PY
java -XshowSettings:properties -version 2>&1 | grep -Eq '^[[:space:]]*java.specification.version = 25$'
mvn -B -ntp compile dependency:build-classpath -Dmdep.outputFile=target/sharkbench-classpath.txt
test -s target/sharkbench-classpath.txt
cp="$(cat target/sharkbench-classpath.txt)"
python3 - "$here" "$root/target/classes:$cp" <<'PY'
import os, pathlib, signal, socket, subprocess, sys, tempfile, time
here, cp=sys.argv[1:]
for port in (5001,5002):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        s.bind(("127.0.0.1",port))
with tempfile.TemporaryDirectory(prefix="sharkbench-admission-") as tmp:
    log=pathlib.Path(tmp)/"guest.log"
    with log.open("wb") as out:
        guest=subprocess.Popen(["java","-cp",cp,"dev.oreslang.launcher.OresMain",
            "--no-core-log","--permission-check=runtime",
            "--allow-net=127.0.0.1:5001,127.0.0.1:5002",
            str(pathlib.Path(here)/"sharkbench-web.atto")],
            stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
        try:
            deadline=time.monotonic()+60
            while True:
                if guest.poll() is not None: raise RuntimeError(f"guest exited before admission: {guest.returncode}")
                try:
                    with socket.create_connection(("127.0.0.1",5001),timeout=.25): break
                except OSError:
                    if time.monotonic()>=deadline: raise RuntimeError("guest listener not ready")
                    time.sleep(.1)
            subprocess.run([sys.executable,str(pathlib.Path(here)/"check_contract.py")],check=True,timeout=120)
            subprocess.run([sys.executable,"-B",str(pathlib.Path(here)/"check_faults.py")],check=True,timeout=120)
            if guest.poll() is not None: raise RuntimeError("guest exited during admission")
        finally:
            try: os.killpg(guest.pid,signal.SIGTERM)
            except ProcessLookupError: pass
            try: guest.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(guest.pid,signal.SIGKILL)
                guest.wait(timeout=5)
            print(log.read_bytes()[-16000:].decode("utf-8",errors="replace"))
PY
test -z "$(git status --porcelain --untracked-files=all)"
