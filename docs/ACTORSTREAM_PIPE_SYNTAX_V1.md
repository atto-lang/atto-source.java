# ActorStream syntax gate (experimental)

Supported spelling targets:

```oreslang
return spawn Source() pipe spawn Transform() pipe spawn Sink();
return ActorUtils.pipe(spawn Source(), spawn Transform(), spawn Sink());
return ActorUtils::pipe(spawn Source(), spawn Transform(), spawn Sink());
```

`::` is the preferred explicit spelling for static functions when a
namespace/class resolution could be ambiguous. It maps to static namespace
member resolution rather than dynamic actor messaging.

All forms construct a bounded, compiler-validated `ActorStream` (2..16
distinct actor capabilities, shared exact closed nominal ActorMail contract).
The runtime binds the full route before any DATA ingress, with one outstanding
round trip: stage 0 -> ... -> stage N-1 -> ... -> stage 0. `ActorStream.send`
sends to the source and `ActorStream.get_done()` observes its physical
completion. Actors run on their own exclusive scheduler turns; stage grouping
does not permit concurrent native execution inside one actor.

The 15-actor fixture sends 14 forward and 14 backward typed hops, rejects
direct middle-stage ingress, duplicate registrations and early STOP through
the same scheduler authority checks as the previous three-stage route.
This gate is Java-first: native LLVM same-byte source acceptance and FD3
runtime attestation are separate release requirements.

## Static namespace disambiguation

`Utility::answer()` and `ActorUtils::pipe(...)` resolve static
namespaces *even if a local variable named `Utility` or `ActorUtils`
exists*. This is enforced in the parser/typechecker and runtime with an
internal, non-source static-namespace receiver; it never calls a shadowing
instance's method. `Utility.answer()` uses ordinary lexical/instance
lookup, and is therefore ambiguous if the value name shadows the class.

## Physical completion gate

`ActorStream.get_done()` is a **whole-route** barrier: success requires
both the completed `2*N` phase transition and physical DONE settlement
from **all N actors**. A failed stage, an aborted route, or an incomplete
roundtrip rejects the observer without treating source-only termination
as sufficient evidence. Cancelling an observation cannot cancel the actors.

The pipeline remains a single-flow construct: only one root DATA ingress
may complete. Replaying a root message or sending directly to a middle
actor is not equivalent to `ActorStream.send`.

## Symbolic `|` actor route (stacked experimental change)

A direct `spawn A() | spawn B() | spawn C()` chain is parsed into the **same**
`pipe` actor-graph AST as `spawn A() pipe spawn B() pipe spawn C()`.
This is a narrowly scoped syntax alias for *spawn-rooted* chains only;
`ActorRef` identifiers separated by `|` are not admitted as actor routes yet.
Ordinary integer bitwise `|`, lambda delimiters, and `|>` value piping retain
their existing behavior. An actor chain with a scalar stage is rejected by
type checking before graph ingress. The JUnit guest execution regression
reuses the same 15-actor forward/backward fixture and expected 32 receipt for
both spellings. Tests are **authored, not yet executed on this new head**;
source-guest parity with `emit`, bounded one-way actor pipelines and other
backends remains separate work. Do not treat the playground preview as proof.


## Named ActorRef symbolic-pipe follow-up (stacked, verification pending)

`|` must work on a statically typed `ActorRef<T>` binding as well as inline
`spawn` expressions. The type checker detects an actor pipe by the leftmost
stage's static `ActorRef<T>` type, then validates all 2..16 stages using the
same closed nominal ActorMail contract as keyword `pipe`. Mixed
`refA | refB pipe refC` chains form **one** atomic graph binding; no
intermediate two-stage pipeline is created. Numeric `5 | 2`, including
numeric local variables, remains bitwise OR; `||`, `|>`, and lambda
delimiters are separate constructs.

The three-actor Java guest regression uses real `self.forward` and
`self.backward` round-trip completion (not unimplemented `emit`). It sends
41 through the symbolic route and 42 through the keyword route; the Sink
prints 42 and 43 respectively. Each route awaits `ActorStream.get_done()`
so success requires physical completion. The source fixture is authored,
but **not proven by CI** until the exact-head jobs execute nonzero steps.
This implementation retains the runtime's single-flow, forward/backward
phase contract; general one-way broadcast/emit semantics are out of scope.
