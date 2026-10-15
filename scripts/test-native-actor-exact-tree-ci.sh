#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

# Run only within an independently authorized source-local checkout, such as
# an ephemeral isolated self-hosted runner attached to the private source repo.
# Never use this as permission to publish source to an unapproved CI carrier.
: "${ORES_CI_EXPECTED_COMMIT_SHA:?pin reviewed 40-hex source commit SHA}"
: "${ORES_CI_EXPECTED_TREE_SHA:?pin reviewed 40-hex source Git tree SHA}"

case "$ORES_CI_EXPECTED_COMMIT_SHA:$ORES_CI_EXPECTED_TREE_SHA" in
  *[!0123456789abcdef:]*)
    echo "CI pin must consist only of lowercase Git SHA hex" >&2
    exit 1
    ;;
esac
if [ "${#ORES_CI_EXPECTED_COMMIT_SHA}" -ne 40 ] ||
   [ "${#ORES_CI_EXPECTED_TREE_SHA}" -ne 40 ]; then
  echo "CI pin must contain exact 40-hex commit and tree SHAs" >&2
  exit 1
fi

actual_commit="$(git rev-parse --verify HEAD)"
actual_tree="$(git rev-parse --verify 'HEAD^{tree}')"
if [ "$actual_commit" != "$ORES_CI_EXPECTED_COMMIT_SHA" ] ||
   [ "$actual_tree" != "$ORES_CI_EXPECTED_TREE_SHA" ]; then
  echo "CI source HEAD/tree differs from reviewed immutable pins" >&2
  exit 1
fi

# The WHOLE checkout must be pristine, not just Java/C subdirectories.
# Maven also loads stdlib, resources, .mvn and potentially other tracked inputs.
# Use an ephemeral fresh source checkout; any pre-existing untracked build
# output must be removed by the operator before entering this gate.
if [ -n "$(git status --porcelain --untracked-files=all)" ]; then
  echo "CI source checkout has modified or untracked inputs" >&2
  exit 1
fi

echo "ORES_NATIVE_ACTOR_CI_SOURCE_VERIFIED commit=$actual_commit tree=$actual_tree"
if [ "$(uname -s)" != Linux ]; then
  echo "native JNI exact-tree gate requires isolated x64 Linux" >&2
  exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven is required to run the complete JNI JUnit gate" >&2
  exit 1
fi
if ! command -v javac >/dev/null 2>&1; then
  echo "a full JDK21+ is required" >&2
  exit 1
fi
java -version
javac -version
mvn -version

# Prove the immutable-source gate rejects common adversarial source and
# identity substitutions before executing any guest or native test workload.
sh scripts/test-native-actor-ci-preflight.sh

# The immutable source check must precede every build, and stale native
# objects and Maven reports must never satisfy a current-head test.
mvn -B -ntp clean
rm -rf target/native target/native-jni

CC=gcc ORES_NATIVE_SANITIZE=0 ORES_NATIVE_TSAN=0 \
  sh scripts/test-native-actor-turn.sh
CC=clang ORES_NATIVE_SANITIZE=0 ORES_NATIVE_TSAN=0 \
  sh scripts/test-native-actor-turn.sh
CC=gcc ORES_NATIVE_SANITIZE=1 ORES_NATIVE_TSAN=0 \
  sh scripts/test-native-actor-turn.sh
CC=clang ORES_NATIVE_SANITIZE=1 ORES_NATIVE_TSAN=0 \
  sh scripts/test-native-actor-turn.sh
CC=gcc ORES_NATIVE_SANITIZE=0 ORES_NATIVE_TSAN=1 \
  sh scripts/test-native-actor-turn.sh

CC=gcc sh scripts/test-native-actor-scheduler-jni.sh

# A zero-step Actions failure or a successful shell syntax check does not
# satisfy this gate. Maven must execute the actual TaskRunner/JNI JUnit class.
mvn -B -ntp -Dtest=NativeActorTurnSchedulerJniTest \
  "-DargLine=-Djava.library.path=$PWD/target/native-jni/lib -Dores.native.actor.turn.experimental=true" \
  test

echo "ORES_NATIVE_ACTOR_EXACT_TREE_CI_PASS commit=$actual_commit tree=$actual_tree"
