# Review-only proposal: Java typed macros #2049 funded private native CI

Status: **CANDIDATE ONLY — no authorization to transfer private source**.
Owner: `ores-truffle-oreslang/oreslang-source.java`.
Target compiler PR: #2049, reviewed candidate head
`d2d0ba9b4ddb14167e6e7bbdd6fd3904419fa2b7`.

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

- PR #2049 exact head: `de6fbd8eaa2aac11f6059b0da9b2abc2682961b0`.
- Source root tree: `812c9b9610dd7a99eadfac58c5ad864a3022b629`.
- New source change closes the identified unquote AST-identity alias: ordinary name/scalar literal leaves are cloned with both `Ast.copyCheckedMetadata()` and `MacroSyntaxProvenance.copyOrigins()`; composite and compiler-internal syntax now fail closed pending an exhaustive copier.
- Three JUnit regressions were added in `MacroUnquoteIdentityIsolationTest`; none has executed yet. The exact updated head again triggered private source-org workflows with `steps=null` (fast-ci run 41516500420; ci run 41516500427; Native Oreslang CLI run 41516500570; Rust-only run 41516500415). Source-org private Actions admission issue #963 remains open.
- Source-free carrier run [41395135081](https://github.com/scintilla-run/scintilla-e2e/actions/runs/41395135081) completed eight hosted workflow steps and reports 73/73 Node contract assertions passed. This is carrier/contract evidence only, not private Java-source, JUnit, or guest-AOT evidence.
- No source transfer is authorized: source `main` was last observed `protected=false`, the approved-destination file has no authorization row, and independent approval remains outstanding.

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
