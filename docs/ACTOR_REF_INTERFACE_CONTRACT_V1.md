# AttoLang ActorRef and actor-interface contract v1

**Status (2026-11-10):** design contract plus **Java compiler type-admission and capability-forgery guards** on this branch. This document is not evidence
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

## November 10 security hardening audit

The initial actor-target checker left two paths to accidental/forged authority
that now fail closed in source type checking:

1. `new ActorRef<Cart>()` and `new CartHandle()` (transparent aliases)
   previously fell through the general non-class `new` expression path and
   could acquire a static actor-reference type without a runtime-minted handle.
   Compiler admission now rejects `new` for any resolved actor-ref-bearing type.
2. The permissive `Unknown`/`Generic` assignability rule could otherwise let
   a value with no proven minted handle flow into `ActorRef<Cart>`, including
   under `Option`, `Result`, lists, records and function results. The shared
   `Types.isAssignable` relation and source checker now guard these shapes.
   Positive typed handles and ordinary non-capability generic inference remain
   available.

The nominal `ActorRef` name is reserved against class/interface/type-alias
shadowing. This does **not** certify that all identity-bearing actor-local values
are unescapable; supervisor CONTROL, `self` borrowing, source-level actor
interfaces, and RPC dispatch need separate compiler/runtime verification.

## P0/P1 hardening, November 10

**Java host/runtime implementation (this branch):** `ActorRuntime.ActorMailboxRef<M>`
is a communication-only wrapper obtained via `ActorRef<M>.messageRef()`.
It deliberately exports message send, nonblocking send, drain, readiness and
completion observation, plus immutable identity metadata. It exports no
`stop`, `cancel`, `kill`, `forceCancel`, `gracefulStop`, raw
`ActorRef` or supervisor CONTROL acquisition. It is a **host capability
substrate**, not a guest-language `ActorRef<P>` RPC implementation.
`ActorRef` retains its legacy lifecycle API for compatibility; full
source-level authority attenuation is **not complete**.

**Runtime identity hardening:** existing actor IDs alone are insufficient to
grant authority. On any live slot, stale or host-fabricated references fail
identity validation before send, sendAsync, drain, stop, cancel, graceful
stop, CONTROL handle acquisition, readiness, completion, parent/child
inspection and blocking termination observation. Actor reference identity is
checked against the actual runtime-owned ActorCell reference, even if the
caller supplies the same ID, kind and generation. An absent/finalized actor
keeps its established terminal-operation behavior rather than gaining
authority over future incarnations. Message-view operations delegate to
the same checked actor runtime; no second independent mailbox/scheduler
is created.

**Host security tests:** `ActorMailboxRefSecurityTest` proves the narrow
method surface and the real mail-delivery path, and uses privileged Java
reflection to construct a same-ID/same-generation fake handle to assert
rejection. Reflection is a deliberately adversarial **host-only model**;
the guest does not gain reflection rights. It is not a native AOT or
compiler-backed AttoLang receipt.

**Dedicated CI:** `.github/workflows/actorref-contract-exact-head.yml`
checks the PR SHA, Git tree, clean worktree and each fixture blob, runs
`ActorRefParameterContractTest`,
`types.ActorRefCapabilityAssignabilityTest`,
`ActorMailboxRefSecurityTest`, `ActorMailTypeContractTest`, and
`SourceActorSpawnExecutionTest` under GraalVM JDK 25, then requires
nonempty, unskipped Surefire case results for every selected suite.
A workflow definition alone is **NOT EXECUTED**. Source-org checks with
`steps=[]` remain runner-admission failures.

**Still pending:** a guest-exported restricted reference type, static actor
interface proof, segregated CALL/REPLY and supervisor CONTROL, virtual
activation identities, five-backend guest parity, and independent review.
Do not promote this draft until exact-head guest tests execute.

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

This branch adds eighteen `tests/fixtures/actor-ref-contract-v1/*.atto` files:

| Fixture | Expected |
| --- | --- |
| `accept-typed-function.atto` | Compiler accept, guest sends through mailbox, prints `actorref-ok` |
| `reject-primitive-target.atto` | Type rejection |
| `reject-class-target.atto` | Type rejection |
| `reject-interface-target.atto` | Fail closed until actor-interface RPC is lowered |
| `reject-unbound-generic-target.atto` | Unresolved actor type argument rejection |
| `accept-alias-typed-function.atto` | Runtime-minted ActorRef passes through a transparent source alias |
| `reject-new-actor-ref.atto` | A new-expression must not mint an actor capability |
| `reject-alias-new-actor-ref.atto` | Alias of ActorRef must not bypass the constructor gate |
| `reject-generic-capability-coercion.atto` | Unbounded generic cannot impersonate ActorRef |
| `reject-nested-generic-capability-coercion.atto` | Option of generic cannot impersonate Option of ActorRef |
| `reject-ref-shadow-class.atto` | Built-in name cannot be replaced by class |
| `reject-ref-shadow-interface.atto` | Built-in name cannot be replaced by interface |
| `reject-ref-shadow-alias.atto` | Built-in name cannot be replaced by type alias |
| `reject-missing-type-argument.atto` | Generic arity rejection |
| `reject-multiple-type-arguments.atto` | Generic arity rejection |
| `reject-direct-actor-method.atto` | No direct `ActorRef` method call |
| `reject-self-as-reference.atto` | Actor-local self is not a reference |
| `reject-other-actor-type.atto` | Nominal mismatch rejection |

Two JUnit suites target these cases: `ActorRefParameterContractTest` includes
real guest execution, and `types.ActorRefCapabilityAssignabilityTest` tests
the shared type relation (a host-only unit test, never a guest receipt).

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
