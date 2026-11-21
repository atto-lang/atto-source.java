# Explicit `external` actor lifecycle marker — Java static admission

**Scope:** parser/AST/nominal-binder stage on top of the cross-file receiver method stack. NOT a runnable guest actor entrypoint. Reference contract: https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/707

## Supported syntax in this branch (static-only)

```atto
namespace example;
declare actor Worker;
define external mtd (Worker w) init(): void { return; }
define external mtd (Worker w) receive(ActorMail<int> mail): void { return; }
```

`external` is contextual before out-of-line `mtd` after `define`. It is stored as `ExternalActorMethodDecl.runtimeEntrypoint()`. The existing unmarked `define mtd` spelling remains an ordinary, non-entrypoint method attachment and is retained for backward compatibility.

The nominal binder accepts runtime role metadata **only** for private `init`/`receive`, rejects public lifecycle entrypoints, rejects raw actor-owner returns from init, rejects arbitrary `external` RPC methods and prevents multiple lifecycle slots differing only by arity. Existing `receive` must accept exactly one `ActorMail<T>` and return `void`. No runtime capability or direct method invocation is granted by this metadata.

**Hardening:** canonical `declare actor Worker;` requires an explicit `external` marker on `init` and `receive` (`ACTOR_EXTERNAL_REQUIRED`). Other ordinary `mtd` declarations may remain unmarked. Existing `define actor Worker as receiver;` and inline actor forms preserve their migration behavior. An `external init` can currently return only `void`: a user-defined nominal `state` return is rejected with `ACTOR_INIT_STATE_BUILDER_UNVERIFIED` until the compiler proves genuine builder provenance, sealed ownership and atomic publication. Out-of-line source unit IDs now reject Windows colon/alternate data streams, Unicode format controls including bidi/zero-width markers, invalid path traversal and non-normalized separators.

## Deliberate limitations

- No actor body `external init` or `external receive` grammar yet; inline class/actor method syntax is a separate AST integration step.
- No `init` bootstrap bridge or atomic state publication; reconcile with Java #2256. Existing `on_start` remains the legacy hook and cannot silently double-run with a new `init`.
- No mailbox turn lowering; reconcile with #2822's private receive guard and with #2786's actor-contract private hook rules.
- No real external `mtd` execution. TypeChecker must still reject with `EXTERNAL_ACTOR_RECEIVER_LOWERING_UNSUPPORTED` before any guest invocation or AOT artifact.
- No cross-file package provenance or contract witness integration beyond parent #2823's caller-supplied static source set; reconcile with #2813 and #2792.
- The ABI must eventually encode entrypoint-role metadata, effect and generation scope in both the shared IR and every backend; this PR does not establish parity.

## Compiler-gate hardening

The same Java draft now prioritizes the external-receiver fail-closed diagnostic
when a source unit contains an out-of-line external method, regardless of
whether the actor declaration appears before or after it. A declared actor with
no external methods remains rejected by `STANDALONE_ACTOR_LOWERING_UNSUPPORTED`.
This removes source-order-dependent error reporting without enabling guest
execution or relaxing the runtime admission boundary.

The three new JUnit suites contain **64 authored test methods** (14 external,
17 canonical declaration, and 33 adversarial hardening cases). The integrated
head now also carries the original #2822 receive-ingress compiler checks
(including `IncrementalCompiler` imported ACTOR metadata), 13 source-level
JUnit receive ingress cases, one **real inline actor mailbox guest execution**
JUnit test, 15 Atto fixtures, and #2256's executable native-Java
`ActorInitPublicationGate` staging component plus 10 gate JUnit cases.
These 20 files were copied within the same private source repository by their
unchanged Git blob SHA (no cross-org mirroring).

The source-org exact-head JDK 25 workflow now requires **nine actual
Surefire suites**, a total of at least **88 authored test cases**, no skipped,
failed or errored tests, and the real inline-mailbox guest output assertion.
The new guest test is for existing **inline class-like actors only**.
The staged one-shot gate is still not wired into out-of-line `init` lowering.
Neither establishes executable `declare actor` behavior. All nine suites
remain unexecuted: the exact-head workflow run #43105295416 concluded failure
with no runner and zero steps. Do not promote these tests as passes.

## Evidence required before integration

Run the nine-suite exact-head JDK 25 gate
(`.github/workflows/actor-declare-external-jdk25-exact-head.yml`)
on an admitted source-org runner. Fix any real compilation, parser/binder,
receive guard, import-linker, host gate or inline-guest failure. Then
integrate **typed out-of-line actor state, runtime-only external method
dispatch and generation-bound actor ownership** with the existing
`ActorRuntime` actor dispatcher and prove real `declare actor` guest
startup/receive. Complete Java no-fallback Native Image and independently
port LLVM/.NET/Wasm/Forge. Treat source-only inventory and zero-step Actions
as insufficient.

Private source must not be mirrored to an unrelated organization without independently authorized exact-head publisher/carrier protection and review.

Canonical out-of-line declarations now use `declare actor Worker;` (`declare pub actor Worker;` for public nominal identity); the old `define actor Worker as worker;` is compatibility-only. `define actor Worker as ... end` continues to mean inline implementation. This draft supports only plain declared actor identities; implementation and `implements` metadata remain independently gated.
