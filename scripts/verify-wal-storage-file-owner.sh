#!/bin/sh
# Exact-head Java host primitive verification; this is NOT an Oreslang guest.
set -eu
export LC_ALL=C
: "${DB_EXPECTED_COMMIT:?independent exact source commit required}"
: "${DB_EXPECTED_TREE:?independent exact source tree required}"
: "${DB_JAVA_BIN:?absolute Java 21 runtime path required}"
: "${DB_JAVAC_BIN:?absolute Java 21 compiler path required}"
: "${DB_JAVA_SHA256:?independent Java binary SHA-256 required}"
: "${DB_JAVAC_SHA256:?independent Javac binary SHA-256 required}"
case "$DB_JAVA_BIN" in /*) ;; *) echo 'Java binary path must be absolute' >&2; exit 2;; esac
case "$DB_JAVAC_BIN" in /*) ;; *) echo 'Javac path must be absolute' >&2; exit 2;; esac
for hash in "$DB_JAVA_SHA256" "$DB_JAVAC_SHA256"; do
  case "$hash" in *[!0123456789abcdef]*|'') echo 'bad SHA-256 digest' >&2; exit 2;; esac
  test "${#hash}" -eq 64 || exit 2
done
for hash in "$DB_EXPECTED_COMMIT" "$DB_EXPECTED_TREE"; do
  case "$hash" in *[!0123456789abcdef]*|'') echo 'bad source pin' >&2; exit 2;; esac
  test "${#hash}" -eq 40 || exit 2
done
test "$(git rev-parse --show-toplevel)" = "$PWD"
test -z "$(git status --porcelain)"
test "$(git rev-parse HEAD)" = "$DB_EXPECTED_COMMIT"
test "$(git rev-parse HEAD^{tree})" = "$DB_EXPECTED_TREE"
printf '%s  %s\n' "$DB_JAVA_SHA256" "$DB_JAVA_BIN" | sha256sum -c - >/dev/null
printf '%s  %s\n' "$DB_JAVAC_SHA256" "$DB_JAVAC_BIN" | sha256sum -c - >/dev/null
command -v timeout >/dev/null 2>&1
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
"$DB_JAVAC_BIN" -Xlint:all -Werror -d "$tmp" \
  src/main/java/dev/oreslang/runtime/WalStorageFileOwner.java \
  src/test/java/dev/oreslang/runtime/WalStorageFileOwnerTest.java
timeout 30s "$DB_JAVA_BIN" -cp "$tmp" \
  dev.oreslang.runtime.WalStorageFileOwnerTest >"$tmp/out" 2>"$tmp/err" || {
  cat "$tmp/err" >&2; exit 1;
}
printf '%s\n' 'WAL_HOST_RUNTIME_BOUNDARY_PASS checks=20; NO ORESLANG GUEST OR DURABILITY CLAIM' > "$tmp/expected"
cmp -s "$tmp/out" "$tmp/expected" || { cat "$tmp/out" >&2; exit 1; }
printf 'SOURCE_COMMIT=%s SOURCE_TREE=%s HOST_JAVA_TESTS=20 NO_GUEST_PROOF\n' \
  "$DB_EXPECTED_COMMIT" "$DB_EXPECTED_TREE"
