#!/usr/bin/env bash
# Restricted .atto completed-Future pipeline -> generated Java / Graal Native Image.
set -euo pipefail
cd "$(dirname "$0")/../.."
bash ci/admission/exact-tree.sh \
  src/compiler/asyncv1/AttoAsyncV1Compiler.java \
  src/compiler/asyncv1/AttoInlineTaskV2Compiler.java \
  tests/compiler/AttoAsyncV1CompilerProbe.java \
  tests/compiler/AttoInlineTaskV2Probe.java \
  tests/compiler/AttoAsyncV1CliSecurityProbe.java \
  tests/compiler/AttoAsyncV1GeneratedIdentifierProbe.java \
  fixtures/async_v1/completed_future.atto \
  fixtures/async_v1/completed_future_typed.atto \
  fixtures/async_v1/sync_inline_int.atto \
  fixtures/async_v1/sync_inline_empty.atto \
  fixtures/async_v1/sync_inline_do_nonvoid.atto \
  fixtures/async_v1/sync_inline_bare_without_do.atto \
  fixtures/async_v1/sync_inline_bare_typed_void.atto \
  fixtures/async_v1/inline_v2_nested.atto \
  fixtures/async_v1/inline_v2_arithmetic.atto \
  fixtures/async_v1/inline_v2_bad_void.atto \
  fixtures/async_v1/inline_v2_unsupported_effect.atto \
  fixtures/async_v1/inline_v2_overflow.atto \
  fixtures/async_v1/inline_v2_wide.atto \
  fixtures/async_v1/inline_v2_min_i64.atto \
  ci/async/verify-compiled-completed-future.sh
test -x "$JAVA_HOME/bin/javac" && test -x "$JAVA_HOME/bin/native-image" || exit 2
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
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1CompilerProbe | tee "$work/probe"
grep -Fx "ATTO_ASYNC_V1_ADMISSION_PASS checks=97" "$work/probe"
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoInlineTaskV2Probe \
  | tee "$work/inline-v2-probe"
grep -Fx 'ATTO_INLINE_V2_PASS positives=20 negatives=116' "$work/inline-v2-probe"
"$JAVA_HOME/bin/java" -cp "$work/classes" \
  atto.compiler.asyncv1.AttoAsyncV1CliSecurityProbe | tee "$work/cli-security"
grep -Fx "ATTO_ASYNC_V1_CLI_SECURITY_PASS checks=10" "$work/cli-security"
"$JAVA_HOME/bin/java" -cp "$work/classes" \
  atto.compiler.asyncv1.AttoAsyncV1GeneratedIdentifierProbe | tee "$work/identifiers"
grep -Fx "ATTO_ASYNC_V1_GENERATED_NAMES_PASS accepted=19 rejected=4" "$work/identifiers"
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
  fixtures/async_v1/completed_future.atto "$work/AttoAsyncV1Guest.java"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/classes" "$work/AttoAsyncV1Guest.java"
set +e
"$JAVA_HOME/bin/java" -cp "$work/classes" AttoAsyncV1Guest >"$work/jvm.out" 2>"$work/jvm.err"
status=$?
set -e
test "$status" -eq 42 || { cat "$work/jvm.err"; exit 1; }
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/jvm.out"
(
  cd "$work"
  "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon \
    -H:Name=atto-async-v1 -cp "$work/classes" AttoAsyncV1Guest
)
test -x "$work/atto-async-v1"
file "$work/atto-async-v1" | grep -Eq 'ELF|Mach-O'
set +e
timeout 60 "$work/atto-async-v1" >"$work/native.out" 2>"$work/native.err"
status=$?
set -e
test "$status" -eq 42 || { cat "$work/native.err"; exit 1; }
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/native.out"
grep -Fx 'ATTO_ASYNC_SCOPE=COMPLETED_FUTURE_ONLY' "$work/native.out"
test ! -s "$work/native.err"
# Canonical syntax task<int> produces Future<int> in this closed-world,
# already-completed scalar source profile. This is not cancellation support.
mkdir -p "$work/typed/classes"
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
  fixtures/async_v1/completed_future_typed.atto "$work/typed/AttoAsyncV1Guest.java"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/typed/classes" \
  "$work/typed/AttoAsyncV1Guest.java"
