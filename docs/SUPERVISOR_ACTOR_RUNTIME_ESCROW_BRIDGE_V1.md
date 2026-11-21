# AttoLang supervisor CONTROL escrow: real ActorRuntime bridge v1

Status: **STACKED DRAFT / NOT MERGED / NOT PRODUCTION CERTIFIED**. Based on Java #2765 (which is based on #1965 → #1962 → #1924 → #1911). This prototype does not enable executable guest `define supervisor`, cross-backend parity, or automated restart policy.

## What is actually connected

`SupervisorFailureEscrowRuntimeBridgeV1` is package-private and trusted-host only. It binds an actual, runtime-minted `ActorRef` and its **real** `generation()` to an independent CONTROL escrow node before the actor's first ready/send/turn. Parent relationships are explicit *supervisory policy parentage*, not implicit ActorGroup cooperation membership. The in-process guardian is an independent trusted sentinel; a whole-process crash requires external process supervision.

The following production `ActorRuntime` boundaries now consult the same escrow ledger, on the opt-in branch:

1. **Binding admission**: under `runtimeLifecycleLock -> cell.lifecycleLock -> ledger`, reject foreign ActorRefs, already-activated/scheduled/stopped actors, duplicate CONTROL owners and unknown/dead/fenced parents. Generation comes only from `ActorRef`, never from a guest parameter.
2. **First terminal failure**: after the runtime records a non-cancellation failure and logically stops the actor, it calls `failedFromRuntime` under the actor lifecycle lock. The ledger atomically adds one generation-bound failure record and fences the entire registered supervisory subtree. It does not run guest hooks, stop another actor or enter the group CONTROL scheduler while under that lock.
3. **New work admission**: `ActorRef.ready()`, initial `send` reservation, final committed `send` mailbox write, and nested actor spawn from a bound parent check the **live escrow fence**. The late send check is mandatory: message isolation/copying can overlap a supervisor failure. Already-committed work is not retroactively discarded by this check.
4. **Physical actor completion**: after the actor memory slice closes and the runtime's finalization boundary is reached, the binding is removed from the runtime map. A normal/cancelled completion marks a clean node stop without forging a failure; failed terminal receipts remain in escrow. This is **not** proof that every FFI/native loan in the supervisor subtree is physically reclaimed.
5. **Host-only decision boundary**: `commitAndAcknowledge(receipt, action)` verifies exact delivery and owning controller generation, admits at most one in-flight policy decision per delivery object, invokes the action outside escrow/runtime locks, and acknowledges only after the action returns successfully and generation is revalidated. A thrown action leaves the failure pending.

No guest code receives the bridge or the CONTROL Delivery object. Ordinary actor DATA cannot fabricate these records.

## Important remaining integration gaps

- This is an **opt-in** bridge; other actors retain current behavior. It is not yet wired to the production supervisor-class compiler lowering, `SupervisorProcess`, or `SupervisorShutdownTree`. The source branch does not contain the newer independent terminal-ledger draft families. Before deployment, reconcile with Java #1222 and #1320: **one authoritative failure ledger**, never two competing ACK authorities.
- Supervisor failure **fences** a registered logical subtree but does **not yet request structural cancellation of all the host-bound ActorRefs** or prove native FFI/arena reclamation. The existing ActorRuntime physical parent-child tree is separate from the policy parentage modeled here. An external trusted coordinator must stop and retire descendants before a replacement generation is authorized.
- Host `commitAndAcknowledge` cannot guarantee exactly-once effects if a coordinator dies after an external effect but before ACK. The production decision sink must be idempotent or transactionally journaled and tied to the existing SupervisorProcess decision-commit fence. This branch **does not yet contain that full production sink**.
- `MAX_NODES=4096` counts lifetime registrations per bridge. No ID reuse, live migration, hot-reparenting or restart-to-generation-2 binding is implemented. Excess capacity must fail closed, not silently discard a notice.
- ActorRuntime's Java `done()`/memory-slice closure does not establish FFM/native release for every backend. Never use it to emit `PhysicalRelease` in the existing supervisor FSM.
- In-process guardian survival is a model assumption, not a guarantee against complete JVM/process/host failure. External guardian + crash-consistent WAL and process-generation fencing are separate release gates.
- The implementation is a trusted Java host integration, not executable source-level `define supervisor` or Java/GraalVM Native Image execution; LLVM, .NET, Wasm, Forge remain NOT_EXECUTED/NOT_CERTIFIED.

## Tests and exact evidence boundaries

New JUnit test class `SupervisorFailureEscrowRuntimeIntegrationTest` covers:

1. Two actual ActorRuntime guest-behavior failures, child notice delivered to supervisor before parent crash, stale ACK denial, ancestor CONTROL reassignment, DATA fence and healthy sibling progress.
2. Fresh unstarted ActorRef binding only; duplicate and foreign ActorRef binding denied.
3. Normal actor completion does not produce a failure notice.

The test also exercises a throwing host decision action before ACK; escrow must remain pending.

The existing dependency-free standalone Java test still passes **557 assertions**, including 8 rounds × 64 simultaneous failure notices, plus clean STOP/late-failure forgery rejection on JDK21 with `javac -Xlint:all -Werror`. This **is host-only evidence**, not the new JUnit suite.

The new `scripts/verify-supervisor-escrow-runtime-bridge-v1.sh` pins the actual `ActorRuntime.java`, bridge, ledger and JUnit-test Git blobs and requires JDK25 Maven to run 3 non-skipped JUnit methods, emitting a Surefire XML report. The source-org Actions queue has repeatedly produced **steps=null**; until a stepful exact-head job succeeds, record **JUnit NOT_EXECUTED**.

Companion finite conformance: [#659](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/659). No cross-org private-source transfer without approved provenance and destination policy.

## Next code slices (not implemented in this PR)

- Unify with authoritative pre-reserved runtime terminal CONTROL ledger and single-flight `SupervisorProcess` decision commits.
- Couple policy parentage to runtime-owned structured child scopes, real group admission fences, and deterministic bottom-up shutdown/physical receipts.
- Execute guest `define supervisor` / actors-as-supervisors, root-guardian failure injection, cancellation and restart backoff/intensity budgets.
- Differential trace-refinement vs TLA+ and reference v2 oracle, then Native Image/LLVM/NativeAOT/Wasm/Forge parity.
