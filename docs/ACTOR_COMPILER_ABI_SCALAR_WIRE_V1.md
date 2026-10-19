# Compiler-ABI-bound SHARED actor scalar wire V1

**Status: DRAFT / opt-in trusted-host scalar integration.**
This is an intermediate step toward a compiler-owned typed message ABI, NOT
production support for arbitrary `ActorMail<T>` records, structs, tuples,
enums, IPC, native LLVM messages, or protected workers.

## Runtime contract

`LogicalSourceActorProxyBridgeV1.spawnSharedAbiBoundLong(...)` enables a
separate canonical ABI-framed wire. Existing `spawnShared(...)` continues to
use its eight-byte, host-only signed i64 compatibility format without changing
existing callers.

The new `SourceActorScalarWireV1` has exactly **72 bytes**, all big-endian:

| Offset | Size | Field |
| --- | ---: | --- |
| 0 | 4 | magic `ORM1` |
| 4 | 1 | version `1` |
| 5 | 1 | signed-i64 tag `1` |
| 6 | 2 | reserved flags, zero |
| 8 | 16 | stable logical actor UUID |
| 24 | 8 | positive logical actor incarnation |
| 32 | 32 | compiler-derived message ABI SHA-256 |
| 64 | 8 | signed i64 message value |

`LogicalSourceActorRouterV1.bindProxyMailboxForSupervisor(mailbox, true)`
reads the ABI digest from its linked, compiler-private
`ActorCodeExecutor.actorMessageAbiSha256(actorTypeName)`; callers cannot
supply a digest through the bridge's public admission API. The resulting
immutable `Binding` belongs to the original bound mailbox, physical router,
logical actor UUID and stable incarnation. No guest reference or mutable
message graph is retained in an admitted frame.

Before sending to `ActorRuntime`, the router validates the frame length,
version, tag, flags, UUID, incarnation, and ABI digest. Rejections happen
**before** `beginPhysicalSendAttempt`, so malformed source bytes can never
claim a physical-send attempt or bypass the bounded proxy.

The message's **route epoch and monotonic logical sequence** remain
authoritative in `LogicalActorProxyMailboxV1`, outside the payload bytes.
This is intentional: bounded admission remains available while an upgrade
freezes *dispatch*. Queued immutable old-generation bytes must survive
publication to the new physical actor, provided cross-generation message ABI
validation succeeds. The initial compiler ABI binding therefore stays fixed
across compatible source upgrade; it is not an old-code pointer.

## Scope and security

This phase binds a host scalar encoder to a **compiler-generated fingerprint**.
The compiler does NOT yet generate the encode/decode implementation from
actual source `ActorMail<T>` definitions. It does not statically prove that
an arbitrary actor's declared receive type is signed i64; the opt-in host
bridge is currently for explicitly known signed-i64 Counter fixtures.
The ABI digest is an identity/compatibility check, NOT a signature or MAC.
An adversarial peer with the digest could forge matching bytes; the endpoint
still requires the trusted package-private physical router and owning mailbox.

Future general typed messages require a compiler-issued closed type layout,
canonical field ordering and endianness, recursive bounded serialization,
type-safe construction/destruction, no mutable actor-state capture, ABI
migration receipts, and validation that message payloads match their
declared `ActorMail<T>` cases. The current magic/version intentionally rejects
unknown future encodings instead of guessing.

Only a successful runtime-minted exact physical `PROCESSED` terminal
receipt refunds proxy credits. Timeout, interruption, foreign binding and
negative terminal disposition stay fail-sealed; no automatic replay or
exactly-once external side-effects are claimed.

## Evidence and acceptance

- `SourceActorScalarWireV1Test` is committed with positive i64 edge
  values, malformed length/version/tag/flags, UUID/incarnation and ABI digest
  mismatch tests.
- `LogicalSourceActorProxyBridgeV1Test` exercises genuine source-compiled
  SHARED actor V1/V2 upgrades with queued ABI-bound mail and atomic route
  epochs, plus tampered ABI rejection before physical send.
- An exact copy of the **Git blob** `31f99f4be4531c8c16f3a5a69ead3f7ee37e6832`
  for `SourceActorScalarWireV1.java` compiled locally using
  **OpenJDK 21**, `javac --release 21 -Xlint:all -Werror`.
  A standalone harness against that exact blob passed **400,000**
  deterministic checks: 100,000 i64 round trips, 100,000 header
  corruptions, 100,000 foreign ABI rejections and 100,000 rejection
  checks for legacy eight-byte payloads in ABI-framed mode.
  This is the real standalone codec class, not a full Oreslang runtime,
  compiler, JUnit or GraalVM run.
- **Release gate:** execute the new JUnit tests and the full actor/runtime
  suite on exact-head JDK25/GraalVM, Native Image, and validated Linux
  runtimes. Source-owned GitHub Actions still fail without executed job steps,
  so no full end-to-end test pass is claimed.
- Cross-organization mirroring of the private source remains prohibited
  while `.github/private-ci-approved-repositories.txt` is empty and
  security issue #962 is unresolved. Runner minutes are not authorization.

Do not merge or enable PRIVATE/UNTRUSTED production hot reload based only
on this scalar compatibility milestone.
