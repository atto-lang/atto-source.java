#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
expected="${ATTO_EXPECTED_HEAD_SHA:-$(git rev-parse HEAD)}"
if [[ ! "$expected" =~ ^[0-9a-f]{40}$ ]] || [[ "$(git rev-parse HEAD)" != "$expected" ]]; then
  echo 'M10_RESTRICTED_SENDER_NOT_EXECUTED: wrong exact source HEAD' >&2
  exit 2
fi
for file in \
    src/compiler/m10/AttoM10RestrictedSenderCompiler.java \
    src/m10/AttoM10TrustedScalarProxy.java \
    tests/m10/M10RestrictedSenderCompilerProbe.java \
    fixtures/m10/remote_adder_sender.atto; do
  expected_blob="$(git rev-parse "HEAD:$file")"
  test "$(git hash-object "$file")" = "$expected_blob" || {
    echo "M10_RESTRICTED_SENDER_NOT_EXECUTED: modified $file" >&2
    exit 2
  }
done
: "${JAVAC:=javac}"
: "${JAVA:=java}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
"$JAVAC" --release 21 -Xlint:all,-try -Werror -d "$work" \
  src/compiler/m10/AttoM10RestrictedSenderCompiler.java \
  src/m10/AttoM10TrustedScalarProxy.java
"$JAVA" -cp "$work" AttoM10RestrictedSenderCompiler \
  fixtures/m10/remote_adder_sender.atto "$work/M10GeneratedSender.java"
"$JAVAC" --release 21 -Xlint:all,-try -Werror -cp "$work" -d "$work" \
  "$work/M10GeneratedSender.java" \
  tests/m10/M10RestrictedSenderCompilerProbe.java
"$JAVA" -cp "$work" M10RestrictedSenderCompilerProbe \
  fixtures/m10/remote_adder_sender.atto
printf 'M10_RESTRICTED_ATTO_SENDER_EXACT_SOURCE_LOCAL=PASS\n'
printf 'M10_LOCAL_TESTED_HEAD=%s\n' "$expected"
