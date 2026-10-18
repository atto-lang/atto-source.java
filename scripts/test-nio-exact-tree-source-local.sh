#!/usr/bin/env bash
# A SOURCE-LOCAL qualification helper. Never exports private Git objects.
# Run only from an authorized checkout on a trusted runner after branch review.
set -Eeuo pipefail

usage() {
  printf 'Usage: %s <40-hex-commit-sha> <40-hex-tree-sha> [--native-aot]\n' "$0" >&2
  exit 64
}
[[ $# -ge 2 && $# -le 3 ]] || usage
EXPECTED_COMMIT="$1"
EXPECTED_TREE="$2"
[[ "$EXPECTED_COMMIT" =~ ^[0-9a-f]{40}$ ]] || usage
[[ "$EXPECTED_TREE" =~ ^[0-9a-f]{40}$ ]] || usage
[[ $# -eq 2 || "$3" == "--native-aot" ]] || usage
NATIVE_AOT=false
if [[ $# -eq 3 ]]; then NATIVE_AOT=true; fi

root="$(git rev-parse --show-toplevel)" || exit 65
cd "$root"
START_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
EVIDENCE_DIR="$root/target/nio-exact-tree-gate"
status=FAIL
stage=source-identity
actual_commit=unverified
actual_tree=unverified
java_version=unverified
javac_version=unverified
maven_version=unverified
finish() {
  local code=$?
  trap - EXIT
  if (( code == 0 )); then status=PASS; fi
  # A dirty checkout can contain an ignored target/ symlink before any
  # preflight. Never follow it when writing a failure receipt (or logs).
  if [[ -L "$root/target" || -L "$EVIDENCE_DIR" ||
        -L "$EVIDENCE_DIR/receipt.txt" ]]; then
    printf 'ERROR: refusing to write qualification evidence through a symlink.\n' >&2
    # A successful test command cannot become a successful qualification if
    # its evidence location is unsafe. Preserve an earlier nonzero status.
    if (( code == 0 )); then code=73; fi
    exit "$code"
  fi
  # Maven clean may remove target/; restore the receipt directory on failure.
  mkdir -p "$EVIDENCE_DIR"
  # This receipt is unsigned and does not replace runner/job-step provenance.
  {
    printf 'gate=nio-exact-tree-source-local\n'
    printf 'result=%s\n' "$status"
    printf 'exit_code=%s\n' "$code"
    printf 'stage=%s\n' "$stage"
    printf 'expected_commit=%s\n' "$EXPECTED_COMMIT"
    printf 'observed_commit=%s\n' "$actual_commit"
    printf 'expected_tree=%s\n' "$EXPECTED_TREE"
    printf 'observed_tree=%s\n' "$actual_tree"
    printf 'started_utc=%s\n' "$START_UTC"
    printf 'ended_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'java_version=%s\n' "$java_version"
    printf 'javac_version=%s\n' "$javac_version"
    printf 'maven_version=%s\n' "$maven_version"
    printf 'native_aot_requested=%s\n' "$NATIVE_AOT"
  } > "$EVIDENCE_DIR/receipt.txt"
  exit "$code"
}
trap finish EXIT

actual_commit="$(git rev-parse --verify 'HEAD^{commit}')"
actual_tree="$(git rev-parse --verify 'HEAD^{tree}')"
if [[ "$actual_commit" != "$EXPECTED_COMMIT" || "$actual_tree" != "$EXPECTED_TREE" ]]; then
  printf 'ERROR: exact source commit/tree mismatch, refusing to run tests.\n' >&2
  exit 66
fi
if [[ -L "$root/target" || -L "$EVIDENCE_DIR" ||
      -L "$EVIDENCE_DIR/receipt.txt" ]]; then
  printf 'ERROR: target/, receipt directory or receipt file symlink is forbidden.\n' >&2
  exit 68
fi
# Git can hide modified tracked content from status when the index carries
# assume-unchanged or skip-worktree flags. An immutable HEAD/tree check is
# meaningless if such entries are allowed in the tested worktree.
# -v prints "H " for ordinary tracked paths; reject every other tag, including
# lower-case assume-unchanged and upper-case S sparse/skip-worktree.
unsafe_index_entries="$(git ls-files -v | LC_ALL=C awk 'substr($0,1,2) != "H " {print}')"
if [[ -n "$unsafe_index_entries" ]]; then
  printf 'ERROR: Git index contains hidden/sparse tracked entries; refusing exact-tree qualification.\n' >&2
  exit 72
fi
# Ordinary porcelain status excludes ignored files. Those files may still
# affect Maven execution (for example .mvn/maven.config with -DskipTests).
# Only root target/ is allowed, and it is wiped before any test runs.
# The check also catches local/global excludes, not merely .gitignore.
unexpected_ignored_files() {
  git -c core.fsmonitor=false ls-files --others --ignored --exclude-standard |
    LC_ALL=C awk 'index($0, "target/") != 1 { print }'
}
if [[ -n "$(unexpected_ignored_files)" ]]; then
  printf 'ERROR: ignored non-build files can affect exact-tree qualification.\n' >&2
  exit 74
fi
# Ignore repository-local overrides that can hide a tracked executable-bit
# mutation, even though HEAD/tree and default git status appear unchanged.
# Disable filesystem monitor caching for identity verification as well.
if [[ -n "$(git -c core.fileMode=true -c core.fsmonitor=false status --porcelain --untracked-files=all)" ]]; then
  printf 'ERROR: dirty or untracked source checkout, refusing to run tests.\n' >&2
  exit 67
fi

stage=toolchain
for tool in java javac mvn python3; do
  command -v "$tool" >/dev/null || { printf 'ERROR: required tool missing: %s\n' "$tool" >&2; exit 69; }
done
java_version="$(java -version 2>&1 | head -n 1)"
javac_version="$(javac -version 2>&1 | head -n 1)"
maven_version="$(mvn -version 2>&1 | head -n 1)"
[[ "$java_version" =~ version\ \"25([.\"]|$) || "$java_version" =~ (^|[[:space:]])25([.[:space:]]|$) ]] || {
  printf 'ERROR: JDK 25 java required; got: %s\n' "$java_version" >&2; exit 69;
}
[[ "$javac_version" =~ (^|[[:space:]])25([.[:space:]]|$) ]] || {
  printf 'ERROR: JDK 25 javac required; got: %s\n' "$javac_version" >&2; exit 69;
}
# Maven must launch using JDK 25 too; do not let Maven silently select JDK 21.
mvn_full_version="$(mvn -version 2>&1)"
[[ "$mvn_full_version" =~ Java\ version:\ 25([.[:space:]]|$) ]] || {
  printf 'ERROR: Maven does not use JDK 25.\n' >&2; exit 69;
}
if $NATIVE_AOT; then
  command -v native-image >/dev/null || {
    printf 'ERROR: --native-aot requires native-image on PATH.\n' >&2; exit 69;
  }
fi

printf 'Pinned checkout %s, tree %s; running JDK25 source-local tests.\n' "$actual_commit" "$actual_tree"
# Eliminate stale ignored target/ classes, test reports and generated output
# before the first selective JUnit run. A clean Git tree alone does not
# demonstrate a clean Maven build because target/ is gitignored.
stage=clean-stale-build
mvn -B -ntp clean
# Maven can be configured to skip or incompletely clean ignored build
# directories. A zero exit code alone is NOT proof of a fresh test run.
# Refuse to run any tests if previous compiler/test artifacts survive.
if [[ -e "$root/target" || -L "$root/target" ]]; then
  printf 'ERROR: Maven clean left target/ present; refusing stale output.\n' >&2
  exit 71
fi
mkdir -p "$EVIDENCE_DIR"
stage=targeted-junit
touch "$EVIDENCE_DIR/targeted-start.marker"
mvn -B -ntp -Dores.runtime.carriers=native \
  -DskipTests=false -Dmaven.test.skip=false -DfailIfNoTests=true \
  '-Dtest=VmNioTcpListenerTest,NativeNetStdlibTest,NioReadinessGroupTest,NativeNioFutureReactorTest,OresSchedulerTest,OresCarrierPermitPoolTest,OresFuturesTest,OresFutureCallbackTest' \
  test 2>&1 | tee "$EVIDENCE_DIR/targeted-junit.log"
stage=targeted-report-audit
python3 scripts/nio_verify_surefire_evidence.py "$EVIDENCE_DIR/targeted-start.marker" targeted

# The targeted suite may emit reports with arbitrarily future-dated mtimes.
# Timestamp comparisons alone cannot prove Maven verify produced new tests.
# Preserve phase-one XML for review, but physically remove it from Surefire's
# input/output directory before full verify. Full verification must repopulate
# the directory and produce its own required suites.
stage=isolate-targeted-reports
if [[ -e "$EVIDENCE_DIR/targeted-surefire-reports" || -L "$EVIDENCE_DIR/targeted-surefire-reports" ]]; then
  printf 'ERROR: targeted evidence archive already exists; refusing qualification.\n' >&2
  exit 76
fi
mv -- "$root/target/surefire-reports" "$EVIDENCE_DIR/targeted-surefire-reports"

stage=full-maven-verify
touch "$EVIDENCE_DIR/full-start.marker"
mvn -B -ntp -Dores.runtime.carriers=native verify 2>&1 \
  | tee "$EVIDENCE_DIR/full-maven-verify.log"
stage=full-report-audit
python3 scripts/nio_verify_surefire_evidence.py "$EVIDENCE_DIR/full-start.marker" full

if $NATIVE_AOT; then
  stage=graal-native-aot
  mvn -B -ntp -Pnative-aot -DskipTests package 2>&1 \
    | tee "$EVIDENCE_DIR/graal-native-aot.log"
fi

stage=postflight-source-identity
[[ "$(git rev-parse --verify 'HEAD^{commit}')" == "$EXPECTED_COMMIT" ]]
[[ "$(git rev-parse --verify 'HEAD^{tree}')" == "$EXPECTED_TREE" ]]
[[ -z "$(git ls-files -v | LC_ALL=C awk 'substr($0,1,2) != "H " {print}')" ]] || {
  printf 'ERROR: Git index flags changed during qualification.\n' >&2
  exit 72
}
[[ -z "$(git -c core.fileMode=true -c core.fsmonitor=false status --porcelain --untracked-files=all)" ]] || {
  printf 'ERROR: tracked or untracked source changed during qualification.\n' >&2
  exit 70
}
[[ -z "$(unexpected_ignored_files)" ]] || {
  printf 'ERROR: ignored non-build files appeared during qualification.\n' >&2
  exit 74
}
stage=complete
printf 'PASS: source-local NIO suite, full Maven verify%s\n' "$( $NATIVE_AOT && printf ', native-aot' || true)"
