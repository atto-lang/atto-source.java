# AttoLang neuron actors — protected state container

**Canonical syntax**:

```atto
define neuron actor LinearNeuron as
  state {
    let float weight;
    let float bias;
  }
end
```

**Status: parser/AST candidate only.** The Java compiler preserves this schema separately from ordinary actor fields but *intentionally refuses execution* with `NEURON_STATE_MIR_UNSUPPORTED` until compiler-checked critical state-loan lowering exists. This is not a working training implementation or verified guest execution. The earlier flat-field demonstration is superseded.

## State ownership and declaration rules

- Every neuron actor has exactly **one** `state { ... }` mutable container with one or more private, explicitly typed `let float name;` fields. The scalar v0 type/layout validator rejects initializer expressions until checked actor activation can evaluate them. Reject duplicate keys, other dtypes, public fields, inferred fields, nested containers, and over 128 fields.
- The protected schema is `ClassDecl.stateFields`; `ClassDecl.fields` stays empty. AST annotation expansion preserves this distinction. No implicit ordinary field, `self.state` alias, getter, or raw mutable reference is synthesized.
- State is actor-owned. Multiple immutable read loans may coexist, but a writer needs an exclusive, fair loan; no actor/supervisor simultaneous mutable ownership, no mutable state borrowing through `ActorMail`, no unrestricted access from `forward` or `receive`.
- STOP/cancellation, restart and hot reload must wait for physical quiescence or fence stale loans and actor generations. Supervisor may request versioned snapshots or messages; it cannot inherit a raw actor-owned pointer.

## Implemented host-only reader/writer primitive

`ActorStateReadWriteLoans` now implements an independent, VM-internal reader/writer
loan ledger per positive actor generation, with `acquireReadAsync(handler, generation)`
and `acquireWriteAsync(handler, generation)`. Reader loans can coexist. A writer
is exclusive, queued writers cannot be bypassed by newly arriving readers,
pending admission is bounded, the same logical handler cannot recursively
acquire another loan, and future cancellation removes queued identities.
Completions/notifications occur outside the loan monitor. Retirement fences
every live loan immediately without pretending that physical holders are
already quiescent; outstanding cleanup is still required.

**This primitive is not yet wired to the actor scheduler, actor-state allocation,
the compiler borrow checker or `handle_state` MIR.** Its public Java types are
host-internal and do not grant guest authority by themselves. In particular,
`Loan.heldBy(...)` is observational, not a valid check-then-use authorization
pattern; only compiler/runtime-enforced guarded access can authorize a state
read or write. A supervisor must never receive these capabilities.

`src/test/java/dev/oreslang/runtime/ActorStateReadWriteLoansTest.java`
contains **12 host-only JUnit test cases**, including reader batching, writer
fairness, cancellation, generation restart/retirement, unpublished grants,
callback lock discipline, contention and epoch exhaustion. These are source
tests pending executed CI receipts, not verified pass results. The targeted
`.github/workflows/neuron-state-contract.yml` gate checks the exact PR head
and tree before invoking Maven's `NeuronActorV0Test` and
`ActorStateReadWriteLoansTest` suites. A queued/failed job with
`steps=null` is not evidence that the Maven invocation occurred.

### Guest/compiler next implementation slice

1. Create an actor-owned allocation for `ClassDecl.stateFields()` with initial
   values and stable state-field layout; no public field projection.
2. Parse and lower `handle_state(critical-read |s| -> ...)` and
   `handle_state(critical-write |s| -> ...)` as compiler intrinsics, not
   spoofable user methods.
3. Perform lexical borrow/escape checking: no state reference can leave its
   critical scope via return, closure capture, actor message, future, global
   field, or async suspension.
4. Bind each intrinsics' guard to the currently running logical actor handler,
   correct generation, and live state region; all reads/writes must be
   checked at use, not only when a loan is acquired.
5. On cancellation, STOP, supervisor restart and hot reload, block reclaim
   until loans and continuations are physically quiescent; create a fresh
   generation for replacement state.
6. Only then lift `NEURON_STATE_MIR_UNSUPPORTED` and execute real positive
   and negative `.atto` inference conformance tests.

## Compiler and host runtime changes on this draft

- `NeuronStateLayoutV0.fromActor(ClassDecl)` produces a bounded immutable
  float-field layout from the typed AST and rejects unsupported field types
  or initializer side effects. `newHostState(generation, maxQueuedLoans)`
  allocates an actor-generation-owned `ActorStateReadWriteLoans` instance.
  This does **not** connect the instance to an actual `ActorCell` yet.
- The runtime now retains `double[]` scalar values and initialization bits
  inside its private arbiter. `readFloat` / `writeFloat` validate the live
  loan, logical handler identity, generation, access mode and field name under
  the **same monitor as scalar access**—not a racy `heldBy()` then read.
  Reading an uninitialized slot fails; no `double[]` or state alias escapes.
- The `self.handle_state(critical-read |s| -> ...)` and
  `self.handle_state(critical-write |s| -> { ... })` spellings are now
  parsed **only inside neuron actors** as internal `$neuron.critical.read`
  / `$neuron.critical.write` AST markers. The parser rejects other critical
  modes, extra arguments and invalid alias signatures. These are AST
  admission markers, **not executable intrinsics or certified borrow scopes**.
- `NEURON_STATE_MIR_UNSUPPORTED` still blocks every neuron actor before
  normal guest emission. The actual compiler must prove borrow escape,
  continuation, cancellation and supervisor boundaries before admission.

The source fixture in `examples/neuron-scalar-v0.atto` now shows the intended
`on_start` write and `forward` read. It is intentionally **not** marked as
an executed guest program.

## Planned execution API (not active)

A future compiler-backed implementation should make the state accessible only via checked critical state-loan scopes, conceptually:

```atto
// Proposed syntax — NOT currently executable.
pub forward(float input): float {
  return self.handle_state(critical-read |s| -> s.weight * input + s.bias);
}
```

The scalar API also specifies a public synchronous `forward(float): float` and private runtime-invoked `receive(ActorMail<float>): void` hook; hook signatures are checked before the MIR rejection. Longer-term `NeuronRef<I,O>` should offer asynchronous `infer`, VJP-based `backward`, `accumulate`, `commit_step`, `cancel_step`, immutable versioned `snapshot`, and supervisor-validated `connect`. Training requires correlated forward/backward tapes, parameter-version pinning, fan-in, atomic idempotent updates, bounded budgets and separate supervisor CONTROL. Mathematical neuron graphs are distinct from physical actor graphs; one actor may host multiple mathematical neurons.

## Verification

The `examples/neuron-scalar-v0.atto` fixture demonstrates canonical state syntax, not a working neuron. `src/test/java/dev/oreslang/NeuronActorV0Test.java` checks parser/AST preservation, invalid schemas, hook signatures and the explicit type-check rejection. It does **not** contain a positive guest-execution test because that would be misleading until state loans are implemented.

No Java/GraalVM, LLVM, .NET NativeAOT, Wasm, Forge/Cranelift or JS backend is certified for this state syntax. Exact head/tree guest receipts with non-zero executed job steps remain mandatory for promotion. `steps=[]`, `steps=null` and `runner_id=0` are runner-admission failures. Existing `docs/ML_ACTOR_AUTODIFF_V0.md` describes only a host numerical reference, not source-compiled actor training. New guest fixtures use `.atto`; `.ores` remains a legacy-compatible extension.
