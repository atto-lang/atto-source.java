#!/usr/bin/env bash
# Execute actual private-CI offline regression suites. Never publishes source.
set -euo pipefail
umask 077
# Sanitize executable lookup BEFORE even dirname is invoked.
# /usr/bin and /bin are system-managed on supported Linux/macOS hosts.
export PATH=/usr/bin:/bin
hash -r
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# Do not accept a caller-supplied executable: PYTHON=/bin/true used to
# produce false-positive receipts without running ANY regression.
if [[ -n "${PYTHON:-}" && "$PYTHON" != python3 ]]; then
  echo 'PRIVATE_CI_UNSAFE_PYTHON_OVERRIDE: offline evidence requires python3' >&2
  exit 2
fi
# A caller-controlled PATH can substitute a fake python3/bash and print a
# successful test receipt without running tests. Use the OS-owned interpreter
# and standard utilities, not shell functions or an untrusted PATH directory.
# On supported Linux/macOS hosts /usr/bin/python3 is required; fail closed
# rather than accepting a user-writable interpreter location.
python_bin=/usr/bin/python3
if [[ ! -x "$python_bin" || ! -x /bin/bash ]]; then
  echo 'PRIVATE_CI_TRUSTED_INTERPRETER_MISSING' >&2
  exit 2
fi
repeats="${PRIVATE_CI_TEST_REPEATS:-3}"
if [[ ! "$repeats" =~ ^[1-9][0-9]?$ ]]; then
  echo 'PRIVATE_CI_INVALID_REPEATS: expected an integer from 1 to 99' >&2
  exit 2
fi
files=(check-private-ci-destination.sh test-private-ci-destination.sh
       run-in-private-ci-destination.sh publish-private-ci-source.py
       test-publish-private-ci-source.py test-private-ci-merged-main.py
       test-private-ci-review-coverage.py
       run-private-ci-regressions-local.sh
       test-run-private-ci-regressions-local.sh
       ../.github/CODEOWNERS
       ../.github/private-ci-approved-repositories.txt
       ../.github/workflows/private-ci-mirror-preflight.yml)
for file in "${files[@]}"; do
  if [[ ! -f "$script_dir/$file" || -L "$script_dir/$file" ]]; then
    printf 'PRIVATE_CI_MISSING_OR_SYMLINKED_FILE: %s\n' "$file" >&2
    exit 2
  fi
done
unset GH_TOKEN GITHUB_TOKEN GH_ENTERPRISE_TOKEN GITHUB_API_TOKEN
unset GIT_ASKPASS SSH_ASKPASS SSH_AUTH_SOCK
# Python -I ignores interpreter environment and user site imports; also
# scrub startup/PYTHONPATH hooks inherited from an untrusted job environment.
unset PYTHONPATH PYTHONHOME PYTHONSTARTUP PYTHONINSPECT PYTHONUSERBASE
export GH_PROMPT_DISABLED=1 GIT_TERMINAL_PROMPT=0
config_sandbox="$(mktemp -d)"
manifest_before="$(mktemp)"
manifest_after="$(mktemp)"
trap 'rm -rf "$config_sandbox"; rm -f "$manifest_before" "$manifest_after"' EXIT
export GH_CONFIG_DIR="$config_sandbox" GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
fingerprint() {
  "$python_bin" -I -B - "$script_dir" "${files[@]}" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
for name in sys.argv[2:]:
    path = root / name
    if path.is_symlink() or not path.is_file():
        raise SystemExit('PRIVATE_CI_FILE_CHANGED: ' + name)
    print(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + name)
PY
}
fingerprint > "$manifest_before"
echo 'PRIVATE_CI_TEST_SCOPE=OFFLINE_NO_PUBLISH'
"$python_bin" -I -B - "$script_dir" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
for name in ('publish-private-ci-source.py', 'test-publish-private-ci-source.py',
             'test-private-ci-merged-main.py', 'test-private-ci-review-coverage.py'):
    path = root / name
    compile(path.read_bytes(), str(path), 'exec')
print('PRIVATE_CI_PYTHON_COMPILE=4')
PY
bash -n "$script_dir/check-private-ci-destination.sh" \
    "$script_dir/test-private-ci-destination.sh" \
    "$script_dir/run-in-private-ci-destination.sh"
echo 'PRIVATE_CI_BASH_SYNTAX=3'
bash "$script_dir/test-private-ci-destination.sh"
"$python_bin" -I -B "$script_dir/test-private-ci-review-coverage.py"
echo 'PRIVATE_CI_REVIEW_COVERAGE_PASSED=1'
for ((i=1; i<=repeats; i++)); do
  printf 'PRIVATE_CI_FULL_PYTHON_RUN=%d/%d\n' "$i" "$repeats"
  "$python_bin" -I -B - "$script_dir" <<'FULL_SUITE'
import importlib.util
from pathlib import Path
import sys
import unittest

root = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location(
    "publisher_offline_test_suite", root / "test-publish-private-ci-source.py")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
if not callable(getattr(module, "load_tests", None)):
    raise SystemExit("PRIVATE_CI_TEST_INVENTORY_MISSING_LOAD_TESTS")
