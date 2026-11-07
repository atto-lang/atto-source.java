#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if git ls-files -- 'src/main/java/dev/oreslang' 'src/test/java/dev/oreslang' | grep -q .; then
  echo "::error::Legacy Java source paths remain under dev/oreslang" >&2
  exit 1
fi

mapfile -d '' -t java_files < <(git ls-files -z -- 'src/main/java/dev/atto' 'src/test/java/dev/atto')
if (( ${#java_files[@]} < 1 )); then
  echo "::error::No migrated dev.atto Java files found" >&2
  exit 1
fi

main_count=0
test_count=0
for file in "${java_files[@]}"; do
  [[ "${file}" == *.java ]] || continue
  if [[ "${file}" == src/main/java/* ]]; then
    relative="${file#src/main/java/}"
    ((main_count += 1))
  else
    relative="${file#src/test/java/}"
    ((test_count += 1))
  fi
  package_dir="${relative%/*}"
  expected_package="${package_dir//\//.}"
  if ! grep -Fqx "package ${expected_package};" "${file}"; then
    echo "::error file=${file}::Expected package ${expected_package}" >&2
    exit 1
  fi
done

if grep -RnE 'dev[.]oreslang|dev/oreslang' --include='*.java' src/main/java src/test/java; then
  echo "::error::Stale Java FQCN/package/path reference" >&2
  exit 1
fi

grep -Fqx 'module dev.atto {' src/main/java/module-info.java
grep -Fq '<targetPath>dev/atto/std</targetPath>' pom.xml
grep -Fq '<groupId>dev.oreslang</groupId>' pom.xml
grep -Fq 'CORE_RESOURCE_PREFIX = "dev/atto/core/"' src/main/java/dev/atto/stdlib/CoreLibrarySources.java
grep -Fq 'STD_RESOURCE_PREFIX = "dev/atto/std/"' src/main/java/dev/atto/stdlib/CoreLibrarySources.java
test -f src/main/resources/dev/atto/core/numeric.atto
test ! -e src/main/resources/dev/oreslang/core/numeric.atto
grep -Fq '"module": "dev.atto"' src/main/resources/META-INF/native-image/dev.oreslang/oreslang/reachability-metadata.json

bash scripts/verify-atto-jni-bindings.sh

echo "Java namespace paths and declarations verified: ${main_count} main, ${test_count} test"
