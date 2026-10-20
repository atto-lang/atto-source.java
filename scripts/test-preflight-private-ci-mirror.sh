#!/usr/bin/env bash
set -euo pipefail

# Synthetic Git/API fixtures only. No private Oreslang source is copied.
root="$(cd "$(dirname "$0")/.." && pwd -P)"
tmp="$(mktemp -d)"

# Catch accidental concatenation/reconciliation corruption before exercising
# the synthetic API cases.
test "$(grep -Fc 'control_root="$(git rev-parse --show-toplevel' "$root/scripts/preflight-private-ci-mirror.sh")" -eq 1
test "$(grep -Fc "printf 'PRIVATE_MIRROR_PREFLIGHT_PASS" "$root/scripts/preflight-private-ci-mirror.sh")" -eq 1
trap 'rm -rf "$tmp"' EXIT

control="$tmp/control"
repo="$tmp/source"
mkdir -p "$control/scripts" "$control/.github" "$tmp/bin"
cp "$root/scripts/preflight-private-ci-mirror.sh" "$control/scripts/"
cp "$root/scripts/check-private-ci-destination.sh" "$control/scripts/"
cp "$root/scripts/run-in-private-ci-destination.sh" "$control/scripts/"
cp "$root/scripts/publish-private-ci-source.py" "$control/scripts/"
cp "$root/.github/private-ci-approved-repositories.txt" "$control/.github/"

git -C "$control" init -q -b main
git -C "$control" -c user.name=CI-Security -c user.email=ci-security@example.invalid add .
git -C "$control" -c user.name=CI-Security -c user.email=ci-security@example.invalid \
  commit -qm 'authoritative synthetic main'
main_sha="$(git -C "$control" rev-parse HEAD)"
main_tree="$(git -C "$control" rev-parse HEAD^{tree})"
policy_blob="$(git -C "$control" rev-parse "HEAD:.github/private-ci-approved-repositories.txt")"
preflight_blob="$(git -C "$control" rev-parse "HEAD:scripts/preflight-private-ci-mirror.sh")"
guard_blob="$(git -C "$control" rev-parse "HEAD:scripts/check-private-ci-destination.sh")"
wrapper_blob="$(git -C "$control" rev-parse "HEAD:scripts/run-in-private-ci-destination.sh")"
publisher_blob="$(git -C "$control" rev-parse "HEAD:scripts/publish-private-ci-source.py")"

git clone -q "$control" "$repo"
git -C "$repo" checkout -q -b feature

git -C "$repo" -c user.name=CI-Feature -c user.email=ci-feature@example.invalid \
  commit --allow-empty -qm 'synthetic source PR head'
head="$(git -C "$repo" rev-parse HEAD)"
tree="$(git -C "$repo" rev-parse HEAD^{tree})"
dest_branch="ci/oreslang-test"
dest_base_commit="cd99139738c4a94b226c10b18b92adafb650eda0"
dest_base_tree="a149a45a540db82b19f202ef697c099767e96db9"
dest_workflow_blob="3107feea0215ae9874ff35a2249c69ab6b9e7ffe"

cat > "$tmp/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

[[ "${1-}" == "api" ]] || exit 64
endpoint="${2-}"
shift 2
case "$endpoint" in
  "repos/ores-truffle-oreslang/oreslang-source.java")
    jq=""
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" == "--jq" ]]; then jq="$2"; shift 2; else shift; fi
    done
    case "$jq" in
      "[.id, .full_name, .private] | @tsv")
        printf '%s\t%s\t%s\n' \
          "${SYNTH_SOURCE_ID:-1400792274}" \
          "${SYNTH_SOURCE_NAME:-ores-truffle-oreslang/oreslang-source.java}" \
          "${SYNTH_SOURCE_PRIVATE:-true}"
        ;;
      *) exit 65 ;;
    esac
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
    jq=""
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" == "--jq" ]]; then jq="$2"; shift 2; else shift; fi
    done
    if [[ -n "$jq" ]]; then
      printf 'true\t1\ttrue\ttrue\ttrue\tfalse\tfalse\t0\t0\t0\n'
    elif [[ "${SYNTH_BRANCH_PROTECTION_AVAILABLE:-false}" == "true" ]]; then
      printf '{}\n'
    else
      printf '%s\n' "${SYNTH_BRANCH_PROTECTION_ERROR:-HTTP 403: Upgrade to GitHub Pro or make this repository public to enable this feature}" >&2
      exit 1
    fi
    ;;
  "repos/ores-truffle-oreslang/oreslang-source.java/rulesets")
    if [[ "${SYNTH_RULESETS_AVAILABLE:-false}" == "true" ]]; then
      printf '[]\n'
    else
      printf '%s\n' "${SYNTH_RULESETS_ERROR:-HTTP 403: Upgrade to GitHub Pro or make this repository public to enable this feature}" >&2
      exit 1
    fi
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
  "repos/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/git/ref/heads/ci/oreslang-test")
    if [[ "${SYNTH_DEST_BRANCH_EXISTS:-false}" == "true" ]]; then
      printf '{"ref":"refs/heads/ci/oreslang-test"}\n'
    else
      printf 'gh: Not Found (HTTP 404)\n' >&2
      exit 1
    fi
    ;;
  "repos/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/branches/main")
    jq=""
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" == "--jq" ]]; then jq="$2"; shift 2; else shift; fi
    done
    [[ "$jq" == ".commit.sha" ]] || exit 65
    printf '%s\n' "${SYNTH_DEST_MAIN_COMMIT:-cd99139738c4a94b226c10b18b92adafb650eda0}"
    ;;
  "repos/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/git/commits/cd99139738c4a94b226c10b18b92adafb650eda0")
    printf '%s\t%s\n' \
      "${SYNTH_DEST_BASE_COMMIT:-cd99139738c4a94b226c10b18b92adafb650eda0}" \
      "${SYNTH_DEST_BASE_TREE:-a149a45a540db82b19f202ef697c099767e96db9}"
    ;;
  "repos/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/contents/.github/workflows/private-oreslang-exact-tree.yml?ref=cd99139738c4a94b226c10b18b92adafb650eda0")
    printf '%s\n' "${SYNTH_DEST_WORKFLOW_BLOB:-3107feea0215ae9874ff35a2249c69ab6b9e7ffe}"
    ;;
  *)
    printf 'unexpected synthetic gh endpoint: %s\n' "$endpoint" >&2
    exit 65
    ;;
