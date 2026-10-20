# Actor / supervisor call–reply correlation without per-call UUIDs (proposal)

Status: **design contract only**, not an implementation or compiler syntax guarantee.
Date: 2026-10-20.

## Relation to existing Oreslang contracts

- Java draft [#456](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/456) already provides an experimental VM-owned `ActorRef.request(In): Future<Out>` path invoking a sealed private `fnc run(In): Out` actor handler. This proposal **does not replace** that API or reopen direct public actor method calls.
- [#458](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/458) tracks remaining hardening of that runtime.
- Common-source [OTP RFC #65](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/65) and Java [#1205](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1205) describe GenServer call/cast/reply. Treat its `call_async` as a higher-level library behavior over the *same runtime correlation substrate*.
- A parent supervisor's reserved CONTROL traffic (panic/raise/throw and lifecycle) must **never** be used as an application request/reply transport. A separately authorized supervisor application DATA endpoint, or a supervisor-owned service actor, handles child-originated application calls.
- This is **not** an assertion that supervisor-to-child, child-to-supervisor, or cross-backend call/reply is fully implemented.

## Decision

Use a runtime-owned one-shot **ReplyPort<R>** (a completion capability), not a guest-visible UUID.

1. **Same trusted VM/runtime domain:** the request envelope carries a reference to a runtime-private completion cell. The callee completes the cell through its return path or its sealed reply operation. There is **no request ID generation, hash table, or correlation lookup** on this fast path. The cell and its lifetime remain in runtime-owned memory, never in guest actor heaps or message fields.
2. **Across isolation, process, IPC, or network boundaries:** a pointer or VM object cannot cross. The origin owns a bounded **generational slot table** of pending completions; transport carries an opaque route/locator with `origin endpoint + incarnation + slot index + slot generation`. Lookup at the origin is indexed O(1), not a UUID hash lookup. Security and type authority are checked separately; the small locator alone is **not** a cryptographic bearer capability.
3. **Every path:** typed request/reply pairing, once-only settlement, bounded admission, restart/upgrade fencing, cancellation/timeout, and actor scheduling discipline are mandatory. A response is queued back to the **originating actor's continuation lane** (or supervisor scheduler), never used to synchronously invoke guest callback code on the responder's thread.

## Suggested public surface (illustrative only)

Existing experimental unary actor surface should remain:

```ores
define actor Pricing as
  state { /* persistent guest data only */ }

  fnc run(QuoteRequest request): QuoteResponse {
    // Access persistent actor state only through verified critical state-loan API.
    return compute_quote(request);
  }
end

// Supervisor or another actor; both hold an ActorRef<Pricing>.
val response = await pricing_ref.request(QuoteRequest{ /* ... */ });
```

For the opposite direction (child calling parent supervisor), either:
- publish a **separate supervisor-owned application service ActorRef** with its own private unary handler and grant that ref explicitly to authorized children; this works with the existing `ActorRef.request` design without widening CONTROL; or
- later introduce a **capability-scoped `SupervisorAppRef<Protocol>.request`** pointing to the supervisor's separately queued DATA handler. This is future syntax/API, not executable Oreslang today.

An actor's `state { ... }` remains its *single guest-owned persistent state field*. The pending-call registry / completion cell is **runtime-owned**, not a second guest actor field. No `actor.run()` direct dispatch, untyped callback invocation, or public guest handler is permitted.

For multiple request variants with distinct response types, define a compiler-checked protocol mapping `RequestVariant -> ResponseType` (or use separate typed unary service endpoints until tagged protocols are supported). Do **not** assume a generic `ResponseUnion` makes an arbitrary response applicable to any request. Preserve the current compiler's prohibition on mixing private `run` and `receive` on one actor until an admitted tagged protocol exists.

## Runtime-private structures (conceptual, not guest-constructible)

```text
CallEnvelope<Req, Res> {
    request: owned/sendable Req
    expected_response_type: runtime nominal/schema token
    reply: LocalReplyPort<Res> | RemoteReplyLocator
    caller: ActorOrSupervisorEndpoint + incarnation + route_epoch
    callee: ActorOrSupervisorEndpoint + incarnation + route_epoch
    deadline: optional monotonic deadline
}

LocalReplyPort<Res> {
    completion_cell: runtime-owned one-shot cell // never serialized
}

RemoteReplyLocator {
    origin_endpoint_id
    origin_incarnation
    origin_route_epoch
    slot_index: u32
    slot_generation: u32
    // transport proves peer identity and may include a keyed authentication tag
}

PendingSlot<Res> {
    generation: u32
    state: RESERVED | ENQUEUED | COMPLETED | CANCELLED | TIMED_OUT | FAILED
    future_or_continuation
    expected_response_schema
    expected_responder_endpoint_and_incarnation
    deadline, cancellation, and accounting metadata
}
```

**Slot key:** `(slot_index << 32) | slot_generation` makes a compact 64-bit caller-local handle. This is not globally unique: it is always scoped to an **origin endpoint incarnation**. Slots may be reused only after settlement with generation incremented. On generation exhaustion, retire the slot rather than wrap into an ABA alias. Actor **incarnation** and hot-upgrade **route epoch** are separate concepts: an incompatible upgrade must be fenced or explicitly drained, without assuming every successful code revision is a crash/restart (see [#1343](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1343)).

Use a bounded segmented vector/slab plus freelist, not an unbounded hash map or permanent tombstone map. No global lock or global sequence generator in the hot path. Registry mutation happens under the origin's scheduler/actor lease or a narrowly synchronized runtime primitive. Use proven safe reclamation/reference ownership for direct local cells; do not let a cancelled call leave a dangling pointer in an enqueued envelope.

## Exact call lifecycle

1. Authorize sender/callee and request protocol; reserve caller pending budget, reply slot/cell, timeout and typed Future **before publication**. Acquire destination admission capacity and retain ownership/copy rules atomically or roll back without losing source ownership.
2. Publish the envelope to a bounded destination DATA mailbox. A failed publication removes the pending entry and completes the Future with a typed admission error.
3. Callee processes the request on its one actor-wide execution lease. For a unary private `run(Req): Res`, a successful **return value** is the response; guest source does not receive or manufacture the reply port. Higher-level GenServer `reply` may consume a runtime-issued one-shot authority.
4. The responder runtime validates sendability, result type/schema, budget, origin and destination identities and epochs. Completion transitions the pending cell **once** with a CAS/lease-serialized state change; duplicate/wrong/late replies fail closed without settling a different call.
5. Schedule the origin's continuation on its own lane; never run an Oreslang callback inline in the responder, network reactor, or another actor's thread (**Zalgo prevention**). `await` yields a carrier, not necessarily an actor message lifetime or a serial admission slot.
6. On cancellation/deadline/termination, settle/remove the caller's pending entry; free the origin credit and discard late replies. Work **already accepted** at the recipient may still execute and hold its separate recipient admission/execution credits. Cancelling does not roll back effects. No indefinitely retained tombstones.
7. On responder failure, fail all affected pending calls with typed actor-stop/throw/raise/panic semantics. Restarted incarnations cannot complete old calls. Completion is at-most-once **locally**; never promise exactly-once remote execution or effect delivery.

### Local vs remote completion

- **Local trusted:** no wire reply identifier, and ideally no separate response mailbox payload; the completion cell is sealed runtime metadata attached to the queued request. Completion must still wake the caller *through its continuation lane*.
- **Remote/untrusted:** no Java refs, native pointers, guest object refs, or shared mutable heap paths. Decode the locator at the origin endpoint, check generation + expected responder + payload type + transport authentication, then settle the cell. An attacker-controlled numeric slot is **not** permission to reply. Bind locator access to a trusted channel session and request-specific capability or MAC (e.g. keyed authentication over endpoint identities, slot+generation, schema/epoch); use a per-session random key/epoch instead of generating a UUID for every call. A compromised peer must not be able to manufacture replies to a different request.
- **Supervisors:** child-to-supervisor application calls use an explicitly authorized typed DATA endpoint, separate from runtime-minted CONTROL failure receipts. CONTROL reservations and authentication remain independent.

## Deadlocks, quotas and failure semantics

- Reject self-call that requires the calling serial actor to re-enter itself. Also detect/report wait-for cycles where `A -> supervisor -> A` cannot make progress under message concurrency 1, rather than silently promise success. A suspended guest continuation releases the **native carrier**, not necessarily logical request admission.
- Distinguish `requestConcurrency` and `messageConcurrency`; do not quietly increase concurrency or execute actor code in parallel to “solve” RPC deadlocks.
- Account independently for caller pending futures, destination mailbox bytes/items, accepted responder work, reply payload bytes, continuation headroom, and reserved CONTROL signals. A caller timeout cannot free an accepted responder's quota early.
- Enforce deadlines via monotonic time; cancellation racing with successful completion has exactly one winner. Preserve original failure cause and expose timeout, admission, cancellation and actor-stop distinctly.
- Response authority is linear and one-shot; it is not ordinary sendable guest data and may not be copied, serialized or replayed. Access control, source-actor/parent binding and immutable request/response ABI metadata are verified before execution.
- Avoid implicit busy waiting, blocking carrier threads, dynamic guest method dispatch and leaking raw slot indices as user-visible types.

## Benchmark and conformance plan

**Runtime correctness tests:** origin supervisor -> actor; actor -> supervisor application endpoint; actor -> actor; 100 concurrent callers and out-of-order responses; FIFO admission where guaranteed; self-call and multi-node cycle; slot reuse / stale generation; actor restart vs code upgrade; timeout before dispatch / timeout during processing; cancellation vs response race; duplicate and forged reply; invalid reply nominal type; full mailbox and full reply slot budget; actor death/panic; receipt routing; untrusted tenant isolation and cross-process serialization; scheduler single-flight and no-Zalgo execution.

**Performance counters:** calls/s and p50/p99 request-to-reply latency, allocations/call, completion lookup cost, pending slot high-water mark, late-reply rejects, actor-migration CPU locality and mailbox/backpressure under contention. Compare:
- direct VM-private completion-cell baseline;
- generational slot lookup (needed at isolation/IPC boundaries);
- random UUID + hash map **as a measurement baseline only**.

UUID generation may be inexpensive relative to queueing/copying/scheduling; make no speedup claim without measuring on the same workloads. Optimize allocations and wakeup-path locality first.

## Rollout

1. Audit #456's existing request Future ownership and ensure the local completion path uses a VM-owned one-shot cell with no unnecessary global correlation map.
2. Implement bounded caller-local generational slot tables only for transports that cannot share the local completion cell; add ABI/version and endpoint/epoch guards.
3. Expose supervisor-owned typed application DATA service endpoints for authorized child calls; **never** overload CONTROL.
4. Share this primitive with the higher-level `std/otp/gen_server.ores` behavior once real guest-source call/cast/reply exists.
5. Prove exact-source Java runtime tests, hostile sender/remote tests and native backend parity independently before marking the proposed API implemented.

Documentation here is an architecture proposal only: no compiler or runtime tests have been run by this change.
