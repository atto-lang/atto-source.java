# Option/Result composition (Java v0, draft)

Status: draft implementation awaiting exact-tree compiler/runtime tests and
ownership/effect integration audit. This is **not** general tagged-sum
construction, a general Monad typeclass, or LLVM parity.

## Source surface implemented on this branch

For *scalar and recursively scalar sum payloads*:

```oreslang
val Option<int> doubled = Some(3).map<int>(|int x| -> {
  return x * 2;
});
val Option<int> chained = doubled.and_then<int>(|int x| -> {
  return Some(x + 1);
});

val Result<int, String> input = Ok(3);
val Result<int, String> checked = input
    .map<int>(|int x| -> { return x + 1; })
    .and_then<int>(|int x| -> { return Ok(x * 2); });
val Result<int, String> renamed = checked.map_err<String>(|String error| -> {
  return error;
});
```

Every callback runs **synchronously** in the caller's current execution
turn, exactly once on the matching sum variant, and not at all otherwise.
`map` wraps the callback's output in `Some` or `Ok`;
`and_then` requires the callback itself return an `Option` or `Result`;
`map_err` runs only for `Err`. Callback failures propagate. These
operations do not affect Future callback scheduling or actor turns.

V0 requires one explicit output type argument, e.g. `map<int>`, so
block-bodied lambdas cannot silently synthesize `Option<Unknown>`.
A future phase may infer result types with a sound return-flow proof.

## Deliberate fail-closed limitations

- Only scalar payloads (including recursively scalar Option/Result) are
  admitted. Lists, records, class instances, borrows, capabilities, and
  unproved generic owners fail closed until consuming ownership semantics
  and exact active-variant drops exist. There is no implicit deep copy.
- Callback values must satisfy a statically checked one-argument function
  signature with the requested result type.
- Source callbacks can still carry effects; **this PR does not implement
  transitive pure/effect-row checking**. Do not describe the combinators as
  automatically pure. Once `with` effect upper bounds are enforced, callback
  effects must propagate rather than disappear behind this primitive.
- The receiver's composite generic arguments must already be known.
- Aggregating helpers (`zip`, `map2`, `traverse`, `sequence`), arbitrary
  tagged enums, full typeclass instances, subscriptions, actor transitions,
  and generalized monads are **not** implemented by this PR.
- Future/Task and trap behavior are unchanged.

## Review / CI gates

```sh
mvn -B -ntp -Dtest=OptionResultCompositionLanguageTest,OptionResultSemanticsTest,FutureCompositionLanguageTest test
mvn -B -ntp verify
```

These are **requested checks**, not claims of passing runs. Reviewers
should also examine mutable value or closure capture, lambda effect escape,
callback mismatch diagnostics, source-level `map<T>` parsing, and both
execution profiles before ready/merge. Keep this PR draft until these gates
are green on an exact Git tree. Mirror privately to a funded CI org only
with explicit authorization and identical verified Git tree SHA.
