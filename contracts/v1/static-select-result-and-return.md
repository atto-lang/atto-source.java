# Static select result, nonblocking completion, and procedural return

**Status (2026-11-25): updated normative target; incremental Java hardening only.**
This contract corrects the older "do select returns are arm-local/discarded"
interpretation. It does not claim that the static value/Future forms have
already been implemented in Java/GraalVM or LLVM.

## Canonical forms — 2026-11-25 superseding decision

Static select expressions are nonblocking by default. This replaces the
historical blocking value-expression target, but is not an implementation claim.

| Form (both braces and over/end) | Result | Return scope |
| --- | --- | --- |
| `select` / `select<T>` | Future<T> | selected arm |
| `await select` / `await select<T>` | T | selected arm; caller resumes after completion |
| `nb select` / `nb select<T>` | Future<T>, deprecated tracked alias | selected arm |
| `do select` | procedural statement, existing cooperative wait | enclosing callable / lexical loop |
| `do nb select` | legacy detached, not canonical | legacy callback |
| `try select` | immediate probe; result shape separately gated | separately gated |

Exactly one angle-bracket result type is allowed. The type is T, not
Future<T> unless a Future-valued result is explicitly requested. Ordinary
generic parsing applies to nested types. No implicit await/flattening,
no type-directed switching between blocking/nonblocking. Await requires a
checked suspension context. Default or ready arms still use tracked
continuations; completion follows arm cleanup, not merely arbitration.

```atto
suspends fnc read_value(Channel<int> incoming): int {
  val int value = await select<int> over
    when readch incoming: int n -> { return n * 2; }
  end;
  return value;
}

fnc read_later(Channel<int> incoming): Future<int> {
  return select<int> {
    when readch incoming: int n -> { return n * 2; }
  };
}
```

The canonical expression and the deprecated nb alias must have the same
AST/result/wait mode after normalization, with a migration diagnostic for
the alias. Historical bare blocking or detached statement syntax must not
silently change meaning; migrate explicitly. The existing do select
statement is retained as a separate procedural construct pending any
further decision about its spelling. Dynamic select-from migration remains
separately coordinated, rather than changing existing Option-valued APIs
without an edition/compatibility plan.

## Counted selection target

`select(n)` and `select<T>(n)` return Future<Array<T>>; ordinary await
returns Array<T>. The count is evaluated once before source registration.
A successful batch contains exactly n results, ordered by sequential
winner commit; zero is empty with no source effects. Bound count/bytes and
allow one pending round, retire one-shot Future aliases, advance fair
rotation, and fail rather than return a short array on exhaustion. Default,
write, try-count and do-count are initially unsupported. See the language
specification counted-select-v1 for lifecycle and streaming proposal.
This is not currently lowered by Java; explicit admission diagnostics
protect the unsupported boundary.

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
4. **Future completeness.** `select` (deprecated alias `nb select`) registers once and returns a live
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

- parse inferred and explicit select<T>, await and deprecated nb aliases in both delimiters; reject empty arms and canonical `do nb select`;
- reject wrong explicit result, missing result, implicit await, illegal await context, extra generic arguments and silent Future discard;
- `do select` bare/valued returns exit enclosing void/nonvoid functions
  and methods, including nested `do select` after suspension;
- wrong return types rejected; nested lambda return remains isolated;
- `do select` break/continue target lexically containing loops;
- defer/finally once on a returned, cancelled, raised or panicked winner;
- `select` arm return local to expression; compile rejects missing
  results and rejects silent discard;
- `select` and deprecated `nb select` return `Future<T>` immediately but settle only after
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
