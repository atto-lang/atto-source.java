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
  printf 'def dummy(): return True\n' > "$fixture/test-private-ci-merged-main.py"
  cat > "$fixture/test-publish-private-ci-source.py" <<'PY'
import os, unittest
class Tests(unittest.TestCase):
    def test_mocked(self):
        self.assertFalse(os.environ.get('GH_TOKEN'))
        self.assertFalse(os.environ.get('GITHUB_TOKEN'))
        self.assertTrue(os.path.isdir(os.environ['GH_CONFIG_DIR']))
        self.assertFalse(os.path.exists(os.path.join(os.environ['GH_CONFIG_DIR'], 'hosts.yml')))
        self.assertEqual(os.environ.get('GIT_TERMINAL_PROMPT'), '0')
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
echo 'PRIVATE_CI_LOCAL_RUNNER_SELFTESTS_PASSED=16' 
