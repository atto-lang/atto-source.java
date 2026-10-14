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
  selected arm completes the nearest typed `ARM_RESULT` boundary and
  does **not** return from the enclosing callable. This draft implements
  a restricted scalar slice with lexical ownership, cleanup and
  suspended SourceTask continuations; it is not general cross-backend
  conformance. Unverified cases fail closed. A direct `return match x
  over ... end` with no explicit `<ret=T>` now uses the containing
  callable's declared result type. Local assignments still require
  `<ret=T>` to avoid inheriting an unrelated callable type.
- `do match<ret=T>` is a **procedural** match with a contract on
  non-local `return` statements. It produces no match expression result:
  every reachable arm must explicitly return a value assignable to `T`.
  A `loop` without a proven return cannot satisfy this contract. The
  declared `T` must also be assignable to the containing callable's return type.
  `do match<T>` is parsed as a compatibility shorthand but emits a
  warning recommending the named spelling. An ordinary expression
  computed inside a do arm and not returned is discarded.

Without a typed return contract, `do match` is procedural.
Bare statement `match` and `switch` are rejected; use explicit `do`
or a result-bearing expression. `return do match` is invalid.
Value-producing `return switch` and braced `return select` remain
separate typed-ARM_RESULT integration gates, not synonyms for
procedural `do switch` and `do select`.
`await`/suspended continuation and cleanup correctness requires
exact-branch CI and JVM/native verification before release.

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

## Return versus finalization

A `return` directly in a `finally` body (including nested blocks and
conditionals in that same callable) is a compile-time error: it would
override or obscure the in-flight result/error. Returns inside an
independent callable created in `finally` are permitted, e.g.
`finally { (|| -> { return true; })(); }`.

For typed procedural matches, the compiler applies a stronger proof than
generic definite-return analysis: infinite loops and discarded callback
returns are not evidence of a Boolean return. Unlowered value-producing
matches are rejected in type checking **and** guarded at both synchronous
and suspended runtime entrypoints.

## Audit / certification status (2026-10-11)

**Java/GraalVM JVM: PASS on the exact original source/test Git trees.**
PR #634 source revision `a96afe733aa45d27921ef9af022237678c840d39`
passed [funded CI 38175134793](https://github.com/zed-pkg-test/java-app/actions/runs/38175134793):
**1,438 Maven tests; zero failures/errors/skips**, including 35 focused
pattern/typed-flow/finally-select cases. `src/main` tree
`f8ad81a190fc4fa4b57abbd0d24481d815e7418f` and `src/test`
tree `d79328c70263fe5880f2ed3d620514b3eb190a4a` were verified
before execution.

**Native image:** both GraalVM AOT and hybrid builds and their compiled
`--build-info` executable smoke checks passed on these trees in
[funded CI 38175134872](https://github.com/zed-pkg-test/java-app/actions/runs/38175134872).
This does not by itself prove execution of typed guest match syntax.
A stronger actual-guest match-hit/miss and invalid-finally check is separately
tracked in [native CI 38175463551](https://github.com/zed-pkg-test/java-app/actions/runs/38175463551);
do not claim it passed until its jobs have completed.

**LLVM restricted i64 scalar dispatch:** exact 179-file source tree
`da56380e9faf791ebeb12836fd126ac445c73482` passed 112/112
native CTests (including actual match fallback hit/miss, default aliases
and duplicate-case rejection), actor stress checks and field-layout
tests in [CI 38175273749](https://github.com/zed-pkg-test/java-app/actions/runs/38175273749).
LLVM does **not** yet implement general typed `match<ret=T>`, tagged tuple
payloads, ownership-aware union extraction, source `finally` lowering,
or suspended expression matching. The LLVM draft is significantly
diverged from current main and needs merge-tree admission testing.

**Finally and control flow:** a direct `return` in `finally` is
forbidden through nested `do select` arms as well as blocks and matches.
Unlike select arms, independently scoped lambda/callable returns are
allowed. The typed procedural return proof tracks fallthrough, return,
and break/continue escapes; nonterminating loops and unreachable returns
are not sufficient proofs of `do match<ret=T>`.

Formatter PR #32 separately passed exact-blob Rust tests and formatting
checks (funded workflow 38166794034). Keep source and compiler
integration PRs **draft** until branch merge-tree verification,
guest-level cross-backend conformance, independent review and unsupported
value-producing forms are resolved.
