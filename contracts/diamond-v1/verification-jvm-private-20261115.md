# Atto diamond reference JVM verification — 2026-11-15

**Result:** PASS for the **six Java-admitted semantic tests** in
`src/test/java/dev/oreslang/AttoDiamondDispatchParityTest.java`.
**No exact playground source parity or JVM Native Image result is claimed.**

## Exact source selection
- Source repo: `ores-truffle-oreslang/oreslang-source.java` (private)
- Draft PR: [#3119](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/3119)
- PR source head: `a5232f663319e0213ccf0c5ce2970f7116dd3b8e`
- Full upstream Git tree: `2e9ede297c0db8745241ab67ecc8b90acf0488db`
- Selected build/runtime/test Git subtree: `9561c2a8d72a632cef82fd32244067840fd61299`
- **298 of 298** selected Git files verified against original Git blob SHA-1,
  including `pom.xml`, all `src/main/` Java/JNI/native sources, target test,
  and `scripts/build-native-thread.sh`; this is a scoped exact-blob copy,
  **not** a claim of a complete 2,249-file source mirror
- Private carrier: `zed-pkg-test/oreslang-private-pr-validation-20261012`,
  branch `validation/atto-java-diamond-jvm-20261115`,
  commit `20dad6a82ae11e7910a7f67bc385c4281329ca8e`
- [Actual GitHub Actions runner execution 43758701399](https://github.com/zed-pkg-test/oreslang-private-pr-validation-20261012/actions/runs/43758701399): **SUCCESS**

## Executed JDK 25 results

Command: `mvn -B -ntp -Dtest=AttoDiamondDispatchParityTest test`.

- Built actual `target/native/liboresthread.so` carrier.
- **6 JUnit tests passed, 0 failed, 0 errors, 0 skipped**, Maven BUILD SUCCESS.
- Observed real JVM guest execution for shared diamond origin dedup + local
  child declaration, base-typed subclass dynamic virtual call and class-qualified
  nonvirtual static dispatch.
- Verified rejection by actual Java parser/typechecker of unresolved two-parent
  conflicting inherited members and duplicate same-name/same-arity overloads.
- Recorded that Java interface method signatures currently require `fnc`.

## Limitations / next work

The [exact Atto playground example](https://github.com/atto-lang/atto-lang.github.io/pull/130)
is checked into `contracts/diamond-v1/playground-exact.atto`, Git blob
`05814acb37c0335c03294875b870560a2839031d`, but Java does **not yet**
execute its `pub override` spelling and concrete trait-default method body.
The JUnit programs use Java's *existing admitted* equivalent syntax, rather than
silently rewriting the website sample. This run did not create a Graal Native
Image, prove Java no-JIT guest AOT, execute LLVM, or establish full backend parity.

Source GitHub Actions jobs failed before runner allocation (runner ID zero, steps
empty). The alternative private-runner workflow supplied actual execution without
cross-org checkout or user-provided secrets.
