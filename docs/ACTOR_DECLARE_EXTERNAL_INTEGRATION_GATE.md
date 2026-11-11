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

## Exact-head source-org static verification

A dedicated read-only workflow exists at
`.github/workflows/actor-declare-external-jdk25-exact-head.yml`.
On same-repository PRs, it checks out the literal immutable PR head
with no persisted credentials, verifies commit/tree/POM/parser/binder blob
identities, requires GraalVM JDK 25, runs the three new and three inherited
JUnit suites, and requires all six real Surefire XML files to contain
nonzero, unskipped, zero-failure tests. No success is inferred from
`workflow status=success` alone. The Surefire receipt explicitly labels
`guest_actor_executed=FALSE`.

The following blockers must be resolved by a **trusted operator**, not by
editing a draft PR to grant itself runner or export authority:

1. At audit time, the source `main` API reported `protected: false`, so
   `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md` denies private-source
   transfers. Do **not** copy private sources to another org's Actions.
2. Source-hosted workflows were failing without a runner (`runner_id=null`,
   zero job steps). An Actions row with `conclusion=failure` and zero
   steps does **not** show whether Java compiled.
3. A source-org ephemeral JDK25 runner, if used, must satisfy the existing
   independent exact-head review and governance checks. Don't weaken those
   checks or use an unreviewed `workflow_dispatch` bypass.

## Runtime and backend release receipts (subsequent gate)

Once actual static tests pass, integrate the three staged actors branches
in a single reviewed integration head. Require:
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
