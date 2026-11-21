# Oreslang actor state: with / using / critical (pre-1.0 normative contract)

> **Pre-1.0 design update (2026-10-12):** The normative contract is
> [WITH_USING_STATE_CONTRACT.md](WITH_USING_STATE_CONTRACT.md):
> `with` declares permitted effects, `using` acquires lexical capabilities,
> `self.handle_state(|state|...)` reads by default, and `critical`
> grants exclusive write access. Historical syntax discussions below
> are retained for audit history but are superseded where they disagree.


## Three distinct responsibilities

1. `with` on a function or actor method declares an upper bound on effects
   (`state.read`, `state.write`, `io`, `suspend`, `block`, etc.).
   It neither creates a borrow nor grants mutable access.
2. `using` acquires a *lexically scoped* resource/state capability. The
   expressions `using state.read(callback)` and
   `using state.write(critical callback)` lower at compile time to the
   actor-owned sealed `self.handle_state` intrinsic. This does not
   create an ordinary guest-callable state function.
3. `critical` controls exclusive admission. Inside state callbacks,
   an unqualified `critical |s|` requests WRITE; an unmarked `|s|`
   requests READ. `critical read |s|` and `critical write |s|` are
   explicit equivalents. Ordinary non-state critical functions retain
   their existing single-flight scheduler semantics.

```oreslang
define actor Counter as
    state { v: int = 0; }

    get(): int with state.read {
        return using state.read(|s| -> s.v);
    }

    set(int x): void with state.write {
        using state.write(critical |s| -> { s.v = x; });
        return;
    }

    increment(): int with state.read, state.write {
        return using state.write(critical |s| -> {
            s.v = s.v + 1;
            return s.v;
        });
    }
end
```

The equivalent sealed form remains:
```oreslang
get(): int with state.read {
    return self.handle_state(critical read |s| -> s.v);
}
increment(): int with state.read, state.write {
    return self.handle_state(critical write |s| -> {
        s.v = s.v + 1;
        return s.v;
    });
}
```

## Admission invariants

- `with` is an effects-only declaration. A wide `with state.read,
  state.write` row does not upgrade a READ capability to WRITE.
- `using state.read` only admits a lexical read callback, and
  `using state.write` requires a critical WRITE callback. The compiler
  must enforce the same read/write permissions in both spellings.
- A direct write incurs `state.write` only; a read incurs `state.read`.
  A read-modify-write incurs both. These are checked transitively, and
  unknown calls under an effect bound must fail closed.
- A `using` declaration in a function signature is forbidden:
  `fnc f() using state.read { ... }` is NOT an effects clause.
- `self.state` never resolves as an actor instance member. The
  callback loan cannot escape, be sent across actors, be stored in a
  closure, or be used in a static actor method.
- The current synchronous scalar-state runtime rejects suspension with a
  live state loan. No OS mutex is held through a guest callback.
- Reader compatibility is a *logical* scheduling property, not physical
  simultaneous execution: at most one native carrier runs a given actor's
  guest code at once. Shared reader leases and writer fairness still need
  a scheduler proof before claiming parallel active reads.

## Compile-positive / compile-negative matrix

| Source form | Effect declaration | Result |
| --- | --- | --- |
| `using state.read(\|s\| -> s.v)` | `with state.read` | accept |
| `using state.write(critical \|s\| -> {s.v=1;})` | `with state.write` | accept |
| `using state.write(critical \|s\| -> {s.v=s.v+1;})` | `with state.read, state.write` | accept |
| READ callback assigning `s.v` | even with both effects | reject |
| WRITE callback reading then assigning | with only state.write | reject |
| `using state.write(\|s\| -> ...)` | with state.write | reject |
| `using state.read(critical \|s\| -> ...)` | with state.read | reject |
| `fnc f() using state.read` | none | reject |
| `fnc f() with state.read` but no read | harmless upper bound | accept |
| direct `self.state.v` | any | reject |

## Backend / verification status

Java/GraalVM: source parser, type/effect and ownership checks and a
synchronous scalar runtime are being verified; this specification is not
a claim of complete interprocedural, AOT or concurrent-reader support.
LLVM: the native admission policy is testable, but compiled general
`state { ... }` guest programs are still an explicit release gate.
