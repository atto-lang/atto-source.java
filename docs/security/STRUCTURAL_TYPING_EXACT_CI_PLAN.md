# Structural typing: exact-source Java/Graal release gate

Status: **review-only execution plan**, not a private-source transfer approval.
Integration sequence: draft Java PRs #1809 → #1828 → #1830.
Security authorization remains tracked by issue #962.

## Required behavior

- `struct{id:int} view`, `view: struct{id:int}`,
  `structural struct{id:int} view`, and `view: structural struct{id:int}`
  are semantically equivalent read-only structural loans.
- The same rule holds for top-level `record{...}` and `{...}`.
- `structural Named value` is required to structurally match a named
  class, interface or trait; without it, nominal rules apply.
- Nested wrapper `Option<struct{...}>` is not itself a structural loan.
- No mutable structural alias, `rt take` into a structural formal,
  unproven borrow provenance, async escape or actor mailbox escape.
- Borrow conflicts must be detected across an entire argument list,
  including mutation/move in the second or later argument.
- Format with a type-first declaration and omit redundant `structural`
  for anonymous inline parameter shapes; reparsing must preserve semantics.

## Source/target admission (must precede private source transfer)

1. Confirm independent code-owner approval of a **dedicated** private
   carrier. Source repo numeric ID: `1400792274`.
2. Verify private/active status and pinned numeric IDs of **both**
   repositories. Require reviewed protected source policy provenance,
   reviewed immutable destination ID and exact allowed PR/test plan.
3. Validate private-source publisher's control plane and exact signed source
   commit/tree against the reviewed policy. Never trust source-controlled PR
   changes to authorise their own transfer.
4. Require the destination's immutable workflow/plan blob SHAs, approved
   fixed base commit, and a scoped one-use authorization.
5. On the destination, require `HEAD:private-oreslang-source` to
   be an actual Git **tree**, not a blob or symlink, and compare its full
   SHA against the authenticated candidate source tree. Use the reviewed,
   fixed-base carrier copy of the source-free Rust
   `verify-private-source-subtree` guard, **not code from the unverified
   nested source**. Run it before compiling any mirrored files. Wrong
   digest, replaced checkout, nested working directory, or changed tree
   fails closed; the expected SHA must be supplied by the independent
   reviewer-pinned source attestation, never read from the carrier itself.
   The verifier additionally rejects missing/symlinked checkout roots,
   staged/unstaged changes, ignored/untracked source files and Git index
   assume-unchanged/skip-worktree flags. Reject a symlink passed as the
   checkout root and any source index entry other than an ordinary 100644 or
   executable 100755 file (including tracked symlinks, gitlinks and nonzero
   index stages). A matching committed tree alone is insufficient to prove
   which bytes a compiler will read. Run the *reviewed carrier-base copy* of
   this verifier before the first compilation or artifact/logging step.
6. Do not print the source code, source files, tokens or binary artifacts
   into public logs. No source-tree transfer to public/internal repos.
7. Recheck authorization during every write batch; treat revocation,
   visibility change, missing metadata, changed workflow/policy digest or
   failed verification as immediate denial.

As of October 21, 2026, `.github/private-ci-approved-repositories.txt`
has no active entries. A successful private alternate-org source-free
JDK25 runner canary at
https://github.com/ores-otel-test/legacy-java-consumer/actions/runs/39658254105
is **capacity evidence only**, not authorization to export private source.

## Minimal source-local execution route

Prefer restoring source-org GitHub Actions admission and running directly in
the private source repository at an immutable, independently recorded commit
and root-tree SHA. This requires **no cross-organization mirror**, carrier
allowlist change, or transfer permission; it does not waive independent review,
branch protection, or an actual running test job. Require nonzero executed
steps and Maven/JUnit/Graal guest evidence; if jobs still have zero steps, keep
the compiler evidence gate red.

