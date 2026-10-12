# Critical state access and using effects — pre-1.0 breaking contract

## Syntax

```oreslang
define actor Counter as
    state { v: int = 0; }
    get(): int using state.read {
        return self.handle_state(critical read |s| -> s.v);
    }
    set(int x): void using state.write {
        self.handle_state(critical write |s| -> { s.v = x; });
    }
    increment(): int using state.read, state.write {
        return self.handle_state(critical write |s| -> {
            s.v = s.v + 1;
            return s.v;
        });
    }
end
```

### Normative invariants

1. Unqualified `critical` is ordinary critical-section admission and does not grant
   authority over an actor's sealed state.
2. `self.handle_state` requires one inline lexical `critical read` or
   `critical write` callback. Bare `critical |s|` and `|s|` are compile errors
   even in `serial actor`.
3. `critical read` grants read-only state access. Read projections may not be
   assigned, moved out, captured, mutated through an alias, or passed to an
   unknown potentially mutating state method. Read-to-write upgrades while a
   read scope is active are rejected.
4. `critical write` grants exclusive read/write access; reading produces the
   `state.read` effect and writing produces the `state.write` effect. A pure
   assignment needs `using state.write`, not automatically `state.read`.
   Read-modify-write needs both.
5. `using` is an upper bound on effects, never an authority grant or lock
   primitive. `using state.read,state.write` **cannot** authorize a write in
   `critical read`. Conversely `critical write` **cannot** override missing
   `state.write` in an explicitly declared `using` upper bound.
6. `critical` scheduling is orthogonal to `using`, `pure`, and
   `requires no_block`. Do not silently infer `io`, `block`, `suspend`
   or any effect solely because a critical mode appears.
7. State capability is a lexical sealed non-sendable, non-escapable loan;
   direct `self.state` is always forbidden. The current executable
   synchronous source path prohibits suspension with live state loans.
8. Each actor retains at most one executing native carrier at a time;
   logical shared-reader admission is future scheduler work, not permission
   for native parallel guest execution.

### Admission and conformance matrix

| Callback mode | Action | Required `using` if declared | Result |
| --- | --- | --- | --- |
| read | read field | state.read | accept |
| read | write field | state.write | **reject** (no authority) |
| read | write field | state.read, state.write | **reject** |
| write | overwrite field without reading | state.write | accept |
| write | read field | state.read | accept |
| write | increment field | state.read, state.write | accept |
| write | increment field | state.write | **reject** (missing read effect) |
| unqualified critical | any state access | any | **reject** |
| no critical mode | any state access | any | **reject** |

The existing Java/Graal source evaluator and parser support only the
synchronous scalar sealed-state subset on this implementation branch.
General LLVM guest `state` lowering, nested compound state structures,
suspendable state loans, cross-actor read-lease scheduling, full
interprocedural effect inference, and formal concurrency verification
remain separate release gates. Do not infer backend parity from shared
documentation or host-only tests.
