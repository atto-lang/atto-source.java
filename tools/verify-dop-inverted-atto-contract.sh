#!/usr/bin/env bash
# Source-org, no-Python invariant gate. Must be run BEFORE and AFTER guest tests.
# Source, fixture, compiler and test mutations must not alter the tested tree.
set -euo pipefail

expected="${EXPECTED_HEAD:?expected immutable PR head SHA required}"
if [[ ! "$expected" =~ ^[0-9a-f]{40}$ ]]; then
    echo 'FAIL: malformed expected PR head' >&2
    exit 1
fi
if [[ "$(git rev-parse HEAD)" != "$expected" ]]; then
    echo 'FAIL: checkout no longer matches immutable PR head' >&2
    exit 1
fi
if ! git diff --quiet --exit-code || ! git diff --cached --quiet --exit-code; then
    echo 'FAIL: source files changed during inverted guest qualification' >&2
    exit 1
fi

# Require immutable Git tree blobs AND actual on-disk bytes for each fixture:
# a forged working-tree hash alone or a staged replacement cannot qualify.
checked=0
while read -r name wanted; do
    path="contracts/dop-inverted-v1/$name"
    if [[ ! -f "$path" || -L "$path" ]]; then
        echo "FAIL: absent or linked canonical guest fixture: $name" >&2
        exit 1
    fi
    actual_bytes="$(wc -c < "$path")"
    if [[ ! "$actual_bytes" =~ ^[0-9]+$ || "$actual_bytes" -eq 0 || "$actual_bytes" -gt 65536 ]]; then
        echo "FAIL: empty/oversize guest fixture: $name" >&2
        exit 1
    fi
    committed="$(git rev-parse "HEAD:$path")"
    disk="$(git hash-object --no-filters -- "$path")"
    if [[ "$committed" != "$wanted" || "$disk" != "$wanted" ]]; then
        echo "FAIL: canonical guest fixture tree or bytes changed: $name" >&2
        exit 1
    fi
    checked=$((checked + 1))
done <<'DOP_CANONICAL_SOURCES'
swap-reuse.atto 8f5599a9e0b11ded2929110b4936441fceb2ef21
removed-index-trap.atto c3c87cfd2515707932734e183bb5768436526a96
positive-immutable-column-swap.atto 863b1f20137656a277fc7bc04415a6f001f76512
DOP_CANONICAL_SOURCES

[[ "$checked" -eq 3 ]]
printf 'DOP_EXACT_HEAD=%s\n' "$expected"
printf 'DOP_SOURCE_TREE=%s\n' "$(git rev-parse 'HEAD^{tree}')"
printf 'DOP_CANONICAL_SOURCE_BLOBS_MATCHED=%s\n' "$checked"