For an external carrier, source-free synthetic Rust verifier tests may prove
the guard's behavior without importing any private Oreslang data. The carrier
guard itself must be the independently reviewed, pinned carrier-base version:
its matching copy in the PR source tree is **not** an execution authority.
The synthetic fixture must include a valid nested tree, a symlinked checkout
root, and a committed tracked source symlink; only the valid plain-file tree
may pass. A private source transfer remains prohibited until all #962
authorization conditions are independently satisfied.

## Tests on the exact approved source tree

Before Maven, compile the zero-dependency `tools/ownership-syntax-audit.rs`
with `rustc --edition=2021` and run its embedded tests. Require the
`--strict` whole-repository gate before Maven; it includes src, docs,
contracts, formal models, CI, tooling, and examples. Its standalone Rust
unit and synthetic strict-mode tests passed in private alternate-org CI
https://github.com/ores-otel-test/legacy-java-consumer/actions/runs/39681504483 .
The separate Rust nested-tree admission/verifier (real Git trees, synthetic
altered-source and wrong-root cases) passed in private alternate-org CI
https://github.com/ores-otel-test/legacy-java-consumer/actions/runs/39682065900 .
Dirty, ignored, and untracked synthetic checkout denials passed in
https://github.com/ores-otel-test/legacy-java-consumer/actions/runs/39683906454 ;
assume-unchanged and skip-worktree bypass denials passed in
https://github.com/ores-otel-test/legacy-java-consumer/actions/runs/39684151222 .
These source-free tests prove neither the Oreslang compiler nor transfer approval.

Use the reviewed JDK25/GraalVM runner toolchain. Run a Maven compile followed
by the focused JUnit matrix, then the full Java/Truffle suite:

```sh
set -euo pipefail
mvn -B -ntp -DskipTests package
mvn -B -ntp -Dtest=AnonymousStructParameterOrderTest,ImplicitAnonymousStructuralParameterTest,AnonymousParamAstInvariantTest,ImplicitStructuralCallLoanTest,StructuralBorrowProvenanceTest,StructuralRetainedBorrowForwardingTest,StructuralMutableCapabilityTest,StructuralTakeRejectionTest,AnonymousStructuralRuntimeParityTest,AnonymousStructuralBoundaryTest,CanonicalMutableBorrowParserTest,StructuralKeywordMatrixTest,StructuralTypingHardeningTest,CallArgumentBorrowExclusivityTest,InvocationMutableBorrowAliasTest test
mvn -B -ntp test
```

Specific checks: both declaration orders; redundant keyword; record/struct
and named-interface matching; exact vs readonly width matching; class field
visibility; nested readonly fields; invalid numeric field coercion; malformed
and duplicate declarations; AST-generated params; `rt mut borrow` syntax;
implicit loan reservation against `rt take` and mutable reborrow; safe
concurrent shared reads and release after call; provenance of borrowed
return values; generics, lambda captures, actor/async bans.

Then run existing Graal polyglot guest and Native Image profiles, if their
native toolchain is available. These must execute on the **same authenticated
candidate tree**, not the untrusted PR test runner's mutable HEAD. Re-test the
final merged/restacked candidate tree.

## Evidence and failure conditions

Record repository identity, exact candidate commit and tree SHAs, workflow
and plan blob digests, job IDs, number of actual executed steps, Maven/JUnit
suite counts, Graal guest execution results, and any native profile blockers.
A workflow that reports failure before executing test steps has **zero**
compiler-conformance value. No source-free canary substitutes for a passing
compiler test. Keep all dependent PRs draft until actual exact-head execution
is green and independent security release approval is complete.

## Cross-target gate

After Java/Graal exact-source execution, follow LLVM #813, WASM #106,
JS #30 and .NET #101, using equivalent success/rejection fixtures.
WASM must compile through a pinned real LLVM frontend, not a synthetic
source-free shim. JS must cover Node and browser workers; .NET needs actual
CIL/Native AOT execution. No parity claim from parser acceptance alone.
