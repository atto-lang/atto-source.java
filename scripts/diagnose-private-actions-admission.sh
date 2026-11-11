#!/usr/bin/env bash
# Read-only, source-free GitHub Actions runner-admission evidence collector.
# Usage: ./diagnose-private-actions-admission.sh OWNER/REPO RUN_ID
# Requires local `gh auth login` with Actions read permission; org-level
# policy/runner endpoints additionally require authorized administrator access.
set -euo pipefail

if [[ $# -ne 2 || ! "$1" =~ ^[[:alnum:]_.-]+/[[:alnum:]_.-]+$ || ! "$2" =~ ^[0-9]+$ ]]; then
  printf 'usage: %s OWNER/REPO RUN_ID\n' "${0##*/}" >&2
  exit 64
fi
for tool in gh jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'missing dependency: %s\n' "$tool" >&2
    exit 69
  fi
done

repo="$1"
run_id="$2"
owner="${repo%%/*}"
run_path="repos/$repo/actions/runs/$run_id"

gh_read() { gh api --method GET "$1"; }
print_json() { jq -r "$1"; }

run_json="$(gh_read "$run_path")" || {
  echo 'RUN_QUERY=UNAVAILABLE (check repo access and Actions:read permission)' >&2
  exit 69
}
head="$(printf '%s' "$run_json" | print_json '.head_sha // ""')"
if [[ ! "$head" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'RUN_HEAD=INVALID (refuse to diagnose an unidentified run)' >&2
  exit 65
fi
printf 'REPO=%s\nRUN_ID=%s\nRUN_HEAD=%s\n' "$repo" "$run_id" "$head"
printf '%s' "$run_json" | print_json '"RUN_ATTEMPT=\(.run_attempt // "unknown") RUN_STATUS=\(.status // "unknown") RUN_CONCLUSION=\(.conclusion // "unknown")"'

# Never follow artifact or checkout URLs or copy private repository contents.
jobs_json="$(gh_read "$run_path/jobs?per_page=100")" || {
  echo 'JOBS_QUERY=UNAVAILABLE' >&2
  exit 69
}
if ! printf '%s' "$jobs_json" | jq -e '.jobs | type == "array"' >/dev/null; then
  echo 'JOBS_QUERY=MALFORMED' >&2
  exit 65
fi
count="$(printf '%s' "$jobs_json" | print_json '.jobs | length')"
total="$(printf '%s' "$jobs_json" | print_json '.total_count // (.jobs | length)')"
printf 'JOB_COUNT=%s JOB_TOTAL=%s\n' "$count" "$total"
if (( total > count )); then
  echo 'JOB_LIST=INCOMPLETE (paginate before making a whole-run claim)' >&2
  exit 65
fi

failed_unassigned=0
executed=0
while IFS=$'\t' read -r id name status conclusion runner steps; do
  [[ -n "$id" ]] || continue
  printf 'JOB_ID=%s NAME=%q STATUS=%s CONCLUSION=%s RUNNER_ID=%s STEPS=%s\n' \
    "$id" "$name" "$status" "$conclusion" "$runner" "$steps"
  if (( steps > 0 )); then
    ((executed += 1))
  elif [[ "$conclusion" == failure && "$runner" == null ]]; then
    ((failed_unassigned += 1))
  fi
  # Check-run annotations can carry GitHub's admission error when no logs exist.
  if annotations="$(gh_read "repos/$repo/check-runs/$id/annotations?per_page=100" 2>/dev/null)"; then
    if printf '%s' "$annotations" | jq -e 'type == "array"' >/dev/null; then
      printf '%s' "$annotations" | jq -r --arg id "$id" \
        '.[] | "ANNOTATION_JOB=\($id) LEVEL=\(.annotation_level // "unknown") MESSAGE=\(.message // "")"'
    fi
  else
    printf 'ANNOTATIONS_JOB=%s UNAVAILABLE (may require checks:read)\n' "$id"
  fi
done < <(printf '%s' "$jobs_json" | jq -r \
  '.jobs[] | [.id, .name // "", .status // "unknown", .conclusion // "null", (.runner_id // "null"), (.steps // [] | length)] | @tsv')

# Read-only, status-only admin queries. Never print authorization tokens.
for endpoint in \
  "repos/$repo/actions/permissions" \
  "orgs/$owner/actions/permissions" \
  "repos/$repo/actions/runners" \
  "orgs/$owner/actions/runner-groups"; do
  if settings="$(gh_read "$endpoint" 2>/dev/null)"; then
    summary="$(printf '%s' "$settings" | jq -c \
      '{enabled: .enabled, allowed_actions: .allowed_actions, total_count: .total_count, selected_actions_url: .selected_actions_url} | with_entries(select(.value != null))')"
    printf 'READ_ONLY_POLICY=%s SUMMARY=%s\n' "$endpoint" "$summary"
  else
    printf 'READ_ONLY_POLICY=%s UNAVAILABLE (permission or endpoint restriction)\n' "$endpoint"
  fi
done

if (( failed_unassigned > 0 )); then
  printf 'ADMISSION=NOT_EXECUTED UNASSIGNED_FAILED_JOBS=%s EXECUTED_JOBS=%s\n' \
    "$failed_unassigned" "$executed"
  echo 'ADMIN_NEXT=inspect annotations, private Actions billing/budgets/holds, hosted runner eligibility, policy; run checkout-free smoke after remediation'
  exit 2
fi
if (( executed > 0 )); then
  printf 'ADMISSION=EXECUTED EXECUTED_JOBS=%s\n' "$executed"
  echo 'NOTE=execution is not proof of passing compiler or guest tests; inspect test reports'
  exit 0
fi
printf 'ADMISSION=UNDETERMINED NO_EXECUTED_JOB_STEPS\n'
exit 2
