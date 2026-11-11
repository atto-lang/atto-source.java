# Explicit `external` actor lifecycle marker — Java static admission

**Scope:** parser/AST/nominal-binder stage on top of the cross-file receiver method stack. NOT a runnable guest actor entrypoint. Reference contract: https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/707

## Supported syntax in this branch (static-only)

```atto
namespace example;
define actor Worker as end
define external mtd (Worker w) init(): void { return; }
define external mtd (Worker w) receive(ActorMail<int> mail): void { return; }
```

`external` is contextual before out-of-line `mtd` after `define`. It is stored as `ExternalActorMethodDecl.runtimeEntrypoint()`. The existing unmarked `define mtd` spelling remains an ordinary, non-entrypoint method attachment and is retained for backward compatibility.

The nominal binder accepts runtime role metadata **only** for private `init`/`receive`, rejects public lifecycle entrypoints, rejects raw actor-owner returns from init, rejects arbitrary `external` RPC methods and prevents multiple lifecycle slots differing only by arity. Existing `receive` must accept exactly one `ActorMail<T>` and return `void`. No runtime capability or direct method invocation is granted by this metadata.

## Deliberate limitations

- No actor body `external init` or `external receive` grammar yet; inline class/actor method syntax is a separate AST integration step.
- No `init` bootstrap bridge or atomic state publication; reconcile with Java #2256. Existing `on_start` remains the legacy hook and cannot silently double-run with a new `init`.
- No mailbox turn lowering; reconcile with #2822's private receive guard and with #2786's actor-contract private hook rules.
- No real external `mtd` execution. TypeChecker must still reject with `EXTERNAL_ACTOR_RECEIVER_LOWERING_UNSUPPORTED` before any guest invocation or AOT artifact.
- No cross-file package provenance or contract witness integration beyond parent #2823's caller-supplied static source set; reconcile with #2813 and #2792.
- The ABI must eventually encode entrypoint-role metadata, effect and generation scope in both the shared IR and every backend; this PR does not establish parity.

## Evidence required before integration

Run `ActorExternalEntrypointModifierTest` plus the inherited `ActorReceiverBinderTest`, `ExternalActorReceiverAstAdmissionTest` and `CrossFileActorReceiverBindingTest` against the **exact final** commit SHA in an admitted JDK 25 runner. Then add real actor init/receive guest execution and negative diagnostics, complete Java no-fallback Native Image, and independently port LLVM/.NET/Wasm/Forge. Treat source-free inventory checks as insufficient.

Private source must not be mirrored to an unrelated organization without independently authorized exact-head publisher/carrier protection and review.
