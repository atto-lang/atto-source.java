# Experimental executable `define state container` and `define virtual actor` (i64 slice)

**Status:** JVM VM-only and opt-in restricted **production AST → typed i64 MIR** admission (`ProtectedScalarMirV1`). It uses the normal `Parser.parse` declarations, but **does not run the general `TypeChecker`/`OwnershipChecker` or normal backend code generation**, and is not available on LLVM, Wasm, Forge, or .NET. No general guest language, actor runtime, or AOT parity is claimed.

## Supported syntax

- One `define state container Name as ... end` **or** one `define virtual actor Name as ... end` per source unit; `define pub` and `pub define` are parsed.
- Exactly one owned `state { let int field: signed_i64; }` scalar field.
- Exactly one single-`int`-parameter method with a checked addition guarded by `handle_state(critical-write |s| -> ...)`. A state-container method returns `int`; a virtual actor method returns `void`.
- No other statements, imports, nesting, declarations, unsafe references, async work, or trait inheritance are admitted. All token sequences must be consumed; unsupported guest syntax fails closed at parsing or with `PROTECTED_MIR_UNSUPPORTED`.
- The method field, parameter and critical guard variables must match exactly. Adds use `Math.addExact`, so overflow raises `ArithmeticException` without changing the scalar state.
- Virtual actors are local and ephemeral, keyed by normalized logical string identity; their serial mailbox admission is bounded. Each message is a signed i64. A failure notifies an injected callback and explicitly settles accepted tickets; this is not yet the real actor supervisor priority CONTROL dispatcher.

## Running the examples

Compile with the project build or with these runtime/compiler sources on a supported JDK, then invoke:

```bash
java -cp <classes> dev.oreslang.compiler.ScalarDeclarationI64Cli \
  examples/experimental/state-container-i64.atto 5 7
# 12

java -cp <classes> dev.oreslang.compiler.ScalarDeclarationI64Cli \
  examples/experimental/virtual-actor-i64.atto 5 7
# processed=2
```

The standalone CLI prints the final scalar for the state container and admitted/completed message count for the virtual actor. It does not print or persist virtual actor internal state. Source budget is 8 KiB; the opt-in MIR requires one method containing at most 96 tokens, and the CLI accepts 1–64 i64 arguments.

## Execution boundaries

