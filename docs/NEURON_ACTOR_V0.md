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

- Every neuron actor has exactly **one** `state { ... }` mutable container with one or more private, explicitly typed `let Type name;` fields (initializers optional). Reject duplicate keys, unknown types at typing time, public fields, inferred fields, nested containers, and over 128 fields.
- The protected schema is `ClassDecl.stateFields`; `ClassDecl.fields` stays empty. AST annotation expansion preserves this distinction. No implicit ordinary field, `self.state` alias, getter, or raw mutable reference is synthesized.
- State is actor-owned. Multiple immutable read loans may coexist, but a writer needs an exclusive, fair loan; no actor/supervisor simultaneous mutable ownership, no mutable state borrowing through `ActorMail`, no unrestricted access from `forward` or `receive`.
- STOP/cancellation, restart and hot reload must wait for physical quiescence or fence stale loans and actor generations. Supervisor may request versioned snapshots or messages; it cannot inherit a raw actor-owned pointer.

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
