#!/usr/bin/env bash
# Source-free, local fault-injection tests of the PR implementation policy gate.
set -euo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/scripts/check_atto_first_policy.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/repo" && cd "$work/repo"
git init -q -b main
git config user.name 'Atto policy test'
git config user.email 'atto-policy@example.invalid'
printf 'initial\n' > README.md
mkdir -p src
printf 'class Shared {}\n' > src/Shared.java
git add . && git commit -qm baseline
base="$(git rev-parse HEAD)"
printf 'documentation\n' >> README.md
git commit -qam docs
no_java="$(git rev-parse HEAD)"
mkdir -p src
printf 'class Host {}\n' > src/Host.java
git add . && git commit -qm host
java_head="$(git rev-parse HEAD)"
cat > "$work/valid" <<'BODY'
AttoLang guest implementation: none: this is solely the parser's AST node admission support.
Java host/compiler exception: parser: adds an AST node so a future .atto body can be compiled.
Guest test evidence: NOT EXECUTED: the exact-head guest compiler runner has not executed.
BODY
cat > "$work/partial" <<'BODY'
AttoLang guest implementation: none: parser support only, full source comes later.
Java host/compiler exception: parser: required AST admission for the new guest construct.
BODY
cat > "$work/placeholders" <<'BODY'
AttoLang guest implementation: TODO
Java host/compiler exception: TBD
Guest test evidence: NOT EXECUTED: actual guest execution has not happened.
BODY
: > "$work/empty"
cat "$work/valid" > "$work/duplicate_conflict"
printf '%s\n' 'Guest test evidence: EXECUTED: contradictory second authority record.' >> "$work/duplicate_conflict"
cat "$work/valid" > "$work/duplicate_identical"
printf '%s\n' 'Guest test evidence: NOT EXECUTED: the exact-head guest compiler runner has not executed.' >> "$work/duplicate_identical"
pass=0
expect_pass() {
  local name="$1"; shift
  if bash "$script" "$@" > "$work/out" 2>&1; then
    echo "PASS $name"; pass=$((pass + 1))
  else
    echo "FAIL unexpectedly rejected $name" >&2; cat "$work/out" >&2; exit 1
  fi
}
expect_fail() {
  local name="$1"; shift
  if bash "$script" "$@" > "$work/out" 2>&1; then
    echo "FAIL unexpectedly accepted $name" >&2; cat "$work/out" >&2; exit 1
  else
    echo "PASS $name"; pass=$((pass + 1))
  fi
}
now='2026-11-04T19:00:00Z'
old='2026-11-04T18:00:00Z'
expect_pass docs_only "$base" "$no_java" "$work/empty" "$now"
expect_fail empty_body "$no_java" "$java_head" "$work/empty" "$now"
expect_fail partial_body "$no_java" "$java_head" "$work/partial" "$now"
expect_fail placeholders "$no_java" "$java_head" "$work/placeholders" "$now"
expect_pass disclosed_java_exception "$no_java" "$java_head" "$work/valid" "$now"
expect_fail contradictory_guest_evidence "$no_java" "$java_head" "$work/duplicate_conflict" "$now"
expect_fail duplicate_guest_evidence "$no_java" "$java_head" "$work/duplicate_identical" "$now"
expect_pass grandfathered_existing_pr "$no_java" "$java_head" "$work/empty" "$old"
expect_fail invalid_base "0000000000000000000000000000000000000000" "$java_head" "$work/valid" "$now"
expect_fail invalid_timestamp "$no_java" "$java_head" "$work/valid" 'yesterday'
mkdir -p 'new java path'
printf 'class NewHost {}\n' > 'new java path/New Host.java'
git add . && git commit -qm spaced
spaced_head="$(git rev-parse HEAD)"
expect_fail spaced_java_path_detected "$java_head" "$spaced_head" "$work/empty" "$now"
printf 'val x: int = 42\n' > guest.atto
git add . && git commit -qm guest
atto_head="$(git rev-parse HEAD)"
expect_pass atto_only "$spaced_head" "$atto_head" "$work/empty" "$now"
git checkout -q -b isolated_guest "$base"
printf 'val x: int = 7\n' > independent.atto
git add . && git commit -qm isolated_guest
isolated_head="$(git rev-parse HEAD)"
git checkout -q main
printf 'class Shared { int version; }\n' > src/Shared.java
git commit -qam unrelated_host_change_on_base
advanced_base="$(git rev-parse HEAD)"
expect_pass diverged_base_ignores_unrelated_java "$advanced_base" "$isolated_head" "$work/empty" "$now"
[[ "$pass" -eq 13 ]] || exit 1
echo "ATTO_FIRST_POLICY_SELFTEST=13/13"
