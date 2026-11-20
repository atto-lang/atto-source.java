# Canonical integer XOR operator v1 — Java/JVM candidate

Authority: draft `atto-lang/atto-spec#143`, and migration candidate
`atto-lang/atto-source.rs#411`. This is an **independent draft** based
on Java main, not an amendment of class-field PR #63 or callable PR #64.

## Profiles and guest dispatch

An explicitly selected strict or versioned AttoLang source profile
parses contiguous `^^` between bitwise `&` and bitwise `|`:
`12 & 10 | 2 ^^ 6` parses as `(12 & 10) | (2 ^^ 6)`.
The strict checker demands concrete integer operands and returns integer.
A single infix `^` fails source admission, and the checker independently
rejects a forged strict infix-`^` AST.

Legacy unversioned Ores source continues parsing `^^` at logical-XOR
precedence, requiring boolean operands. `BooleanOps.Xor` remains the
intentional Boolean convenience. No syntactic profile is guessed from
`.atto` filename or imported package text.

The Truffle evaluator handles both source profiles with an eager
type-checked `xorValues` dispatch: two Booleans yield Boolean XOR;
two integral numeric values yield signed-64-bit bitwise XOR.
Mismatched/float operands throw, never coerced to truthiness.
Both synchronous and suspendable evaluation are included.

## Verification and boundaries

`AttoCanonicalIntegerXorParityTest` includes strict front-end and
**real JVM guest execution** through the one-shot prechecked-program
lease, with source bytes and code-unit identity checked by
`OresLanguage`. Positives cover `12 ^^ 10 = 6`, precedence
`12 & 10 | 2 ^^ 6 = 12`, and `^ 12 ^^ 10;` return.
Negatives cover bool `^^`, mixed/float operands, infix single `^`
and separated `^ ^`, plus legacy Boolean guest behavior.
The existing `BooleanIntrinsicsTest` remains in the exact-head gate.

These assertions are **authored, NOT EXECUTED** until a nonzero-step
source-side runner executes Maven/GraalVM 25/JDK25. They are not proof
of native image AOT, cross-backend source identity, isolated tenant VM
capability checks, or a complete formatter/conformance parity audit.

**Separate unresolved syntax:** the old JVM parser still implements
integer `<<`/`>>` as shifts, whereas canonical operator contract
reserves these for verified typed channel/stream I/O; `<<<`/`>>>`
shift migration is separate. Do not mark full operator parity PASS.

Privacy and merge governance: `atto-source.java#65` denies private
source transfer until a reviewed V3 carrier contract is authorized.
Do not export private source to an alternate org without approval;
do not merge without independent review and executed exact-head CI.

## Competing Java implementation reconciliation (2026-11-20)

Overlapping Java [#70](https://github.com/atto-lang/atto-source.java/pull/70)
and this PR both modify the parser, typechecker, and suspendable Truffle binary
evaluation. This branch is the proposed integration target because it retains
the explicit historical Boolean-`^^` profile, while strict AttoLang requires
integer-`^^`. A final compatibility review must confirm the legacy behavior
is still authorized by the canonical spec and migration policy; no broad
unversioned-language support is claimed here.

Ported the unique #70 continuation safety fix: guest binary operator
evaluation is inside a narrow catch, while the downstream continuation is
invoked after the catch. Otherwise, if a continuation throws after observing
the result, a broad catch would invoke the same continuation again with
that failure. The original `^^`-specific dispatch already used this separation;
the generic binary path now does too.

Added `canonicalIntegerXorRhsFailureExecutesEffectsOnlyOnce` to the strict
guest test and made the exact-head CI gate require its JUnit receipt. This
tests failed RHS side effects and no result publication; the generic
continuation-once proof still requires additional direct callback fault
injection and complete JVM/GraalVM guest execution.

Do **not** merge #70 or #72 independently without reconciling the overlapping
parser/typechecker/runtime edits, preserving unique tests and resolving the
historical profile policy. #70's workflow also includes a Python-based XML
checker, which violates the organization's no-Python tooling policy; do not
copy that workflow into AttoLang.

**Current verification:** these additions are authored and GitHub-blob
committed, not material exact-head JDK25/GraalVM/JUnit or Native Image
execution. No cross-org private-source transfer was made. Source CI remains
governed by security issue #65 and the approved-destination policy.

## Consolidation regression coverage (2026-11-20)

Copied the distinct strict-profile regression *intent* from competing Java #70 into this canonical #72 test suite, rather than porting #70's global legacy behavior change or its Python-based workflow. New exact-source strict Truffle guest tests cover signed-i64 negative XOR, bitwise precedence and left-associative chains, eager left-to-right successful operand evaluation exactly once, and preservation of short-circuit `&&`/`||` behavior. Existing #72 tests already cover failed-RHS exactly-once, strict typing, single-caret return/rejection and historical Boolean XOR admission. The ported tests use `canonicalGuest` with the strict prechecked-AST lease, not the legacy default parser. None has executed on the exact current GitHub head yet; do not claim JVM/Native Image parity. Java #70 remains a competing draft until independent review can verify disposition and CI.
