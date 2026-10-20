#!/usr/bin/env bash
# Source-free adversarial smoke test for the offline regression runner itself.
set -euo pipefail
runner="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/run-private-ci-regressions-local.sh"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
fixture="$root/scripts"
make_fixture() {
  rm -rf "$fixture" "$root/.github"
  mkdir -p "$fixture" "$root/.github/workflows"
  printf '/.github/ @ORESoftware\n' > "$root/.github/CODEOWNERS"
  printf '# synthetic allowlist for offline test only\n' > "$root/.github/private-ci-approved-repositories.txt"
  printf 'name: synthetic source-free test workflow\n' > "$root/.github/workflows/private-ci-mirror-preflight.yml"
  cp "$runner" "$fixture/run-private-ci-regressions-local.sh"
  cp "$(dirname "$runner")/test-run-private-ci-regressions-local.sh" \
    "$fixture/test-run-private-ci-regressions-local.sh"
  printf 'def dummy(): return True\n' > "$fixture/test-private-ci-review-coverage.py"
  printf 'def dummy(): return True\n' > "$fixture/publish-private-ci-source.py"
  cat > "$fixture/test-private-ci-merged-main.py" <<'MERGED'
import unittest
class MergedMainProvenanceTests(unittest.TestCase):
    pass
mandatory = ("test_independently_reviewed_exact_main_positive",
             "test_wrong_main_head_denied")
for index in range(10):
    name = mandatory[index] if index < len(mandatory) else f"test_synthetic_{index:03d}"
    setattr(MergedMainProvenanceTests, name,
            lambda self: self.assertTrue(True))
MERGED
  cat > "$fixture/test-publish-private-ci-source.py" <<'PY'
import importlib.util, os, unittest
from pathlib import Path
class DestinationBoundPublisherTests(unittest.TestCase):
    def test_mocked(self):
        self.assertFalse(os.environ.get('GH_TOKEN'))
        self.assertFalse(os.environ.get('GITHUB_TOKEN'))
        self.assertTrue(os.path.isdir(os.environ['GH_CONFIG_DIR']))
        self.assertFalse(os.path.exists(os.path.join(os.environ['GH_CONFIG_DIR'], 'hosts.yml')))
        self.assertEqual(os.environ.get('GIT_TERMINAL_PROMPT'), '0')
mandatory = (
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
    "test_github_api_rejects_duplicate_security_fields_at_any_depth",
    "test_github_api_rejects_nonfinite_json_constants",
    "test_github_api_rejects_overflowing_finite_syntax_numbers",
    "test_github_api_accepts_finite_json_float",
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
)
for index in range(116):
    name = mandatory[index] if index < len(mandatory) else f"test_synthetic_{index:03d}"
    setattr(DestinationBoundPublisherTests, name,
            lambda self: self.assertTrue(True))
def load_tests(loader, suite, pattern):
    spec = importlib.util.spec_from_file_location(
        "synthetic_merged_tests", Path(__file__).with_name("test-private-ci-merged-main.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    suite.addTests(loader.loadTestsFromModule(module))
    return suite
if __name__ == '__main__':
    unittest.main()
PY
  for script in check-private-ci-destination.sh run-in-private-ci-destination.sh; do
    printf '#!/usr/bin/env bash\nset -euo pipefail\n' > "$fixture/$script"
  done
  printf '#!/usr/bin/env bash\nset -euo pipefail\necho SYNTHETIC_BASH_TEST_PASSED=1\n' > "$fixture/test-private-ci-destination.sh"
}
make_fixture
GH_TOKEN=secret GITHUB_TOKEN=secret PRIVATE_CI_TEST_REPEATS=3 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1
grep -q '^PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED=1$' "$root/out"
grep -q '^PRIVATE_CI_FULL_PYTHON_RUN=3/3$' "$root/out"
grep -q '^PRIVATE_CI_REVIEW_COVERAGE_PASSED=1$' "$root/out"
echo 'PASS: normal execution strips credentials, executes review-coverage, repeats entire suite'
make_fixture
for n in 0 nope 100; do
  if PRIVATE_CI_TEST_REPEATS="$n" bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
  grep -q PRIVATE_CI_INVALID_REPEATS "$root/out"
done
echo 'PASS: invalid repeat settings are rejected'
make_fixture
python3 - "$fixture/test-publish-private-ci-source.py" <<'PYTHON'
import pathlib, sys
p=pathlib.Path(sys.argv[1]); s=p.read_text()
s=s.replace("self.assertFalse(os.environ.get('GH_TOKEN'))", "self.assertTrue(os.environ.get('GH_TOKEN'))")
p.write_text(s)
PYTHON
if bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
! grep -q '^PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED=1$' "$root/out"
echo 'PASS: broken Python test never produces success receipt'
make_fixture
cat >> "$fixture/test-private-ci-destination.sh" <<'SH'
printf '\n# tampered\n' >> "$(dirname "$0")/publish-private-ci-source.py"
SH
if PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_FILES_CHANGED_DURING_TEST "$root/out"
echo 'PASS: mutation of a checked-in test dependency is detected'
make_fixture
mv "$fixture/publish-private-ci-source.py" "$root/external"
ln -s "$root/external" "$fixture/publish-private-ci-source.py"
if bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_MISSING_OR_SYMLINKED_FILE "$root/out"
echo 'PASS: symlinked source inputs fail closed'
make_fixture
rm "$fixture/test-private-ci-merged-main.py"
if bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_MISSING_OR_SYMLINKED_FILE "$root/out"
echo 'PASS: missing merged-main production tests fail closed'
make_fixture
cat >> "$fixture/test-private-ci-destination.sh" <<'SH'
printf '\n# tampered runner\n' >> "$(dirname "$0")/run-private-ci-regressions-local.sh"
SH
if PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_FILES_CHANGED_DURING_TEST "$root/out"
echo 'PASS: mutation of executing offline runner is detected'
make_fixture
cat >> "$fixture/test-private-ci-destination.sh" <<'SH'
printf '\n# tampered coverage\n' >> "$(dirname "$0")/test-private-ci-review-coverage.py"
SH
if PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_FILES_CHANGED_DURING_TEST "$root/out"
echo 'PASS: mutation of review-coverage verifier is detected'
make_fixture
mv "$fixture/test-private-ci-review-coverage.py" "$root/coverage"
ln -s "$root/coverage" "$fixture/test-private-ci-review-coverage.py"
if bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_MISSING_OR_SYMLINKED_FILE "$root/out"
echo 'PASS: symlinked review-coverage verifier fails closed'
make_fixture
printf 'raise SystemExit(71)\n' > "$fixture/test-private-ci-review-coverage.py"
if bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
! grep -q '^PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED=1$' "$root/out"
! grep -q '^PRIVATE_CI_REVIEW_COVERAGE_PASSED=1$' "$root/out"
echo 'PASS: failed review-coverage verifier cannot create a success receipt'
make_fixture
if PYTHON=/bin/true bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_UNSAFE_PYTHON_OVERRIDE "$root/out"
echo 'PASS: /bin/true cannot masquerade as a passing Python interpreter'
make_fixture
mkdir -p "$root/injected"
cat > "$root/injected/sitecustomize.py" <<'PY_SITE'
import os
with open(os.environ['PYTHON_SITE_MARKER'], 'w') as handle:
    handle.write('unexpected Python startup injection')
PY_SITE
if ! PYTHONPATH="$root/injected" PYTHON_SITE_MARKER="$root/startup-marker" \
      PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" \
      > "$root/out" 2>&1; then
  cat "$root/out" >&2
  exit 1
fi
test ! -e "$root/startup-marker"
grep -q '^PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED=1$' "$root/out"
echo 'PASS: injected sitecustomize and PYTHONPATH cannot execute'
make_fixture
cat >> "$fixture/test-private-ci-destination.sh" <<'SH'
printf '\n# tampered owner\n' >> "$(dirname "$0")/../.github/CODEOWNERS"
SH
if PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_FILES_CHANGED_DURING_TEST "$root/out"
echo 'PASS: mutation of CODEOWNERS source-control policy is detected'
make_fixture
cat >> "$fixture/test-private-ci-destination.sh" <<'SH'
printf '\n# tampered workflow\n' >> "$(dirname "$0")/../.github/workflows/private-ci-mirror-preflight.yml"
SH
if PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_FILES_CHANGED_DURING_TEST "$root/out"
echo 'PASS: mutation of reviewed CI workflow is detected'
make_fixture
mv "$root/.github/private-ci-approved-repositories.txt" "$root/external-policy"
ln -s "$root/external-policy" "$root/.github/private-ci-approved-repositories.txt"
if bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_MISSING_OR_SYMLINKED_FILE "$root/out"
echo 'PASS: symlinked CI approval policy fails closed'
make_fixture
rm "$root/.github/CODEOWNERS"
if bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_MISSING_OR_SYMLINKED_FILE "$root/out"
echo 'PASS: missing CODEOWNERS policy fails closed'
make_fixture
cat > "$fixture/test-publish-private-ci-source.py" <<'PY'
import unittest
class DestinationBoundPublisherTests(unittest.TestCase):
    def test_only_one_pass(self): self.assertTrue(True)
if __name__ == "__main__": unittest.main()
PY
if PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_TEST_INVENTORY_MISSING_LOAD_TESTS "$root/out"
if grep -q PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED "$root/out"; then exit 1; fi
echo 'PASS: a single passing test without full discovery cannot produce a success receipt'
make_fixture
python3 - "$fixture/test-publish-private-ci-source.py" <<'PY'
from pathlib import Path
import sys
file = Path(sys.argv[1])
content = file.read_text()
assert "range(116)" in content
file.write_text(content.replace("range(116)", "range(1)"))
PY
if PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_TEST_INVENTORY_REJECTED "$root/out"
if grep -q PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED "$root/out"; then exit 1; fi
echo 'PASS: shortened suite with valid load_tests is rejected'
make_fixture
python3 - "$fixture/test-publish-private-ci-source.py" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
data = p.read_text()
required = "test_stored_root_tree_tampering_denied_before_commit_or_ref"
assert data.count(required) == 1
p.write_text(data.replace(required, "test_disarmed_root_tree_tampering_denied_before_commit_or_ref"))
PY
if PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_TEST_REQUIRED_CASES_MISSING "$root/out"
if grep -q PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED "$root/out"; then exit 1; fi
echo 'PASS: equal-sized suite missing a mandatory adversarial case is rejected'
make_fixture
cat >> "$fixture/test-publish-private-ci-source.py" <<'PY'
DestinationBoundPublisherTests.test_stored_root_tree_tampering_denied_before_commit_or_ref = unittest.skip(
    "attacker removed critical assertion")(
    DestinationBoundPublisherTests.test_stored_root_tree_tampering_denied_before_commit_or_ref)
PY
if PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_FULL_PYTHON_TEST_FAILURE "$root/out"
grep -q 'skipped=1' "$root/out"
if grep -q PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED "$root/out"; then exit 1; fi
echo 'PASS: discovered mandatory test marked skipped cannot yield success'
make_fixture
cat >> "$fixture/test-publish-private-ci-source.py" <<'PY'
def attack_expected_failure(self):
    self.fail("attacker masked critical failure")
DestinationBoundPublisherTests.test_stored_root_tree_tampering_denied_before_commit_or_ref = unittest.expectedFailure(
    attack_expected_failure)
PY
if PRIVATE_CI_TEST_REPEATS=1 bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then exit 1; fi
grep -q PRIVATE_CI_FULL_PYTHON_TEST_FAILURE "$root/out"
grep -q 'expectedFailures=1' "$root/out"
if grep -q PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED "$root/out"; then exit 1; fi
echo 'PASS: discovered mandatory test marked expectedFailure cannot yield success'
make_fixture
mkdir -p "$root/poisoned-PATH"
for executable in python3 dirname; do
  cat > "$root/poisoned-PATH/$executable" <<'FAKE_EXEC'
#!/bin/sh
printf 'unexpected executable invoked\n' >> "$PATH_INJECTION_MARKER"
exit 0
FAKE_EXEC
  chmod +x "$root/poisoned-PATH/$executable"
done
# The current runner must sanitize PATH *before* calling even dirname.
if ! PATH="$root/poisoned-PATH:$PATH" PATH_INJECTION_MARKER="$root/path-injection-marker" PRIVATE_CI_TEST_REPEATS=1 /bin/bash "$fixture/run-private-ci-regressions-local.sh" > "$root/out" 2>&1; then
  cat "$root/out" >&2
  exit 1
fi
test ! -e "$root/path-injection-marker"
grep -q '^PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED=1$' "$root/out"
echo 'PASS: caller PATH cannot replace trusted Python or dirname'
make_fixture
printf '%s\n' 'printf "unsafe hook executed\n" >> "$CI_HOOK_MARKER"' > "$root/bash-env-hook"
status=0
CI_HOOK_MARKER="$root/bash-env-triggered" \
  BASH_ENV="$root/bash-env-hook" \
  PRIVATE_CI_TEST_REPEATS=1 /bin/bash "$fixture/run-private-ci-regressions-local.sh" \
  > "$root/out" 2>&1 || status=$?
test "$status" -eq 2
test -s "$root/bash-env-triggered"
grep -q PRIVATE_CI_UNTRUSTED_LAUNCH_ENV "$root/out"
if grep -q PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED "$root/out"; then exit 1; fi
echo 'PASS: direct BASH_ENV startup injection cannot earn regression receipt'
make_fixture
env -i PATH=/usr/bin:/bin HOME=/ PRIVATE_CI_TEST_REPEATS=1 \
  /bin/bash "$fixture/run-private-ci-regressions-local.sh" \
  > "$root/out" 2>&1 || { cat "$root/out" >&2; exit 1; }
grep -q '^PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED=1$' "$root/out"
echo 'PASS: clean env -i launch can earn regression receipt'
echo 'PRIVATE_CI_LOCAL_RUNNER_SELFTESTS_PASSED=24'
