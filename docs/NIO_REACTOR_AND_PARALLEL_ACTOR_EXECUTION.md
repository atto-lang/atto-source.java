# VM-shared NIO and actor physical-carrier policy (Java/GraalVM)

Status: **staged implementation on current Java main**. This document describes
the behavior in this branch; it does not claim production readiness or enable
multi-turn actor execution.

LLVM companion work landed in `oreslang-source.llvm` PR #880. Java and LLVM
share the observable goals—nonblocking readiness, bounded carrier admission,
generation/lifecycle fences, and fail-closed actor parallelism—but use different
runtime mechanisms.

## 1. VM-shared nonblocking TCP

The guest-facing `network.listen_nonblocking("127.0.0.1", ...)` path creates a
`VmNioTcpListener`. Listeners own sockets, **not selector threads**. All
listeners in one `OresVM` share its `NioReadinessGroup`.

Current source-level constraints are deliberately conservative:

- listener creation, outbound connect and connection authority are supervisor/host-owned;
- only loopback listen is admitted by this staged API;
- outbound connect accepts canonical numeric IPv4 only; hostname/DNS resolution
  fails closed until a separate async DNS provider exists;
- accepted/outbound connections are not transferable into actor state yet;
- one kernel operation owns a channel lease at a time;
- connect/accept/read/write/close settle through `OresFuture`;
- connect uses nonblocking `SocketChannel.connect` + `OP_CONNECT` +
  `finishConnect`; reads/writes are bounded and nonblocking;
- cancellation races readiness/kernel claims/close through explicit ownership
  retirement;
- listener and connection operations are context-owner checked.

There is no per-listener compatibility reactor in this branch. The canonical
guest path is the VM-shared readiness group.

## 2. Thread and carrier topology

Current Java `main` deliberately **prestarts the CONTROL pool** during
`OresVM` startup. This branch preserves that invariant. Therefore the valid
cold-start claim is:

- CONTROL: prestarted according to current VM policy;
- NIO readiness reactors: zero until the first accepted watch;
- standalone NIO completion workers: zero until a completion is runnable;
- actor JVM workers: demand-driven;
- actor native carriers: retain current-main native startup behavior.

The first accepted NIO watch starts reactor #0. Multiple idle/pending sockets do
not imply one thread per socket.

A full `OresContext` binds readiness completion delivery to
`ActorRuntime.executeHostIoCompletion()`. A selector never executes guest code
or arbitrary Future subscribers. If actor carriers are cold, one short-lived
off-selector bootstrap helper may submit the first runnable completion. The
completion itself executes on actor-carrier capacity. Once a carrier is warm,
selector-originated delivery uses a verified no-bootstrap/no-growth admission
path.

For Java carrier lanes, `AdaptiveCarrierExecutor` supplies the no-growth
contract. For native lanes, `NativeCarrierExecutor.executeWithoutBootstrap()`
uses a shutdown/enqueue admission fence and requires an already-started carrier.
Unknown executor implementations are rejected from selector-originated
delivery rather than trusted speculatively.

## 3. Optional VM-wide physical carrier budget

`-Dores.runtime.carriers.global.max=N` accepts `N=5..256`.

This property is enforced only when both of these are explicit:

```
-Dores.runtime.carriers=java
-Dores.actor.affinity=none
```

Any other topology fails closed instead of claiming a physical limit that the
runtime cannot prove.

The permit coordinator protects one reserve for each execution domain:

1. CONTROL
2. PRIVATE actors
3. SHARED actors
4. UNTRUSTED actors
5. LIFECYCLE/bootstrap host work

The conservative preferred partition sums exactly to `N`. JVM non-affine
actor domains may borrow otherwise-idle permits, but borrowing cannot consume
another domain's final reserve. The short-lived NIO cold-start helper also
acquires from the LIFECYCLE domain, so it cannot transiently escape the
advertised ceiling.

