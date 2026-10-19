#!/usr/bin/env bash
set -euo pipefail

# Synthetic Git/API fixtures only. No private Oreslang source is copied.
root="$(cd "$(dirname "$0")/.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

repo="$tmp/source"
mkdir -p "$repo/scripts" "$repo/.github" "$tmp/bin"
cp "$root/scripts/preflight-private-ci-mirror.sh" "$repo/scripts/"
cp "$root/scripts/check-private-ci-destination.sh" "$repo/scripts/"
cp "$root/scripts/run-in-private-ci-destination.sh" "$repo/scripts/"
cp "$root/scripts/publish-private-ci-source.py" "$repo/scripts/"
cp "$root/.github/private-ci-approved-repositories.txt" "$repo/.github/"

git -C "$repo" init -q -b main
git -C "$repo" -c user.name=CI-Security -c user.email=ci-security@example.invalid add .
git -C "$repo" -c user.name=CI-Security -c user.email=ci-security@example.invalid \
  commit -qm 'authoritative synthetic main'
main_sha="$(git -C "$repo" rev-parse HEAD)"
policy_blob="$(git -C "$repo" rev-parse "HEAD:.github/private-ci-approved-repositories.txt")"
preflight_blob="$(git -C "$repo" rev-parse "HEAD:scripts/preflight-private-ci-mirror.sh")"
guard_blob="$(git -C "$repo" rev-parse "HEAD:scripts/check-private-ci-destination.sh")"
wrapper_blob="$(git -C "$repo" rev-parse "HEAD:scripts/run-in-private-ci-destination.sh")"
publisher_blob="$(git -C "$repo" rev-parse "HEAD:scripts/publish-private-ci-source.py")"
git -C "$repo" update-ref refs/remotes/origin/main "$main_sha"

git -C "$repo" -c user.name=CI-Feature -c user.email=ci-feature@example.invalid \
  commit --allow-empty -qm 'synthetic source PR head'
head="$(git -C "$repo" rev-parse HEAD)"
tree="$(git -C "$repo" rev-parse HEAD^{tree})"

cat > "$tmp/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

[[ "${1-}" == "api" ]] || exit 64
endpoint="${2-}"
shift 2
case "$endpoint" in
  "repos/ores-truffle-oreslang/oreslang-source.java")
    printf '%s\n' "1400792274"
    ;;
  "repos/ores-truffle-oreslang/oreslang-source.java/pulls/1425")
    jq=""
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" == "--jq" ]]; then jq="$2"; shift 2; else shift; fi
    done
    case "$jq" in
      ".head.sha") printf '%s\n' "$SYNTH_HEAD" ;;
      ".state") printf '%s\n' "open" ;;
      ".base.ref") printf '%s\n' "main" ;;
      ".head.repo.full_name") printf '%s\n' "ores-truffle-oreslang/oreslang-source.java" ;;
      *) exit 65 ;;
    esac
    ;;
  "repos/ores-truffle-oreslang/oreslang-source.java/branches/main")
    jq=""
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" == "--jq" ]]; then jq="$2"; shift 2; else shift; fi
    done
    case "$jq" in
      ".commit.sha") printf '%s\n' "$SYNTH_MAIN_SHA" ;;
      ".protected") printf '%s\n' "${SYNTH_PROTECTED:-false}" ;;
      *) exit 65 ;;
    esac
    ;;
  "repos/ores-truffle-oreslang/oreslang-source.java/branches/main/protection")
    printf 'true\t1\ttrue\ttrue\ttrue\tfalse\tfalse\t0\t0\t0\n'
    ;;
  "repos/ores-truffle-oreslang/oreslang-source.java/issues/comments/101")
    printf '%s\n' \
      "${SYNTH_MAINT_LOGIN:-the1mills}" \
      "${SYNTH_MAINT_ID:-2148448}" \
      "${SYNTH_MAINT_ASSOC:-MEMBER}" \
      "2026-10-19T19:00:00Z" \
      "${SYNTH_MAINT_UPDATED:-2026-10-19T19:00:00Z}" \
      "https://api.github.com/repos/ores-truffle-oreslang/oreslang-source.java/issues/1084" \
      "${SYNTH_MAINT_BODY:-$SYNTH_APPROVAL_PAYLOAD}"
    ;;
  "repos/ores-truffle-oreslang/oreslang-source.java/issues/comments/102")
    printf '%s\n' \
      "${SYNTH_SECURITY_LOGIN:-ORESoftware}" \
      "${SYNTH_SECURITY_ID:-11139560}" \
      "${SYNTH_SECURITY_ASSOC:-MEMBER}" \
      "2026-10-19T19:00:00Z" \
      "${SYNTH_SECURITY_UPDATED:-2026-10-19T19:00:00Z}" \
      "https://api.github.com/repos/ores-truffle-oreslang/oreslang-source.java/issues/1084" \
      "${SYNTH_SECURITY_BODY:-$SYNTH_APPROVAL_PAYLOAD}"
    ;;
  "repos/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra")
    printf '1412142815\tores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra\ttrue\ttrue\n'
    ;;
  *)
    printf 'unexpected synthetic gh endpoint: %s\n' "$endpoint" >&2
    exit 65
    ;;
esac
EOF
chmod +x "$tmp/bin/gh"

