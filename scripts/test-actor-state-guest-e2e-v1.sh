#!/usr/bin/env bash
# Real Java JVM guest candidate; never standalone release certification.
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
: "$EXPECTED_HEAD"
[[ "$EXPECTED_HEAD" =~ ^[0-9a-f]{40}$ ]] || exit 64
[[ "$(git -C "$root" rev-parse HEAD)" == "$EXPECTED_HEAD" ]] || exit 78
for tool in java mvn timeout sha256sum git; do command -v "$tool" >/dev/null || exit 69; done
for name in positive sealed-direct-state-reject; do
  rel="tests/fixtures/actor-state-e2e-v1/$name.atto"
  src="$root/$rel"
  [[ -f "$src" && ! -L "$src" ]] || exit 78
  [[ "$(git -C "$root" ls-files --stage -- "$rel")" =~ ^100644[[:space:]] ]] || exit 78
  [[ "$(git -C "$root" rev-parse "HEAD:$rel")" == "$(git -C "$root" hash-object --no-filters "$src")" ]] || exit 78
done
(cd "$root" && mvn -B -ntp -DskipTests clean package && mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/conformance-classpath.txt)
class_file="$root/target/classes/dev/oreslang/launcher/OresMain.class"
cp_file="$root/target/conformance-classpath.txt"
[[ -f "$class_file" && -s "$cp_file" ]] || exit 69
run_guest() {
  timeout 90s java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -cp "$root/target/classes:$(cat "$cp_file")" dev.oreslang.launcher.OresMain "$@"
}
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
positive="$root/tests/fixtures/actor-state-e2e-v1/positive.atto"
negative="$root/tests/fixtures/actor-state-e2e-v1/sealed-direct-state-reject.atto"
printf 'ACTOR_E2E_OK|42\n' > "$tmp/expected"
run_guest --check "$positive" > "$tmp/check.out" 2> "$tmp/check.err" || { cat "$tmp/check.err" >&2; exit 1; }
for ((i=1;i<=16;i++)); do
  rc=0
  run_guest "$positive" > "$tmp/out" 2> "$tmp/err" || rc=$?
  [[ "$rc" == 0 ]] && cmp -s "$tmp/out" "$tmp/expected" && [[ ! -s "$tmp/err" ]] || {
    echo "JVM actor guest failed iteration=$i exit=$rc" >&2
    cat "$tmp/out" "$tmp/err" >&2; exit 1;
  }
done
rc=0
run_guest --check "$negative" > "$tmp/negative.out" 2> "$tmp/negative.err" || rc=$?
[[ "$rc" != 0 && "$rc" != 124 && "$rc" != 125 ]] || exit 1
grep -Eiq 'sealed.*(actor|state|field)|actor.state.*(denied|private|sealed|access)|handle_state.*(required|only|must)|critical-(read|write).*(required|only|must)' "$tmp/negative.err" || { cat "$tmp/negative.err" >&2; exit 1; }
[[ "$(git -C "$root" rev-parse HEAD)" == "$EXPECTED_HEAD" ]] || exit 78
for name in positive sealed-direct-state-reject; do
  rel="tests/fixtures/actor-state-e2e-v1/$name.atto"
  [[ "$(git -C "$root" rev-parse "HEAD:$rel")" == "$(git -C "$root" hash-object --no-filters "$root/$rel")" ]] || exit 78
done
printf 'JAVA_ACTOR_GUEST_EXECUTED_NOT_CERTIFIED|head=%s|repeats=16|source_sha256=%s|launcher_sha256=%s\n' "$EXPECTED_HEAD" "$(sha256sum "$positive" | cut -d' ' -f1)" "$(sha256sum "$class_file" | cut -d' ' -f1)"
