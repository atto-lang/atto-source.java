# Review-only proposal: Java typed macros #2049 funded private native CI

Status: **CANDIDATE ONLY — no authorization to transfer private source**.
Owner: `ores-truffle-oreslang/oreslang-source.java`.
Target compiler PR: #2049, reviewed candidate head
`ae38cdf1e177255475f3102677ff02212bd54be8`.

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
