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
- Source order among concrete cases is preserved; duplicate constants and a second fallback are errors.
- Each branch body is a block. No implicit fallthrough.
- Optional commas and semicolons **after complete arm bodies** are not part of expression syntax and may be stripped by the canonical formatter.
- Existing runtime/compiler type checking of constant cases is retained.

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