set +e
"$JAVA_HOME/bin/java" -cp "$work/typed/classes" AttoAsyncV1Guest \
  >"$work/typed/jvm.out" 2>"$work/typed/jvm.err"
status=$?
set -e
test "$status" -eq 42 || { cat "$work/typed/jvm.err"; exit 1; }
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/typed/jvm.out"
(
  cd "$work/typed"
  "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon \
    -H:Name=atto-async-v1-typed -cp "$work/typed/classes" AttoAsyncV1Guest
)
test -x "$work/typed/atto-async-v1-typed"
file "$work/typed/atto-async-v1-typed" | grep -Eq 'ELF|Mach-O'
set +e
timeout 60 "$work/typed/atto-async-v1-typed" \
  >"$work/typed/native.out" 2>"$work/typed/native.err"
status=$?
set -e
test "$status" -eq 42 || { cat "$work/typed/native.err"; exit 1; }
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/typed/native.out"
test ! -s "$work/typed/native.err"
echo 'ATTO_ASYNC_V1_TYPED_PAYLOAD_JVM_NATIVE=PASS'
echo 'ATTO_ASYNC_V1_JVM_AND_NATIVE=PASS'
echo 'ATTO_GENERAL_FUTURE_SUSPENSION_AND_BORROW_CHECKER=NOT_VERIFIED'

# Verify lexically inline source-to-Java execution and no TaskSpawn/Future.
for case in sync_inline_int sync_inline_empty; do
  mkdir -p "$work/$case/classes"
  "$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
    "fixtures/async_v1/$case.atto" "$work/$case/AttoAsyncV1Guest.java"
  if grep -Eq 'CompletableFuture|\.join\(' "$work/$case/AttoAsyncV1Guest.java"; then
    echo "inline task unexpectedly allocated a Future or joined" >&2
    exit 1
  fi
  "$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/$case/classes" \
    "$work/$case/AttoAsyncV1Guest.java"
  set +e
  "$JAVA_HOME/bin/java" -cp "$work/$case/classes" AttoAsyncV1Guest \
    >"$work/$case/jvm.out" 2>"$work/$case/jvm.err"
  status=$?
  set -e
  test "$status" -eq 42 || { cat "$work/$case/jvm.err"; exit 1; }
  grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/$case/jvm.out"
  grep -Fx 'ATTO_ASYNC_SCOPE=PURE_INLINE_TASK_ONLY' "$work/$case/jvm.out"
  test ! -s "$work/$case/jvm.err"
done
(
  cd "$work/sync_inline_int"
  "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon \
    -H:Name=atto-sync-inline-v1 -cp "$work/sync_inline_int/classes" AttoAsyncV1Guest
)
test -x "$work/sync_inline_int/atto-sync-inline-v1"
set +e
timeout 60 "$work/sync_inline_int/atto-sync-inline-v1" \
  >"$work/sync_inline_int/native.out" 2>"$work/sync_inline_int/native.err"
status=$?
set -e
test "$status" -eq 42 || { cat "$work/sync_inline_int/native.err"; exit 1; }
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/sync_inline_int/native.out"
grep -Fx 'ATTO_ASYNC_SCOPE=PURE_INLINE_TASK_ONLY' "$work/sync_inline_int/native.out"
test ! -s "$work/sync_inline_int/native.err"
echo 'ATTO_ASYNC_V1_PURE_INLINE_JVM_NATIVE=PASS'

# Invalid discarded task payloads must fail before creating generated Java.
mkdir -p "$work/do-sync-negative"
set +e
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
  fixtures/async_v1/sync_inline_do_nonvoid.atto \
  "$work/do-sync-negative/AttoAsyncV1Guest.java" \
  >"$work/do-sync-negative/out" 2>"$work/do-sync-negative/err"
status=$?
set -e
test "$status" -ne 0
grep -F 'E_TASK_RETURN_TYPE' "$work/do-sync-negative/err"
test ! -e "$work/do-sync-negative/AttoAsyncV1Guest.java"
echo 'ATTO_DO_SYNC_VOID_ONLY_NO_ARTIFACT=PASS'

