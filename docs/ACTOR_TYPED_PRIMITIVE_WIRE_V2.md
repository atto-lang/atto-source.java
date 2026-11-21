# ORM2 typed primitive actor-mail transport — October 19, 2026

**Status: DRAFT, opt-in, trusted-host SHARED-only integration.**
This is the next bounded/compiler-proven transport increment after canonical
ORM1 signed-i64 wire. It does **not** yet provide general
struct/record/tuple/enum/union serialization, remote IPC, authenticated
worker receipts, arbitrary guest message references or LLVM parity.

## Authority chain

1. The linked source actor compiler resolves the exact
   `receive(ActorMail<T>)` declaration from the local AST and implements
   `ActorCodeExecutor.actorProxyWireScalarKind(actorTypeName)`.
   Its return type is an explicit closed enum:
   `ProxyWireScalarKind.{INT, BOOL, STRING}`.
   Only direct `ActorMail<int>`, `ActorMail<bool>`, and
   `ActorMail<string>` are accepted. All unresolved, aliased, inferred,
   generic, arbitrary object or unknown types fail closed. The default
   ActorCodeExecutor implementation returns `Optional.empty()`.
2. `LogicalSourceActorProxyBridgeV1.spawnSharedTypedPrimitive(...)`
   checks bounded frame capacity before spawning a physical actor.
   The authoritative router binds one immutable
   `SourceActorTypedWireV2.Binding` to the compiler-derived message ABI,
   stable logical actor UUID, incarnation and one exact primitive kind.
   An unrelated caller cannot pass a self-declared digest to the public
   bridge API.
3. `admitLong`, `admitBoolean`, and `admitString` are type-specific
   host operations. Wrong-kind messages reject before mailbox admission;
   byte and message quotas remain charged from admission through the exact
   physical `PROCESSED` receipt. Payload bytes are copied by the
   bounded mailbox.
4. The physical router decodes and validates the immutable frame while
   holding its publication lock and **before** reserving a physical send.
   Wrong magic, version, flags, kind, UUID/incarnation, ABI digest, frame
   size, declared payload length, boolean representation or UTF-8 fail
   closed. The existing terminal physical receipt and mailbox completion
   remain the sole refund authority.
5. Candidate hot upgrades require both the same compiler-proven primitive
   kind and the same compiler message ABI fingerprint **before** physical
   freeze/checkpoint handoff. Queued immutable frames intentionally do not
   embed a route epoch: the logical mailbox owns the monotonic route epoch
   and sequences, so compatible queued mail can move to the new code image.

## Canonical ORM2 frame

| Offset | Length | Field |
| --- | ---: | --- |
| 0 | 4 | ASCII magic `ORM2` |
| 4 | 1 | version `2` |
| 5 | 1 | kind tag: int=1, bool=2, string=3 |
| 6 | 2 | zero reserved flags |
| 8 | 16 | logical actor UUID (network byte order) |
| 24 | 8 | positive actor incarnation |
| 32 | 32 | compiler ABI SHA-256 |
| 64 | 4 | signed nonnegative payload length, big-endian |
| 68 | N | canonical typed payload |

All numbers are big-endian. INT is exactly eight signed bytes. BOOL is
exactly one byte with only 0/1 accepted. STRING is strict UTF-8 with no
replacement, truncation or malformed surrogate normalization; empty
strings and valid Unicode are supported. There is no allowance for
trailing bytes, version downgrade or unknown type tags.

The frame has an absolute one-MiB length cap and an additional
per-mailbox configured byte cap. Encoder string capacity is preflighted
using UTF-16 length as a lower bound, then enforced against actual UTF-8
bytes. Java's `CharsetEncoder` OVERFLOW is a rejection, `isError()`
is a malformed-input rejection, and ordinary UNDERFLOW means successful
completion; this final distinction was corrected after exact-source
local tests exposed a `BufferUnderflowException` on valid strings.

The ABI SHA-256 is a compatibility fingerprint **not** a signature/MAC.
No source-data export or authentication across trust boundaries is
claimed, and individual logical mail dispositions remain local.

## Test sources and executed evidence

Committed product JUnit classes:
- `SourceActorTypedWireV2Test`: header-bit corruption matrix, canonical
  int/bool/string representation, strict UTF-8, bad declared lengths,
  invalid logical/ABI identity and 20,000 seeded three-type round trips.
- `SourceActorTypedPrimitiveUpgradeTest`: compiler receive proof for all
  three kinds, real source-compiled SHARED V1/V2 string/bool transitions,
  queued UTF-8 message delivery, stable logical incarnation and epoch,
  wrong-kind admission denial, oversized/malformed string refusal,
  incompatible compiler ABI rejection and tampered-frame denial before
  any physical-send attempt.

**Executed exact standalone codec:** copied
`SourceActorTypedWireV2.java` into a local isolated Java 21 compilation
environment with minimal ActorRuntime enum and identity binding stubs.
The real codec file's `git hash-object` matched the GitHub blob
`c302cdf467e4802033f09586aeab3c301a841a88`.
`javac --release 21 -Xlint:all -Werror` succeeded. The executable
harness passed **400,559 / 400,559** checks: 100,000 seeded long
round trips and byte-length checks, 100,000 bool checks, 100,000 string
checks and string lengths, 544 mutated header-bit denials, Unicode
round trips, malformed UTF-8, invalid bools, wrong type, oversized
strings and malformed length bounds. This verifies the production
codec in isolation **only**. It does not compile or execute the real
Oreslang source compiler, physical router, TaskScope, GraalVM,
Native Image, or source-compiled actor JUnit fixtures.

## CI and confidential source

Source-owned Actions have repeatedly failed with jobs reporting zero
executed steps. The protected
`.github/private-ci-approved-repositories.txt` destination allowlist
is empty and GitHub security issue #962 remains open; an alternate
organization's GHA budget alone is not authority to mirror private
source. No cross-organization private source copy was made.

Before merging, execute exact-head JDK25 Maven/JUnit against the real
compiler, actors, receipt protocol and upgrade tests; then full Graal
Native Image and cancellation/recovery suites on an authorized carrier.
Use a protected, independently reviewed CI policy and verified source
tree/destination identity before any private mirror.
