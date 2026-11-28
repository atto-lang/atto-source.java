#!/usr/bin/env python3
"""Exact-source JVM Oreslang guest execution for common-source parity v1.

Strictly JVM/Truffle guest execution, NOT compiled guest Native Image AOT.
The class-owned Array and private-field fixtures remain pending on this backend.
"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile

SCALAR = """pub fnc main(): int {
  return 20 + 22;
}
"""
SCALAR_SHA256 = "ab1ac790ba3928b8876b428230ae4215984380a221c02c604f85cd3fca1d790f"


def run(argv, cwd):
    result = subprocess.run(argv, cwd=cwd, capture_output=True,
                            timeout=100, check=False)
    if len(result.stdout) > 131072 or len(result.stderr) > 131072:
        raise RuntimeError("JVM guest output exceeded 128 KiB")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--java-probe", required=True, type=Path)
    args = parser.parse_args()
    try:
        root = args.repo_root.resolve(strict=True)
        probe = args.java_probe.resolve(strict=True)
        expected_probe = root / "contracts/v1/java_probe.py"
        if probe != expected_probe or not probe.is_file() or probe.is_symlink():
            raise RuntimeError("must use unmodified in-repository Java guest probe")
        launcher = root / "target/classes/dev/oreslang/launcher/OresMain.class"
        classpath = root / "target/conformance-classpath.txt"
        if (not launcher.is_file() or launcher.is_symlink() or
                not classpath.is_file() or not classpath.read_text().strip()):
            raise RuntimeError("missing Maven-built launcher or resolved dependency classpath")
        exact = SCALAR.encode("utf-8")
        if hashlib.sha256(exact).hexdigest() != SCALAR_SHA256:
            raise RuntimeError("shared source fixture bytes differ from canonical pinned fixture")
        with tempfile.TemporaryDirectory(prefix="ores-java-parity-v1-") as td:
            fixture = Path(td) / "scalar-main.ores"
            fixture.write_bytes(exact)
            check = run([sys.executable, str(probe), str(fixture)], root)
            if check.returncode != 0:
                raise RuntimeError("real Oreslang Java guest compiler rejected scalar fixture: " +
                                   check.stderr[:1200].decode("utf-8", "replace"))
            execution = run([sys.executable, str(probe), str(fixture), "--execute"], root)
            if execution.returncode != 42 or execution.stdout:
                raise RuntimeError("guest main() did not return exact exit code 42 with empty stdout")
        print("PASS: real JVM/Truffle guest scalar-main SHA256=" + SCALAR_SHA256,
              flush=True)
        print("NOT CERTIFIED: guest Native Image AOT; owned array; private field diagnostics")
        return 0
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print("JAVA_GUEST_V1_DENIED: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
