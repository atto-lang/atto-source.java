# Namespace actor scalar critical v0 — compiler-internal, fail-closed

**Status:** experimental internal compiler/runtime slice; **not** general
AttoLang guest lowering or production actor state/critical method support.

## Canonical source

The executable fixture is
`contracts/namespace-actor-state-v1/critical-scalar-turn.atto`, mirrored
byte-identically into AttoRMQ
`examples/native-namespace-critical-scalar.atto`.

```atto
use namespace foo;
declare actor Probe;
define state on actor Probe as {
    let int count: 0;
}
external mtd (Probe p) init(): void {
    return;
}
critical mtd (Probe p) add(int delta): void {
    p.state.count = p.state.count + delta;
}
external mtd (Probe p) receive(ActorMail<int> mail): void {
    p.add(mail.value);
    return;
}
```

## Precisely what is implemented

- Parser retains a dedicated `criticalMethod` AST flag rather than
  turning a critical method into ordinary/public/runtime-ingress dispatch.
  Compatibility constructors keep legacy unmarked AST nodes unprivileged.
- Static receiver binder rejects public critical methods and attempts to
  declare runtime-only `external mtd init/receive` as critical.
- Package-private `NamespaceActorScalarCriticalCompiler` accepts exactly
  the source AST graph above, with a single signed-i64 literal field and
  no arbitrary statements, dynamic functions, methods, modules, contracts,
  borrowed mail, source effects or additional state fields.
- The existing ActorRuntime owns mailbox FIFO, per-actor incarnation and
  exclusive physical mailbox dispatch. A private actor-owned Java `Owned`
  object is allocated on the actor initialization turn; no process-global
  counter or host-owned mutable broker state is used.
- The compiler creates a private temporary `Permit` only **after** checking
  actor identity, code image, mailbox envelope and active actor execution.
  Its mutation method enforces actor identity, runtime, carrier and live
  permit; `finally` revokes the permit at the end of the critical call.
- Arithmetic is checked with `Math.addExact`, so overflow fails the
  responsible actor, not a sibling, without silently wrapping a counter.

## Verification and limits

`NamespaceActorScalarCriticalCompilerTest` exercises a thousand
serialized increments, independent actors, exact overflow boundary,
malformed mail, forged entrypoint invocation, rejected noncritical/direct
writes, changed arithmetic and call sites, invalid schema/contract and
normal TypeChecker rejection. Separate return-only bootstrap and stale
actor generation regressions remain in the same CI carrier.

This is an **actual Java ActorRuntime execution** of a compiler-recognized
closed source pattern. It is **not** an ordinary Graal guest execution of
`declare actor`, not native-image/AOT, LLVM, general critical MIR,
a persistent actor arena, or evidence that other actor state
transformations are safe. The program is not accepted by the normal
`TypeChecker` for general runtime execution; it must stay that way
until source/manifest provenance, typed IR and borrow/loan checks exist.

The funded carrier `voxletra/voxletra-e2e` mirrors and asserts exact
individual source/test/runtime blobs; its whole Git tree is different
from Java PR #2892. Approval and a full-head executed CI gate are
mandatory before merging.

## Executed exact-file regression receipt (November 11, 2026)

[Funded carrier run #43151720757](https://github.com/voxletra/voxletra-e2e/actions/runs/43151720757)
at head `814558eeefc25d404c18ede17abfa0dac810bd04`, tree
`d7978afebe9795b913f30c325be8f1b3ee9c9670`, executed
**56/56 JUnit tests, zero failures/errors/skips** with nonempty CI steps:

- Scalar critical compiler/runtime: 7
- Return-only namespace bootstrap: 6
- Ingress parser/static binder admission: 11
- Attached-state schema/source provenance: 19
- Namespace actor header admission: 13

Git object checks proved **individual** source/test/runtime/fixture blobs
before Maven executed. They did **not** prove source PR-head tree identity.
The normal guest code generation, general critical MIR, Native Image,
LLVM parity and actual AMQP publish/get/ACK remain **NOT_EXECUTED**.

AttoRMQ's [PR #14](https://github.com/atto-poc/atto-rmq-clone.atto/pull/14)
contains the byte-identical critical scalar fixture. Its
[exact-head Rust/Go run #43151304374](https://github.com/atto-poc/atto-rmq-clone.atto/actions/runs/43151304374)
passed, but those reference/source tests are not guest execution.

## Next P0

Replace this closed hand-recognized source pattern with a general
compiler-verified ownership/critical effect IR, paying special
attention to nested method calls, re-entrant suspension, cancellation,
state permit escape, actor generation changes, and independent
native/Graal/LLVM execution. Only then wire AttoRMQ ConnectionActor and
ChannelActor to NIO and the one-message publish/get/ACK acceptance test.
