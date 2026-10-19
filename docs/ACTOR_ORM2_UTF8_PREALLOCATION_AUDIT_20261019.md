# ORM2 UTF-8 admission / pre-allocation hardening — October 19, 2026

Status: **DRAFT**, production-code fix committed, **full source actor
integration CI not executed**.

## Finding

`SourceActorTypedWireV2.encodeString` previously bounded the input's UTF-16
code-unit length and allocated an intermediate UTF-8 ByteBuffer of
`min(available, 4 * value.length())` bytes before attempting conversion.
For multibyte Unicode, the UTF-16 length can fit the byte budget while
the UTF-8 representation does not. An oversized message could therefore
allocate a large intermediate buffer before `CharsetEncoder` finally
returned OVERFLOW. The old encoder also allocated and copied a second
intermediate payload byte array before constructing the final immutable
frame. Although the mailbox never admitted the overflowing message,
rejecting it was not allocation-bounded by the actual encoded length.

## Fix

`checkedUtf8Length` now makes a source-String-only pass before any payload
ByteBuffer allocation. It computes the **exact** UTF-8 byte count and
rejects a byte budget overflow as soon as the next scalar would exceed
remaining capacity. It validates UTF-16 surrogate pair structure: lone
high/low surrogates, incorrect ordering and truncated pairs are errors.
Subtract-before-add capacity checks cannot overflow, because configured
capacity is already limited to one MiB.

Only after a successful preflight does the encoder allocate a buffer of
**exactly** that byte count. Java's strict UTF-8 CharsetEncoder independently
confirms correct encoding, and the final position must match the preflight
length. No redundant intermediate byte-array copy is needed; the existing
`frame` method still allocates/copies the exact final wire, preserving
previous immutable-byte ownership and stable canonical ORM2 binary layout.

No changes were made to the message type tags, source compiler proof,
logical actor UUID/incarnation, compiler message-ABI digest, logical
sequence/route epoch ownership, or exact physical `PROCESSED` receipt gate.
This is a bounded-memory encoding hardening, **not** remote authentication,
exactly-once side effects or arbitrary ActorMail<T> serialization.

## Tests committed

`SourceActorTypedWireV2Test.exactUnicodeFrameBoundariesRejectBeforePayloadAllocation`
covers exact-capacity and next-scalar rejection at 1-, 2-, 3- and 4-byte
UTF-8 boundaries, malformed surrogate order/pairs, the absolute 1MiB
frame limit, and a largest-fit multibyte frame. The existing test suite
also exercises ABI/header corruption, bool/int/string matching, UTF-8
decoder rejection, and 20,000 deterministic primitive round trips.

## Tests actually executed (narrow, source-exact)

An isolated OpenJDK 21 harness compiled the **exact production Java
`SourceActorTypedWireV2.java`** from committed GitHub blob
`c8a3ff82ec17c3bd3d42d0fc06d82e0ec29a3209`.
`git hash-object` in the local container matched that exact SHA.
It compiled with `javac --release 21 -Xlint:all -Werror`, using minimal
stub definitions of only `ActorRuntime.ProxyWireScalarKind` and the
immutable `SourceActorScalarWireV1.Binding` type.

- Existing `Orm2ExactCodecCheck`: **400,559 assertions PASS**, including
  100k each of seeded signed integer, Boolean and String round trips and
  canonical header/UTF-8 negative cases.
- New `Orm2QuotaAudit`: **189,553 assertions PASS**, including 65,000
  randomly composed Unicode strings (ASCII, 2/3-byte BMP and 4-byte
  supplementary scalars), exact UTF-8 lengths and below-capacity rejection,
  malformed surrogates, and 1MiB boundary frames.
- **590,112 total local source-exact codec assertions passed**.

These local tests execute the actual codec class, **not** the complete
Oreslang compiler, ActorRuntime, logical router, source-compiled upgrade
JUnit fixtures, Maven/JDK25/GraalVM Native Image, or native LLVM backend.

## CI and protected source policy

Source-org Actions jobs have repeatedly failed with zero executed
job steps; an illustrative fast-ci run is
https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39319416888
with `steps: null` and job logs unavailable (GitHub BlobNotFound).
That is **not** evidence of a failing Java assertion or runner-budget
exhaustion by itself.

The protected `.github/private-ci-approved-repositories.txt`
destination allowlist is still empty. Open security bootstrap issue
https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962
documents missing independently protected source provenance and reviewed
numeric destination identity in the publishing workflow. Alternative
organizations with GitHub Actions minutes **cannot** receive this
private repository's source until those security requirements are met.
No private cross-org source mirror was attempted in this audit.

The next release gate is executing full source-compiled SHARED
integration/JUnit, code/Task/state/route atomic publication and Native
Image tests against an authenticated exact source Git tree on an
authorized runner. Do not merge this PR based only on standalone
codec checks.
