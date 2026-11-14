#!/usr/bin/env bash
# Administrator-local, read-only GitHub Actions admission diagnostic.
# No repository checkout, token output, cache, upload, rerun or policy mutation.
set -euo pipefail

repo='ores-truffle-oreslang/oreslang-source.java'
repo_id='1400792274'
show_annotations=false
case "${1:-}" in
  --show-annotations) show_annotations=true; shift ;;
esac

case "${1:-canary}" in
  canary)
    run_id=42403125247
    job_id=128495004829
    expected_head=25aebefbddc13723df045aa48b30cb36ce788ef3
    ;;
  compiler)
    run_id=42398879382
    job_id=128482139649
    expected_head=401d2faa9cd685d538ed62a4c7526b226b7a56e5
    ;;
  *)
    echo 'usage: inspect_source_owned_runner_admission.sh [--show-annotations] [canary|compiler]' >&2
    exit 2
    ;;
esac
[[ $# -le 1 ]] || { echo 'unexpected arguments' >&2; exit 2; }
[[ -z "${GH_HOST:-}" || "$GH_HOST" == github.com ]] || {
  echo 'refusing non-github.com GH_HOST' >&2; exit 2;
}
command -v gh >/dev/null || { echo 'GitHub CLI (gh) required' >&2; exit 2; }
command -v jq >/dev/null || { echo 'jq required' >&2; exit 2; }

api_get() { gh api --method GET -H 'Accept: application/vnd.github+json' "$1"; }
repo_json="$(api_get "repos/$repo")"
run_json="$(api_get "repos/$repo/actions/runs/$run_id")"
job_json="$(api_get "repos/$repo/actions/jobs/$job_id")"
annotations_json="$(api_get "repos/$repo/check-runs/$job_id/annotations?per_page=100")"

# Reject wrong repository/run/head/job instead of attributing unrelated evidence.
[[ "$(jq -er '.id | tostring' <<<"$repo_json")" == "$repo_id" ]] || { echo 'repository ID mismatch' >&2; exit 3; }
[[ "$(jq -er '.repository.id | tostring' <<<"$run_json")" == "$repo_id" ]] || { echo 'run repository mismatch' >&2; exit 3; }
[[ "$(jq -er '.id | tostring' <<<"$run_json")" == "$run_id" ]] || { echo 'run ID mismatch' >&2; exit 3; }
[[ "$(jq -er '.head_sha' <<<"$run_json")" == "$expected_head" ]] || { echo 'stale/wrong head SHA' >&2; exit 3; }
[[ "$(jq -er '.id | tostring' <<<"$job_json")" == "$job_id" ]] || { echo 'job ID mismatch' >&2; exit 3; }
[[ "$(jq -er '.run_id | tostring' <<<"$job_json")" == "$run_id" ]] || { echo 'job/run association mismatch' >&2; exit 3; }
jq -e 'type == "array"' <<<"$annotations_json" >/dev/null || { echo 'invalid annotation response' >&2; exit 3; }

runner_id="$(jq -r '.runner_id // 0' <<<"$job_json")"
steps="$(jq -r 'if (.steps | type) == "array" then [.steps[] | select(.status == "completed")] | length else 0 end' <<<"$job_json")"
annotation_count="$(jq -r 'length' <<<"$annotations_json")"
classification='NOT_EXECUTED'
if [[ "$runner_id" =~ ^[1-9][0-9]*$ ]] && [[ "$steps" =~ ^[1-9][0-9]*$ ]]; then
  classification='EXECUTION_OBSERVED_NOT_CERTIFIED'
fi

jq -n \
  --arg repo "$repo" --arg head "$expected_head" \
  --arg conclusion "$(jq -r '.conclusion // "unknown"' <<<"$job_json")" \
  --arg classification "$classification" \
  --argjson run_id "$run_id" --argjson job_id "$job_id" \
  --argjson runner_id "$runner_id" --argjson completed_steps "$steps" \
  --argjson annotation_count "$annotation_count" \
  '{repository:$repo,head_sha:$head,run_id:$run_id,job_id:$job_id,runner_id:$runner_id,completed_steps:$completed_steps,conclusion:$conclusion,annotations:$annotation_count,classification:$classification}'

if [[ "$show_annotations" == true ]]; then
  echo 'LOCAL ANNOTATIONS BELOW — review/redact before sharing; no data uploaded by this script.' >&2
  jq '[.[] | {annotation_level, path, title, message}]' <<<"$annotations_json"
else
  echo 'Run with --show-annotations to see the full GitHub error locally; redact before sharing.' >&2
fi