approval_payload="PRIVATE_CI_EXPORT_APPROVAL_V1 source_repo=ores-truffle-oreslang/oreslang-source.java source_repo_id=1400792274 source_pr=1425 source_commit=$head source_tree=$tree destination_repo=ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra destination_repo_id=1412142815 policy_blob=$policy_blob preflight_blob=$preflight_blob guard_blob=$guard_blob wrapper_blob=$wrapper_blob publisher_blob=$publisher_blob"

run_preflight() {
  (
    cd "$repo"
    env \
      PATH="$tmp/bin:$PATH" \
      SYNTH_HEAD="$head" \
      SYNTH_MAIN_SHA="$main_sha" \
      SYNTH_APPROVAL_PAYLOAD="$approval_payload" \
      "$@"
  )
}

assert_exit() {
  expected="$1"
  shift
  got=0
  "$@" >"$tmp/out" 2>&1 || got="$?"
  if [[ "$got" != "$expected" ]]; then
    printf 'expected exit %s, got %s: %s\n' "$expected" "$got" "$*" >&2
    cat "$tmp/out" >&2
    exit 1
  fi
}

# Preferred protected-main mode needs no per-export comments.
assert_exit 0 run_preflight SYNTH_PROTECTED=true \
  bash scripts/preflight-private-ci-mirror.sh 1425 "$head" "$tree"

# Unprotected main fails unless the exact dual attestation is supplied.
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  bash scripts/preflight-private-ci-mirror.sh 1425 "$head" "$tree"

# Correct, distinct, unedited attestations pass.
assert_exit 0 run_preflight SYNTH_PROTECTED=false \
  bash scripts/preflight-private-ci-mirror.sh 1425 "$head" "$tree" 101 102

# Reusing one comment cannot satisfy two independent approvals.
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  bash scripts/preflight-private-ci-mirror.sh 1425 "$head" "$tree" 101 101

# Wrong security identity, edited comments, or payload drift all fail closed.
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_SECURITY_LOGIN=someone-else \
  bash scripts/preflight-private-ci-mirror.sh 1425 "$head" "$tree" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_SECURITY_ID=999999 \
  bash scripts/preflight-private-ci-mirror.sh 1425 "$head" "$tree" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_SECURITY_UPDATED=2026-10-19T19:00:01Z \
  bash scripts/preflight-private-ci-mirror.sh 1425 "$head" "$tree" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_SECURITY_BODY=wrong-payload \
  bash scripts/preflight-private-ci-mirror.sh 1425 "$head" "$tree" 101 102

# A clean feature commit that changes the running preflight must not be trusted
# merely because its caller supplies the new commit/tree correctly.
printf '\n# synthetic unauthorized feature mutation\n' >> "$repo/scripts/preflight-private-ci-mirror.sh"
git -C "$repo" -c user.name=CI-Feature -c user.email=ci-feature@example.invalid add scripts/preflight-private-ci-mirror.sh
git -C "$repo" -c user.name=CI-Feature -c user.email=ci-feature@example.invalid \
  commit -qm 'mutate preflight only on feature head'
head="$(git -C "$repo" rev-parse HEAD)"
tree="$(git -C "$repo" rev-parse HEAD^{tree})"
approval_payload="PRIVATE_CI_EXPORT_APPROVAL_V1 source_repo=ores-truffle-oreslang/oreslang-source.java source_repo_id=1400792274 source_pr=1425 source_commit=$head source_tree=$tree destination_repo=ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra destination_repo_id=1412142815 policy_blob=$policy_blob preflight_blob=$preflight_blob guard_blob=$guard_blob wrapper_blob=$wrapper_blob publisher_blob=$publisher_blob"
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  bash scripts/preflight-private-ci-mirror.sh 1425 "$head" "$tree" 101 102

# Reset to authoritative main, then mutate only the publisher. The approval
# binds that blob too, so a feature branch cannot swap the copy routine after
# security review.
git -C "$repo" reset -q --hard "$main_sha"
git -C "$repo" -c user.name=CI-Feature -c user.email=ci-feature@example.invalid \
  commit --allow-empty -qm 'synthetic source PR head after reset'
head="$(git -C "$repo" rev-parse HEAD)"
printf '\n# synthetic unauthorized publisher mutation\n' >> "$repo/scripts/publish-private-ci-source.py"
git -C "$repo" -c user.name=CI-Feature -c user.email=ci-feature@example.invalid add scripts/publish-private-ci-source.py
git -C "$repo" -c user.name=CI-Feature -c user.email=ci-feature@example.invalid \
  commit -qm 'mutate publisher only on feature head'
head="$(git -C "$repo" rev-parse HEAD)"
tree="$(git -C "$repo" rev-parse HEAD^{tree})"
approval_payload="PRIVATE_CI_EXPORT_APPROVAL_V1 source_repo=ores-truffle-oreslang/oreslang-source.java source_repo_id=1400792274 source_pr=1425 source_commit=$head source_tree=$tree destination_repo=ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra destination_repo_id=1412142815 policy_blob=$policy_blob preflight_blob=$preflight_blob guard_blob=$guard_blob wrapper_blob=$wrapper_blob publisher_blob=$publisher_blob"
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  bash scripts/preflight-private-ci-mirror.sh 1425 "$head" "$tree" 101 102

echo "PRIVATE_MIRROR_PREFLIGHT_SYNTHETIC_TEST_PASS"
