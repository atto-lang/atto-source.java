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
