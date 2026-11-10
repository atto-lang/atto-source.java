# Host event journal bridge v1 (stacked draft)

Status: **HOST-ONLY, UNEXECUTED AT THIS EXACT HEAD**. Parent PR: Java
[persistence sidecar #2282](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2282).

The trusted `ActorPersistenceBridge` now exposes event-shaped variants of
its existing private actor activation-scoped snapshot submission API:
`submitOwnedEvent` and `onEventCompletionInCurrentActor`.

Only one event is committed per `Write` at this stage; the provider increments
the journal version by exactly one. A domain command yielding multiple events
MUST fail closed rather than splitting them into non-atomic separately ACKed
journal writes. General atomic multi-event append is a P0 follow-up.

## Security boundaries

- The host alone constructs `ActorPersistenceBridge.Activation` inside the
  authenticated, correct owner actor turn after installing provider-side fence.
  Guest code cannot choose tenant, state identity, epoch, Store, or DB path.
- Submitted byte arrays are copied through the `Write` contract, not
  actor-owned pointers or views. A state loan must close before submission.
- Sidecar verifies provider-confirmed receipt against persisted operation
  ledger; event completion adds per-actor turn, operation, durable status and
  expected-version checks before invoking the **host** continuation.
- No guest-visible `persist_event` intrinsic or compiler-generated state
  publication exists yet; this API cannot make application state durable by
  itself. Non-durable/mock receipts may never publish a durable state.
- Timeout/outcome unknown remains unresolved. The current provider rejects a
  repeated operation ID written in a different activation epoch with
  `OP_ID_CONFLICT`; this prevents double append but does NOT yet let an
  application safely acknowledge an old command on retry. The next milestone
  is provider-authorized cross-epoch operation resolution with exactly matched
  digest and durable inbox status, never a new operation ID.

## Narrow proposed integration fixture

`EventSourcedActorRuntimeIntegrationTestMain` uses the real
`ActorRuntime.spawnPrivateTrusted`, owner-checked actor continuation, direct
private actor memory, SQLite WAL/FULL sidecar, retirement and fresh actor
incarnation. It deliberately drops an ACK after committing a journal event,
then reloads from a provider-fenced read and replays to a new arena. It checks
that an operation ID collision after epoch rotation cannot append a second
deposit. This is a **host** fixture; it does not compile an AttoLang actor.

**Evidence at this head:** not executed. CI requires nonzero runner steps,
exact code source provenance, JDK21 full Maven test-compile, pinned JDBC driver
and actual fixture exit 0. Zero-step Actions failures cannot be passes.

## Follow-up merge gates

1. Add trusted provider read-only `resolve_operation` across epochs with
   original command payload fingerprint, canonical actor identity and
   operation ID binding; never grant old writer authority.
2. Add a durable inbox (or equivalent persisted command response) so
   retransmitted command IDs return the earlier durable result without
   re-running `decide`.
3. Add atomic multi-event append and snapshot+outbox transactions.
4. Compile and run actual `.atto` `EventSourcedActor`, with compiler-sealed
   state, pure reducer, asynchronous continuation and supervisor recovery.
5. Independently execute Java and LLVM plus .NET/Wasm/Forge parity.