suite = unittest.defaultTestLoader.loadTestsFromModule(module)
def each_test(group):
    for item in group:
        if isinstance(item, unittest.TestSuite):
            yield from each_test(item)
        else:
            yield item
cases = list(each_test(suite))
ids = [case.id() for case in cases]
publisher = sum(".DestinationBoundPublisherTests." in identifier for identifier in ids)
merged = sum(".MergedMainProvenanceTests." in identifier for identifier in ids)
if (len(ids) < 126 or publisher < 116 or merged < 10
        or publisher + merged != len(ids) or len(set(ids)) != len(ids)):
    raise SystemExit(
        "PRIVATE_CI_TEST_INVENTORY_REJECTED: "
        f"publisher={publisher} merged={merged} total={len(ids)}")
# Counts alone can be satisfied by padding the suite with inert test methods.
# Pin the security-critical case identities as well as the discovery floor.
required_publisher = {
    "test_stored_root_tree_readback_precedes_commit_publication",
    "test_stored_root_tree_tampering_denied_before_commit_or_ref",
    "test_unreadable_base_root_tree_denied_before_private_blob",
    "test_created_ci_commit_readback_fails_closed_before_ref",
    "test_created_ci_ref_readback_rejects_changed_or_malformed_ref",
    "test_source_pr_move_stops_next_private_blob_upload",
    "test_protected_main_revocation_stops_next_blob_upload",
    "test_destination_codeowners_revoked_after_first_blob",
    "test_destination_codeowners_hidden_sensitive_override_denied",
    "test_unavailable_source_branch_protection_api_blocks_before_source_read",
    "test_unavailable_destination_branch_protection_api_blocks_first_blob",
    "test_destination_branch_protection_api_revoked_mid_upload",
    "test_missing_source_main_codeowner_denies_before_source_read",
    "test_source_main_codeowner_override_denies_before_source_read",
    "test_source_main_codeowner_blob_tampering_denied",
    "test_source_main_codeowner_api_unavailable_denied_before_source_read",
    "test_source_codeowner_revocation_stops_next_private_blob",
    "test_github_api_absolute_cli_and_sanitized_environment",
    "test_github_api_rejects_missing_or_ambiguous_explicit_auth",
    "test_github_api_supports_single_actions_token_without_config",
    "test_complete_publisher_uses_data_only_preflight",
    "test_preflight_does_not_execute_checkout_controlled_shell",
    "test_denied_preflight_halts_before_api",
    "test_unreviewed_source_pr_denied_before_source_read",
    "test_noncanonical_policy_line_separators_cannot_grant_approval",
    "test_source_codeowners_line_separators_are_denied_before_source_read",
    "test_destination_codeowners_line_separators_are_denied_before_upload",
    "test_invalid_utf8_and_control_byte_records_fail_closed",
    "test_source_repository_numeric_identity_cannot_be_replaced",
    "test_preflight_destination_id_must_be_attested_before_source_read",
    "test_destination_id_swap_after_real_preflight_denies_before_read",
    "test_noncanonical_and_out_of_range_repository_ids_fail_closed",
    "test_duplicate_casefolded_destination_approval_is_denied",
    "test_real_preflight_denies_numeric_repository_substitution",
    "test_name_only_approval_is_not_a_numeric_id_authorization",
    "test_destination_numeric_id_change_stops_next_private_blob",
    "test_destination_repository_id_must_be_numeric",
    "test_preflight_rejects_noninteger_or_missing_destination_identity",
}
required_merged = {
    "test_independently_reviewed_exact_main_positive",
    "test_wrong_main_head_denied",
}
discovered_publisher = {s.rsplit(".", 1)[-1] for s in ids
                        if ".DestinationBoundPublisherTests." in s}
discovered_merged = {s.rsplit(".", 1)[-1] for s in ids
                     if ".MergedMainProvenanceTests." in s}
if not required_publisher <= discovered_publisher or not required_merged <= discovered_merged:
    raise SystemExit("PRIVATE_CI_TEST_REQUIRED_CASES_MISSING")
print(f"PRIVATE_CI_TEST_INVENTORY_OK publisher={publisher} "
      f"merged={merged} total={len(ids)}", flush=True)
result = unittest.TextTestRunner(verbosity=2).run(suite)
# unittest.wasSuccessful() still returns True if mandatory security cases are
# skipped or marked expectedFailure. A complete suite must execute its
# assertions, not merely discover the test IDs and count them as successful.
if (not result.wasSuccessful() or result.testsRun != len(ids)
        or result.skipped or result.expectedFailures or result.unexpectedSuccesses):
    raise SystemExit(
        "PRIVATE_CI_FULL_PYTHON_TEST_FAILURE: "
        f"executed={result.testsRun} expected={len(ids)} "
        f"skipped={len(result.skipped)} "
        f"expectedFailures={len(result.expectedFailures)} "
        f"unexpectedSuccesses={len(result.unexpectedSuccesses)}")
FULL_SUITE
done
fingerprint > "$manifest_after"
if ! cmp -s "$manifest_before" "$manifest_after"; then
  echo 'PRIVATE_CI_FILES_CHANGED_DURING_TEST' >&2
  exit 1
fi
echo 'PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED=1'
cat "$manifest_after"
