#!/usr/bin/env bash
# Source-free, offline tests. These fixtures never contact github.com.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
script="$root/diagnose-private-actions-admission.sh"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
cat > "$temp/gh" <<'MOCK_GH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == api && "$2" == --method && "$3" == GET ]] || exit 31
endpoint="$4"
case "$endpoint" in
  repos/ores-truffle-oreslang/oreslang-source.java/actions/runs/43040059926)
    printf '%s\n' '{"head_sha":"6dd91511c18f4cd7c60b725279ee4f3dea43a586","run_attempt":1,"status":"completed","conclusion":"failure"}' ;;
  repos/ores-truffle-oreslang/oreslang-source.java/actions/runs/43040059926/jobs\?per_page=100)
    case "${MOCK_CASE:-unassigned}" in
      assigned)
        printf '%s\n' '{"total_count":1,"jobs":[{"id":130589130358,"name":"native-test","status":"completed","conclusion":"success","runner_id":12345,"steps":[{"status":"completed","conclusion":"success"}]}]}' ;;
      unassigned)
        printf '%s\n' '{"total_count":1,"jobs":[{"id":130589130358,"name":"native-test","status":"completed","conclusion":"failure","runner_id":null,"steps":[]}]}' ;;
      skipped)
        printf '%s\n' '{"total_count":1,"jobs":[{"id":130589130358,"name":"skipped","status":"completed","conclusion":"skipped","runner_id":12345,"steps":[{"status":"completed","conclusion":"skipped"}]}]}' ;;
      unassigned_spoofed_step)
        printf '%s\n' '{"total_count":1,"jobs":[{"id":130589130358,"name":"bad-metadata","status":"completed","conclusion":"failure","runner_id":null,"steps":[{"status":"completed","conclusion":"success"}]}]}' ;;
      incomplete)
        printf '%s\n' '{"total_count":101,"jobs":[{"id":130589130358,"name":"native-test","status":"completed","conclusion":"failure","runner_id":null,"steps":[]}]}' ;;
    esac ;;
  repos/ores-truffle-oreslang/oreslang-source.java/check-runs/130589130358/annotations\?per_page=100)
    printf '%s\n' '[{"annotation_level":"failure","message":"SYNTHETIC ADMISSION ERROR (NOT ACTUAL GITHUB)"}]' ;;
  repos/ores-truffle-oreslang/oreslang-source.java/actions/permissions)
    printf '%s\n' '{"enabled":true,"allowed_actions":"all"}' ;;
  orgs/ores-truffle-oreslang/actions/permissions)
    printf '%s\n' '{"enabled":true,"allowed_actions":"all"}' ;;
  repos/ores-truffle-oreslang/oreslang-source.java/actions/runners)
    printf '%s\n' '{"total_count":0,"runners":[]}' ;;
  *) exit 22 ;;
esac
MOCK_GH
chmod +x "$temp/gh"
run_case() {
  local kind="$1" expected_exit="$2" expected_text="$3" status=0
  MOCK_CASE="$kind" PATH="$temp:$PATH" bash "$script" \
    ores-truffle-oreslang/oreslang-source.java 43040059926 >"$temp/out" 2>"$temp/err" || status=$?
  [[ "$status" == "$expected_exit" ]] || { echo "FAIL $kind: exit $status != $expected_exit"; cat "$temp/out" "$temp/err"; exit 1; }
  grep -q -- "$expected_text" "$temp/out" "$temp/err" || { echo "FAIL $kind: missing $expected_text"; cat "$temp/out" "$temp/err"; exit 1; }
  echo "PASS $kind exit=$status"
}
run_case unassigned 2 'ADMISSION=NOT_EXECUTED'
run_case assigned 0 'ADMISSION=EXECUTED EXECUTED_JOBS=1'
run_case skipped 2 'ADMISSION=UNDETERMINED'
run_case unassigned_spoofed_step 2 'ADMISSION=NOT_EXECUTED'
run_case incomplete 65 'JOB_LIST=INCOMPLETE'
status=0
PATH="$temp:$PATH" bash "$script" 'wrong repo' not-a-number >"$temp/out" 2>"$temp/err" || status=$?
[[ "$status" == 64 ]] || { echo "FAIL invalid arguments exit=$status"; exit 1; }
echo 'PASS invalid arguments exit=64'
