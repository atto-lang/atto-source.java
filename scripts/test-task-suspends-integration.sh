#!/usr/bin/env bash
# Run from an authorized local checkout or private CI runner.
# No repository transfer or external credentials are needed.
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

# An approved GitHub runner must execute only a reviewed exact commit/tree in
# the source repository on a protected ref. No private source export.
if [[ "${GITHUB_ACTIONS:-}" == 'true' ]]; then
  [[ "${GITHUB_REPOSITORY:-}" == 'ores-truffle-oreslang/oreslang-source.java' ]] || {
    echo 'ERROR: source repository identity mismatch' >&2
    exit 2
  }
  [[ "${GITHUB_REF_PROTECTED:-}" == 'true' ]] || {
    echo 'ERROR: unprotected source ref' >&2
    exit 2
  }
  [[ "${ORES_TASK_EXPECTED_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || {
    echo 'ERROR: reviewed commit SHA missing or invalid' >&2
    exit 2
  }
  [[ "${ORES_TASK_EXPECTED_TREE:-}" =~ ^[0-9a-f]{40}$ ]] || {
    echo 'ERROR: reviewed tree SHA missing or invalid' >&2
    exit 2
  }
  [[ "$(git rev-parse 'HEAD^{commit}')" == "$ORES_TASK_EXPECTED_SHA" ]] || {
    echo 'ERROR: checkout is not the reviewed commit' >&2
    exit 2
  }
  [[ "$(git rev-parse 'HEAD^{tree}')" == "$ORES_TASK_EXPECTED_TREE" ]] || {
    echo 'ERROR: checkout tree differs from reviewed tree' >&2
    exit 2
  }
  [[ "${GITHUB_SHA:-}" == "$ORES_TASK_EXPECTED_SHA" ]] || {
    echo 'ERROR: workflow event SHA differs from reviewed commit' >&2
    exit 2
  }
fi
frontend='6a15dba1c4be4e0e7f2313e58b6a68c31c7ad4e8'
runtime='1a0e1607c51386ab77cd32ff49de19e4351f22f5'
integrated_merge='5d4b582d6c36570f6829ab63710941137491e185'
source_base='03bd361158aa9f04455577226466ca1e28b10b75'
git merge-base --is-ancestor "$frontend" HEAD || {
  echo 'ERROR: frontend source commit is not an ancestor' >&2
  exit 1
}
git merge-base --is-ancestor "$runtime" HEAD || {
  echo 'ERROR: runtime source commit is not an ancestor' >&2
  exit 1
}
git merge-base --is-ancestor "$integrated_merge" HEAD || {
  echo 'ERROR: reviewed runtime integration merge is not an ancestor' >&2
  exit 1
}
git merge-base --is-ancestor "$source_base" HEAD || {
  echo 'ERROR: source base commit is not an ancestor' >&2
  exit 1
}

# Ensure that all 17 files from the frontend review are byte-for-byte
# identical on the integration branch (the runtime-only hardening edits are
# deliberately separate). Avoid accepting a synthetic but mismatched merge.
mapfile -t front_paths < <(git diff --name-only "$source_base" "$frontend")
if (("${#front_paths[@]}" == 0)); then
  echo 'ERROR: frontend diff unexpectedly empty' >&2
  exit 1
fi
for path in "${front_paths[@]}"; do
  expected="$(git rev-parse "$frontend:$path")"
  actual="$(git rev-parse "HEAD:$path")"
  if [[ "$expected" != "$actual" ]]; then
    printf 'ERROR: frontend blob mismatch at %s\n' "$path" >&2
    exit 1
  fi
done
printf 'PROVENANCE_OK frontend=%s runtime=%s integration=%s files=%s\n' \
  "$frontend" "$runtime" "$(git rev-parse HEAD)" "${#front_paths[@]}"

# Verify the latest runtime subtree without replacing the integration-only
# rejection and teardown guards. All changed runtime files except the
# conflict-resolved TaskScope must be byte-identical to the source runtime
# branch. The TaskScope merged blob is reviewed independently.
runtime_previous='0f276633ae896b316d99274a3894d0f3496c52f4'
scope_path='src/main/java/dev/oreslang/runtime/OresTaskScope.java'
scope_reviewed='6220209e55dc43311e7d2ead98225e916ee9540e'
context_path='src/main/java/dev/oreslang/runtime/OresContext.java'
context_reviewed='0fe6d122925a2df8e23478edd48fbcd8929ab372'
frame_test_path='src/test/java/dev/oreslang/runtime/OresTaskScopeFrameTest.java'
frame_test_reviewed='349fe2389a24b77dc9c6e2895ebaa00145aa3d2d'
teardown_helper_path='src/main/java/dev/oreslang/runtime/OresContextTeardown.java'
teardown_helper_reviewed='5ed0f9e4948901234a3f1c54f5a7fd47def2f4f4'
teardown_test_path='src/test/java/dev/oreslang/runtime/OresContextTeardownTest.java'
teardown_test_reviewed='d80cd94056dc7cf3141d4c336a974b424f569075'

mapfile -t runtime_paths < <(git diff --name-only "$runtime_previous" "$runtime")
if (("${#runtime_paths[@]}" != 7)); then
  echo 'ERROR: reviewed runtime changed-file set drifted' >&2
  exit 1
fi
for path in "${runtime_paths[@]}"; do
  if [[ "$path" == "$scope_path" ]]; then
    expected="$scope_reviewed"
  else
    expected="$(git rev-parse "$runtime:$path")"
  fi
  actual="$(git rev-parse "HEAD:$path")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'ERROR: runtime integration blob mismatch at %s\n' "$path" >&2
    exit 1
  fi
done
for entry in "$context_path:$context_reviewed" "$frame_test_path:$frame_test_reviewed" "$teardown_helper_path:$teardown_helper_reviewed" "$teardown_test_path:$teardown_test_reviewed"; do
  path="${entry%%:*}"
  expected="${entry#*:}"
  actual="$(git rev-parse "HEAD:$path")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'ERROR: protected teardown/release regression at %s\n' "$path" >&2
    exit 1
  fi
done
printf 'TASK_INTEGRATION_EXACT_BLOBS frontend=%s runtime=%s\n' \
  "${#front_paths[@]}" "${#runtime_paths[@]}"


if ! command -v java >/dev/null || ! command -v mvn >/dev/null; then
  echo 'ERROR: Java 21 and Maven must be installed on the actual test runner' >&2
  exit 2
fi
java -version
mvn -version
# Maven Surefire: fail if no selected JUnit tests match. Never confuse
# a pre-step Actions startup failure with this test's executable result.
mvn -B -ntp -DfailIfNoTests=true \
  -Dtest=SuspendsCallbackIsolationTest,SuspendsTaskEffectContractTest,SuspendsContractLanguageTest,TaskOwnershipBoundaryTest,TaskBlockSyntaxTest,TaskLambdaIifeSyntaxTest,TaskReturnScopeAuditTest,TaskSpellingSyntaxTest,MutexLanguageTest,OresTaskTest,OresCancellationSourceTest,OresTaskScopeTest,OresTaskScopeFrameTest,OresTaskCooperativeSchedulerTest,OresContextTeardownTest \
  test
printf 'TASK_INTEGRATION_JUNIT_OK sha=%s\n' "$(git rev-parse HEAD)"
