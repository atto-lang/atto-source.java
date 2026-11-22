#!/usr/bin/env bash
# Exact-head Atto .atto source -> generated Java -> javac -> JVM / Native Image.
# No Python/JavaScript model substitutes for compiled Atto guest execution.
set -euo pipefail
cd "$(dirname "$0")/../.."
fixture_count="$(find fixtures/pipelines -type f -name '*.atto' | wc -l)"
test "$fixture_count" -eq 23 || { echo 'ATTO_PIPELINE_FIXTURE_COUNT_MISMATCH' >&2; exit 2; }
find fixtures/pipelines -type f -name '*.atto' -print0 | sort -z | \
  xargs -0 bash ci/admission/exact-tree.sh \
    src/compiler/pipeline/AttoFunctionalPipelineCompiler.java \
    ci/compiler/verify-functional-pipelines.sh \
    ci/compiler/verify-functional-pipeline-edges.sh \
    .github/workflows/java-functional-pipelines.yml

test -x "$JAVA_HOME/bin/javac" || exit 2
test -x "$JAVA_HOME/bin/native-image" || exit 2
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/compiler"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/compiler" \
  src/compiler/pipeline/AttoFunctionalPipelineCompiler.java

execute_expected() {
  local label="$1" expected="$2" stdout_file="$3" stderr_file="$4"
  shift 4
  local actual
  set +e
  "$@" >"$stdout_file" 2>"$stderr_file"
  actual=$?
  set -e
  if [[ "$actual" != "$expected" ]]; then
    cat "$stderr_file" >&2
    echo "ATTO_JAVA_PIPELINE_GUEST_FAILED case=$label expected=$expected got=$actual" >&2
    exit 1
  fi
  echo "ATTO_JAVA_PIPELINE_EXECUTED case=$label exit=$expected"
}

for spec in \
  block-body-83:83 \
  inline-arrow-83:83 \
  reverse-42:42 \
  grouped-42:42; do
  name="$(printf '%s' "$spec" | cut -d: -f1)"
  expected="$(printf '%s' "$spec" | cut -d: -f2)"
  source="fixtures/pipelines/$name.atto"
  mkdir -p "$work/$name" "$work/$name-replay"
  produced="$work/$name/AttoFunctionalPipelineGuest.java"
  replay="$work/$name-replay/AttoFunctionalPipelineGuest.java"

  "$JAVA_HOME/bin/java" -cp "$work/compiler" atto.compiler.pipeline.AttoFunctionalPipelineCompiler \
    "$source" "$produced" >"$work/$name/compiler.stdout"
  test -s "$produced"
  "$JAVA_HOME/bin/java" -cp "$work/compiler" atto.compiler.pipeline.AttoFunctionalPipelineCompiler \
    "$source" "$replay" >"$work/$name-replay/compiler.stdout"
  cmp "$produced" "$replay"
  "$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/$name" "$produced"
  execute_expected "$name-jvm" "$expected" \
    "$work/$name/jvm.stdout" "$work/$name/jvm.stderr" \
    "$JAVA_HOME/bin/java" -cp "$work/$name" AttoFunctionalPipelineGuest
  test ! -s "$work/$name/jvm.stdout"
  test ! -s "$work/$name/jvm.stderr"

  if [[ "$expected" == 83 ]]; then
    (
      cd "$work/$name"
      "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon \
        -H:Name=atto-functional-pipeline -cp "$work/$name" AttoFunctionalPipelineGuest
    )
    test -x "$work/$name/atto-functional-pipeline"
    execute_expected "$name-native-image" "$expected" \
      "$work/$name/aot.stdout" "$work/$name/aot.stderr" \
      "$work/$name/atto-functional-pipeline"
    test ! -s "$work/$name/aot.stdout"
    test ! -s "$work/$name/aot.stderr"
  fi
done

# Positive source-level i64::MIN is executable as a real compiled JVM
# callable; the adapter only observes the value and does not evaluate it.
mkdir -p "$work/signed-int64-min"
"$JAVA_HOME/bin/java" -cp "$work/compiler" atto.compiler.pipeline.AttoFunctionalPipelineCompiler \
  fixtures/pipelines/signed-int64-min.atto \
  "$work/signed-int64-min/AttoFunctionalPipelineGuest.java" >"$work/min-compiler.stdout"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/signed-int64-min" \
  "$work/signed-int64-min/AttoFunctionalPipelineGuest.java"
cat >"$work/signed-int64-min/AttoMinObserver.java" <<'JAVA'
public final class AttoMinObserver {
  public static void main(String[] args) {
    System.out.println(AttoFunctionalPipelineGuest.f_main());
  }
}
JAVA
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -cp "$work/signed-int64-min" \
  -d "$work/signed-int64-min" "$work/signed-int64-min/AttoMinObserver.java"
observed="$("$JAVA_HOME/bin/java" -cp "$work/signed-int64-min" AttoMinObserver)"
test "$observed" = "-9223372036854775808" || {
  echo "ATTO_JAVA_I64_MIN_NATIVE_JVM_CALL_MISMATCH observed=$observed" >&2
  exit 1
}
echo 'ATTO_JAVA_I64_MIN_NATIVE_JVM_CALL=EXECUTED'

for spec in \
  mixed-forward-reverse:E_PARSE \
  mixed-reverse-forward:E_PARSE \
  missing-semicolon:E_PARSE \
  wrong-type:E_TYPE \
  unknown-call:E_SYMBOL \
  arity:E_ARITY \
  bad-callee:E_PARSE \
  forward-local:E_SYMBOL \
  duplicate-local:E_DUP_LOCAL \
  arrow-brace-no-return:E_PARSE \
  signed-int64-underflow:E_LITERAL; do
  name="$(printf '%s' "$spec" | cut -d: -f1)"
  diagnostic="$(printf '%s' "$spec" | cut -d: -f2)"
  mkdir -p "$work/negative-$name"
  output="$work/negative-$name/AttoFunctionalPipelineGuest.java"
  set +e
  "$JAVA_HOME/bin/java" -cp "$work/compiler" atto.compiler.pipeline.AttoFunctionalPipelineCompiler \
    "fixtures/pipelines/negative/$name.atto" "$output" \
    >"$work/negative-$name/stdout" 2>"$work/negative-$name/stderr"
  actual=$?
  set -e
  test "$actual" -eq 2 || { echo "ATTO_JAVA_NEGATIVE_STATUS_MISMATCH=$name" >&2; exit 1; }
  grep -Fq "$diagnostic:" "$work/negative-$name/stderr" || {
    cat "$work/negative-$name/stderr"
    echo "ATTO_JAVA_NEGATIVE_DIAGNOSTIC_MISMATCH=$name" >&2
    exit 1
  }
  test ! -e "$output" || { echo "ATTO_JAVA_NEGATIVE_ARTIFACT_LEAK=$name" >&2; exit 1; }
done

bash ci/compiler/verify-functional-pipeline-edges.sh "$work"
echo 'ATTO_JAVA_PIPELINE_GUEST_MATRIX=EXECUTED jvm=5 native_image=2 negative=11 plus_edges=7'
