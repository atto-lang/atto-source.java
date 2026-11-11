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
contains **20 host-only JUnit test cases**, including reader batching, writer
fairness, cancellation, generation restart/retirement, unpublished grants,
callback lock discipline, contention and epoch exhaustion. These are source
tests pending executed CI receipts, not verified pass results. The targeted
`.github/workflows/neuron-state-contract.yml` gate checks the exact PR head
and tree before invoking Maven's `NeuronActorV0Test`,
`NeuronStateLayoutV0Test` and `ActorStateReadWriteLoansTest` suites.
A queued/failed job with
`steps=null` is not evidence that the Maven invocation occurred.

### Additional audit hardening (source committed; execution not verified)

- The loan arbiter now has an **independent active-reader cap** (128 by
  default, configurable 1..65,536) and a separate, bounded pending queue
  (0..65,536). Saturated reads queue rather than allocating unlimited active
  capabilities; FIFO writer admission remains intact.
- Cancelling a **reserved but unpublished** read or write Future revokes the
  reservation immediately. The unused notification closure is removed from
  the pending publication queue; otherwise a stalled earlier subscriber
  could retain arbitrarily many cancelled grant callbacks and block capacity.
  Both modes have deterministic stall/cancel regressions, plus a 256-cycle
  retention stress case, concurrent read/write count checks, and repeated
  cancellation/retirement schedules.
- Neuron actors are restricted to concrete **shared** v0 actor declarations,
  and `forward` / `receive` reject mutable/structural parameters and
  explicit receiver types. This is deliberately narrow until the isolation
  and alternate-receiver ABI is proven.
- The linked public-class metadata admission path rejects neuron actor
  signatures with `NEURON_STATE_LINK_UNSUPPORTED` instead of permitting
  a separate compilation unit to import an unlowered protected state ABI.
- Self-hosted Linux CI is **manual workflow_dispatch only**; scheduling it
  automatically on every PR left the entire run queued when no matching
  runner existed, also preventing hosted-job retries. Hosted attempt 2 was
  accepted but again failed with `steps=null`, without Maven execution.
  No private source was copied to another organization.

The 20 reader/writer JUnit methods, 10 neuron parser/type-check methods,
4 layout methods, 13 scoped-scalar preflight methods, and four real-ActorRuntime
turn/state host methods are **51 previously committed JUnit tests, NOT EXECUTED**
on the current exact PR tree. No compiled guest `.atto` inference is certified.

### Compiler scoped-scalar preflight (new, still fail closed)

`NeuronScalarScopeV0.preflight(ClassDecl)` now performs a **closed AST walk**
of the initial `on_start` write and `forward` read scopes. It is invoked
from `TypeChecker.rejectUnloweredCheckedEffects` only **before** the existing
`NEURON_STATE_MIR_UNSUPPORTED` mandatory throw. It does **not** grant a loan
or execute the neuron. The preflight:

- Accepts only the compiler-marked `self.handle_state` call, one inferred
  nonmutable state alias, and the expected `critical-write` or
  `critical-read` form. It rejects duplicate hooks, direct critical scopes
  in other methods, unmatched read/write pairs, or extra statements.
- Requires `on_start` to initialize every declared float slot exactly once.
  The right-hand side is checked **before** its assignment marks the slot
  initialized, so `s.weight = s.weight + 1.0` is not an implicit zero read.
- Allows only scalar double literals, initialized state-field loads, the
  explicitly declared `float` forward argument, and straight-line `+`,
  `-`, `*` arithmetic. Rejects closures, arbitrary calls, `await`,
  `return s`, other projections, array/object references, mutation in read
  scopes, and unsupported operations by default.
- Returns an immutable **descriptive** plan of read/write field names.
  Its public record data is **not an authorization receipt**. A complete
  borrow checker must still validate all program paths and all methods, and
  bind the loan to the actual physical actor execution/heap generation.

`NeuronScalarScopeV0Test` adds 13 source-level JUnit methods for valid
preflight shape and negative escape, side-effect, early-read, unexpected
method and expression-depth cases. The targeted workflow includes this
suite along with the prior three host suites. This is **not executable
guest MIR**, and no current-head test pass may be asserted from these
committed tests alone.

- Scalar v0 requires an **explicit `return;`** after the
  `on_start` critical-write block, consistent with AttoLang's explicit
  function-return rules. The source fixture and tests use that form.
- Retirement now attempts to fail **all queued Future waiters**, even if an
  observer throws during settlement, and preserves physical loan cleanup
  before state-storage zeroization.

### Trusted ActorRuntime turn bridge (new, still host-only)

The runtime now mints `ActorRuntime.NeuronActorTurnProof` only from an
**admitted physical shared-actor activation or mailbox command**. Its witness
binds the exact `ActorRef`, carrier/execution context, and an actor-owned
monotonic message-scope epoch. The epoch advances for startup and every
mailbox/continuation envelope, **including multiple messages in one throughput
batch**. A captured proof is invalid at the next message, off-carrier, after
STOP, or when another actor attempts to use it. The epoch fails closed on
overflow. The proof retains **no mailbox payload** or sender references, and
the fast path allocates no additional per-envelope identity object.

The package-private `NeuronActorScalarTurnStateV0` is an opt-in trusted host
bridge, not a guest or public supervisor API. Construction requires a live
neuron turn proof, and the state generation is derived from the runtime-minted
`ActorRef.generation()`, never a caller-supplied epoch. Each scope read/write
revalidates the exact actor turn before asking the scalar arbiter to access
its private fields. `tryRead()` / `tryWrite()` use **nonparking** immediate
loans and return `null` on fair queue contention; no actor carrier joins an
unresolved Future or sleeps on a reader/writer lock. A retained scope from a
previous message cannot be reused, and loan cleanup is allowed after the
physical turn has exited.

