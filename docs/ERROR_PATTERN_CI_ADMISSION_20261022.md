# Error-pattern CI execution and privacy gate — 2026-10-22

## Source and pending execution

- Java compiler/matching hardening: draft [#1909](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1909).
- Java 100-case regression matrix: draft [#1914](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1914).
- Error-guard effect audit and focused CI: draft [#1917](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1917).
- Portable 100-case manifest, independent model and Node tests: **merged** [platform #155](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/155). This merges reference infrastructure, not passing backend compiler evidence.

The canonical source branch runs the pinned GraalVM/JDK25 workflow
`.github/workflows/error-pattern-source-verification.yml`. It selects
`ErrorPatternMatrixTest`, `ErrorPatternGuardPurityTest`,
`PatternMatchingHardeningTest`, `ValueMatchExpressionTest`,
`ValueMatchSuspensionTest`, and `TypedExceptionalEffectAdmissionTest`.
The workflow records the checked-out commit and only uploads Maven report
artifacts when a runner actually executes. No custom GitHub credentials
or cross-org checkout are configured.

## Observed capacity probes

| Private repository | GitHub Actions run | Actual result |
| --- | --- | --- |
| `akrion-sim/akrion-sim-e2e` | [39818928324](https://github.com/akrion-sim/akrion-sim-e2e/actions/runs/39818928324) | Failed with **zero executed steps** |
| `agent-pontifex/agent-pontifex-daemon` | [39819051380](https://github.com/agent-pontifex/agent-pontifex-daemon/actions/runs/39819051380) | Failed with **zero executed steps** |
| `zed-pkg-test/java-app` | Fresh canary branch, no matching run returned | No capacity claim |
| `3fa-app-test/api-contract-e2e` | [39819328909](https://github.com/3fa-app-test/api-contract-e2e/actions/runs/39819328909) | Failed with **zero executed steps** |
| `canonical-cloud-test/mcp-contract-e2e` | [39819345360](https://github.com/canonical-cloud-test/mcp-contract-e2e/actions/runs/39819345360) | **Passed**, 3 completed steps including an actual Ubuntu runner command |

These are runner-admission probes, **not** Oreslang compiler test results.
The successful canary shows one private cross-organization carrier could
execute a trivial workflow at that moment. It does not attest to future
budget, artifact retention, source provenance or compiler parity.

## Private source publication boundary

Both source and candidate carrier repositories were checked as **private**,
unarchived and write-accessible. Git object IDs are content identifiers,
not cross-repository permissions: a destination cannot point its Git tree
at an unimported private source blob. The attempt to transfer private
source blobs across organizations through the connected GitHub route was
**blocked** before any source blobs were written to the carrier. Do not
work around the denial by using plaintext file writes, alternate APIs,
untrusted Actions tokens, a public carrier or an unapproved export path.

**No source mirroring succeeded. No Java, Node, LLVM, Wasm, .NET or JS
compiler/conformance run has been completed by this attempt.** The carrier
has only a self-contained runner canary.

A future permitted cross-org source verification requires an explicitly
authorized private source publication path, a destination allowlist,
an isolated non-default branch, no credentials/secrets in the repo,
an identical exact-source Git **tree SHA** or per-blob SHA proofs for
a declared sparse subset, and a newly successful executable workflow.
Never promote a source catalog or backend parity based on a runner canary,
a manifest check or a test suite merely committed to Git.

The GitHub Actions run [39820079924](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39820079924)
for this branch's focused Java workflow also failed with zero executed steps,
so it is *not* a compiler failure or success.

## October 22 follow-up: whole-match Error guard admission

Java #1917 now conservatively checks the whole matching arm set when the
scrutinee is a nominal Error domain or any arm explicitly matches Error.
Previously, a `match Any` arm like `on _ when sideEffect()` could run
before a later `on Error error` arm without triggering the Error guard
restriction. The statement and value-match checkers now reject this, while
ordinary `Any` matches without an Error arm retain existing callable/async
guard semantics. Three new JUnit tests cover the two bypasses and the
non-Error compatibility case.

Exact source head `f63201972250284d54fa7a7a1b5ee9451655747d`
triggered [focused workflow run 39892515056](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39892515056),
which failed at runner admission with **zero executed job steps**.
This is not proof of passing or failing source tests. Security issue #1501
still requires source-local execution or an independently approved private
destination before any cross-org Oreslang source transfer. Do not move these
drafts out of draft based on reference corpus/model success alone.

## Exact-head workflow correction and reconciliation preflight

The initial `pull_request` workflow checked out GitHub's default synthetic
PR-merge revision while calling itself "exact-head". Commit
`4c356caaf3d57a864a1e9d2a8febf8660c7a55fd` fixes this by:

1. Checking out `${{ github.event.pull_request.head.sha || github.sha }}`
   directly, with persisted checkout credentials disabled.
2. Verifying source-repository identity, the actual HEAD commit against the
   expected event head, and a clean working tree **before** executing tests.
3. Executing the six focused JUnit classes followed by full `mvn verify`.
4. Naming the Surefire artifact with the exact source-head SHA rather than
   GitHub's synthetic pull-request merge SHA.

The ensuing [workflow run 39901913095](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39901913095)
still failed at admission with **0 job steps**, no runner identity, and
no Maven/Surefire evidence. These workflow checks have not executed.

The 2026-10-22 parent-base-to-main comparison found 106 intervening commits.
The only changed Java TypeChecker hunk on `main` adds 17 lines to
`checkTypedSumCombinator` for an `Option.or_else` literal `None` fallback,
rather than the error-pattern proof/guard code. GitHub reports the
#1909 → #1914 → #1917 stack mergeable, but this is not a test or a
guarantee of integrated semantic correctness. Do not rewrite the stack merely
because of the behind-count; reconcile against a current main and retest
when a runner actually becomes available.

`ErrorPatternGuardPurityTest` now contains 17 `@Test` methods, including
a bounded-equality audit for recursive Option/Result/List comparisons,
broad Any subjects, and flow-sensitive boolean guard bindings.
Those tests have been committed but **not executed**.

## October 23 — recursive guard observation hardening (draft #1917)

The Error-domain matching checker now conservatively rejects
`candidate matches Some(...)` / recursive pattern predicates in guards.
The Java evaluator's `patternMatches` recursively descends Option/Result,
tuples and structural matching, so syntactically "pure" is not sufficient
to certify bounded supervisor/error-dispatch execution. The same strict
check applies to procedural and value-producing Error matches. Non-Error
matches retain the existing `matches` guard behavior.

The bounded observation pass also rejects structural record/tuple targets
in `is type` and optional `as?` casts inside Error guards; unresolved
type aliases are rejected until boundedness can be proven. Nominal type
tests remain allowed. The dedicated Error guard regression class contains
**22 committed test methods** (five newly added), but compiler admission,
execution, and runtime parity **are not verified**.

Latest tested-for-admission code/test head
`e52f05ed82a1bf26f5cf96e9a8afaea35dde319b` produced
[workflow run 39902814640](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39902814640),
which has zero recorded job steps and an empty runner name. The workflow
did not run Maven. The later diagnostic-only code revision also does not
establish passing tests.

### Private cross-org carrier authorization result

`.github/private-ci-approved-repositories.txt` on source `main`
still has no active destination. The only checked-in carrier contract
pins `opto-sync-test/go-java-validation` for PR #1482, not #1917.
Authority PR #1724 is an explicit no-op HOLD and destination policy PR
#1538 is an unmerged draft, both requiring independent security review.
Therefore **do not** mirror private #1917 source to another organization
via generic connector writes, temporary branches, or token workarounds.
Source-free runner probes elsewhere may establish scheduling capacity
but not source transfer permission or build-test evidence.

Next permitted path: get source-local GitHub-hosted or approved self-hosted
JDK25/GraalVM execution; alternatively obtain exact reviewed destination
identity, pinned source-free workflow, approved test plan, and enforced
source governance before using the approved publisher. Only mark the
matching stack ready after exact SHA Maven/JUnit and full verification
jobs actually execute and report their results.

## October 24 — semantic transplant onto current Java main

The stacked Java pattern proof PR #1909 is merged into main and the
100-case matrix from #1914 exists in the current main tree. Portable PR #155
is merged, but its 100 backend-confirmation fields remain empty.
This PR's Error-guard additions were reconciled directly against Java main
at 1f3912a772f6aa46aacb8a745f4c3cba1015fb73. The integrated TypeChecker retains main's later unrelated
changes; its error-specific guard block, whole-arm checking, bounded
comparison/type tests and 22 regression methods are retained. This is
source reconciliation only, **not** a Maven test pass. The source-org Actions
and private CI destination authorization remain independent blockers.

## October 24 follow-up — fail-closed equality type admission

Code audit found that `boundedErrorGuardEqualityType` previously returned
`true` for any resolved Type not in a short denylist. In particular,
`List<int>` resolves as `Types.ListType`, not `Named("List")`;
list equality therefore escaped the intended recursive-comparison gate.
String equality can traverse arbitrary-length strings, and decimal/bigint
equality can perform arbitrary-precision conversions. The resolver
currently maps both `bigint` and `int` to `Primitive.INT`.

The checker now *allowlists* bounded equality carriers instead: boolean,
float/complex fixed-size scalars, guest nominal class reference identity,
and designated symbol/actor identity tokens. Resolved `ListType`, strings,
decimals, integer/bigint-erased equality, maps, Option/Result, records,
tuples, functions, unknowns and future Type variants are denied in
Error-domain guards. General non-Error matching retains existing semantics.
The regression class has **28 committed tests**, including new integer,
bigint, string and decimal rejections plus allowed nominal/bool equality.

**Known limit:** Oreslang's current `Primitive.INT` type erases the
machine-integer/bigint distinction. Relational `<`, `<=`, `>`, `>=`
guards still admit integer values for compatibility; full fixed-cost
real-time certification of these operations requires a distinct bigint
type or runtime budget. This change only claims the conservative equality
admission gate, not a hard real-time guarantee for all guard expressions.

These changes have **not** been executed through Maven/JUnit. Source-org
workflow failures before any steps are not test verdicts, and private
cross-org source publication remains blocked pending approved governance.

## October 24 follow-up — string/decimal ordering must not enter Error dispatch

The evaluator uses lexicographic `String.compareTo` for string ordering and
numeric conversions for decimal comparisons. Earlier error-guard admission
checked only equality operands for boundedness, allowing `left < right`
on arbitrary-length strings and decimal values. The Error-domain checker now
checks `<`, `<=`, `>`, `>=` operands against a narrow numeric allowlist:
`Primitive.FLOAT` and provisional `Primitive.INT`. It rejects strings,
decimal, unknown and custom comparator domains. General non-Error matching
retains its existing string-comparison semantics.

The Error guard regression class now includes **33 distinct JUnit tests**
(five new ordering cases), covering string and decimal rejection in statement
and value matches, earlier catch-all guards on `Any`, accepted float ordering,
and non-Error string-ordering compatibility. The 100 portable Error-pattern
fixture manifest remains exactly 55 accepted and 45 rejected cases, with
**zero backend execution confirmations**.

**Remaining precision limitation:** `Primitive.INT` is shared by `int`
and `bigint`, so preserving old `attempts > 0` guards does **not** prove
hard-real-time bounded integer ordering. Tracked in #1994. Any zero-step
Actions failure is an infrastructure admission failure, never a compiler
verdict. No source was moved into an unapproved cross-organization carrier.

## October 24 — optional-cast and mixed-Complex allocation audit

Inspection of `OresEvalRootNode` established that **optional casts allocate**
a new `OptionValue` on the synchronous evaluator path (and also in the
suspendable implementation). Previous error guard syntax validation allowed
`Ast.CastExpr(OPTIONAL)`; nested `(candidate as? Error) is type Option<Error>`
could therefore allocate while satisfying a pure-looking type predicate.
`pureMatchGuardExpr` now rejects **all** casts within Error-domain guards.
Direct nominal `is type` remains admitted; non-Error match semantics are
unchanged.

In addition, evaluator `valueEquals` converts numeric operands to
`Complex` via `asComplex`, which allocates `new Complex` when passed a
`Number`. The new pairwise checker allows homogeneous Complex equality but
rejects mixed Complex/non-Complex (including unions with an uncertain
Complex alternative) for Error-domain guards. Homogeneous bool and class
identity checks remain admitted.

The dedicated JUnit class contains **42 unique @Test methods** (six new
cast/complex regression tests). These tests are committed, not executed.
The portable 100-case matching corpus was independently reconstructed
from the Java test generator and matched the platform manifest **100/100**
for `id`, source, expected result and diagnostic, without executing
any compiler backend.

**Remaining allocation boundary:** `oresTypeMatches` allocates
`LinkedHashSet` ancestry/interface traversal state for nominal class
and interface predicates; unlike syntax purity, per-dispatch allocation-free
matching has **not** been proven. See [#1999](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1999).
Integer/bigint ordering cost remains unproven under #1994.
No private-source cross-org transfer is authorized while #1501 is open
and the protected source allowlist has no approved destination.

## October 24 — receiver-polymorphic identity equality, source-only verification

The error-domain guard classifier already treats `SelfType(bound)` as its
underlying Error class for whole-match purity admission. The bounded-equality
type allowlist, however, rejected every `SelfType` even when `self` was a
guest class reference. Java evaluator `valueEquals` compares guest
`OresObject` instances by reference identity by default. The checker now
unwraps `SelfType` into its resolved bound **only** for this equality
admission, so `self == self` and `self == candidate` inside an Error
class or subclass no longer fail solely due to receiver polymorphism.
Unbounded aggregates and effectful guards remain disallowed.

Two new JUnit tests bring `ErrorPatternGuardPurityTest` to **44 distinct
`@Test` methods**. This is *not* evidence of passing compiler tests.
Focused exact-head Actions [run #40132482962](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40132482962)
failed during job admission with **zero executed steps and no runner**.
Full GraalVM JDK25 / Maven / JUnit execution and the corresponding
Surefire proof remain blocked. The source repository's approved private-CI
destination list is still empty, so **no cross-org private-source mirror**
was created. Independent review authority #1724 remains draft.
