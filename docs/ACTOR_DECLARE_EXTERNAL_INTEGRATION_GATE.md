# `declare actor` + `external init/receive`: integration and execution gate

**Gate status: NOT PROMOTABLE.** This document records implementation
dependencies and exact-head evidence rules. It does not confer runnable actor
or mailbox authority on the static AST/binder.

## Fixed nominal contract

```atto
namespace vault;
declare actor Vault;
define external mtd (Vault v) init(): void { return; }
define external mtd (Vault v) receive(ActorMail<int> mail): void { return; }
```

- `declare actor` creates a single nominal identity, **not** a runnable
  empty class, forward `declare type` or unrestricted actor reference.
- Exactly one lifecycle bootstrap selector per incarnation: `init` or
  legacy `on_start` on migration actors, **never both**. Canonical
  `declare actor` rejects legacy `on_start` entirely.
- `init` and `receive` on a canonical declaration require an
  explicit `external` marker. They are private to guest code.
- The only currently admitted `init` result is `void`. A user-defined
  type called `state` is not a compiler-owned state builder.
- `receive` must be private `(ActorMail<T>): void`; nothing about
  nominal method attachment permits a guest to call or capture it.
- `declare actor Vault implements Contract;` is **not** accepted yet.
  Do not drop or fabricate typed contract witness metadata.

## Integration dependencies (all draft / independently unverified)

| PR | Scope and source overlap | Gate |
| --- | --- | --- |
| [#2823](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2823) | This PR is stacked directly on exact head `fd84c6956f657c699e91b112476e506b1c7515cd`: `Parser.java`, `ActorReceiverBinder.java` | Preserve receiver-first and old-name-first forms; verify source-set provenance |
| [#2256](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2256) | `Ast.java`, `Parser.java`, `TypeChecker.java`, `ActorRuntime.java`, `OresEvalRootNode.java` plus startup gate | Must reconcile reserved inline `init` with out-of-line `external init` rather than bypass both; prove atomic state publication |
| [#2822](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2822) | `TypeChecker.java`, `IncrementalCompiler.java` | Preserve rejection of direct, imported, captured, inherited and aliased actor `receive` |
| [#2813](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2813) | `PackageNamespaceBuild.java`, `ActorMethodIndex.java` | Authenticate actual package/module membership before using `bindSourceSet` metadata |
| [#2792](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2792) | `Ast.java`, `Parser.java`, `TypeChecker.java`, `IncrementalCompiler.java` | Add `implements` only with checked, unique method witnesses across files |

`Ast.ExternalActorMethodDecl.runtimeEntrypoint()` is currently source metadata
only. Executable lowering must introduce a typed internal role/capability in
HIR/MIR and invoke it exclusively in actor-owned runtime paths. The current
`TypeChecker` correctly aborts before guest execution.

## Exact-head source-org static and inline guest verification

A dedicated read-only workflow exists at
`.github/workflows/actor-declare-external-jdk25-exact-head.yml`.
On same-repository PRs, it checks out the literal immutable PR head
with no persisted credentials, verifies commit/tree/POM/parser/binder blob
identities, requires GraalVM JDK 25 and runs **11 JUnit suites**:
six declaration/binder suites, the 13-case `ActorReceiveIngressBoundaryTest`,
`ActorReceiveIngressGuestExecutionTest` (real inline mailbox guest),
the 10-case `ActorInitPublicationGateTest` native host model,
the 18-case `ActorSourceLifecycleGateTest` actor-cell admission suite,
and the 14-case `StatelessDeclaredActorExecutionTest` (including a real
**opt-in stateless** `declare actor` guest program). The exact-head
workflow now requires at least **176** executed, unskipped Surefire cases (all 11 classes are checked against their exact authored case counts),
and prints exact source/git tree receipts. This is NOT 120 executed cases
until a runner starts and Maven finishes.

A *separate isolated local check* compiled the exact gate source blob
`f338b808161133e2d9248ed8b9cd12a8dc54d4ab` using Java 21 with
a narrow `ActorRuntime` API stub and executed 18 assertions. That checks
only the actor lifecycle gate itself; it is NOT the production Maven,
GraalVM, Java25, guest, Native Image or backend parity result.

`OresCompiler.parseAndTypeCheckStatelessDeclaredActors` is an explicit
opt-in prototype that lowers a fully bound, **stateless same-source** actor
with inert `external init` and private typed `external receive` into the
existing actor runtime path, followed by the unchanged type and ownership
checks. The ordinary source compilation path stays fail-closed. It is
documented in `docs/STATELESS_DECLARED_ACTOR_OPT_IN_V1.md`.
A passing opt-in guest test will not prove general stateful/cross-file
actor ownership or backend parity.

The following blockers must be resolved by a **trusted operator**, not by
editing a draft PR to grant itself runner or export authority:

1. At audit time, the source `main` API reported `protected: false`, so
   `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md` denies private-source
   transfers. Do **not** copy private sources to another org's Actions.
2. The exact-head integrated JUnit workflow, Actions
   [#43105295416](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/43105295416),
   had `runner_id=null`, `steps=0` and `conclusion=failure`.
   This is not evidence of a Java test failure or guest execution.
3. A source-org ephemeral JDK25 runner, if used, must satisfy the existing
   independent exact-head review and governance checks. Don't weaken those
   checks or use an unreviewed `workflow_dispatch` bypass.

## Runtime and backend release receipts (subsequent gate)

The exact-blob receive/guest fixtures from #2822 and the one-shot gate
from #2256 are staged on this Java integration head. The existing
`ActorRuntime` source actor path is now additionally guarded by
`ActorSourceLifecycleGate` around startup, receive, teardown and
retirement. The **opt-in stateless** out-of-line compiler lowering reuses
that actor executor, but **general stateful source lowering and #2256's typed
init/spawn arguments remain unintegrated and unverified**. Require:
- typed spawn arguments, exactly-once `init`, state-builder authenticity,
  atomic READY publication, startup failure supervision and cleanup;
- bounded mailbox sends and `ActorMail<T>` decode with generation-scoped
  owner authority, direct/captured receive rejection, late/stale delivery
  rejection and terminal shutdown;
- actual guest Atto actor programs for positive and negative cases, plus
  real no-fallback Java Native Image;
- conformance manifest
  [#707](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/707),
  with 14 target fixtures (3 positive, 11 negative) and exact source tree,
  compiler binary, and fixture SHA evidence;
- independent LLVM, .NET NativeAOT, Wasm and Forge frontend/runtime receipts.

**No merge/promotion while tests are authored but unexecuted.**