# Bare sync task expressions are forbidden as standalone statements. Both
# invalid syntax forms must emit E_SYNTAX and no Java guest source artifact.
for case in sync_inline_bare_without_do sync_inline_bare_typed_void; do
  target="$work/$case/AttoAsyncV1Guest.java"
  mkdir -p "$work/$case"
  set +e
  "$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
    "fixtures/async_v1/$case.atto" "$target" \
    >"$work/$case/out" 2>"$work/$case/err"
  status=$?
  set -e
  test "$status" -ne 0
  grep -F 'E_SYNTAX' "$work/$case/err"
  test ! -e "$target" || { echo "ATTO_BARE_SYNC_GUEST_ARTIFACT_LEAK=$case"; exit 1; }
done
echo 'ATTO_SYNC_STANDALONE_REQUIRES_DO_NO_ARTIFACT=PASS'

# Typed bounded inline v2: nested lexical scopes and checked integer arithmetic.
for case in inline_v2_nested inline_v2_arithmetic; do
  mkdir -p "$work/$case/classes"
  "$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
    "fixtures/async_v1/$case.atto" "$work/$case/AttoAsyncV1Guest.java"
  ! grep -Eq 'CompletableFuture|\.join\(|TaskSpawn|new Thread' \
    "$work/$case/AttoAsyncV1Guest.java"
  "$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/$case/classes" \
    "$work/$case/AttoAsyncV1Guest.java"
  set +e
  "$JAVA_HOME/bin/java" -cp "$work/$case/classes" AttoAsyncV1Guest \
    >"$work/$case/stdout" 2>"$work/$case/stderr"
  status=$?
  set -e
  test "$status" -eq 42
  grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/$case/stdout"
  grep -Fx 'ATTO_ASYNC_SCOPE=PURE_INLINE_TYPED_V2' "$work/$case/stdout"
  test ! -s "$work/$case/stderr"
done
(
  cd "$work/inline_v2_nested"
  "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon \
    -H:Name=atto-inline-v2 -cp "$work/inline_v2_nested/classes" AttoAsyncV1Guest
)
test -x "$work/inline_v2_nested/atto-inline-v2"
set +e
timeout 60 "$work/inline_v2_nested/atto-inline-v2" \
  >"$work/inline_v2_nested/native.out" 2>"$work/inline_v2_nested/native.err"
status=$?
set -e
test "$status" -eq 42
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/inline_v2_nested/native.out"
grep -Fx 'ATTO_ASYNC_SCOPE=PURE_INLINE_TYPED_V2' "$work/inline_v2_nested/native.out"
test ! -s "$work/inline_v2_nested/native.err"
echo 'ATTO_INLINE_V2_JVM_NATIVE=PASS'

# Canonical int is signed 64-bit; valid wide/signed-min guest values must
# survive generated Java without narrowing to Java int. Only OS exit status
# is narrowed at the host boundary; guest result is verified via stdout.
for entry in inline_v2_wide:2147483648 inline_v2_min_i64:-9223372036854775808; do
  case_name="${entry%%:*}"
  expected="${entry#*:}"
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
  test "$status" -eq 0
  grep -Fx "ATTO_ASYNC_V1_RESULT=$expected" "$work/$case_name/stdout"
  test ! -s "$work/$case_name/stderr"
done
echo 'ATTO_INLINE_V2_SIGNED_I64_JVM=PASS'

# Unsupported effects or nonvoid discarded payloads must reject before
# writing a generated guest artifact, even when nested.
for case in inline_v2_bad_void inline_v2_unsupported_effect; do
  mkdir -p "$work/$case"
  output="$work/$case/AttoAsyncV1Guest.java"
  set +e
  "$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
    "fixtures/async_v1/$case.atto" "$output" \
    >"$work/$case/stdout" 2>"$work/$case/stderr"
  status=$?
  set -e
  test "$status" -ne 0
  test ! -e "$output"
  if test "$case" = inline_v2_bad_void; then
    grep -F 'E_TASK_RETURN_TYPE' "$work/$case/stderr"
  else
    grep -F 'E_UNSUPPORTED_INLINE_EFFECT' "$work/$case/stderr"
  fi
done
echo 'ATTO_INLINE_V2_NEGATIVE_NO_ARTIFACT=PASS'

# Runtime overflow must trap rather than silently wrap.
mkdir -p "$work/inline_v2_overflow/classes"
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
  fixtures/async_v1/inline_v2_overflow.atto \
  "$work/inline_v2_overflow/AttoAsyncV1Guest.java"
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
echo 'ATTO_INLINE_V2_CHECKED_OVERFLOW=PASS'
