# AttoLang ActorRef and actor-interface contract v1

**Status (2026-11-10):** design contract plus a **narrow, implemented Java
type-checker admission change** on this branch. This document is not evidence
of executable actor-interface RPC, `ActorContext<T>`, or virtual-actor guest
lowering. Preserve `.ores` compatibility; new fixtures are `.atto`.

## Canonical public types

- `ActorRef<A>`: **opaque communication capability** referring to an actor.
  `A` must be a compiler-known actor declaration (current executable slice).
  Possession never grants an actor-state reference or direct method dispatch.
- `ActorId<A>`: **proposed** typed logical identity, *not* an executable
  capability; resolving one into a reference requires an authenticated
  runtime admission check. The existing `ActorId` remains unparameterized.
- `ActorContext<A>`: **proposed** actor-affine privileged execution context.
  Only a runtime-originating current turn may use it; it cannot be sent,
  serialized, returned from a handler, or held across teardown.
- `ActorRef<P>` where `P` is an **actor interface/protocol**: **proposed**
  restricted call surface. Such a view is minted only by the actor runtime
  after static implementation, protocol/version, and authority checks.
  Currently **rejected** rather than treated as an ordinary interface or a
  structural object.

Do not introduce a second `ActorType` wrapper for running actors. A type
descriptor/factory, if needed, is separate from an addressable actor reference.

## Current executable source-level slice

```atto
define actor Cart as
  receive(ActorMail<int> mail): void {
    self.end();
    return;
  }
end

pub fnc queue_item(ActorRef<Cart> cart): void {
  cart.send(1);
  return;
}
```

The `ActorRef<Cart>` argument is a nominal mailbox endpoint. Its target must
resolve to a known actual actor class, never a primitive, arbitrary class,
interface, unresolved generic, or unknown declaration. `ActorRef` requires
exactly one explicit type parameter; actor reference argument compatibility is
invariant. Function parameters may receive handles from `spawn`; they do
**not** receive an actor instance or actor heap.

An ordinary `self` inside `Cart` is an actor-local receiver, **not** an
`ActorRef<Cart>`. `foo(self)` must not implicitly manufacture a transport
handle. Future `self.ref<P>()` would require a runtime-minted restricted
capability with explicit authority.

`ActorRef<Cart>` currently has legacy built-ins such as send, stop, kill,
cancel, identity metadata, and lifecycle futures. This PR does **not** claim
these are a least-privilege public interface. Follow-up work must distinguish
ordinary message capability from supervisor CONTROL/lifecycle authority,
preferably through a separate non-guest-forgeable control capability and a
compatible migration path. Do not make a new restricted actor interface expose
those control methods by default.

## Proposed method protocol (not yet admitted)

```atto
define pub actor interface CartReader as
  get_count() -> int
end

define virtual actor Cart implements CartReader as
  // compiler-sealed state + bounded mailbox/activation
  // get_count must be a public exported RPC operation
end

define pub async fnc foo(ActorRef<CartReader> cart) -> int {
  return await cart.get_count()
}
```

This is illustrative language design, **not currently compilable code**.
A conforming compiler must:

1. Maintain distinct nominal actor-interface metadata. Ordinary class
   interfaces/module contracts cannot silently become actor RPC interfaces.
2. At link time, prove each exported operation has an exact signature,
   visibility, request/reply serialization, error effects, and protocol version.
   Never use reflection or duck typing to discover RPC handlers.
3. Lower an actor-interface call to a statically selected, bounded
   mailbox CALL/REPLY transaction, not a direct synchronous method call.
   The actor receives it on its own non-reentrant lane.
4. Enforce ownership of request and result across actor boundaries; no live
   `borrow`, mutable actor-state aliases, closures, or host pointers cross.
5. Enforce least authority: a read interface cannot send arbitrary messages,
   mutate state, kill/stop an actor, or acquire supervisor CONTROL.
   A narrowed reference cannot be widened without an explicit grant.
6. Bind authorization to actor identity, tenant, endpoint generation, protocol
   and activation incarnation, including after restart/hot reload.
7. Use bounded pending CALL slots, deadlines, cancellation, version checks,
   exactly-one terminal completion, and safe quota retirement. Cancellation of
   a waiter does not guarantee that an accepted call never executed.
8. Require a caller to have an async/suspending context before awaiting a
   reply. Never block a carrier or reacquire the target actor through a direct
   reentrant callback.

**Self-call safety:** `await self.ref<P>().operation()` must reject when the
operation requires the same serialized turn to finish. Actor suspension does
not make a non-reentrant wait cycle safe. For an internal read, use an
actor-local method under a correctly scoped read guard, return a snapshot,
then call an ordinary function with the value.

**Critical state:** `critical-read` allows concurrent immutable loans,
`critical-write` requires exclusive state authority; do not suspend with
either loan held. STOP/restart/reclamation must await physical quiescence.
A supervisor never inherits a raw pointer/borrow into the actor's heap.

## Virtual actors

`VirtualActor.get<Cart>(namespace, key)` should eventually return the same
`ActorRef<Cart>` abstraction backed by a stable logical identity and
activation-generation fencing. It must not reveal a raw activation pointer or
promise a live physical instance. It must reject or fail explicit calls when
auth, activation, mailbox capacity, deadline or tenant isolation checks fail.

At the time of this contract, Java source parsing recognizes `define virtual
actor` but type checking intentionally raises
`VIRTUAL_ACTOR_MIR_UNSUPPORTED`. Java's activation registry is **host-only**;
it does not provide guest-source virtual-actor RPC, and there is no verified
five-backend parity.

## Reusable source conformance inputs

This branch adds `tests/fixtures/actor-ref-contract-v1/*.atto`:

| Fixture | Expected |
| --- | --- |
| `accept-typed-function.atto` | Compiler accept, guest sends through mailbox, prints `actorref-ok` |
| `reject-primitive-target.atto` | Type rejection |
| `reject-class-target.atto` | Type rejection |
| `reject-interface-target.atto` | Fail closed until actor-interface RPC is lowered |
| `reject-missing-type-argument.atto` | Generic arity rejection |
| `reject-multiple-type-arguments.atto` | Generic arity rejection |
| `reject-direct-actor-method.atto` | No direct `ActorRef` method call |
| `reject-self-as-reference.atto` | Actor-local self is not a reference |
| `reject-other-actor-type.atto` | Nominal mismatch rejection |

Execution receipt requirements: record exact branch head SHA, fixture blobs,
nonzero actual compiler and guest steps, diagnostics, and output. A host-only
or source-only pass is not guest evidence. `steps=[]`, `steps=null`, or
`runner_id=0` is runner-admission failure, not test success.

## Backend follow-up

- **Java/GraalVM:** finish actor-interface AST/HIR/typing, restricted proxy
  minting, mailbox call/reply lowering, and actor-self wait-cycle rejection.
- **LLVM:** mirror nominal actor-ref target admission and prove the same
  negative fixtures at O0/O2 with native guest execution.
- **.NET NativeAOT, Wasm, Forge/Cranelift:** mirror ABI/protocol manifest and
  negative ownership/capability checks before claiming parity.
- **All:** prove incarnation fencing, actor isolation, non-reentrancy,
  cancellation, quiescence, supervisor authority, and no raw-state escape.

This stage deliberately avoids changing runtime dispatch, supervisor CONTROL,
or virtual-actor activation semantics before the corresponding compiler and
guest tests exist.