`NeuronActorTurnProofRuntimeTest` and `NeuronActorScalarTurnStateV0Test`
add four tests against the **real** `ActorRuntime` (not a mocked actor):
startup proof minting, same-batch message fencing, off-carrier/cross-actor
denial, stable actor-derived generation, read/write initialization and the
expected host scalar arithmetic result `2 * 3 + 1 = 7`. This result is
**HOST-ONLY**, not compiled `.atto` inference and not an AOT receipt.

**New lifecycle integration:** an actor can bind exactly one host scalar
state during its authenticated activation; the runtime rejects duplicate
bindings, a mismatched `ActorRef`, or late attachment from a message turn.
`ActorCell.finalizeStopLocked` closes and zeroizes that host state only after
its existing physical turn/source-task/native-I/O quiescence gates, and only
when every neuron scalar loan has been physically released. An outstanding
loan **delays finalization** rather than allowing premature reclamation.
The last physical scope close notifies `ActorCell` outside the scalar monitor,
which rechecks the normal STOP/source-task/native-I/O quiescence gates and
zeroizes state only after all loans have drained. If a loan never closes,
the actor remains stopped but not finalized; the supervisor must eventually
quarantine/reap it under an explicit liveness policy, rather than silently
freeing the memory. The real-runtime tests assert automatic successful STOP
cleanup and late-loan-release finalization without a host/manual state close.

**Still blocking guest enablement:** the compiler does not emit this bridge;
the host lifecycle registration is not yet a general source actor ownership
proof across backends; and an external STOP racing a single scalar write
requires a unified lifecycle/state-operation linearization proof.
Quarantined leaked-loan recovery needs an explicit supervisor policy.
These missing boundaries remain blocked by
`NEURON_STATE_MIR_UNSUPPORTED`. Host-only tests do not certify
source-compiled inference, native AOT, or arbitrary FFI memory reclamation.

The targeted suite now has **56 committed JUnit methods** across seven test
classes, including five real-runtime host tests and four typed-MIR descriptor
tests. At the time of this
edit the exact-head GitHub-hosted test admission remains at
`steps=null`; source tests are not executed receipts.

### Structured scalar MIR descriptor (AOT preparation, not emission)

`NeuronScalarMirV0.lower(ClassDecl)` now consumes the already preflighted
real parser AST and emits a **typed, immutable float-only instruction plan**:
`CONST_F64`, `LOAD_STATE_F64`, `LOAD_INPUT_F64`, `NEG_F64`,
`ADD_F64`, `SUB_F64`, `MUL_F64`, and `STORE_STATE_F64`.
It has no dynamic dispatch, reflection, guest callback, runtime parser,
macro evaluation, or unbounded field lookup. Both critical read and critical
write are represented as structured `GuardedScope` blocks with a mandatory
`RELEASE_IN_FINALLY` edge, not a fallthrough-only release instruction.
A real emitter must implement the cleanup edge for traps, cancellation,
runtime failures and all normal exits.

The TypeChecker now requests this descriptor for a complete preflighted
scope pair and then **still throws** `NEURON_STATE_MIR_UNSUPPORTED` without
emitting guest code. `NeuronScalarMirV0Test` adds four JUnit cases proving
the accepted static instruction sequence, malformed/inconsistent descriptor
rejection, expression/alias negative cases, and the retained type-check gate.
The descriptor is not a portable executable binary, authenticated loan, or
compiled JVM/LLVM/.NET/Wasm/Forge result; native backend-specific lowering
and physical actor lifecycle binding remain outstanding.

## MIR descriptor and physical-host model hardening

The scalar `NeuronScalarMirV0` descriptor now rejects hand-built
`GuardedScope` programs with operand-stack underflow, leftover values,
missing read result, missing write, write from an inference input, or a
write from a read-only scope. The `Plan` constructor separately checks
every state symbol against the immutable declared layout and enforces
one write per field, reads after initialization, and a consistent
inference-input name. These checks apply even if no parser-produced
preflight is involved. Neither the descriptor nor this validation
constitutes a guest execution capability.

The host `NeuronActorScalarTurnStateV0.ReadScope` close operation
uses a single atomic release claim, so concurrent STOP/cancellation
cleanup cannot double-close the physical loan. Its STOP drain callback
is claimed at most once when the last loan releases. Parser admission
now restricts contextual `self.handle_state(critical-...)` syntax
to `on_start` or `forward`, and rejects direct/nested occurrences
inside `receive` or helper methods.

`NeuronScalarMirHostModelTest` feeds an actual parsed
`.atto` fixture into `NeuronScalarMirV0.lower`, then runs the
**descriptive** instructions using a test-only scalar stack model
whose state loads/stores are routed through a real
`ActorRuntime` turn-bound protected scope. Its expected result is
`weight=2, bias=1, input=3 -> 7`, and injected instruction failures
must release loans through Java finally. **This is host-model
integration testing, not compiled AttoLang inference or a release
gate receipt.**

The targeted exact-head suite now has **61 committed JUnit methods**
in eight classes, including the new constructed-MIR, concurrent
cleanup and host-model regressions. As with all earlier tests, they
remain **unexecuted** until a trusted runner produces a real
nonzero-step Maven test report on the exact branch head.

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
