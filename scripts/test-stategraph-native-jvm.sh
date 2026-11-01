#!/bin/bash
# Real JVM/Truffle Oreslang guest verification; not Native Image AOT.
set -euo pipefail
cd "$(dirname "$0")/.."
test "$(git rev-parse HEAD)" = "${EXPECTED_SOURCE_SHA:?pin exact source SHA}"
test -s target/classes/dev/oreslang/launcher/OresMain.class
test -s target/conformance-classpath.txt
test -f contracts/v1/java_probe.py
test ! -L contracts/v1/java_probe.py
printf 'JAVA_STATEGRAPH_GUEST_BEGIN head=%s tree=%s launcher_sha256=%s\n' \
  "$(git rev-parse HEAD)" "$(git rev-parse 'HEAD^{tree}')" \
  "$(sha256sum target/classes/dev/oreslang/launcher/OresMain.class | cut -d' ' -f1)"
for name in scalar branch nominal; do
  file="tests/stategraph-portability/$name.ores"
  git ls-files --error-unmatch -- "$file" >/dev/null
  test -f "$file" && test ! -L "$file"
  timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$file"
  output="$(mktemp)"
  error="$(mktemp)"
  set +e
  timeout --kill-after=3s 80s python3 contracts/v1/java_probe.py "$file" --execute > "$output" 2> "$error"
  status=$?
  set -e
  if [[ "$status" != 42 || -s "$output" ]]; then
    cat "$error" >&2
    rm -f "$output" "$error"
    echo "JAVA_STATEGRAPH_GUEST_NOT_ACCEPTED $name status=$status" >&2
    exit 1
  fi
  rm -f "$output" "$error"
  printf 'JAVA_STATEGRAPH_GUEST_PASS case=%s blob=%s result=42\n' \
    "$name" "$(git rev-parse "HEAD:$file")"
done
echo 'JAVA_STATEGRAPH_NATIVE_JVM_PASS count=3 (NOT Native Image AOT or full graph parity)'