- `ProtectedScalarMirV1` reads the **production `Parser` AST** and type-checks one i64 state field and a single fully consumed guarded addition body, producing `ACQUIRE_WRITE`, `LOAD_STATE_I64`, `CHECKED_ADD_I64`, `STORE_STATE_I64`, `RELEASE_WRITE`, and return instructions. Its JVM implementation uses the checked atomic `ScalarStateContainer.tryAddChecked`, which releases its write lease on all exits. The old `ScalarDeclarationI64Compiler` remains a compatibility-only experimental component.
- `ScalarStateContainer` stores only one native Java `long`, so there are no nested object aliases. The `StateContainerAccessGate` enforces compatible read leases and exclusive write leases; shutdown revokes authority before any new mutation.
- `LocalVirtualActorI64` executes one mailbox turn at a time when the embedding runtime explicitly calls `run_one`. It requires two-phase activation publication, logical identity fencing and explicit completion disposition. No separate carrier pool is spawned.
- **Quiescent handler disposal:** after a successful final admission disposition and an incarnation fence, the VM invokes each resource-owning `Turn.close()` exactly once, outside directory/slot monitors, during explicit deactivation, acknowledged panic recovery, and successful runtime shutdown. A `retiring` fence rejects activation while disposal is in progress. Disposal failures quarantine the slot and block silent shutdown. Existing stateless `Turn` lambdas use a no-op default hook; the opt-in protected scalar compiler provides an explicit hook that closes its scalar state and verifies access-gate quiescence.
- **Unforgeable completion surface (breaking experimental Java API change):** `LocalVirtualActorI64.SendResult` is now an immutable, runtime-issued class with a private constructor, and `completion()` returns a read-only `Completion` handle rather than a writable `CompletableFuture`. Senders retain `join()`, `isDone()`, `thenRun()`, and read-only nonblocking `stage()` composition, but cannot call `complete`, `cancel`, `obtrudeValue`, or substitute another accepted message's completion into a newly forged admission record. A child completion stage returned from `thenRun` cannot settle its parent; nor can a writable copy created through `stage().toCompletableFuture()`. This prevents a host caller from reporting an accepted actor message as processed before `run_one` actually executes. Host reflection/unsafe access remains outside this limited VM API trust boundary.
- **Allocation-free denied admission:** immutable `CLOSED`, `ACTIVATING`, `UNAVAILABLE`, and `BACKPRESSURE` responses are shared; a full slot mailbox returns before building an envelope or completion promise. Authoritative generation-fenced admission still occurs inside `VirtualActorActivationRegistry`. This only reduces the VM allocation pressure of rejected sends; successful sends still allocate managed Java objects.
- **Concurrent publication race:** `try_send` now rechecks a fully published activation after a concurrent initializer finishes; a caller that observed `initializing=true` must not spuriously return permanent `UNAVAILABLE` after publication. A six-publisher (2,400-turn) bounded stress caught this race in the prior implementation and passes with the corrected state recheck.
- **Fail-closed receipts and shutdown:** successful turns settle receipts before removing envelopes from the pending queue; after a successful supervisor failure notice, pending envelopes likewise remain attached until all failure dispositions are settled. Shutdown preallocates every resource-disposal record before committing the directory-closed state. Identity, activation, admission and state-gate lease capabilities are allocated before changing their associated bounded counters/ownership states.
- **Admission preparation ordering:** `LocalVirtualActorI64.try_send` constructs the mailbox envelope, completion future, and admitted result before incrementing any admitted-work credit. It stages a provisional envelope under the slot monitor, then obtains the registry ticket; denial or exception removes that provisional entry. `VirtualActorActivationRegistry.tryAdmit` similarly allocates its ticket before incrementing outstanding credits. This avoids allocation failures stranding accepted work during this narrow Java VM admission path; it does not prove scheduler-backed delivery, native-allocator safety, or host out-of-memory recovery.
- **Not a native allocator receipt:** a scalar Java `long` is still a managed Java field, and Java object/collection allocations remain GC-managed. The verified property requested here is *logical guest resource lifetime and admission fencing*, not native-memory deallocation, zero-GC, GraalVM Native Image, or independent backend conformance. Bounded-native-arena allocation and final physical reclamation remain separate future evidence gates.

- Handler failures emit a notice through an injected host callback; if notice publication fails, the accepted work remains unsettled and shutdown refuses to forget it. These are provisional local control semantics, not validated supervisor CONTROL priority.
- No remote placement, durable state, guest-written arbitrary methods, generalized borrow checking or native AOT codegen.

## Tests and promotion gate

- `ProtectedDeclarationAstAdmissionTest`, `ProtectedScalarMirV1Test`, `ScalarDeclarationI64CompilerTest`, and `ExecutableVirtualActorStateContainerTest` cover normal AST structure, restricted MIR, checked scalar state, key reactivation, failures and fail-closed negative cases.
- `LocalVirtualActorQuiescentDisposalTest` now has 16 JUnit cases covering settle-before-dispose, single disposal per incarnation, acknowledged-panic retirement, failed-CONTROL no-disposal, disposal-failure quarantine, reactivation fencing during an in-progress disposer, callback monitor safety, in-flight guest turns, pending supervisor CONTROL, and provisional-envelope rollback under backpressure.
- `ProtectedActorStandaloneSmoke` is a dependency-free JDK21 host test with 13 scenarios, including foreign-ref denial and a six-publisher, 2,400-message bounded FIFO/receipt stress. `scripts/test-protected-actor-exact-head-jdk21.sh` rejects dirty/mismatched checkouts, compiles five pinned source files with `--release 21 -Xlint:all -Werror`, and prints non-promotion proof boundaries. The source-local GitHub Actions workflow uses pinned checkout/setup-java actions and read-only contents permission.
- Exact-blob private-runner receipts exercise the production AST, typed scalar MIR, guarded VM classes, and both `.atto` CLI examples under JDK 21; these remain narrow proofs, not a complete project Maven/JUnit or AOT run.
- Before general language promotion: lower arbitrary guarded method AST into typed HIR/MIR, prove nested heap ownership/borrow lifetimes, integrate production actor scheduling and priority CONTROL, add source-module callable binding and execute the same conformance cases on Java, LLVM, Wasm, Forge, and .NET. The **general TypeChecker rejection remains active** until those proofs and backends exist.

