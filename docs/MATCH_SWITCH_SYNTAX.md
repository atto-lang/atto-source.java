# Switch and match syntax compatibility (draft)

## Switch

Both `switch value { ... }` and `switch (value) { ... }` are supported.
The historical `switch value; case EXPR -> { ... } default -> { ... } end`
syntax remains a compatibility form.

Example:

```ores
fnc classify(string v): string {
  switch (v) {
    _ { return "star"; },
    case "foo", "car", "mar" { return "bar"; };
  }
  return "unreachable";
}
```

- `case` accepts comma-separated compile-time constants.
- `default` and `_` are aliases for **one** fallback arm.
- The fallback arm may appear anywhere in source, but is semantically considered **only after all explicit cases**. It never shadows a case written after it.
- Source order among concrete cases is preserved; **folded scalar-value duplicates** and a second fallback are errors. The compiler compares folded values rather than source-expression spelling; unsupported structured case constants are rejected until their compile-time-equality ABI is defined.
- Each branch body is a block. No implicit fallthrough.
- Optional commas and semicolons **after complete arm bodies** are not part of expression syntax and may be stripped by the canonical formatter.
- Switch labels must be provable compile-time scalar constants, checked against the subject type. Sync and suspended execution use the same guest `valueEquals` relation.

## Match

The two bracketing forms are:

```ores
match value over
  on is MessagePatterns.Reset as m { process(m); }
  on MessagePatterns.Delta m { process(m); }
end

match value over {
  on MessagePatterns.Reset as m { process(m); }
  on MessagePatterns.Delta as m { process(m); }
}
```

Canonical nominal binding prefers `on Type as value`. An optional `is` after `on` and a bare `Type value` binding are accepted for source compatibility.
Braced bodies do not require the historical `->` separator, which remains accepted.

A tuple payload may be bound as:

```ores
on MessagePatterns.Tuple as (left, right) {
  process(left, right);
}
```

Use `default` as the canonical match catch-all, without the `on` prefix.
`on _` and `on default` remain parser aliases. An optional
`default as m` (or `on _ as m`) binds the entire unmatched subject.
The binding has the subject's static type, **not** an implicit `Option<T>`.
Future refinements may narrow to a provably remaining closed-union variant,
but no unproved variant-member access is permitted.

Like switch, a designated default is evaluated only after all explicit
patterns, even if it occurs earlier in source. Explicit match patterns
still use first-match **source order**. There must be at most one default;
default cannot have a `when` guard (use a guarded named pattern instead).
Bare `default { ... }` does not introduce a binding named `m`.

The standalone `_` is reserved as a wildcard/discard and cannot name a
type, class, actor, callable, ordinary variable, or struct declaration.
The keyword `default` is reserved by the lexer. Prefix/suffix identifiers
such as `_cache` and `defaultHandler` remain valid. Sequence
destructuring continues to permit `_` in discard positions.

Here the tuple is the variant **payload**, not arbitrary fields of a
nominal object. Fixed tuple arity, recursive patterns, binding uniqueness,
and ownership/copy semantics must be proved by the type checker.

**Implementation boundary:** the new parser and AST recognize the tuple
binding, but the current runtime/typechecker can only destructure an actual
runtime Tuple. It **rejects nominal tagged-union payload extraction** until
variant representation and owned binary message lowering provide the correct
payload accessor. Do not treat this syntax as support for serialized actor
message variant envelopes yet.

Unlike `switch`, explicit `match` patterns are evaluated in source
order. A named binding pattern `on x { ... }` can shadow subsequent arms,
but `on _` is a default alias and is normalized to a fallback-only arm
regardless of its source position. The type checker requires matches
to be provably exhaustive.

## Typed match return contracts

The new grammar reserves these parameter forms:

```ores
match<bool> value over { default { return false; } }
match<ret=bool> value over { default { return false; } }
do match<ret=bool> value over { default { return false; } }
```

`ret` is the canonical *named* parameter. `match<T>` is positional
shorthand for `match<ret=T>`; `type=` is not a valid named parameter
because it could be confused with the matched subject's type.
Unknown/duplicate parameters fail parsing.

Two distinct contracts must **not** be conflated:

- `match<ret=T>` is **value-producing**. A `return value` inside its
  selected arm must end that arm and provide the match result, not return
  from the enclosing function. Its arms must exhaustively produce a
  `T`; using it as a statement should eventually warn about unused
  match results. **This lowering is NOT implemented in the current
  Java/GraalVM production source. The parser and AST accept the syntax,
  but the type checker rejects it with an explicit fail-closed error
  rather than silently using non-local function returns.**
- `do match<ret=T>` is a **procedural** match with a contract on
  non-local `return` statements. It produces no match expression result:
  every reachable arm must return a value assignable to `T` (subject
  to future proof of throwing/non-returning control flow). The declared
  `T` must also be assignable to the containing callable's return type.
  `do match<T>` is parsed as a compatibility shorthand but emits a
  warning recommending the named spelling. An ordinary expression
  computed inside a do arm and not returned is discarded.

Without a typed return contract, `do match` retains the historical
statement-level dispatch behavior. Do not infer that unannotated plain
`match` has already been converted into a value-producing expression
on all execution backends.

`return do match`, typed `return match`, exhaustive inference,
`await`/suspended continuation handling, and cross-backend LLVM/WASM
lowering must be validated before declaring general expression-match
support.

## Formatter

One canonical style: two-space indentation, braces for compact examples,
preserve the author's arm ordering, remove superfluous arm separators, and
keep commas separating case constants or tuple-pattern components.
Never reinterpret punctuation in strings, comments, or nested expressions.

Future work: canonicalize optional `is` and implicit nominal binders to
`on Type as value` in the formatter once parsing round trips are verified;
rewrite fallback aliases in legacy `end`-delimited syntax;
unify the typed variant payload representation between Java/GraalVM and LLVM;
test catch-all placement, narrowing, arity, and exhaustive-return statements.

## Audit / certification status (2026-10-11)

Formatter PR #32: private funded exact-blob CI passed `cargo test --all-targets` and `cargo fmt --check` (see gha-indie-worker/gha-indie-worker-docs Actions run 38166794034). Java parser in PR #634: exact four source Git blobs compiled with JDK 21 and 23 parser/AST smoke checks passed in the same run. The whole Java Truffle/typechecker/runtime, LLVM, actor mail variant payload extraction, and source organization's current-head CI have **not** been certified by this smoke test. Keep implementation PRs draft until those gates execute and pass.
