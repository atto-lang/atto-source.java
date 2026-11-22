#!/usr/bin/env bash
# Exact-source Java compiler + JVM guest gate, independent of native-image.
set -euo pipefail
cd "$(dirname "$0")/../.."
bash ci/admission/exact-tree.sh \
  src/compiler/asyncv1/AttoAsyncV1Compiler.java \
  src/compiler/asyncv1/AttoInlineTaskV2Compiler.java \
  tests/compiler/AttoAsyncV1CompilerProbe.java \
  tests/compiler/AttoInlineTaskV2Probe.java \
  tests/compiler/AttoAsyncV1CliSecurityProbe.java \
  tests/compiler/AttoAsyncV1GeneratedIdentifierProbe.java \
  fixtures/async_v1/inline_v2_nested.atto \
  fixtures/async_v1/inline_v2_arithmetic.atto \
  fixtures/async_v1/inline_v2_wide.atto \
  fixtures/async_v1/inline_v2_min_i64.atto \
  fixtures/async_v1/inline_v2_overflow.atto \
  ci/async/verify-java-signed-i64-jvm.sh

: "${JAVA_HOME:?JAVA_HOME must point to the selected JDK}"
test -x "$JAVA_HOME/bin/javac" && test -x "$JAVA_HOME/bin/java"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/classes" \
  src/compiler/asyncv1/AttoAsyncV1Compiler.java \
  src/compiler/asyncv1/AttoInlineTaskV2Compiler.java \
  tests/compiler/AttoAsyncV1CompilerProbe.java \
  tests/compiler/AttoInlineTaskV2Probe.java \
  tests/compiler/AttoAsyncV1CliSecurityProbe.java \
  tests/compiler/AttoAsyncV1GeneratedIdentifierProbe.java

"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1CompilerProbe \
  | tee "$work/admission.txt"
grep -Fx 'ATTO_ASYNC_V1_ADMISSION_PASS checks=97' "$work/admission.txt"
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoInlineTaskV2Probe \
  | tee "$work/inline.txt"
grep -Fx 'ATTO_INLINE_V2_PASS positives=20 negatives=116' "$work/inline.txt"
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1CliSecurityProbe \
  | tee "$work/security.txt"
grep -Fx 'ATTO_ASYNC_V1_CLI_SECURITY_PASS checks=10' "$work/security.txt"
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1GeneratedIdentifierProbe \
  | tee "$work/names.txt"
grep -Fx 'ATTO_ASYNC_V1_GENERATED_NAMES_PASS accepted=19 rejected=4' "$work/names.txt"

for item in \
  inline_v2_nested:42 \
  inline_v2_arithmetic:42 \
  inline_v2_wide:2147483648 \
  inline_v2_min_i64:-9223372036854775808; do
  case_name="${item%%:*}"
  result="${item#*:}"
  mkdir -p "$work/$case_name/classes"
  "$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
    "fixtures/async_v1/$case_name.atto" "$work/$case_name/AttoAsyncV1Guest.java"
  "$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/$case_name/classes" \
    "$work/$case_name/AttoAsyncV1Guest.java"
  set +e
  "$JAVA_HOME/bin/java" -cp "$work/$case_name/classes" AttoAsyncV1Guest \
    >"$work/$case_name/stdout" 2>"$work/$case_name/stderr"
  status=$?
  set -e
  case "$case_name" in
    inline_v2_nested|inline_v2_arithmetic) test "$status" -eq 42 ;;
    *) test "$status" -eq 0 ;;
  esac
  grep -Fx "ATTO_ASYNC_V1_RESULT=$result" "$work/$case_name/stdout"
  grep -Fx 'ATTO_ASYNC_SCOPE=PURE_INLINE_TYPED_V2' "$work/$case_name/stdout"
  test ! -s "$work/$case_name/stderr"
done

mkdir -p "$work/inline_v2_overflow/classes"
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
  fixtures/async_v1/inline_v2_overflow.atto "$work/inline_v2_overflow/AttoAsyncV1Guest.java"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/inline_v2_overflow/classes" \
  "$work/inline_v2_overflow/AttoAsyncV1Guest.java"
set +e
"$JAVA_HOME/bin/java" -cp "$work/inline_v2_overflow/classes" AttoAsyncV1Guest \
  >"$work/inline_v2_overflow/stdout" 2>"$work/inline_v2_overflow/stderr"
status=$?
set -e
test "$status" -ne 0
grep -F 'ArithmeticException: long overflow' "$work/inline_v2_overflow/stderr"
! grep -q 'ATTO_ASYNC_V1_RESULT' "$work/inline_v2_overflow/stdout"
echo 'ATTO_JAVA_SIGNED_I64_COMPILED_JVM=PASS'
