# Durable actor execution quiescence: host runtime boundary (draft)

This change adds a host/supervisor-only, nonblocking `ActorRuntime.requestDurableQuiescence(ActorRef<?>)` operation. It is **a runtime execution barrier only**, not a durable persistence adapter, guest heap serializer, ownership transfer protocol, or complete GraalVM restart feature.

## Protocol

1. The supervisor obtains the actor handle and requests quiescence; actors/CONTROL Mailmen cannot call this host-only API.
2. Under the actor's existing `lifecycleLock`, the request inhibits new scheduling and turn admission, including runtime continuations. Already-admitted envelopes finish.
3. The returned `CompletableFuture<DurableQuiescence>` settles only after `TurnExecutor.execute` has returned and the physical carrier owner and dispatch token have been cleared. Callbacks are dispatched off-carrier through the common pool, not inline under the actor lock.
4. Suspended source receives, outstanding owned futures, unfinished cleanup and active untrusted logical tasks fail closed rather than publishing an unsafe snapshot. No polling or synchronous waiting occurs on the CONTROL scheduler.
5. While the opaque proof is held, new user messages may still enter the bounded mailbox but cannot execute. `close()` releases the scheduler barrier on failure or aborted persistence; `stopAfterHibernate()` seals/stops the actor *before* reopening dispatch admission on successful persistence.
6. Duplicate freeze requests reject; rejected Future cancellation does not silently resume execution.

## Invariants and deliberate limitations

- The physical worker lease remains the existing `ActorCell` single-writer lease; freezing uses the same lifecycle monitor. Queued dispatch tickets cannot run guest code if freeze admission won.
- The proof is *not* a PostgreSQL lease owner credential. It does not bind `DurableActorStore.Address` to a runtime `ActorRef`; an authorized supervisor must supply that binding.
- The existing `DurableActivationCoordinator.freezeLeased` still needs to be migrated to accept this runtime proof, and database I/O must occur off-carrier.
- No snapshot bytes are generated, no actor heap is restored, and no external side effects become exactly-once.
- This first implementation cannot serialize a suspended receive with active continuations; it rejects instead of pretending those continuations are quiescent.
- Scoped verification can parse the exact Java source and test the existing PostgreSQL host adapter. **Only full Java/GraalVM CI executing `ActorDurableQuiescenceTest` can prove real runtime behavior.**

## Planned regression coverage

`src/test/java/dev/oreslang/runtime/ActorDurableQuiescenceTest.java` covers in-flight turns, queued messages, suspended receives, no resurrection after stop, host callback isolation, and delayed `TurnExecutor` exit.
