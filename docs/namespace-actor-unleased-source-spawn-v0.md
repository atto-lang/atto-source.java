# Namespace actor source-level spawn v0 — narrow unleased Graal path

**Status: experimental.** Stacked on Java #2972 → #2959 → #2947 → #2892.
This is the first intentionally restricted use of `spawn ActorType()` from
ordinary `.atto` source through `Context.eval` without the prior one-shot
trusted-host lease. It is **not** general namespace-actor lowering, compiler
parity, or an AMQP broker.

## Source contract

The canonical exercised source is
`contracts/namespace-actor-state-v1/unleased-source-spawn.atto`.
`NamespaceActorUnleasedSpawnV0` uses the production parser, verifies the
complete root AST, and requires one namespace, one declared shared actor,
a sealed schema with checked integer fields, private runtime ingress
`init/receive`, 1..8 checked `critical mtd` operations, and exactly one
`pub fnc main(): int`.

The admitted `main` body permits only:

- At most eight distinct immutable `const ref = spawn ActorType()`
  bindings with **zero constructor arguments**
- `ref.send(int-literal);` on a statically known, previously spawned ref
- Bounded `for (let i = 0; i < N; i = i + 1)` loops whose sole body
  statement is a checked literal send, with `1 <= N <= 1024`
- A terminal signed-i64 literal `return N;`
- At most 4,096 total statically enumerated mailbox sends

No dynamic method call, member capture, reflection, `super`, actor-state
access from `main`, mutable actor ref alias, async `await`, or
unverified critical/externals are permitted. Counter variables cannot shadow
actor refs. Any unverified source still falls through to the ordinary
fail-closed `TypeChecker`. The candidate text filter is **not** an admission
check: every admitted program goes through `Parser.parse` and complete AST
verification.

## Execution model

`OresLanguage.parse` creates a specialized Truffle root only for the
verified source subset. No caller-supplied AST, bearer descriptor, or Java
callback crosses from guest code. Each entry executes the source-defined
spawn and send actions on the context-owned `ActorRuntime`, with a new
exact-program `SharedCodeImageStore.CodeImage` per invocation.

The compiler's existing `VerifiedActorDescriptor` supplies the declared
namespace actor type, verified field layout and private critical-method
identity. Actual state is created per actor in `Owned` and mutated only
during actor mailbox turns. The root waits for bounded processing completion,
verifies immutable post-mailbox `StateReceipt` processed counts and actor
generation, and then retires both actors before revoking their code image.
The source `return` is an integer literal; no guest state or actor ref
escapes this restricted root.

The source effect/capability checker is invoked for the complete program,
and adversarial contexts remain prohibited in v0. This bounded bridge runs
on the caller, **not by blocking an ActorRuntime/CONTROL carrier**. It is
still a specialized Java-backed Truffle root, **not general Graal expression
nodes** for actor calls, typed async continuations, or actor reply channels.

## Evidence / nonclaims

`NamespaceActorUnleasedSpawnV0Test` executes a normal Polyglot
`Context.eval` against the canonical source. It verifies two actor
spawns and 1,001 real mailbox turns, repeated execution with fresh actor
incarnations, renamed source actors/namespaces, altered returned literal,
and strict rejection of unauthorized sends, direct external ingress,
mutable alias, state access, unknown actors, unbounded loops, loop variable
shadowing, dynamic send expressions and unverified critical effects.

The funded GitHub Actions carrier mirrors exact **individual Git blobs**
from the draft Java branch and runs the new seven-test suite alongside
the 79 previous namespace actor/Graal tests with nonzero executed steps.
The carrier **full Git tree differs from the Java PR source head**. It
does not certify exact-PR-head CI, LLVM, .NET, Wasm, NativeAOT, or broker
compatibility.

**Not yet delivered:** a guest-visible actor request/reply channel, source
`await` and true continuation lowering, general Graal AST actor nodes,
full ownership IR, physical guest heaps, actor hot upgrade parity, NIO2
`ConnectionActor`, AMQP publish/get/ACK or quorum durability.

## Next gate

Replace this closed-world Java interpreter with **actual compiler-generated
Truffle expression nodes** for admitted `spawn`, mail sends, async drain,
and compiler-checked actor output/request-reply, while preserving the
immutable code-image descriptor and private runtime-only actor ingress.
Test exact source PR heads and obtain independent approval before merging.
