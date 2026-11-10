# Review-only proposal: Java typed macros #2049 funded private native CI

Status: **CANDIDATE ONLY — no authorization to transfer private source**.
Owner: `ores-truffle-oreslang/oreslang-source.java`.
Target compiler PR: #2049, reviewed candidate head
`c97fd5e093059ffcc56ac4fa50b4c1d74fe1c9cc`.

## Candidate destination (must be independently approved)

- Private repository: `scintilla-run/scintilla-e2e`.
- Immutable GitHub repository ID at time of proposal: `1346629495`.
- Other private repositories and prior tested Cranelift trees are not automatic authorization for this source.
- Source repository `main` as checked November 2, 2026:
  `181ee5afd5d60b7ad73d80b6e18973923c83260a`,
  **protected=false**.
- `.github/private-ci-approved-repositories.txt`: no approved destination.
- Source PR #2049: requested independent `@ORESoftware` reviewer;
  no independent review was present during the last audit.

## Explicit gates before any source-bearing transfer

1. Source owner must enable **live, enforced protected `main`**, meeting
   `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md` and V3 requirements.
   A PR review or source-free runner is not a substitute for protection.
2. Submit an independent security review binding exact source commit/tree,
   candidate destination numeric ID, destination privacy, GitHub workflow
   Git blob SHA, exact carrier main commit/root tree, and test-plan blob SHA.
3. `@ORESoftware` must independently approve the resulting governance
   policy and exact carrier test-plan changes. Any destination ID, workflow
   or tree drift invalidates the approval.
4. Only after the policy is active and the V3 preflight passes, mirror the
   **exact PR head Git tree** into a temporary branch in the reviewed
   private destination using GitHub Git-data APIs, verifying every Git blob
   and the complete subtree SHA. The destination workflow must check out
   its local branch with `persist-credentials: false`. No cross-org
   checkout, PAT or private-source credential in the runner.
5. Execute the exact-head Java/Graal Maven test suite with real allocated
   job steps, including `mvn -B -ntp -Dores.runtime.carriers=native verify`
   and the macro parser/evaluator, ownership/hygiene and incremental cases.
   Record job ID, run attempt, executed steps, source-tree SHA and test
   counts. `steps=null` proves no tests were executed.
6. Native Image of a general Truffle interpreter does **not** prove full
   program-specific AttoLang guest AOT. The release gate additionally
   requires linked guest application code and runtime-JIT-off execution
   for typed macro source examples MA001/MA002, and compiler rejection of
   MA003–MA006/MA008 with no partial artifacts. A supported scalar-only
   backend cannot be credited as full-language parity.

## Current exact candidate snapshot (2026-11-02 20:05 UTC)

