# Oreslang: functional `|>` versus actor-stream `pipe`

**Status (2026-10-10):** `|>` is supported by the parser as ordinary function
application. `pipe` is recognized as a contextual infix operator, but
actor-stream connections currently **fail compilation intentionally**.
Do not represent `pipe` as an executable stream graph until the transport,
static type, ownership and lifecycle contracts are complete.

## The two operations

```oreslang
// Eager synchronous function application.
const result = input |> parse |> validate |> enrich;

// Future actor-stream construction; NOT EXECUTABLE YET.
const graph = spawn Parser()
    pipe spawn Validator()
    pipe spawn Enricher();
```

`|>` passes the expression on the left as argument zero of the function on
the right. For `value |> func(x, y)`, the parser builds
`func(value, x, y)`. For `value |> func`, it builds `func(value)`.
Both operators associate left and bind less tightly than logical/arithmetic
operators but more tightly than ternary `?:`. The parser lowers `|>`
into ordinary `Ast.CallExpr` nodes so type checking, ownership rules,
invocation and compiler optimization use the established callable semantics.

`pipe` is **not** an alias for `|>`. It builds a distinct
`Ast.BinaryExpr("pipe", left, right)`. It is a contextual keyword:
`pipe` remains legal as a normal identifier in positions other than the
infix operator. It must never be interpreted as eager function application.

The existing `|`, `||`, and `|...| ->` tokens and precedence are
unchanged. The lexer emits a dedicated `PIPE_FORWARD` token for `|>`
rather than merging it with bitwise or lambda delimiters.

## Required transport contract before actor pipes execute

A stage must declare its input **and** output element types. The current
`ActorRef<T>` identifies the actor class and supports typed
`receive(ActorMail<I>)`, but its `ActorOutputStream` element type is not
currently inferred from actor code, and direct `self.send(...)` can
fail when a bounded output buffer fills. Therefore connecting
`ActorRef` outputs to another actor's `send` is *not* sufficient to
implement dataflow or backpressure.

The first executable actor composition pass must add:

1. A nominal `StreamStage<I,O>` contract (or equivalent explicit ports),
   and reject ambiguous/multiple implicit ports.
2. Compatible input/output type unification at every connection.
3. Bounded, suspension-aware output and mailbox admission: a full downstream
   stage must apply backpressure upstream rather than failing or dropping.
4. A graph-owned lifecycle with ready, done, end-of-stream, cancellation,
   and failure propagation, with ownership distinction for shared stages.
5. A safe copy/transfer path across PRIVATE/UNTRUSTED actor boundaries;
   actor references and mutable capabilities must not silently cross.
6. An explicit ordering/concurrency policy: one native carrier at a time may
   execute an actor, but several message continuations may interleave at
   suspension boundaries.
7. Tests for composition chains, type mismatch, bounded flow, early stop,
   cyclic pipelines, teardown, and actor failures.

There must be no automatic `await spawn` insertion: `spawn T()` already
produces a typed actor reference, and startup readiness remains an explicit
future/lifecycle boundary.

Until these contracts exist, Oreslang rejects actor `pipe` expressions in
`TypeChecker` with a diagnostic explaining the missing transport guarantees.
This is deliberate fail-closed behavior, not a complete actor-pipeline API.
