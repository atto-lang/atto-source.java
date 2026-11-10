# Event sourcing in AttoLang stdlib — super-actor API v1

Status: **DRAFT SOURCE CONTRACT, NOT EXECUTED GUEST IMPLEMENTATION** (2026-11-10).

This change adds new **.atto** stdlib source under `stdlib/`, without a new
`event actor` keyword or replacing existing
`stdlib/actor_persistence.ores` and `stdlib/otp/supervisor.ores`.

## Types and division of responsibility

| Library type | Responsibility | Not authorized to do |
| --- | --- | --- |
| `DurableActor<Command>` | Stateless superclass: operation/version admission | Mint ActorId, epochs or commit receipts |
| `EventSourcedActor<State,Command,Event>` | Pure decide/apply hooks and bounded event-batch validation | Directly perform I/O, mutate a leaf's sealed state, dispatch prior to commit |
| `SnapshotActor<State,Command>` | Pure snapshot decision and bounded payload validation | Claim enqueued data is durable |
| `ProjectionActor<Event,View>` | Pure read-model reducer and contiguous offset checks | Claim atomic read-model+offset commit without provider transaction |
| `DurableOutboxActor<Message>` | Stable operation/message identity preflight | Claim exactly-once external effects |
| `actor_event_sourcing.CommitGate` | One-pending-command policy, unknown-outcome fence and version tracking | Authenticate its own receipt inputs |
| `actor_event_sourcing.ReplayWindow` | Bounded, contiguous event replay admission | Independently validate stored bytes, schemas, provider authority |
| `actor_event_sourcing.ProjectionOffset` | Contiguous local checkpoint policy | Persist the checkpoint or ensure atomicity |

**Do not** use or reinterpret parent class fields as actor state. Abstract parent
actors must be stateless. A concrete leaf has the only `state { ... }` declaration
and all access is under `handle_state(critical-read/critical-write)`. The host
controls the publish boundary; a guest cannot invoke a privileged
`accept_verified_commit` as proof of durability merely by providing arguments.

## Required runtime/compiler bridge (NOT IMPLEMENTED)

1. Compile actor-kind-preserving, stateless `abstract actor` inheritance with
   `from`; reject ordinary class -> actor escalation, state-inheriting parents,
   alternate state write paths and actor method RPC.
2. Resolve `#event_sourced`/`#snapshot` selectors from trusted activation
   policy, with optional `#batched_sidecar` transport. Enforce exact
   `#symbol` identity and reject strings or `#batched_sidecar` without an
   underlying persistence policy.
3. Compile `decide`, `apply_event`, `decide_snapshot` and
   `apply_projection` as total-with-respect-to-effects, deterministic,
   side-effect-free user functions; reject transitive I/O, suspension, sends,
   random, nondeterministic clocks and raw/borrowed data crossing persistence.
4. Stage owned serialized event/state envelopes after releasing all sealed
   state loans. Trusted broker binds a canonical ActorId, tenant, immutable
   operation ID, fingerprint, expected-version CAS and fenced activation epoch.
5. A privileged async provider worker appends events and dedupe/inbox/outbox
   records in one provider transaction where that guarantee is requested.
   `admitted` is queue admission only. `committed` requires an authenticated
   provider receipt. A lost receipt is `OUTCOME_UNKNOWN` until authoritative
   reconciliation by the SAME operation ID. Never silently retry with a new ID.
6. Actor runtime validates the verified receipt, applies committed events,
   publishes the new state and replies. Callbacks resume in the actor turn
   lane, never on the storage worker; no critical loan crosses `await`.
7. The supervisor controls recovery and grants fenced activation, verifies a
   consistent state-head/snapshot/journal read, checks contiguous versions,
   decodes schema/upcasters, replays bounded events and enables command
   admission only after complete recovery. Panic uses CONTROL escalation.
8. Persisted outbox and inbox are logically separate from command execution;
   external delivery is typically at-least-once with recipient deduplication,
   not exactly once. Multi-actor transactions require saga/coordinator rules
   or provider-enforced atomic operations, never an implicit distributed ACID
   transaction.

`CommitGate` models ONLY local policy. Its `finish_verified_replay` and
`accept_verified_commit` arguments are untrusted without the compiler-sealed
runtime adapter; **production must not expose their invocation to untrusted
guest code**. Until the bridge exists, no security/durability guarantee is
implemented by those public guest methods. A future type-safe sealed receipt
must be runtime-created and non-constructible from guest source.

## Candidate concrete actor (PROPOSED, NOT EXECUTABLE YET)

```atto
define actor BankAccount from EventSourcedActor<AccountState, AccountCommand, AccountEvent> as
  state { balance: int = 0 }

  pure decide(AccountState old, AccountCommand command): Result<Array<AccountEvent>, String> {
    // pure validation; return owned, immutable events
  }
  pure apply_event(AccountState old, AccountEvent event): AccountState {
    // deterministic reducer, no I/O or direct state mutation
  }
end
```

No successful native compile/run is claimed for this illustration.

## Test and merge gates

- ES01: parser/formatter/typechecker accepts exact stateless inheritance and
  rejects inherited actor `state`, dual state schemas and improper overrides.
- ES02: negative compiler tests reject direct sealed state access, escaped
  borrows and `await` inside `critical-write`.
- ES03: real source-compiled guest runs a bank account actor and commits once.
- ES04: kill after commit before acknowledgment; same operation ID returns
  one event, one balance change after restart, no duplicate.
- ES05: actor A awaits async I/O, actor B proceeds on one carrier.
- ES06: stale epoch and wrong tenant/ActorId rejected by provider, even if
  source constructs a matching `CommitGate`.
- ES07: queue-admitted but unflushed write is lost on crash; no durable ACK.
- ES08: CAS conflict, duplicate operation ID + different payload, invalid
  digest, forged receipt and unknown outcome all fail closed.
- ES09: journal gap, corrupt schema, missing snapshot, replay budget exceeded
  and failed upcaster never publish partial state.
- ES10: supervisor crash/restart and child crash/stack overflow preserve
  independent journals and CONTROL routing, including parent hierarchy.
- ES11: batch 1,000+ actors with per-actor FIFO and actual provider commits,
  tenant fairness and bounded memory, including zero-byte message quota.
- ES12: snapshot+projection+outbox atomicity and retry correctness; external
  at-least-once effects are deduplicated by recipients.
- ES13: all five independent compilers (Java/Graal, LLVM, .NET, Wasm, Forge)
  execute **the same** positive/negative AttoLang fixtures; Cranelift remains
  independent. A parser-only or host-only pass is not guest parity.

### Existing foundations

- Conformance drafts [#227](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/227)
  and [#231](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/231).
- Java host persistence draft [#2282](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2282).
- LLVM host persistence draft [#1180](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1180).

This source-only proposal is deliberately a **draft PR**. Do not merge or
advertise it as a working event-sourcing subsystem until the new actor
superclass lowering, trusted runtime bridge, negative compiler checks, real
guest lifecycle tests and cross-backend exact-head evidence have executed.
