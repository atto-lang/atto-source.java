# Static select result, nonblocking completion, and procedural return

**Status (2026-10-13): normative pre-1.0 target and incremental Java hardening.**
This contract corrects the older "do select returns are arm-local/discarded"
interpretation. It does not claim that the static value/Future forms have
already been implemented in Java/GraalVM or LLVM.

## Canonical forms

| Syntax | Kind | Result | Meaning of return inside selected arm |
| --- | --- | --- | --- |
| `select { ... }` | blocking/suspending expression | `T` | terminal `return T;` exits the **select expression arm**, not the enclosing callable |
| `do select { ... }` | blocking/suspending statement | `void` | `return T;` / `return;` exits the **enclosing function/method**, like `do match` and `do switch` |
| `nb select { ... }` | nonblocking expression | `Future<T>` | `return T;` supplies eventual Future result; cannot return from an already continued caller |
| `do nb select { ... }` | **not canonical** | none | reject in the final grammar; legacy Java-only detached syntax must be migrated to a tracked `Future<void>` |

Here, "blocking" means **suspending a continuation**, never parking a bounded
actor scheduler carrier. `select {}` in the table means the braced syntax
family, not that an empty case set is valid. The final grammar rejects an empty
static arm set.

## Distinct return scopes (normative)

```ores
pub fnc read_or_exit(Channel<int> incoming): int {
  do select {
    when readch incoming: int value -> {
      if value < 0 then
        return -1;  // returns from read_or_exit
      fi
      stdio.stdout.write(value);
    }
    when cancelled: -> {
      return 0;     // returns from read_or_exit
    }
  }
  return 42;         // reached only when winning arm falls through
}

pub fnc read_value(Channel<int> incoming): int {
  const int v = select {
    when readch incoming: int value -> {
      return value + 1; // select-expression result, not read_value's return
    }
    when cancelled: -> {
      return 0;
    }
  };
  return v * 2;
}

pub async fnc read_later(Channel<int> incoming): int {
  const Future<int> pending = nb select {
    when readch incoming: int value -> {
      return value + 1;  // resolves pending, never returns from read_later
    }
    when cancelled: -> {
      return 0;
    }
  };
  return await pending;
}
```

These examples use the typed-arrow `Selectable` source syntax **target**.
Existing Java channel-specific select arms may use legacy
`when readch ch: val value { ... }` while migration occurs.

A void-result nonblocking expression still has an observable Future:

```ores
const Future<void> completed = nb select {
  when readch incoming: int value -> {
    log(value);
    return;
  }
};
await completed;  // propagate errors/cancellation; completion is not dropped
```

This design does not create an implicit detached fire-and-forget operation.

## Semantic audit and hardening requirements

1. **Static expression typing.** All reachable winning arms of `select`
   must yield compatible result types; infer one `T` or type-check against
   an explicit expected `T`. A bare `return;` requires `T=void`; use
   `do select` for synchronous procedural side effects.
   Do not silently discard returned values or type-erase heterogeneous arms.
2. **Procedural statement flow.** `do select` uses the caller's return
   type/effects and lexical loop targets. `return`, `break`, `continue`
   propagate through the winning arm, including after suspensions.
   A nested lambda's return still belongs to its own lambda, not its caller.
   A nested `do select` introduces no additional return boundary.
3. **Dynamic-source invariants.** The selected arm executes only after a
   single atomic winner commits; losing read/write/stream arms do not
   consume or publish data. Case operands are evaluated once in source order;
   use `Selectable<T>` operations from the cross-backend QoS contract.
4. **Future completeness.** `nb select` registers once and returns a live
   `Future<T>` without executing a guest arm inline. The Future settles
   **after** the arm body completes, including any nested suspension.
   The result, failure and cancellation have distinct terminal states.
   Arm-level `return T` resolves the Future but never the caller.
5. **Lifetime and ownership.** A deferred arm captures only values it can
   validly own until it executes. Non-Copy owned captures move at
   registration; lexical borrows/guards may not escape. Actor `self`
   re-enters only on that actor's single-flight execution lane.
6. **Failure and cancellation.** Arm failure must fail the returned Future
   exactly once (or follow an explicit actor terminal effect), not disappear
   into a fire-and-forget callback. Actor STOP/cancel removes registrations,
   releases credits/leases, and prevents late execution on a restarted
   incarnation; losing Future listeners are detached without cancelling
   shared producers. Unobserved failures must be surfaced by runtime policy.
7. **Scope and cleanup.** Every defer/finally runs exactly once, even for
   return/break/continue/raise/panic/actor STOP and cancellation races.
   A return from a winning `do select` cannot bypass a containing
   cleanup barrier. An arm cannot return through an already-ended stack.
8. **Progress and fairness.** No spinning/polling for readiness, no blocked
   native carrier, deterministic FAIR selection by default, optional
   explicit FIRST/RANDOM; one committed winner per selection.
   A `default` arm chooses immediately if nothing else is ready.
9. **Exhaustion.** All candidates retired/EOF without a winner must produce
   a typed `SelectExhausted` or explicit terminal result; never invent a
   payload or hang forever. Preserve `Option<T>`/event wrappers when a
   source's EOF needs to be observed as an arm result.
10. **Rejection/compatibility.** Bare static `select` statements and
    detached static `nb select` are transitional Java compatibility forms.
    Do not relabel them as implemented expression or Future semantics.
    Disallow `do nb select` in the canonical new syntax; migrate old users
    only after tracked completion and cancellation work is real.
    Continue to reject `do select from cases`: unhandled dynamic cases
    could consume data without processing their outcomes.

## Conformance matrix

Required tests across Java/GraalVM, LLVM and oreslang-format:

- parse four spellings, reject empty arms and canonical `do nb select`;
- `do select` bare/valued returns exit enclosing void/nonvoid functions
  and methods, including nested `do select` after suspension;
- wrong return types rejected; nested lambda return remains isolated;
- `do select` break/continue target lexically containing loops;
- defer/finally once on a returned, cancelled, raised or panicked winner;
- `select` arm return local to expression; compile rejects missing
  results and rejects silent discard;
- `nb select` returns `Future<T>` immediately but settles only after
  selected arm finishes, with no host-thread execution of guest code;
- `Future<void>` completion and error propagation; unwatched failure
  policy; source-level cancellation and ownership negatives;
- losing arms never steal data, double-commit a write, leak a subscription
  or invoke a continuation after stop/restart;
- fairness, quota, control-lane progress, no carrier blocking, and
  byte-identical cross-backend contract updates.

## Implementation and release guard

Java main currently has `Ast.SelectStmt` and a separate
`Ast.DynamicSelectExpr`. Static `select` and `nb select` still parse as
statements, so a declaration of `Future<T>` from static `nb select`
does **not** yet compile. The incremental Java change for blocking
`do select` propagates the caller's control flow instead of swallowing it.
The previous warning `W-SELECT-RETURN` is **no longer valid for blocking
`do select`**; it remains applicable only to the temporary legacy detached
`do nb select` implementation.

Implement typed static select expressions and Future completion with new
AST/IR, source-frame lowering and actor-owned continuations, then remove
the legacy statement ambiguity and retired warning path. LLVM must reject
unsupported source-level forms rather than claim parity. Keep feature PRs
draft until exact-source Maven/JDK25, AOT/hybrid, LLVM CTest + sanitizer
and formatter idempotence evidence are green.
