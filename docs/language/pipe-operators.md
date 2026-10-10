# Oreslang: functional `|>` versus actor-stream `pipe`

**Status (2026-10-10):** `|>` is implemented as ordinary eager function
application. `pipe` is a distinct contextual infix operator. The compiler
now checks `StreamOperator<I,O>` actor port declarations, `self.send`
output types, and adjacent input/output compatibility. A type-correct graph
still **fails compilation intentionally** until bounded transport, actor
startup, cancellation and graph lifecycle are implemented. The interpreter
also rejects unchecked `pipe` ASTs before evaluating either stage: malformed
or bypassed compiler calls cannot spawn orphan actor operands.

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

## Statically checked actor stage ports (not executable yet)

```ores
define actor Parse implements StreamOperator<String, int> as
    receive(ActorMail<String> mail): void {
        self.send(42);
        return;
    }
end

define actor Validate implements StreamOperator<int, bool> as
    receive(ActorMail<int> mail): void {
        self.send(true);
        return;
    }
end

// Type-compatible, but rejected until ActorStream runtime is implemented:
const graph = spawn Parse() pipe spawn Validate();
```

The marker is legal only on actors and requires exactly **two explicit
types**. The declared input must match the actor's inbound
`receive(ActorMail<I>)` payload. `self.send` must match the declared
output; `StreamOperator<I,void>` stages cannot emit a value. A bare
`ActorRef<T>` without the marker cannot be used as a pipe stage.
`ActorStream<I,O>` is a graph type for future composition, **not a
sendable message payload**, and cannot be forged into a running graph by
declaring a variable with that type.

These checks do **not** implement graph creation or transport session
adapters. `define http actor`, `define tcp actor`, and
`define websocket actor` still require native, connection-aware adapters;
a generic actor `pipe` cannot convert them into listeners.

## Required transport contract before actor pipes execute

A stage must declare its input **and** output element types. The current
`ActorRef<T>` identifies the actor class and supports typed
`receive(ActorMail<I>)`, but its `ActorOutputStream` element type is not
currently inferred from actor code, and direct `self.send(...)` can
fail when a bounded output buffer fills. Therefore connecting
`ActorRef` outputs to another actor's `send` is *not* sufficient to
implement dataflow or backpressure.

The first executable actor composition pass must retain the **implemented**
compile-time `StreamOperator<I,O>` port and compatibility checks and add:
1. Bounded, suspension-aware output and mailbox admission: a full downstream
   stage must apply backpressure upstream rather than failing or dropping.
2. A graph-owned lifecycle with ready, done, end-of-stream, cancellation,
   and failure propagation, with ownership distinction for shared stages.
3. A safe copy/transfer path across PRIVATE/UNTRUSTED actor boundaries;
   actor references and mutable capabilities must not silently cross.
4. An explicit ordering/concurrency policy: one native carrier at a time may
   execute an actor, but several message continuations may interleave at
   suspension boundaries.
5. Tests for composition chains, type mismatch, bounded flow, early stop,
   cyclic pipelines, teardown, and actor failures.

There must be no automatic `await spawn` insertion: `spawn T()` already
produces a typed actor reference, and startup readiness remains an explicit
future/lifecycle boundary.

Until these contracts exist, Oreslang rejects actor `pipe` expressions in
`TypeChecker` with a diagnostic explaining the missing transport guarantees.
This is deliberate fail-closed behavior, not a complete actor-pipeline API.
