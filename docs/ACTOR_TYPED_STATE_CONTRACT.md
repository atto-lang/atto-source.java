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

## Compiler proof-of-concept status (October 11, 2026)

**Target user experience (source lowering remains unfinished):**

```oreslang
define actor Counter from Countable as
    state {
        count: int = 0;
        enabled: bool = true;
    }

    pub get_count(): int using state.read {
        return self.handle_state(|s| -> s.count);
    }

    pub increment(): int using state.read, state.write {
        return self.handle_state(|s| -> {
            s.count = s.count + 1;
            return s.count;
        });
    }

    critical pub audit(): void using state.read {
        self.handle_state(|s| -> { stdio.println(s.count); });
        return;
    }
end
```

Here `state` is the **one and only actor-owned persistent storage
declaration**. The `handle_state` callback is the sole access path; guest
code may not access `self.state`, return the state capability, capture it
into another deferred closure, or retain a conflicting mutable loan across
a reentrancy boundary. Nested **same-continuation** critical methods inherit
the logical admission ticket, but nested callbacks have separate ephemeral
state-access loans.

Map alternatives:

```oreslang
state: Map<string, int>;

state: Map<string, int> {
    hits: int = 0;
    errors: int = 0;
}
```

For a map with declared keys, those keys have exact known types; dynamic
keys use the map's declared key/value types. A heterogeneous typed-key
schema may later constrain each declared key to a specific subtype of a
dynamic value union. Runtime dynamic `get`/`set` and mixed-type schema
lowering are NOT yet implemented.

`handle_state` **permits** `await`, `select`, other suspension points,
and explicitly blocking calls. Prefer I/O outside it when possible, but
do not ban it at the language level. `*_blocking` call effects must come
from trusted function signatures, not from guessing based on method names.
In an actor, blocking on a carrier must obey scheduler isolation rules;
strict RT admission may reject unbounded blocking/FFI. Each admitted slice
remains logically exclusive; yielding can allow another actor continuation
to enter, so callback-wide atomicity across `await` is NOT implied.

`from` is a proposed contextual actor interface/trait conformance
spelling. Existing Java parser recognizes `from` at actor declarations,
but full interface/trait contract validation across backends is pending.
`using` describes effects and `requires` needs verified proofs; the
prototype performs conservative syntactic checks but no cross-call-graph
effect inference. The existing `pure`, `nlex`, `trap` contracts remain
separate, and the existing `with` keyword is not reused.

**Current boundaries:** Java/GraalVM has parser/AST and conservative
source-checker scaffolding for schema, sealed state access and `using`.
It still uses a synthetic, non-public field marker in the AST; this MUST
be lowered to actor-runtime-owned storage, not an ordinary class field.
Existing legacy actor fields are temporarily allowed only for migration
when no `state` schema is declared. New schema actors reject other fields.
Java's synchronous FFM host map is not a source-level `handle_state`
implementation; source `critical` methods currently fail closed pending
scheduler tickets. LLVM has matching typed effect bitsets and narrow
`using` parsing but intentionally rejects source `state` pending
compiler/runtime lowering. Neither backend yet provides source-level
suspendable `handle_state` or hard-real-time-certified reduction regions.

Compound `+=` assignment remains unsupported by the inspected Java
source parser; examples use `s.count = s.count + 1`. Lowering either
spelling must guarantee no reentrancy between the read and commit of an
actor-local primitive read-modify-write.
