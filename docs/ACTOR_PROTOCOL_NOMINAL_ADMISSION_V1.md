# AttoLang state-indexed actor protocols: nominal compiler admission v1

Status: **DRAFT, source-local declaration preflight only**. No guest runtime
protocol handle, stream continuation, actor-state commit, permission to mint a
reply sidecar, or production full-AOT codegen is implemented by this slice.

## Source model

The compiler recognizes a contiguous root **protocol prelude** (after an
optional flat namespace header). The prelude contains finite states and
`on Message(Payload) -> Successor replies R` or `streams T` transitions.
Normal declarations after the prelude are parsed by the **production Java
Parser** into `Ast.Program`. A protocol after any ordinary declaration, a
nested protocol, and a second protocol after ordinary source are rejected
(`E_PROTOCOL_SCOPE`); protocol declarations are never silently stripped out
of executable bodies.

```atto
define protocol Subscription as
  initial Idle
  state Idle as
    on Connect(Config) -> Active replies Ack
  end
  state Active as
    on Subscribe(Topic) -> Active streams Event
    on Close -> Finished replies Ack
  end
  terminal state Finished
end

define class Config as end
define class Topic as end
define class Ack as end
define class Event as end
define interface EventFeed extends StreamRequest<Event> as end
define class Connect implements UnaryRequest<Ack> as end
define class Subscribe implements EventFeed as end
define class Close implements UnaryRequest<Ack> as end
define actor Client implements Subscription as end
```

**This source is checked and then intentionally rejected** with
`E_UNSUPPORTED_PROTOCOL_LOWERING`. It must not create runnable actor code.

## Compile-time invariants already checked

1. A protocol's state graph is finite and bounded; initial/terminal states,
   transition uniqueness, successor existence, and paths to terminal states
   are checked by `ActorProtocolTypestateV1`.
2. Protocol transitions can only request `UNARY` or `STREAM`. No implicit
   one-way-to-stream conversion is accepted.
3. All message types must resolve to non-generic concrete source-local classes.
   Declared response/item/payload types must be concrete known scalars or
   source-local classes, not `Any`, `void`, aliases, generic message
   declarations or imported unlinked types.
4. A message's nominal mode must arise from actual AST class/interface parent
   type references: `UnaryRequest<R>`, `StreamRequest<T>`, or a transitive
   unambiguous chain. No method return, user string map, or ordinary
   `OneWayMessage` may masquerade as a streaming reply contract.
5. Type-erased markers, unresolved parent declarations, cycles, incompatible
   inherited markers, and duplicate/ambiguous type names fail closed.
6. Protocol actor attachments are derived from parsed actor declarations and
   their actual `implements` references, not incidental source-token text.
7. A state-union send obligation is admitted only when **every possible state**
   permits the same message, response cardinality and response payload type.
   The returned metadata indicates that authority *would* be consumed, but
   does not execute the move or mint an affine capability.

This is a bounded in-file source graph, **not** a package-wide import resolver.
The parser cannot prove named generic specialization, structural payload layout,
actor mailbox admission, or cross-module type conformance here. These remain
explicitly unsupported, rather than returning a false proof.

## Critical future work

- Integrate imported nominal identities and mode markers into the full
  TypeChecker/HIR without permitting user-defined aliases to spoof compiler
  markers; preserve this metadata through optimization, ABI digests and AOT.
- Integrate a CFG-based **affine ProtocolHandle/StreamLease loan model**:
  exactly one owner, no duplicate successor handle, no use-after-`take!`,
  correct union joins, no state loan across `await`/critical callbacks.
- Connect authenticated, generation-fenced actor mailbox admission,
  commit-before-reply, bounded per-direction credits, monotonically ordered
  stream items, exactly one EOF or terminal error, and cancellation/restart
  fencing. Do not confuse request streams with persistent `pipe`/`rpipe`.
- Add client-stream and duplex modes with independent half-close; do not
  equate these with server-streaming or repeated unary request calls.
- Run real guest `.atto` programs and negative compiler fixtures on JVM,
  Native Image/full AOT, LLVM, Wasm, Forge, .NET and further supported targets.
  **No backend can be labeled verified from this parse-only admission.**

## Verification and governance

The focused source-only Java smoke workflow remains separate from actual
Maven/JUnit and guest/AOT evidence. The workflow now requests full JDK25
production compilation and focused JUnit tests, but only **executed CI steps**
on the exact head count as test evidence.

The private-source repository's approved CI destination list is empty.
Cross-organization source transfer is forbidden until its reviewed V3
security policy and live source-main protection requirements are met.

Related: atto-spec #203 (state-indexed protocols), atto-spec #40 (nominal
one-way/unary/stream reply modes), atto-source.java #1708/#1735 (original
stages), atto-source.rs #250 (nominal prototype), and the integration PR #1743.

## Affine CFG oracle — source-only milestone

The Java compiler branch now also contains `ActorProtocolAffineCfgV1`.
It is a **bounded, acyclic, typed-CFG proof model**. The only authorized input
is a complete, typed compiler CFG with authenticated `Init`/branch provenance;
a guest must never be able to construct its own authorization witness.
The preferred static entry point
`verifyNominallyResolved(resolvedDeclarations, cfg)` checks nominal request
modes again before running the affine verifier.

- `Init(place, protocol)` is restricted to the entry block and must correspond
  to a compiler-verified actor spawn; it initializes only the declared initial
  state. The model cannot prove that correspondence itself.
