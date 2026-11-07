# Proposal: separate value piping (`|>`) from actor-stream connection (`pipe`)

Status: **partial compiler implementation**. `|>` is implemented as parser desugaring to ordinary eager calls on this branch. `pipe` syntax is recognized and **intentionally rejected by the type checker** until the actor graph/port runtime contract exists. There is no user-visible streaming graph implementation yet.

## Operator meanings

| Expression | Semantics | Timing |
| --- | --- | --- |
| `value |> f` | `f(value)` | Eager function application |
| `value |> f(x, y)` | `f(value, x, y)` | Eager function application |
| `actorA pipe actorB` | Connect default output of A to default input of B | Persistent topology |
| `graph pipe actorC` | Extend the graph with a typed, bounded edge | Persistent topology |

Functional piping is **not polymorphic actor piping**: no overload based on the runtime values, no implicit message send and no `await`. `pipe` remains distinct even if operators are used in adjacent expressions.

### Eager `|>`: initial source contract

```ores
pub fnc add(int item, int increment): int { return item + increment; }
pub fnc triple(int item): int { return item * 3; }

pub routine main(): void {
    val int result = 3 |> add(4) |> triple; // 21
}
```

The lexer gives adjacent `|>` a single token, leaving `|` (integer bitwise OR / type unions / lambda delimiters) unchanged. Parsing is **left associative** and below boolean/arithmetic operators, above `?:` and assignment. The parser lowers each stage into an existing CallExpr (prepend the previous expression as argument 0), so type inference, borrow checking, call-site errors and async-call semantics follow the *normal* function call path.

The initial stage target must be a simple function name, a statically named member path (e.g. `module.transform`), or a direct call of one. Higher-order **computed callee expressions** such as `value |> factory()(x)` are rejected until the compiler has an evaluation-order-preserving `PipeExpr` lowering. In particular, `|>` never guesses between `f(value, args)` and `f(args)(value)` or applies automatic currying by comparing argument types. Use an explicit lambda when a different argument position or curried call is needed.

```ores
val int result = 5 |> add(1);             // add(5, 1)
// later, use a lambda for custom argument positions:
val int other = 5 |> |x| -> add(1, x);  // proposed; this branch initially
                                       // restricts pipe stage targets to names
```

Side-effectful callee factories are rejected precisely because plain call desugaring must not reorder callee/left-expression evaluation. Each accepted left-hand expression appears **once** in the lowered CallExpr; no repeated evaluation or hidden intermediate actor. No implicit await or parallel execution occurs.

### Persistent `pipe`: future ActorStream contract

```ores
// Conceptual syntax: StreamOperator and ActorStream are NOT implemented yet.
pub fnc build_pipeline(): ActorStream<Raw, Enriched> {
    return spawn Parser()
        pipe spawn Validator()
        pipe spawn Enricher();
}
```

`spawn` returns an `ActorRef` immediately. `ready : Future<void>` is the separate startup barrier. The planned graph construction will attach links and reserve endpoints before any stage can emit into the pipeline, then resolve `graph.ready` only after all stages are ready.

The keyword `pipe` is **reserved in infix-operator position**, not globally: existing variables or fields named `pipe` remain valid (the Rx library already uses such identifiers). The grammar treats `pipe` and `|>` as separate same-precedence, left-associative operators. If mixed, explicit parentheses are preferred.

Type admission is limited to `ActorRef<Stage<I,M>> pipe ActorRef<Stage<M,O>> -> ActorStream<I,O>` and graph extension/composition with exact compatible port types. Multi-input or multi-output stages require named ports. Endpoints are exclusively claimed; no accidental fanout of move-only values, unbounded buffers, or runtime type guesses. For a true source-to-sink graph, returning `ActorGraph` rather than fabricating external input/output ports is preferable.

### Runtime requirements before `pipe` can execute

- Explicit `StreamOperator<I,O>` trait and actor conformance metadata recognized by the type checker
- Bounded data links and outstanding write registrations; stable admission with source actor startup delayed until topology is wired
- Channel state/data ownership and move/borrow checks; one consumer per link, no implicit cross-tenant pointer sharing
- Graph lifecycle (`ready`, `done`, close/drain, cancel, supervision and predictable EOF/error propagation)
- For isolate/untrusted actors: serialization/copy of messages and generation fencing for hot-swapped workers
- Port edge/deadlock analysis, cancellation isolation, no orphan actors, fail-fast typed mismatch
- End-to-end tests with slow sinks, bounded queues, failures, restarts, backpressure and compiler JVM/native/hybrid/isolate execution

**Fail-closed gate:** `pipe` is parsed into an Ast.BinaryExpr with operator `pipe`; TypeChecker rejects every such expression with a clear message, rather than accepting it as a normal function call or pretending to create live edges. We will remove this gate only when the graph contract is implemented.

See also the more detailed actor-stream RFC on draft core [PR #540](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/540).
