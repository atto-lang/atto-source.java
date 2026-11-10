# AttoLang v1: value blocks, ternary expressions and integer ranges

Status: **implementation under review**; no cross-backend parity claim without executed exact-head compiler-backed guest tests.

## Grammar

```ebnf
range_expression = logical_or_expression, ((".." | "..="), logical_or_expression)?;
ternary_expression = range_expression, ("?", expression, ":", ternary_expression)?;
value_block = "{", { statement }, expression_without_trailing_semicolon, "}";
for_in = "for", [ "const" | "val" | "let" ], identifier, "in", expression, loop_body;
```

* `..` means **start-inclusive/end-exclusive**. `..=` includes both endpoints.
* The integer v1 range admits signed `int` endpoints; negative/descending ranges iterate zero times. Endpoint expressions execute **once in order** upon creation.
* Integer ranges are constant-space, bounded by i64, and their final step **must not overflow** (including `i64::MAX` inclusive).
* `for id in 1..1024` defaults to an immutable `const` per-iteration binding. `for let id in ...` requests a mutable binding. `for ... of ...` remains available for compatibility.
* Ranges are first-class values with type `Iterator[int]`, and only the iterator type, not a materialized list, is exposed.
* `?:` conditions are Boolean, arms are lazy, and nesting is right-associative. Ordinary `if` stays statement-oriented.
* `{ ... final_expr }` evaluates prefix statements in a distinct lexical environment and produces exactly the final **unterminated** expression, not a function return.
* `{ ... last_expr; }`, `{}`, and prefix `return` are invalid as value blocks. A block does not confer permission to `break`/`continue` an enclosing loop.
* Both branches must satisfy contextual type and ownership checking. A borrowed value cannot escape a value block until a loan-lifetime proof is implemented.
* Cancellation, backpressure and safepoints use the same iterator loop mechanisms as existing `for ... of ...`. A branch with `await` must preserve its scope across suspension and perform cleanup exactly once.

## Example

```atto
for id in 1..1024 {
    val successors = id eq 1023 ? {
        []
    } : {
        [id + 1]
    };
}
```

This grammar intentionally does **not** adopt Rust's expression-oriented `if`.
It also does not adopt Rust's `vec!` macro syntax; AttoLang uses typed collections and `[]` literals.

## Verification gates

1. Lexer parity: `..`, `..=`, `...`, decimal literals; no accidental dot/namespace regressions.
2. Parser positive/negative: range nesting, malformed chains, correct braces, missing tail, nested ternaries.
3. Type checker: bool condition, typed endpoints, lexical escapes, ownership, disallowed borrowed results, common branch types.
4. Java guest runtime: exact file `tests/conformance/ternary_blocks_ranges.atto`; inclusive max, empty ranges, selected branch only, resulting values.
5. Suspension: selected block `await`, cancellation cleanup, no duplicate continuation delivery.
6. Cross-backend compilers (.NET NativeAOT, LLVM, Wasm, Forge/Cranelift, JS): separate **exact-head executed guest receipts** required.
7. Formatter: AST-preserving brace/semicolon rules, idempotence, strings/comments untouched. Formatter-only success is **not** compiler proof.
8. CI with `steps=[]`, `steps=null`, or `runner_id=0` is **runner admission failure**, not execution evidence.

No adoption or merge claim is made solely by this design note or source fixtures.
