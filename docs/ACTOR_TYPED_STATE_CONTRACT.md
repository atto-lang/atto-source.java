# Sealed actor state: typed schema and dynamic map contract

This is a design contract; source compiler support is not yet implemented.

```oreslang
actor Counter {
    state {
        count: int = 0;
        enabled: bool = true;
    }
    pub fnc increment(): int {
        return self.handle_state(|s| {
            s.count += 1;
            return s.count;
        });
    }
}
```

The `state` block declares a finite set of typed keys. It does not declare a
field accessible as `self.state`. Each actor instance owns separate storage;
initializers execute before its ready barrier. Required keys cannot be deleted,
unknown keys are compile errors, and a write must match the declared type.

For homogeneous dynamic keys, use an explicit map declaration:

```oreslang
actor Cache {
    state: Map[string, int];
    pub fnc put(key: string, value: int) {
        self.handle_state(|s| { s.set(key, value); });
    }
}
```

No declaration means no implicit untyped map. `critical handle_state` is a sealed actor intrinsic, not user-overridable.
The callback may await or select. The scheduler preserves single-flight logical actor execution **per admitted
slice**, releases actor-lane admission at a verified suspension boundary, and
may admit other continuations before the callback resumes. It must not retain
a state-backed mutable loan or JVM monitor across await. Nested same-continuation
synchronous callbacks are permitted; the innermost loan temporarily fences the
outer loan. A distinct continuation cannot enter an active exclusive state
scope; it must be separately admitted on the actor lane.
State capabilities and mutable state-backed aliases must not escape their
valid ownership scope. Runtime invalidation is a
second line of defense, not a replacement for compiler ownership checks.

The Java host `ActorPersistentI64Map.handleState` API is only a bounded
native i64-to-i64 prototype; it does not yet implement this source contract.
ActorGroup must own initialization and deterministic cleanup on termination,
panic, cancellation and failed initialization. JVM carrier thread identity is
not the owner identity. LLVM and Java must share typed field-layout metadata
and source-level conformance fixtures before declaring parity.

For effect inference, concrete proposed `using`/`requires` signature syntax,
and current `pure`/`nlex`/`trap` limitations, see
[function-effect contract](FUNCTION_EFFECTS_AND_SIGNATURE_QUALIFIERS.md).
For bounded preemption and strict real-time admission, see
[reduction-region scheduling](REDUCTION_REGION_SCHEDULING.md).
