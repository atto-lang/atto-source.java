#!/usr/bin/env bash
# Exact production CONTROL retry class + dependency-free stress checks.
# Does not validate full ActorRuntime, JUnit, Native Image or LLVM lowering.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
source_file="$root/src/main/java/dev/oreslang/runtime/ControlWakeRetryQueue.java"
test_file="$root/src/test/java/dev/oreslang/runtime/ControlWakeRetryStandaloneStress.java"
if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
    echo 'A Java 17+ JDK with javac is required.' >&2
    exit 2
fi
if [[ ! -f "$source_file" || ! -f "$test_file" ]]; then
    echo 'Run this script in the full Oreslang Java repository checkout.' >&2
    exit 2
fi
# The required hash is an immutable Git blob, not a mutable PR branch name.
# This proves this *one source file*; it does not attest the full checkout.
if [[ -n "${ORESLANG_EXPECTED_RETRY_BLOB_SHA:-}" ]]; then
    if ! command -v git >/dev/null 2>&1; then
        echo 'git is required for exact-blob verification.' >&2
        exit 2
    fi
    actual="$(git hash-object -- "$source_file")"
    if [[ "$actual" != "$ORESLANG_EXPECTED_RETRY_BLOB_SHA" ]]; then
        echo "retry source mismatch: expected $ORESLANG_EXPECTED_RETRY_BLOB_SHA, actual $actual" >&2
        exit 2
    fi
fi
work="$(mktemp -d "${TMPDIR:-/tmp}/oreslang-retry.XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT
# Fail the gate on compiler warnings as well as invalid source. The stress
# runner and the production class use only Java 17 language facilities.
javac -encoding UTF-8 -Xlint:all -Werror -d "$work" "$source_file" "$test_file"
java -Xss512k -ea -cp "$work" dev.oreslang.runtime.ControlWakeRetryStandaloneStress