Kernel readiness reactor threads are not actor/CONTROL execution carriers and
remain outside this permit count. Explicit user-owned `OresScheduler(N)`
instances are likewise separate resources.

When the global property is absent, existing mainline per-domain behavior is
preserved.

## 4. Strict NIO policy

`ores.runtime.io.mode=nio-only` is fail-closed.

In that mode:

- `AsyncNativeIo` may not hide blocking host I/O behind another thread;
- legacy JDK `HttpServer` listen is rejected;
- the staged nonblocking TCP path remains available where capability and owner
  checks admit it.

The strict integration branch does not expose a guest compatibility mode for
legacy blocking FS/network/HTTP/stdio transport. Unsupported operations fail
closed. Virtual-thread or worker offload is not equivalent to NIO-only semantics.

## 5. Actor physical-turn tiers

The language-owned vocabulary is:

```ores
define enum MaxActorThreads as
    LOW int = 1
    MEDIUM int = 2
    HIGH int = 3
end
```

and:

```ores
@ActorMaxThreads(MaxActorThreads.LOW)
define actor Worker as
end
```

`LOW` is the only executable source setting in this branch. `MEDIUM` and
`HIGH` are parsed and then rejected before runtime execution.

`ActorPhysicalTurnCredits` is host-side bounded infrastructure only. It does
**not** make `ActorRuntime.ActorCell` safe for simultaneous guest turns.

Before source actors can execute two or three physical turns concurrently, the
runtime/compiler must prove at least:

- independent per-message continuation/execution frames;
- safe actor-state read/write loans and exclusive write admission;
- no mutable aliases crossing messages, Tasks, awaits or captures;
- compatible Truffle/heap access for simultaneous turns;
- cancellation, stop, panic and teardown barriers that quiesce every turn;
- per-turn watchdog/quota accounting;
- generation-fenced completion delivery.

Unsupported parallel settings must reject; they may not silently downgrade or
run unsafely.

## 6. Shutdown and ownership invariants

`OresContext.close()` is an external-host lifecycle operation.

The required order is:

1. fence new listener/connect admission;
2. cancel pending outbound connects and close context-owned outbound connections;
3. close context-owned TCP listeners;
4. stop and drain VM NIO readiness/completion delivery while actor carriers
   remain available;
5. stop async/actor runtime domains;
6. close the VM root/CONTROL domain.

`OresVM.close()` rejects self-join from actor, scheduler, CONTROL or NIO
worker execution. Teardown aggregates NIO/root/CONTROL failures so one failure
cannot skip retirement of another owned execution domain.

Accepted NIO completion records must either settle exactly once or be
explicitly cancelled/failed. A selector shutdown must not strand a Future
behind an actor executor that has already been destroyed.

## 7. Qualification gates

Java merge qualification requires executed exact-head evidence for:

- parser/typechecker fixtures for `MaxActorThreads`;
- strict NIO denial;
- shared-selector connect/accept/read/write/close and cancellation races;
- connect refusal, unresolved-address fail-closed behavior, and post-failure
  descriptor/watch retirement;
- no selector execution of guest callbacks;
- cold handoff followed by no-growth warm-carrier delivery;
- affine and non-affine Java carrier paths;
- global permit reserve/borrowing/retirement invariants;
- context/VM shutdown ordering;
- JDK 25 JVM tests and GraalVM Native Image coverage.

A workflow failure with **zero executed steps is runner/admission evidence, not
a runtime or test failure**.

As of this branch, source-organization Actions have not provided executed
Java exact-head test evidence. Keep the PR draft until an authorized runner
executes the relevant source tree.

## 8. LLVM parity status

LLVM PR #880 merged the native epoll/VM lifecycle side and was qualified by an
executed private exact-tree run. That run exercised GCC/Clang sanitizers, the
full native CTest inventory, repeated C NIO ABI checks, and Java/native
comparison fixtures.

This does not transfer validation to the Java branch. Each backend must retain
its own exact-head evidence while matching the common observable contracts.
