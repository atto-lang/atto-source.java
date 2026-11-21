# Runtime data layouts and safe supervisor lanes (draft v1)

**Status: host-only executable reference, not guest source execution, not an integrated security boundary.** This draft is stacked on Java inverted-class PR #1445, and must not be confused with a merged feature. The two Java helpers under \`runtime\` are trusted-host reference designs. They are **not** wired to \`ActorRuntime\`, \`ActorSupervisor\`, \`nb\` Tasks, the protected pooled JIT isolate, a full compiler borrow checker, or native LLVM lowering.

## Scope and decision

One nominal \`define inverted class\` should admit three **physical** layouts in an owning collection:

* **AoS**: adjacent complete records, good for row-at-a-time processing.
* **SoA**: adjacent per-field columns, good for scans and batch SIMD.
* **AoSoA(N)**: N rows per block, with contiguous per-field lanes inside each block; good for SIMD-friendly chunks and partition locality.

\`inverted\` opts into a class whose fields can be individually addressed by a compiler-known storage schema. It does not force SoA forever. Ordinary class identity, method semantics and privacy remain unchanged; a storage layout is **not** a subclass, an actor domain or a permission. An ordinary \`class\` remains conventional AoS by default. \`InvertedArray<T>\` owns one canonical layout, not three mutable copies.

Proposed guest syntax (NOT YET admitted in Java or LLVM):

\`\`\`ores
define inverted class ChildHealth as
    actorId: i64;
    state: i32;
    restarts: i32;
    revision: i64;
    lastFailure: i64;
end

let store = InvertedArray<ChildHealth>(layout: #aosoa, blockSize: 64);
let compact = InvertedArray<ChildHealth>(layout: #soa);
let rowWise = InvertedArray<ChildHealth>(layout: #aos);
\`\`\`

\`layout:\` is a **store construction parameter**, not a nominal class parameter. The three collections should expose the same checked row/field API. \`as_layout(#soa)\` or \`set_layout(#soa)\` must be an explicit, checked **relocation**: copy/transpose and atomically replace the backing buffer after acquiring exclusive structural access. Existing \`(owner, slot, generation)\` handles survive only if their logical row survived; active raw column loans must block relocation, and region-relative raw pointers/iterators become invalid. No implicit zero-copy claim across incompatible physical layouts.

Do not default to adaptive layout migration; expose profiling and opt-in changes at a safepoint. Fixed-width pointer-free fields are initial guest admission. Store owned strings, references, resource handles, FFI, async continuations, atomics and actor state only after separate layout/ownership ABI reviews.

## Physical addressing

For field \`f\`, dense physical row \`r\`, field count \`F\`, allocated capacity \`C\` and chunk width \`N\`:

* AoS: \`index = r*F + f\`
* SoA: \`index = f*C + r\`
* AoSoA: \`index = ((r/N)*F + f)*N + (r%N)\`

These indexes count **homogeneous scalar lanes** in the *host reference*. A production schema must use each field's actual size, alignment, padding and explicit ABI layout, rather than assume every field is 64 bits. Canonical \`bool\`, endianness, signedness, padding initialization, NaN representation and enum validity must agree with LLVM. Alignment and vector width differ by target architecture.

The Java \`SupervisorDenseTable\` intentionally stores five 64-bit numeric metadata fields in one contiguous \`long[]\`, selects exactly one of the three layouts, uses a dense swap-delete table, carries generational logical slot handles, and transposes at a synchronized safepoint. This is a reference to test correctness and backpressure before optimizing; it is **not** the generic \`InvertedArray<T>\` implementation or evidence of lower cache-miss rates.

A supervisor child row is **metadata only**. No stored actor-private mutable pointers, actor heaps, mailbox internals, continuation stacks or borrowed child state. An \`ActorRef\` or opaque runtime identity is an external handle, not permission to read memory or to restart the actor. Metadata field mutation by untrusted guest source remains forbidden.

## Reusable layout policy: target data structures

The long-term design is a generic `alloc::LayoutStore<T>` plus target-specific indexes and owning arenas, **not** one specialized supervisor table per subsystem. Most **dense fixed-record metadata** structures should admit all three layouts, but their authoritative ordering/indexing structures should retain the algorithms they require.

| Subsystem | AoS | SoA | AoSoA | Separate canonical index / constraint |
| --- | --- | --- | --- | --- |
| Supervisor child metadata | yes | yes | yes | child-spec order, failure dense-set, restart window |
| Actor registry entries | yes | yes | yes | hash index, incarnation and domain fencing |
| Task metadata / continuation headers | yes | yes | yes | per-worker work-stealing deques; actual stack frames remain separate |
| Future completion metadata | yes | yes | yes | wake queues and one-shot completion state |
| Timers and lease metadata | yes | yes | yes | hierarchical timing wheel or heap; deadlines cannot require full scans |
| Connection descriptors | yes | yes | yes | socket/event readiness poller; buffer ownership separately tracked |
| ActorGroup membership metadata | yes | yes | yes | sparse-set / CSR membership adjacency and epoch indexes |
| Mailbox envelope headers | yes | yes | yes | bounded per-actor FIFO ring/segmented queue, no payload reshuffling |
| Capabilities and revocation epochs | yes | yes | yes | authority in opaque tokens and trusted revocation/epoch registry |
| Hot code generations and pin counters | yes | yes | yes | generation leases, ABI manifests and safepoint barriers |
| Actor private mutable state | within owner | within owner | within owner | never implicitly merged across actor heaps |

Do not force a FIFO queue, time wheel, lock-free deque or ordered supervision tree into an archetype store merely for uniformity; represent its **bulk metadata** in the chosen physical layout, and retain the algorithmically necessary queue/index. Columnar layouts do not override atomics, lifetime, transactional versioning or safe synchronization.

Default choices should be proven by measurements (AoS for small per-record operations, SoA for field-heavy scans, AoSoA for chunked SIMD). Changing a layout is an explicit stop-the-world-for-that-region transaction with failed-allocation rollback and active-loan exclusion. Reads/writes on disjoint views require compiler-checked `ExecutionPermit<Region,AccessSet>`; host synchronized reference tests do not prove borrow-safe concurrent guest execution.
## Supervisor control-plane design

An Oreslang supervisor is a nominal isolated actor with runtime-minted authority, not an ActorGroup and not an OS process by default. Keep two independent logical inboxes, owned by the trusted runtime:

1. **Priority CONTROL**: authenticated, unforgeable child panic/exit/stop/ready/done/upgrade/control-ack signals. Panic does not execute untrusted or failing actor user hooks. Messages are immutable typed notices containing verified child identity, incarnation, supervisor ID, sequence and trusted reason code. CONTROL is **not** a public user send path.
2. **Regular message lane**: typed/serialized actor-to-supervisor messages, bounded in bytes and message credits (queued *plus* in-flight). Receipts become authoritative only after admission. Dispatch is not an acknowledgement. Credit settlement happens on logical completion, cancellation or verified drop, exactly once. Regular traffic cannot borrow CONTROL capacity.

The critical authority check happens at **every actual host delivery ingress**, using the trusted executing actor turn or authenticated remote/proxy binding. **Never trust a guest-provided sender ID, generation, supervisor pointer, \`ActorRef\`, payload metadata or claimed CONTROL marker.** Bind a child to its supervisor at spawn *before publication*; bind logical identity and incarnation separately, so restart/hot reload/remote failover cannot reuse stale permissions. Revocation and admission share one synchronization/order boundary; new sends fail closed after revocation. Supervisor restart revisions must pin the event ABI/code generation while in-flight turns drain.

CONTROL boundedness is non-negotiable. If CONTROL capacity is exhausted, the **trusted runtime** must atomically stop unsafe admission and escalate to an independently bounded parent/emergency coordination path. A return code alone does **not** implement escalation: until that wiring exists this helper is not a secure production mailbox. If the parent path is full, escalate recursively to the runtime root's fail-stop policy; do not drop a crash event and claim successful supervision. Use per-supervisor reserved limits and at-most-once child-failure coalescing by verified incarnation; avoid letting one child monopolize the reserved budget.

For normal messages, enforce layered (child, lineage, supervisor, tenant, route) message and byte token buckets, bounded outstanding credits, single-reservation rollback and nonparking rejection/suspension. This requires issue #1265 / draft PR #1266 integration; the prototype only offers per-mailbox message/byte limits. Source send, response, direct mailbox, host interop, proxy/remote and alternate delivery paths **must share the same enforcement point**. Do not relax existing restrictions on UNTRUSTED arbitrary messaging.

### Fairness and restart ordering

* Prefer CONTROL for urgent work but cap consecutive CONTROL dispatches (e.g. configurable burst 8) to avoid starving normal requests; panic escalation safety takes precedence.
* Preserve per-lane FIFO where guaranteed; inter-lane priority makes **global** ordering impossible. Expose sequence/watermark when a policy needs order, don't promise an order that was not enforced.
* \`one_for_one\`, \`one_for_all\` and \`rest_for_one\` restart **planning** may use dense state columns/failed sets; actual stop/restart must follow child-spec order and await true \`get_done()\` quiescence before rebuilding, obeying restart windows, backoff and escalation.
* Retire child inbox capabilities before releasing actor-owned arenas. Reincarnation creates new bindings and new rights, not an update to the old token.
* Detached, immutable summaries may be read by the supervisor. Mutable or zero-copy child heap access is never granted by group membership or by this table.
* Supervisor policy hot swap publishes a new code revision only at a safepoint after validated ABI, CONTROL lease quiescence and state migration. Structural child identities survive a code-only revision.

## Host reference entry points

* \`SupervisorDenseTable\`: AoS/SoA/AoSoA, checked transpose, stable handles, swap-delete, detached snapshots. All methods synchronized; performance is unclaimed. Instances belong to trusted runtime state only.
* \`TrustedSupervisorInbox\`: **package-private** model with trusted-only binding, a separate panic queue, nonparking bounded user queue, defensive payload copies, one-shot delivery completion, immediate revocation of queued user data and an escalation-required overflow result. It intentionally does not claim to have authenticated \`ActorRuntime\` turn identity, cross-runtime authorization, hierarchical quotas, fairness scheduling, supervisor actor policies, or physically completed teardown.
* Both must stay disconnected from untrusted guest APIs until compiler and host admission hooks, verified actor-domain ownership and real protected-isolate tests are complete.

## Implementation gates

1. Land the triple-layout **host reference** without enabling guest syntax. Confirm AoS/SoA/AoSoA differential values on 1k, 10k and 100k rows, mixed insert/delete, stable handles, overflow and close.
2. Add the per-child, per-lineage, per-supervisor and per-tenant quota path and a runtime-minted supervisor identity. Integrate the protected UNTRUSTED ingress and all bypassable send routes; keep current deny policy until proven.
3. Connect the actual \`ActorRuntime\` crash boundary and supervisor CONTROL lane. Test spoofed failure, reincarnation ABA, revocation-vs-send, overflow parent escalation, policy hot upgrade, cancellation, in-flight credits and cleanup.
4. Integrate \`InvertedArray<T>(layout: ...)\` compiler-owned layout/effect/borrow constraints and LLVM zero-GC native analogues. For Java target minimized GC via actor-owned arenas; for LLVM use native arena-owned allocations and full AOT. Keep freestanding \`core\` free of mandatory allocation and OS dependencies; these stores belong to \`alloc\`/runtime.
5. Use end-to-end guest-source compile/run (not just host tests) and exact-head CI on Java and LLVM. Benchmark 1k / 10k / 100k / 1M child records: cache misses, ns/op, allocations, memory per live row, p50/p99 CONTROL latency and forced overflow during failure storms. Choose block sizes empirically, avoid arbitrary >1000 thresholds.

## Test evidence (Oct 19, 2026)

The included two JUnit test classes were run locally on **OpenJDK 21** with an intentionally minimal JUnit-compatible assertion shim and reflection runner: **7/7 test methods passed**. Coverage includes 12,000-row 3x3 layout transitions, 4,000 randomized insert/update/delete/relayout operations, stale and foreign handle rejection, priority-lane independence, credit retention until completion, overflow signaling, restart/revocation fencing and foreign binding rejection. The source files compiled with \`javac\`. This is NOT a Maven/Surefire run, not a real Oreslang guest execution test, not a protected isolate proof, not a cache benchmark and not a native ASan/UBSan result.

Related: Java inverted #1445, LLVM inverted #657, Java issues #1424, #1437, #476, #641 and #1265, security draft #1266.
