#!/usr/bin/env python3
"""Run the authoritative merged generic-scalar corpus on real Java guest runtimes.

Requires a clean SHA-pinned oreslang-common-source Git tree, not a vendored copy.
JVM uses the in-repository Maven-resolved real launcher probe; native-image mode
requires an independently compiled Oreslang AOT launcher. No synthetic exits.
"""
import argparse
from pathlib import Path
import subprocess
import sys

COMMON_ROOT_TREE = "858c37d48a4c3a886260b26320bb0dd931b228ab"
COMMON_CONTRACT_TREE = "fd5301ebd354049917e2ecaf1da920b661d6d829"
COMMON_SUITE_DIGEST = "80a5053f8ba147346793a209e4f8ec04b8daf4cedb6a149fb1d127ea4af4ffb5"
PROJECT = Path(__file__).resolve().parents[1]


def read(command, timeout=30):
    return subprocess.run(command, capture_output=True, text=True,
                          timeout=timeout, check=False)


def validate_common_source(root):
    suite = root / "contracts/v1/generic-scalar"
    verifier = suite / "verify.py"
    runner = root / "scripts/verify_generic_guest_parity.py"
    if not verifier.is_file() or verifier.is_symlink() or not runner.is_file() or runner.is_symlink():
        raise ValueError("missing/symlinked canonical common-source verifier")
    git = read(["git", "-C", str(root), "rev-parse", "--show-toplevel"])
    if git.returncode:
        raise ValueError("canonical source requires an actual Git checkout")
    top = Path(git.stdout.strip()).resolve(strict=True)
    rel_root = root.relative_to(top)
    rel_suite = suite.relative_to(top)
    root_ref = "HEAD^{tree}" if not rel_root.parts else "HEAD:" + rel_root.as_posix()
    tree = read(["git", "-C", str(top), "rev-parse", root_ref])
    contract = read(["git", "-C", str(top), "rev-parse", "HEAD:" + rel_suite.as_posix()])
    if tree.returncode or tree.stdout.strip() != COMMON_ROOT_TREE:
        raise ValueError("untrusted common-source Git tree (requires merged PR #32 snapshot)")
    if contract.returncode or contract.stdout.strip() != COMMON_CONTRACT_TREE:
        raise ValueError("generic corpus does not match canonical pinned subtree")
    status = read(["git", "-C", str(top), "status", "--porcelain=v1",
                   "--untracked-files=all", "--", rel_root.as_posix() if rel_root.parts else "."])
    if status.returncode or status.stdout.strip():
        raise ValueError("modified or untracked common-source files")
    proof = read([sys.executable, "-B", str(verifier), "--validate-only"], timeout=20)
    expected = "GENERIC_SCALAR_SUITE_SHA256=" + COMMON_SUITE_DIGEST + "; cases=20"
    if proof.returncode or expected not in proof.stdout:
        raise ValueError("generic scalar fixture manifest/hash mismatch")
    return runner


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--common-source-root", type=Path, required=True)
    ap.add_argument("--backend", choices=("jvm", "native-image"), required=True)
    ap.add_argument("--native-binary", type=Path)
    ap.add_argument("--report", type=Path)
    args = ap.parse_args()
    try:
        runner = validate_common_source(args.common_source_root.resolve(strict=True))
        argv = [sys.executable, "-B", str(runner), "--backend", args.backend]
        if args.backend == "jvm":
            if args.native_binary:
                ap.error("--native-binary forbidden in jvm mode")
            argv += ["--java-probe", str(PROJECT / "contracts/v1/java_probe.py")]
        else:
            if not args.native_binary:
                ap.error("--native-binary required for native-image")
            argv += ["--native-binary", str(args.native_binary.resolve(strict=True))]
        if args.report:
            argv += ["--report", str(args.report.resolve())]
        result = subprocess.run(argv, timeout=600, check=False)
        return result.returncode
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(f"CANONICAL_GENERIC_GATE_FAILED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
