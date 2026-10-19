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

This phase binds a host scalar encoder to a **compiler-generated fingerprint**
AND a separate, closed-world source-compiler receive-type proof. The linked
`OresEvalRootNode.Evaluator.actorReceivesProxyLong(actorTypeName)` checks the
resolved `receive` handler's exact `ActorMail<int>` type AST, rejecting
`ActorMail<string>`, aliased/inferred/unrecognized types, and any
`ActorCodeExecutor` that does not implement the proof (default DENY).

This proof is checked both before binding the initial physical proxy mailbox
and when preparing a replacement compiler generation, before the runtime
attempts checkpoint/physical handoff. A self-consistent message ABI hash is
NOT permission to treat a non-int receive contract as an i64.

The compiler still does NOT generate arbitrary encode/decode implementations
from source `ActorMail<T>` declarations; this opt-in path is a
compiler-*verified* signed-i64-only host bridge, not general source-language
message serialization.
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
  It also stages a real `ActorMail<string>` actor and checks that the
  compiler denies the signed-i64 proxy without consuming a generation pin;
  a real `ActorMail<int>` actor remains admissible. The JUnit fixture is
  committed but not an executed test pass while full source CI is blocked.
- An exact copy of the **Git blob** `442260316ef1f4db313ca0b1001ea44e37b6e6db`
  for `SourceActorScalarWireV1.java` compiled locally using
  **OpenJDK 21**, `javac --release 21 -Xlint:all -Werror`.
  A standalone harness against that exact blob passed **400,000**
  deterministic checks: 100,000 i64 round trips, 100,000 header
  corruptions, 100,000 foreign ABI rejections and 100,000 rejection
  checks for legacy eight-byte payloads in ABI-framed mode.
  The current codec uses allocation-free, full-header digest comparison
  instead of per-dispatch digest/UUID temporary objects. The **new exact
  optimized blob** was recompiled and the same 400,000 checks rerun.
  This is the real standalone codec class, not a full Oreslang runtime,
  compiler, JUnit or GraalVM run.
- **2026-10-19 compiler receive-type hardening:** The source compiler now
  implements `actorReceivesProxyLong()` using the linked actor's resolved
  `receive(ActorMail<int>)` AST declaration, while other code executors
  default-deny. The router checks the compiler proof before binding any
  ABI-bound scalar mailbox and before any candidate physical handoff.
  New product JUnit fixtures explicitly deny `ActorMail<string>` on
  initial proxy creation and on V1-to-V2 migration, and retain a positive
  `ActorMail<int>` case. They are committed but **not executed** on
  the full Graal/Maven actor runtime.
- A separate, source-free **Java 21** type-proof oracle compiled with
  `javac --release 21 -Xlint:all -Werror` and executed
  **200,024/200,024** deterministic and seeded randomized checks.
  These exercise exact allowed `ActorMail<int>` and deny inferred,
  nested, aliased, wrong-arity, and foreign message type spellings.
  This is an independent structural model, not compilation of the
  actual `OresEvalRootNode` code or a product JUnit pass.
- **Release gate:** execute the new JUnit tests and the full actor/runtime
  suite on exact-head JDK25/GraalVM, Native Image, and validated Linux
  runtimes. Source-owned GitHub Actions still fail without executed job steps,
  so no full end-to-end test pass is claimed.
- Cross-organization mirroring of the private source remains prohibited
  while `.github/private-ci-approved-repositories.txt` is empty and
  security issue #962 is unresolved. Runner minutes are not authorization.

Do not merge or enable PRIVATE/UNTRUSTED production hot reload based only
on this scalar compatibility milestone.
