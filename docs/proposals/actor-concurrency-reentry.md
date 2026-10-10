# RFC: actor transport concurrency and re-entry

Status: staged compiler/runtime implementation, not a claim that parallel HTTP/TCP/WS actors execute yet.

## Definitions

An Oreslang actor is an independently addressable message processor that privately owns its state, receives asynchronous messages, and participates in supervised lifecycle/scheduling. A channel transports data. An ActorStream connects actors and channels without introducing another scheduler.

Serial actors guarantee one *logical* handler at a time across await, select, rt cooperate and source-loop preemption. The existing ActorCell.beginTurn rejects concurrent activeTurns; sourceReceiveSuspended prevents another mailbox receive until the first suspended receive completes. The physical carrier can run unrelated actors while that logical lease remains held.

## Canonical syntax

    define serial http actor CriticalSectionActor as
    end

is the same immutable actor execution configuration as:

    define http actor CriticalSectionActor as
      int requestConcurrency = 1;
      int messageConcurrency = 1;
    end

The following config parses into immutable AST metadata but is NOT currently admitted for source actor execution:

    define http actor ConcurrentActor as
      int requestConcurrency = 3;
      int messageConcurrency = 4;
    end

The contextual modifiers serial, http, tcp, websocket and ws do not globally prohibit those words as identifiers. Configuration values are positive constant integers; duplicate declarations or serial actors with other values are rejected. Transport and concurrency policy must not silently change across inheritance. These fields are VM metadata, not ordinary mutable instance state.

The type checker rejects request/message concurrency greater than 1 until per-request execution and state loans are implemented. Generic spawn of a transport actor is also rejected because generic ActorRef mailbox scheduling is not an HTTP/TCP/WebSocket binding. Source runtime checks independently fail closed for these cases.

## Two-dimensional runtime admission primitive

ActorTransportAdmission(3,4,maxPendingRequests,maxPendingMessagesPerRequest) is a VM-internal component with bounded FIFO request and per-request message waiters. Acquiring one request or message lease returns an OresFuture immediately, with no parked carrier or dedicated per-connection thread. Closing a request stops new message admission, fails queued message waiters and retains its global request slot until its active message leases finish. Cancellation detaches pending waiters. Closing the gate fails outstanding waiters and blocks new admission.

This is a standalone scheduler-free admission controller. It does NOT implement actor handler execution, grant access to shared actor fields, start TCP connections or activate parallel source actors. This limitation is enforced until the runtime can prove owned-state safety.

## Borrow checking and atomicity

Memory safety is not identical to logical transaction atomicity. Borrow checking can rule out overlapping mutable references, but cannot make two separately protected accesses into one atomic read/check/write transaction. A bank withdrawal must maintain a single exclusive actor-state loan across the whole check-and-update, or run on a serial actor. Releasing the loan before an authorization await requires snapshot/version checks and revalidation after resumption.

In a serial actor, await/rt cooperate relinquishes the physical carrier but DOES NOT relinquish the logical actor-state lease. No second handler starts during the suspend. Neither all machine instructions nor all statements become globally atomic; only operations against that actor-owned state are serialized relative to its other mailbox handlers.

For future concurrent actors, a request handler must own separate request-local state or receive an exclusive loan for shared actor fields; message handlers within one connection must similarly partition/guard per-connection state. Mutable loan aliasing is forbidden. The runtime may re-enter at compiler-declared safepoints ONLY when the loan state is compatible; suspending with an unsafe mutable state loan must either hold the logical loan across await (preventing conflicting re-entry) or be rejected at compile time. Never silently unlock a critical section on an implicit yield.

Parallel actor handlers may enter concurrently up to requestConcurrency and messageConcurrency only once the borrow/effect checker, request-local state partitions, actor scheduling and supervisor lifecycle support it. No blanket implicit mutex for whole parallel actors, and no implicit shared-memory access across untrusted or isolated actors; serialize/copy cross-domain transport.

## Required admission and regression gates

1. Bind HTTP/TCP/WebSocket connection handlers and per-request/per-message state regions to actor metadata. Keep the current generic mailbox semantics unchanged.
2. Integrate two-dimensional admission on the existing actor VM scheduler and lifecycle, including cancellation, output ordering, backpressure, restart generation fencing, fail-stop and no orphan requests.
3. Extend the static borrow checker and runtime loan arbitration to reject overlapping mutable actor-state loans and unsafe awaits/re-entries.
4. Prove account withdraw(80) racing withdraw(30) against balance=100 never admits both, even across await and preemption. Demonstrate truly isolated request state can achieve the configured 3x4 overlap.
5. Check bounded waiters, FIFO fairness, per-connection order, cancellation of pending futures and complete slot reclamation.
6. Run exact-head Graal JVM, AOT, hybrid and isolate tests. A zero-step GitHub Actions failure is not an executable test result.

## Relation to pipelines

The functional |> operator calls functions eagerly. Contextual infix pipe will compose ActorStream graphs only after a typed stream port/graph runtime exists. ActorStream does not own a second scheduler. Stage concurrency is the actor execution policy, not an implicit side effect of graph composition.