- `Take(from, into)` moves handle authority. `Copy` is always invalid.
  Borrow/EndBorrow enforce exclusive versus read-only loans, and all loans
  must end before any suspend/await. A branch-conditional live handle remains
  an outstanding obligation at `Return`, even when it was moved on another
  path. Handle, lease, loan, edge and operation counts are bounded.
- `Begin(from, lease, message, payload, mode, replyType)` checks the
  protocol's state-union admission and consumes the source handle into an
  exclusively owned request lease. Lease handles can themselves be moved, but
  not duplicated, double-cancelled or used after moving.
- `AwaitUnary(lease, into)` must terminate a block with **four distinct
  labeled successors**: `SUCCESS, ERROR, TIMEOUT, CANCEL`. It retires
  the lease on every edge, returning a successor-state owner only on SUCCESS.
- `AwaitStreamStep(lease, into)` must terminate a block with **five distinct
  labeled successors**: `ITEM, EOF, ERROR, TIMEOUT, CANCEL`. ITEM leaves
  the lease outstanding for another step; successful EOF retires the lease
  and issues a successor owner once; failures retire the lease without a
  successor. Repeated items can be modeled by finite unrolling. Cyclic
  stream pumps and general loops are still intentionally rejected.
- `CancelLease` retires the local lease and never returns a state proof.
  `DisposeTerminal` is legal only in a definitely terminal state.
  `ReturnHandle` transfers a definitely live owner to the caller; the
  caller's return type is a separate compiler obligation.
- Every `Return` requires all loans and leases settled and all possible
  owned handles explicitly consumed/returned. Branch joins reject divergent
  pending lease or loan identities; optional live handles may not be used or
  silently discarded.

**Evidence:** The checker and its smoke suite were compiled and executed
locally with JDK 21, `javac --release 21 -Xlint:all -Werror`, against
isolated substitute implementations of the nominal resolver and protocol
catalog. **38/38 focused model cases passed**. The GitHub blobs for both
production checker and smoke suite were compared byte-for-byte using their
Git blob SHA-1 values. This is NOT a build of the complete Java repository
or real `.atto` guests. A JDK 25 source-only CI job and Maven/JUnit coverage
are committed; their status depends on actual executed Actions job steps.

**Open safety requirements before enabling guest lowering:** the source CFG
extractor, complete exception/defer/cancellation edges, actor spawn provenance,
authenticated unary reply and stream item/EOF branch provenance, state lease
identity and generation fences, cycle/loop proof, and actual native/AOT backend
lowering. Neither an arbitrary `Program` object nor this oracle's `Proof`
is a runtime capability. No fallback interpreter, unchecked host callback or
fabricated stream completion is authorized.

## Source-derived CFG slice (22 November 2026)

`ActorProtocolSourceCfgV1.verifySource` is a source-local, **production
lexer/Parser AST** → `ActorProtocolAffineCfgV1` adapter. It parses the same
bounded protocol prelude and resolves request marker inheritance from real
class/interface AST declarations before extracting a *single* specified
function body. Unlike manually authored CFG tests, the accepted operations
must be present in the source AST.

A trusted entry-map supplies the actor-spawn handle owner identity:
`Map<parameterName, protocolName>`. This is a **model input contract**, not
proof that any actual guest spawn occurred; no guest can construct this map
to authorize code generation. The adapter requires each trusted parameter to
have an exact `ProtocolHandle<Protocol>` type, rejects untrusted sibling
`ProtocolHandle` parameters and source declarations that shadow compiler
model types (`ProtocolHandle`, `UnaryLease`, `StreamLease`). Imports are
not admitted until package-aware nominal linkage supplies verified identity.

The first source subset maps:

- `const ProtocolHandle<P> dst = take! src` → affine `Take`
- `const UnaryLease<R>/StreamLease<T> lease = owner.request(message)` →
  `Begin`, with `message` an explicitly typed parameter and its mode
  checked from real nominal class/interface inheritance
- `lease.cancel()` → `CancelLease`
- `return handle` / `return;` → explicit ownership transfer / terminal
  exit, with definite-live and declared return-type checks
- Pure Boolean `if/else` conditions and both branches → bounded CFG
  split/join, including rejected divergent live loans/leases

Other statements and expressions—including `await`, `match`, loops, defer,
exceptions, arbitrary method calls, inferred aliases, dynamic request
dispatch, task capture and implicit function exits—are **rejected**, not
silently dropped. Callable generic parameters and declared throw/raise/panic
effects are rejected in this restricted projection.

The source adapter and its JUnit tests are committed to draft
`atto-source.java#1743`; the tests include real parsed .atto snippets for
take!, request/cancel, use-after-move, live-lease escape, forged authority,
simple if/else, unbalanced joins, deferred effects and unsupported awaits.
These new JUnit tests have **no verified executed CI receipt yet**.

**Remaining blocker to full implementation:** even a successful
`verifySource` result is *not* a guest codegen permit. The compiler must
link it to the same exact typed-HIR/MIR nodes actually emitted, trace
every exceptional/cancellation branch, prove actor spawn provenance and
generation-fenced leases, and authenticate EOF/commit at the runtime.
The canonical Rust mirror is staged in `atto-borrow-checker.rs#50`, but
both sides must execute identical conformance cases before parity is claimed.
