#!/usr/bin/env bash
# Exact-head candidate for REAL compiled AttoLang guest. No host emulation.
# This currently fails closed on missing source ABI / failure hooks / CONTROL.
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
: "${EXPECTED_HEAD:?supply exact 40-char PR head}"
[[ "$EXPECTED_HEAD" =~ ^[0-9a-f]{40}$ ]] || exit 64
[[ "$(git -C "$root" rev-parse HEAD)" == "$EXPECTED_HEAD" ]] || exit 78
for tool in java mvn timeout sha256sum git cmp; do command -v "$tool" >/dev/null || exit 69; done
relative="tests/fixtures/actor-two-peer-failure-v1/two-peer-failure-matrix.atto"
source="$root/$relative"
[[ -f "$source" && ! -L "$source" ]] || exit 78
[[ "$(git -C "$root" rev-parse "HEAD:$relative")" == eaac813262ca184930ba124180ed692e21329969 ]] || exit 78
[[ "$(git -C "$root" hash-object --no-filters "$source")" == eaac813262ca184930ba124180ed692e21329969 ]] || exit 78
[[ "$(git -C "$root" ls-files --stage -- "$relative")" =~ ^100644[[:space:]] ]] || exit 78
(cd "$root" && mvn -B -ntp -DskipTests clean package &&
    mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/conformance-classpath.txt)
class_file="$root/target/classes/dev/oreslang/launcher/OresMain.class"
cp_file="$root/target/conformance-classpath.txt"
[[ -s "$class_file" && -s "$cp_file" ]] || exit 69
run_guest() {
  timeout 90s java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI \
    --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
    -cp "$root/target/classes:$(cat "$cp_file")" dev.oreslang.launcher.OresMain "$@"
}
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
expected="$tmp/expected"
printf 'ACTOR_TWO_PEER_V1_OK|6|42\n' > "$expected"

# This must be the real compiler front end. A parser or host-model success
# is NOT enough. Preserve stderr to locate the earliest unsupported syntax.
if ! run_guest --check "$source" > "$tmp/check.stdout" 2> "$tmp/check.stderr"; then
  echo 'JAVA_ACTOR_TWO_PEER_COMPILER_NOT_ADMITTED|guest=NOT_EXECUTED' >&2
  cat "$tmp/check.stderr" >&2
  exit 1
fi
for ((iteration=1;iteration<=16;iteration++)); do
  rc=0
  run_guest "$source" > "$tmp/out" 2> "$tmp/err" || rc=$?
  if [[ "$rc" != 0 ]] || ! cmp -s "$tmp/out" "$expected" || [[ -s "$tmp/err" ]]; then
    echo "JAVA_ACTOR_TWO_PEER_GUEST_FAIL|iteration=$iteration|exit=$rc" >&2
    cat "$tmp/out" "$tmp/err" >&2
    exit 1
  fi
done
# Stdout is not independently authenticated proof of hooks or CONTROL.
# The runtime must expose a trusted, independently reviewed per-child ledger.
# Until then a green stdout is intentionally NOT sufficient.
: "${ACTOR_TWO_PEER_VERIFIED_LEDGER:?trusted runtime ledger required; stdout alone is not proof}"
[[ -s "$ACTOR_TWO_PEER_VERIFIED_LEDGER" && ! -L "$ACTOR_TWO_PEER_VERIFIED_LEDGER" ]] || exit 78
[[ "$(git -C "$root" rev-parse HEAD)" == "$EXPECTED_HEAD" ]] || exit 78
echo "JAVA_TWO_PEER_GUEST_CANDIDATE_ONLY|head=$EXPECTED_HEAD|repeats=16|fixture_git_blob=eaac813262ca184930ba124180ed692e21329969|launcher_sha256=$(sha256sum "$class_file" | cut -d' ' -f1)"
