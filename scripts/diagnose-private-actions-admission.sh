#!/usr/bin/env bash
# Read-only, source-free GitHub Actions runner-admission evidence collector.
# Usage: bash scripts/diagnose-private-actions-admission.sh OWNER/REPO RUN_ID
# Requires an authorized `gh auth login` with Actions and Checks read access;
# org-level runner/policy endpoints may additionally require administrator access.
# Do not post full annotations publicly: review for secrets before sharing.
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

run_json="$(gh_read "$run_path")" || {
  echo 'RUN_QUERY=UNAVAILABLE (check repo and Actions read permission)' >&2
  exit 69
}
if ! printf '%s' "$run_json" | jq -e '.head_sha | type == "string"' >/dev/null; then
  echo 'RUN_QUERY=MALFORMED' >&2
  exit 65
fi
head="$(printf '%s' "$run_json" | jq -r '.head_sha')"
if [[ ! "$head" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'RUN_HEAD=INVALID' >&2
  exit 65
fi
printf 'REPO=%s\nRUN_ID=%s\nRUN_HEAD=%s\n' "$repo" "$run_id" "$head"
printf '%s' "$run_json" | jq -r '"RUN_ATTEMPT=\(.run_attempt // "unknown") RUN_STATUS=\(.status // "unknown") RUN_CONCLUSION=\(.conclusion // "unknown")"'

# Query only run/job metadata, annotations, and settings; never checkout,
# read, upload, or mirror private source or download logs/artifacts.
jobs_json="$(gh_read "$run_path/jobs?per_page=100")" || {
  echo 'JOBS_QUERY=UNAVAILABLE' >&2
  exit 69
}
if ! printf '%s' "$jobs_json" | jq -e \
  '(.jobs | type) == "array" and ((.total_count // (.jobs | length)) | type) == "number"' >/dev/null; then
  echo 'JOBS_QUERY=MALFORMED' >&2
  exit 65
fi
count="$(printf '%s' "$jobs_json" | jq -r '.jobs | length')"
total="$(printf '%s' "$jobs_json" | jq -r '.total_count // (.jobs | length)')"
printf 'JOB_COUNT=%s JOB_TOTAL=%s\n' "$count" "$total"
if (( total > count )); then
  echo 'JOB_LIST=INCOMPLETE (paginate before making whole-run claims)' >&2
  exit 65
fi

# Distinguish scheduled/skipped steps from ones that actually executed, and
# require a real (nonzero) runner assignment before reporting execution.
# Fields are numeric or canonical enum strings, not arbitrary user strings.
if ! job_lines="$(printf '%s' "$jobs_json" | jq -r '
  .jobs[] | [
    .id,
    (.name // "unknown" | gsub("[\t\r\n]"; " ")),
    (.status // "unknown"),
    (.conclusion // "null"),
    (.runner_id // 0),
    ((.steps // []) | length),
    ([ (.steps // [])[] | select(
      .status == "completed" and
      (.conclusion == "success" or .conclusion == "failure" or .conclusion == "cancelled")
    ) ] | length)
  ] | @tsv')"; then
  echo 'JOB_LIST=MALFORMED' >&2
  exit 65
fi

failed_unassigned=0
executed=0
while IFS=$'\t' read -r id name status conclusion runner steps completed_steps; do
  [[ -n "$id" ]] || continue
  printf 'JOB_ID=%s NAME=%q STATUS=%s CONCLUSION=%s RUNNER_ID=%s STEPS=%s EXECUTED_STEPS=%s\n' \
    "$id" "$name" "$status" "$conclusion" "$runner" "$steps" "$completed_steps"
  if [[ "$runner" =~ ^[1-9][0-9]*$ ]] && (( completed_steps > 0 )); then
    ((executed += 1))
  elif [[ "$conclusion" == failure && ( "$runner" == 0 || "$runner" == null ) ]]; then
    ((failed_unassigned += 1))
  fi
  # Annotations may expose GitHub's actual admission error without job logs.
  # Emit them only to the administrator; review/redact before public posting.
  if annotations="$(gh_read "repos/$repo/check-runs/$id/annotations?per_page=100" 2>/dev/null)"; then
    if printf '%s' "$annotations" | jq -e 'type == "array"' >/dev/null; then
      printf '%s' "$annotations" | jq -r --arg id "$id" \
        '.[] | "ANNOTATION_JOB=\($id) LEVEL=\(.annotation_level // "unknown") MESSAGE=\((.message // "") | gsub("[\r\n]"; " "))"'
    else
      printf 'ANNOTATIONS_JOB=%s MALFORMED\n' "$id"
    fi
  else
    printf 'ANNOTATIONS_JOB=%s UNAVAILABLE (may require Checks read access)\n' "$id"
  fi
done <<< "$job_lines"

# Read-only, status-only admin queries. Never print authorization tokens.
for endpoint in \
  "repos/$repo/actions/permissions" \
  "orgs/$owner/actions/permissions" \
  "repos/$repo/actions/runners" \
  "orgs/$owner/actions/runner-groups"; do
  if settings="$(gh_read "$endpoint" 2>/dev/null)" && \
     summary="$(printf '%s' "$settings" | jq -c \
       '{enabled: .enabled, allowed_actions: .allowed_actions, total_count: .total_count} | with_entries(select(.value != null))' 2>/dev/null)"; then
    printf 'READ_ONLY_POLICY=%s SUMMARY=%s\n' "$endpoint" "$summary"
  else
    printf 'READ_ONLY_POLICY=%s UNAVAILABLE (permission, policy or API response)\n' "$endpoint"
  fi
done

if (( failed_unassigned > 0 )); then
  printf 'ADMISSION=NOT_EXECUTED UNASSIGNED_FAILED_JOBS=%s EXECUTED_JOBS=%s\n' \
    "$failed_unassigned" "$executed"
  echo 'ADMIN_NEXT=inspect literal annotations and billing/budgets/holds, Actions policy, runner eligibility; retry source-free canary'
  exit 2
fi
if (( executed > 0 )); then
  printf 'ADMISSION=EXECUTED EXECUTED_JOBS=%s\n' "$executed"
  echo 'NOTE=execution does not prove tests passed; inspect exact-head test receipts'
  exit 0
fi
printf 'ADMISSION=UNDETERMINED NO_ASSIGNED_EXECUTED_JOB_STEPS\n'
exit 2
