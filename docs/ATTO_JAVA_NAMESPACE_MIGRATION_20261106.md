# AttoLang Java namespace migration

This change is **stacked on draft PR #2553**, which is itself stacked on PR #2544. It does not rewrite or merge either parent PR.

## Java namespace

All 196 production Java files and 567 Java test files are moved from `src/{main,test}/java/dev/oreslang/` to `src/{main,test}/java/dev/atto/`, with package statements, imports, and fully qualified class names changed to `dev.atto`.

The Java module becomes `dev.atto`; launcher commands, standalone Java probes, shell scripts, and GitHub Actions references follow the new names. Native-image resources are updated to refer to the new module and runtime classes. Compiler-owned core resource `numeric.atto` moves to `dev/atto/core/`; Maven publishes `stdlib/*.atto` at `dev/atto/std/`.

## Deliberate compatibility boundaries

The Maven artifact coordinates remain `dev.oreslang:oreslang-source` for existing consumers. Accordingly, the Maven-specific `META-INF/native-image/dev.oreslang/oreslang/` configuration directory retains that coordinate path; only its contents use the new Java names.

The AttoLang guest source import spellings, `.atto` and legacy `.ores` formats, compiler/CLI identifiers, language registration IDs, and repository names are **not** renamed by this Java package migration.

## Validation

`bash scripts/verify-atto-java-namespace.sh` checks tracked Java paths against declarations, rejects stale Java package references, checks JPMS identity, resource placement and the deliberate Maven-coordinate compatibility. The `atto-java-namespace` workflow checks out the immutable PR head and records its tree before running that static gate.

A successful static gate is not compiler-backed evidence. Before this PR can be marked ready or merged, require Maven compilation/tests, native-image metadata/resource loading, exact-head workflows with **nonzero executed steps**, and independent review. `steps=[]` or `steps=null` is a runner-admission failure, never a Java/guest success.

## JNI binding hardening

The first migration commit moved `dev.oreslang.runtime.NativeCarrierExecutor` without changing C JNI exports. This would cause `UnsatisfiedLinkError` when loading `liboresthread`. The follow-up changes all ten `Java_dev_oreslang_runtime_NativeCarrierExecutor_*` exports to `Java_dev_atto_runtime_NativeCarrierExecutor_*`. `scripts/verify-atto-jni-bindings.sh` compares the declared native methods with the C symbol multiset; `scripts/test-atto-jni-bindings.sh` adds negative mutation fixtures for old package, missing symbol, duplicate symbol, and missing declaration. The exact-head namespace workflow also includes a native C syntax check with the runner JDK's JNI headers. Authored checks are not counted as executed until runner admission succeeds.