- PR #2049 exact head: `c97fd5e093059ffcc56ac4fa50b4c1d74fe1c9cc`.
- Source root tree: `7138e283c1daa49f94741a767041a251aea01cf0`.
- New source change closes the identified unquote AST-identity alias: ordinary name/scalar literal leaves are cloned with both `Ast.copyCheckedMetadata()` and `MacroSyntaxProvenance.copyOrigins()`; composite and compiler-internal syntax now fail closed pending an exhaustive copier.
- Three JUnit regressions were added in `MacroUnquoteIdentityIsolationTest`; none has executed yet. The exact updated head again triggered private source-org workflows with `steps=null` (fast-ci run 41516500420; ci run 41516500427; Native Oreslang CLI run 41516500570; Rust-only run 41516500415). Source-org private Actions admission issue #963 remains open.
- Source-free carrier run [41395135081](https://github.com/scintilla-run/scintilla-e2e/actions/runs/41395135081) completed eight hosted workflow steps and reports 73/73 Node contract assertions passed. This is carrier/contract evidence only, not private Java-source, JUnit, or guest-AOT evidence.
- No source transfer is authorized: source `main` was last observed `protected=false`, the approved-destination file has no authorization row, and independent approval remains outstanding.

## Source-free Java toolchain carrier qualification — 2026-11-02 20:16 UTC

The previously validated private `scintilla-run/scintilla-e2e` carrier (repo ID `1346629495`) now has three separate, source-free hosted validations:

1. **73/73 Node contract tests:** run [41395135081](https://github.com/scintilla-run/scintilla-e2e/actions/runs/41395135081), commit `180de0fb780c1ed2d0658b55969373ace624ae0f`. Ubuntu 24.04 / Node 22.16.0, eight workflow steps all completed, five exact semantic Git blobs verified, 73 passed / 0 failed, including negative receipt/authorization tests.
2. **JDK 25 Maven/JUnit:** run [41517400532](https://github.com/scintilla-run/scintilla-e2e/actions/runs/41517400532), exact carrier commit `71343eb9bce4f6d512bdc4d0593b9bb5ce4f3c50`. Ubuntu 24.04; Eclipse Temurin `25.0.5+8`; Maven 3.8.7; three disposable JUnit 5 tests passed, zero failures/errors/skips; Maven `BUILD SUCCESS`. The workflow creates a temporary Maven project in `RUNNER_TEMP` and does not checkout compiler source.
3. **GraalVM 25 Native Image:** run [41517400534](https://github.com/scintilla-run/scintilla-e2e/actions/runs/41517400534), same exact carrier commit. Oracle GraalVM `25.0.4` / Native Image `25.0.4`; `native-image --no-fallback` built a Linux ELF x86-64 executable, which ran and returned `graalvm-native-aot-ok:42`; marker `GRAALVM25_NATIVE_IMAGE_SOURCE_FREE=PASS`.

These demonstrate alternative hosted capacity, JDK 25 Maven/JUnit execution, and a working Native Image toolchain. They are all **source-free carrier qualification**. They are not the AttoLang compiler's JUnit suite and not AttoLang guest AOT.

No private Java source was checked out or mirrored. Source `main` was last directly observed `protected=false`; the exact destination approval file remains comment-only; the independent @ORESoftware approval remains absent. The source-export block is unchanged. The next approval gate is still enforced live source-main protection plus an independent review binding exact destination ID, source commit/tree, carrier workflow, and test-plan blobs. After that, and only after that, use the exact-tree carrier flow and verify the complete root-tree SHA before running the real JDK25 Maven/JUnit and macro guest-AOT test plan.

## Fresh seven-file semantic reconciliation notes (2026-11-02)

These observations compare the current source `main` blobs with macro PR #2049 head `c97fd5e093059ffcc56ac4fa50b4c1d74fe1c9cc`. They are deliberately not a textual merge:

| File | Current-main behavior to retain | Macro-branch behavior to retain / reconciliation rule |
| --- | --- | --- |
| `Ast.java` | The central `copyCheckedMetadata()` policy transfers `CheckedOptionDepth`, `CheckedClassView` and `CheckedStructuralFieldDomain` only across compatible expression node types. | Macro AST declarations/arguments/invocations and source-site models are branch additions. The new unquote leaf copier calls both `copyCheckedMetadata()` and `MacroSyntaxProvenance.copyOrigins()`; internal generated/captured identities remain fail-closed. |
| `OresCompiler.java` | `compileProtectedScalarI64(String)` is present only on current `main`. Its implementation depends on `ProtectedScalarMirV1`, which is **absent** from the macro branch, as is `ProtectedScalarMirV1Test`. Do not add only the API method; port and review the implementation and tests together. | Preserve `MacroSourceUnitIdentity.attach()`, `planFiniteAotGenerics()` and `lowerFiniteAotHir()`. Final API should expose both independently gated features after their combined dependency graph compiles. |
| `OresEvalRootNode.java` | Current `main` imports and invokes `Utf8StringAbi.encode(text)` at host-string admission/marshalling boundaries. `Utf8StringAbi.java` is **absent** from the macro branch. Port the helper and its exact tests together; do not drop the UTF-8 rejection of unpaired UTF-16 surrogates. | Preserve the macro branch's runtime erasure: executable evaluation must never expand macros or introduce a runtime compiler. |
| `Lexer.java` / `Token.java` | Current `main` has the existing template/string and lexical token behavior but no `MACRO` token. | Keep the macro keyword/token and contextual `quote`/`unquote` handling. The merge must be a union, not replacing the enum or keyword map wholesale. |
| `Parser.java` | Both heads contain the same-scope `declare` forward-type validation (`ForwardType`, root/module scopes, and exact definition checks). Keep it. | The macro branch adds `define macro`, typed parameters/results, declaration and expression invocation nodes, and block-position rejection. Retain all of these alongside the forward-type logic. |
| `LinkedProgramRunner.java` | Current `main` checks capabilities against a separately parsed `programs` map after compilation. | The macro branch uses `checkCompiledCapabilities(build, ...)` over `build.units().values()` and the exact `CompiledUnit.program()`. Preserve this stronger compiled-tree check; do not regress to re-parsing a pre-expansion source tree for capability evidence. |
| `TypeChecker.java` | Keep all current-main ownership/effect, HTTP response, and native runtime admission changes through a three-way merge. | Keep macro expansion before semantic checking, residual macro rejection, and post-annotation revalidation so no macro node or unchecked generated declaration escapes into executable analysis. |

The two missing support files above are hard dependencies for a complete feature union. The next safe action is a reviewed three-way merge against common base `f40b9c3a3b5b4691eb4f4a5d5d0558a112f237aa`, followed by tests covering both sets of invariants. No bulk overwrite, textual-only merge, or PR promotion is appropriate before that compile/test pass.
## Current compiler merge risk

The typed-macro branch and `main` have independently advanced by 229
and 211 commits since common base
`f40b9c3a3b5b4691eb4f4a5d5d0558a112f237aa`.
Their overlapping edited paths are `Ast.java`, `OresCompiler.java`,
`OresEvalRootNode.java`, `Lexer.java`, `Parser.java`,
`LinkedProgramRunner.java` and `TypeChecker.java`.
Reconcile semantic AST/type changes by review; never overwrite one
side wholesale or merge legacy token-macro code without a single
canonical typed expansion plan.

**This proposal intentionally does NOT add an allowlist row, edit the
carrier contracts, claim approval, or transfer any private Java source.**

## Rebase preservation matrix (review required; no bulk file overwrite)

| Compiler path | Must preserve from current `main` | Must preserve from macro PR #2049 |
| --- | --- | --- |
| `OresCompiler.java` | `compileProtectedScalarI64` and new `ProtectedScalarMirV1` admission | `MacroSourceUnitIdentity.attach`, bounded finite AOT generic/HIR entrypoints |
| `Lexer.java` / `Token.java` | newer lexical and template/string handling; keyword/token parity | canonical `macro` token and `name!(...)` syntax |
| `OresEvalRootNode.java` | newer HTTP egress, `Utf8StringAbi` and native runtime hooks | typed macro erasure and no runtime expansion/fallback |
| `Ast.java` / `Parser.java` | current parser/AST semantics for native scalar and template features | `MacroDecl`, typed macro invocation nodes, compile-time AST hygiene/provenance |
| `TypeChecker.java` / `LinkedProgramRunner.java` | current type/ownership/runtime contracts | phase ordering: expand before semantic checks, enforce no runtime `Syntax<T>` |

The seven exact overlapping source paths require **3-way semantic** review against
merge base `f40b9c3a3b5b4691eb4f4a5d5d0558a112f237aa`. A clean textual
merge alone is insufficient if type AST or native lowering semantics change.
Do not add `compileProtectedScalarI64` to the older macro branch without
also bringing its corresponding implementation and AST dependencies.

Validation must include at least:
- Existing protected scalar MIR and native transport/UTF-8 tests from main;
- Macro parser, typed AST identities, incremental ABI cache, template budget,
  hygiene, ownership, capability leakage and unquote repetition regressions;
- End-to-end JVM/JUnit and program-specific guest Native Image (not interpreter
  Native Image) proof on exact reconciled head.

The existing source branch is left unchanged until this merge can be compiled
under an admitted source-local runner or separately approved private carrier.


## Latest metadata-regression test update — 2026-11-02 20:23 UTC

Java PR #2049 has advanced to exact head `681ad0d6cd8f6af51e7e96cd985b087d7dfb107b`, root tree `0a4459a7a69d7f5a10843482d1254e226f202010`.

`MacroUnquoteIdentityIsolationTest` now has six JUnit cases. Besides the four identity/provenance tests, two tests verify the central checker metadata survives cloning:
- `CheckedClassView` and `CheckedStructuralFieldDomain` remain available on the copied index receiver;
- `CheckedOptionDepth` remains available on the copied call identity.

Exact-head source-org admission still prevented all execution:
- fast-ci run `41518551747`, job `125528323738`, `steps=null`;
- Rust-only run `41518551761`, job `125528323680`, `steps=null`;
- native CLI run `41518551825`, job `125528323937`, `steps=null`;
- CI run `41518551820`, jobs `125528324220`, `125528324674`, `125528324711`, `125528324727`, `125528324736`, each `steps=null`.

The alternate private Scintilla carrier separately passed source-free Node contract (73/73), JDK 25 Maven/JUnit (3/3), and GraalVM 25 Native Image compile+execute. None of these included the AttoLang source. Therefore all six new Java JUnit cases remain NOT_EXECUTED. No source transfer is approved; do not mirror compiler blobs to the carrier. Both PRs remain draft pending live branch protection, independent exact destination/snapshot approval, and actual source-head execution.

## Exact-head follow-up: bounded composite unquote copier (2026-11-02 20:22 UTC)

Java macro PR #2049 has progressed to `c97fd5e093059ffcc56ac4fa50b4c1d74fe1c9cc`, root tree `7138e283c1daa49f94741a767041a251aea01cf0`.

The source fix now performs a bounded recursive clone of a narrow expression allowlist for caller-supplied `Syntax<Expr>`: ordinary identifiers, immutable scalar literals, unary/binary/assignment/conditional expressions, spreads, member/index access, calls, runtime calls, object construction, await, lists, tuples and non-explicit-struct objects. It checks maximum nodes/depth, rejects internal/authority-bearing identifier metadata and unsupported expression types, and copies both central checker metadata and prior macro provenance for every fresh node. No alias-by-reference fallback remains.

`MacroUnquoteIdentityIsolationTest` now contains four cases:
- deep-copy composite binary syntax; assert fresh root/child identities and preserved prior provenance at both levels;
- scalar identity/provenance copy;
- unsupported syntax rejection with no aliasing;
- identity/provenance isolation across independent expansions.

All four are authored and **NOT EXECUTED**. The exact head again hit source-org runner admission failures: Rust-only `41518243860` job `125527240142`; fast-ci `41518243965` job `125527240798`; Native CLI `41518243942` job `125527240862`; CI `41518243905` jobs `125527242689`, `125527243037`, `125527243232`, `125527243245`, `125527243327`. All jobs report `steps=null`.

The alternate private Scintilla carrier has successfully executed source-free Node 73/73, JDK25 Maven/JUnit 3/3, and GraalVM 25 Native Image compile+execute. No Java compiler source was checked out there. Keep export blocked and both PRs draft until main branch protection and exact independent authorization are satisfied, then execute the actual candidate source at the pinned tree.