esac
EOF
chmod +x "$tmp/bin/gh"

approval_payload="PRIVATE_CI_EXPORT_APPROVAL_V1 source_repo=ores-truffle-oreslang/oreslang-source.java source_repo_id=1400792274 source_selector=pr:1425 source_commit=$head source_tree=$tree destination_repo=ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra destination_repo_id=1412142815 destination_branch=$dest_branch destination_base_commit=$dest_base_commit destination_base_tree=$dest_base_tree destination_workflow_blob=$dest_workflow_blob policy_blob=$policy_blob preflight_blob=$preflight_blob guard_blob=$guard_blob wrapper_blob=$wrapper_blob publisher_blob=$publisher_blob"

run_preflight() {
  (
    cd "$control"
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
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch"

# A source checkout is an untrusted Git repository. A local replace ref must not
# rewrite the authenticated commit/tree inspected by the authorization gate.
printf '%s\n' replacement-only > "$repo/replacement-only.txt"
git -C "$repo" add replacement-only.txt
git -C "$repo" -c user.name=CI-Feature -c user.email=ci-feature@example.invalid \
  commit -qm 'synthetic malicious replacement object'
replacement="$(git -C "$repo" rev-parse HEAD)"
git -C "$repo" replace "$head" "$replacement"
GIT_NO_REPLACE_OBJECTS=1 git -C "$repo" reset --hard -q "$head"
assert_exit 0 run_preflight SYNTH_PROTECTED=true \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch"
git -C "$repo" replace -d "$head" >/dev/null

# Post-merge verification may target the authenticated current main head.
assert_exit 0 run_preflight SYNTH_PROTECTED=true \
  bash scripts/preflight-private-ci-mirror.sh "$control" main "$main_sha" "$main_tree" "$dest_branch"
assert_exit 1 run_preflight SYNTH_PROTECTED=true \
  bash scripts/preflight-private-ci-mirror.sh "$control" main "$head" "$tree" "$dest_branch"

# Unprotected main fails unless the exact dual attestation is supplied.
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch"

# Source repository identity is bound by immutable GitHub repository ID before
# any authorization mode can pass. The hard-coded canonical API route plus
# immutable repository ID prevents name-only destination/source substitution.
assert_exit 1 run_preflight SYNTH_SOURCE_ID=999999 SYNTH_PROTECTED=true \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch"
assert_exit 1 run_preflight SYNTH_SOURCE_NAME=ores-truffle-oreslang/not-the-source SYNTH_PROTECTED=true \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch"
assert_exit 1 run_preflight SYNTH_SOURCE_PRIVATE=false SYNTH_PROTECTED=true \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch"

# Correct, distinct, unedited attestations pass only when GitHub explicitly
# reports both protection mechanisms as unavailable on the current plan.
assert_exit 0 run_preflight SYNTH_PROTECTED=false \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_DEST_BRANCH_EXISTS=true \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102

# Authorization is bound to the exact reviewed destination carrier base and
# the exact inline-cleanup workflow blob contained in that immutable carrier.
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_WORKFLOW_BLOB=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102

# Authorization is bound to the exact reviewed destination carrier base and it
# must still be the live carrier main required by connector authorization.
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_MAIN_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_TREE=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102

# A previously reviewed carrier pair is stale once authorization is re-pinned.
# This prevents an old green carrier from being reused after the reviewed
# execution boundary changes.
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_COMMIT=4d1d65445a09eb47f383bd5dc373d36056346056 \
  SYNTH_DEST_BASE_TREE=825c75a6b4cf84c62510f717290d6c87f4611b9a \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_COMMIT=054b65982f0950a4e2f3fb4752e78f585198c2ff \
  SYNTH_DEST_BASE_TREE=09ccea168b563ee91f9cd7a938ddabb5a33078ec \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_COMMIT=1012deffdaa3d0aec03a98073dc1f48ec1565c08 \
  SYNTH_DEST_BASE_TREE=de8053d4005b326929e8734a5be741e82b40e9e7 \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_COMMIT=ec6a5ddc39dd5524285fcc18902d777c4827c666 \
  SYNTH_DEST_BASE_TREE=c7eba95c9abfc008e395194360327026cf33bd9c \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_COMMIT=fc49aa44726eac47301ae2d676c4b38954d3cc46 \
  SYNTH_DEST_BASE_TREE=1e9dec690e45df80758547e991d37f6b18872e33 \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_COMMIT=5e1079638ba322d0208631ef34fcbd3f129b40ab \
  SYNTH_DEST_BASE_TREE=1f2f5d5f816213192d13c179d0e9381d00fdeaf7 \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_COMMIT=c5f5155259aa37b808f4e96dbe09cddded4c04ed \
  SYNTH_DEST_BASE_TREE=15fcb558350a2965d9edf1f503d45a6d8e7f98df \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_COMMIT=38957a4218d0025526ed7e6c13b5d38280a94ede \
  SYNTH_DEST_BASE_TREE=94e48a8a5e8677d4dab7fe2ad9942101c2002890 \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_DEST_BASE_COMMIT=0e494bc70fde8fd74204475ca223a3886d1fe7a3 \
  SYNTH_DEST_BASE_TREE=46b9c0b186edf6a2e509fceb9e6dc2fde107249a \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102

# Merely leaving protection disabled must never activate the fallback when
# either classic protection or rulesets is actually available.
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_BRANCH_PROTECTION_AVAILABLE=true \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_RULESETS_AVAILABLE=true \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102

# Opaque permission/integration failures are not proof of plan unavailability.
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_BRANCH_PROTECTION_ERROR="HTTP 403: Resource not accessible by integration" \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  SYNTH_RULESETS_ERROR="HTTP 403: Resource not accessible by integration" \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102

# Reusing one comment cannot satisfy two independent approvals.
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 101

# Wrong security identity, edited comments, or payload drift all fail closed.
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_SECURITY_LOGIN=someone-else \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_SECURITY_ID=999999 \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_SECURITY_UPDATED=2026-10-19T19:00:01Z \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102
assert_exit 1 run_preflight SYNTH_PROTECTED=false SYNTH_SECURITY_BODY=wrong-payload \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102

# A clean feature commit that changes the running preflight must not be trusted
# merely because its caller supplies the new commit/tree correctly.
printf '\n# synthetic unauthorized feature mutation\n' >> "$repo/scripts/preflight-private-ci-mirror.sh"
git -C "$repo" -c user.name=CI-Feature -c user.email=ci-feature@example.invalid add scripts/preflight-private-ci-mirror.sh
git -C "$repo" -c user.name=CI-Feature -c user.email=ci-feature@example.invalid \
  commit -qm 'mutate preflight only on feature head'
head="$(git -C "$repo" rev-parse HEAD)"
tree="$(git -C "$repo" rev-parse HEAD^{tree})"
approval_payload="PRIVATE_CI_EXPORT_APPROVAL_V1 source_repo=ores-truffle-oreslang/oreslang-source.java source_repo_id=1400792274 source_selector=pr:1425 source_commit=$head source_tree=$tree destination_repo=ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra destination_repo_id=1412142815 destination_branch=$dest_branch destination_base_commit=$dest_base_commit destination_base_tree=$dest_base_tree destination_workflow_blob=$dest_workflow_blob policy_blob=$policy_blob preflight_blob=$preflight_blob guard_blob=$guard_blob wrapper_blob=$wrapper_blob publisher_blob=$publisher_blob"
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102

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
approval_payload="PRIVATE_CI_EXPORT_APPROVAL_V1 source_repo=ores-truffle-oreslang/oreslang-source.java source_repo_id=1400792274 source_selector=pr:1425 source_commit=$head source_tree=$tree destination_repo=ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra destination_repo_id=1412142815 destination_branch=$dest_branch destination_base_commit=$dest_base_commit destination_base_tree=$dest_base_tree destination_workflow_blob=$dest_workflow_blob policy_blob=$policy_blob preflight_blob=$preflight_blob guard_blob=$guard_blob wrapper_blob=$wrapper_blob publisher_blob=$publisher_blob"
assert_exit 1 run_preflight SYNTH_PROTECTED=false \
  bash scripts/preflight-private-ci-mirror.sh "$repo" pr:1425 "$head" "$tree" "$dest_branch" 101 102

echo "PRIVATE_MIRROR_PREFLIGHT_SYNTHETIC_TEST_PASS"