## November 3, 2026 local verified runtime slice

The five source file Git blob IDs for `StateContainerAccessGate.java`, `VirtualActorActivationRegistry.java`, `ScalarStateContainer.java`, `LocalVirtualActorI64.java`, and `ProtectedActorStandaloneSmoke.java` were independently reconstructed and checked byte-for-byte against the GitHub blobs. On OpenJDK 21, all five compiled with `javac --release 21 -Xlint:all -Werror`; the **9/9 standalone host checks passed for 100 separate runs**, including 240,000 accepted concurrent turns across 100 iterations of the 2,400-message stress case.

This evidence is **LOCAL_EXACT_BLOB_VM_VERIFIED**, not a stepful GitHub Actions CI receipt. It does not run the full Maven/JUnit suite, general AttoLang ownership checker, an independent compiler-backed `.atto` guest, native arenas or any native-image/backend. Source-org GitHub Actions still fails before runner steps. The alternative test organization has executed other source-free/previously authorized carrier jobs, but `.github/private-ci-approved-repositories.txt` has no approved destinations and issue #962 remains open; no new cross-org private source transfer was made for this change.

### November 3 admission-completion hardening (current draft)

The newest draft commits change the host-facing `SendResult`/completion API and add a 10,000-attempt backpressure test, a forgery-negative JUnit test, and a matching standalone smoke case. The earlier 100-run host execution receipt is for the *previous* source blobs and must **not** be reused as exact-head execution evidence for these new changes. The new completion API was separately compile-checked on JDK21 in an isolated fragment test, not yet compiled as part of this entire private source repository. Full JUnit/Maven, source-local Actions and guest/AOT proof remain unexecuted for the new exact head.

### November 3, 2026 — upstream reconciliation and source-local negative test gates

The draft now incorporates the newer `main` commit `5c3d5414a15f887a078b64d6aed3bafb7132427f` by merging its 27 non-overlapping changed files and retaining the draft's 10 changed-file paths. GitHub comparison after reconciliation showed `behind=0`. The upstream `ActorRuntime` addition permits the separate immutable `PersistentActorMap` during transport freeze and is **not** evidence that the host-only `LocalVirtualActorI64` disposal path is production ActorRuntime integrated. In particular, physical source Task/descendant retirement from issue #1566 remains independently required for native arena reclamation.

Three further adversarial tests now exercise (1) shutdown while a guest factory physically initializes, (2) failure/abort followed by fresh activation without a lost credit, and (3) independent stage-copy cancellation without cancellation of the accepted mailbox turn. Both the JUnit and host standalone suites contain these cases; **they have not yet executed on the new draft head**.

The repository's exact-content `scripts/test-protected-actor-exact-head-jdk21.sh` (Git blob `51e81321710ab62e0883d279b9feb586793a3d42`) was copied byte-for-byte into an isolated synthetic Git checkout for a **local script-gate negative test**. Three executions correctly rejected: malformed `EXPECTED_HEAD` with exit 2; well-formed but incorrect SHA with exit 3; and a dirty/untracked checkout at its correct SHA with exit 4. This validates only the **test entrypoint's fail-closed source-identity gates**, not its javac/JUnit path, because the synthetic checkout intentionally contains no private compiler source.

Exact-head private Actions admission continues to fail before runner assignment. The checkout-free synthetic workflow and the JDK21 source-local smoke both report `runner_id=null` / `steps=null`. No source transfer to any unapproved external organization was performed. Independent protected-branch review and concrete GitHub administrator billing/eligibility diagnosis remain blockers.
