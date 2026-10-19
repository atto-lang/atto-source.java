# Send/Sync and actor-owned ECS: staged safety contract (v1)

Status: implementation slice / provisional. This document is a proposal for the
end-to-end contract, not evidence that parallel actor ECS already executes.

## Thread traits versus actor transport

* `Send<T>`: moving **owned** T between worker/carrier threads is safe.
* `Sync<T>`: immutable references to T may be accessed on multiple threads.
* Neither is a serialization contract, remote-message contract, actor capability
  grant, ability to clone, nor a proof that a reference outlives a thread/task.
* `&T: Send` iff `T: Sync`; `&mut T: Send` iff `T: Send`.
  `&T: Sync` and `&mut T: Sync` require `T: Sync`. Borrow scopes and
  exclusivity are enforced separately by the ownership checker.
* `Copy` is separate from `Send`, `Sync`, and `rt share` (deep read-only).
  A move-only owner can be Send without being copyable or Sync.
* Unknown/generic/erased type parameters, callable captures, host pointers,
  runtime capabilities, and actor instances are **not** assumed Send/Sync.
  Unresolved recursive nominal graphs cannot be accepted by a repeated-name
  shortcut. A later nominal fixed-point proof must inspect instantiated fields.
* Unsafe, guest-supplied trait implementations are out of scope. Auto traits
  must be compiler-known and unforgeable by type-name aliases.

## Current implemented slice

* `ThreadSafetyTraits` is a conservative compiler-internal query for
  structural primitives, tuples, records, sum types, borrowed types, and
  certain explicitly recognized built-ins.
* Owned mutable Array/List/Vector/Slice, ordinary writable record, FixedArray
  and FixedList can be Send when all elements are Send, but are **not Sync**
  just because those elements are Sync. An immutable Tuple derives Sync
  only when all its nested elements are Sync. **No structural Record currently
  derives Sync**, even when `Record.readOnly` is true: that flag is a view
  capability that can be overlaid onto a mutable owner's storage, not
  proof of exclusive ownership or a deep-frozen backing allocation.
  Readonly records remain Send when their fields are Send. A separate
  compiler-backed freeze/alias-exclusivity witness must precede Record Sync.
  Later immutable buffer/container APIs need similarly verified storage
  and aliasing semantics; `rt share` cannot manufacture Sync from
  read-only syntax alone.
* `TypeChecker.validateAsyncBoundaryType` now requires both the pre-existing
  owned/task-safe test and a Send proof; the existing actor message-specific
  rejection rules remain intact.
* A channel handle gets a narrow Send proof when its payload is Send. We do
  **not** claim Sync for the handle until the runtime and immutable reference
  implementation are audited; `Mutex`, `SharedMutex`, futures, actor
  handles and arbitrary Java/host capabilities remain unproven in this slice.
* Nominal owned class Send is currently admitted only through the existing
  transitive `isSharedSafe` class-field check. Nominal class Sync is denied
  until a separate proof covers identity, inherited fields, mutability,
  allocation domain, interior mutation, and host interop.
* This does not yet expose `Send`/`Sync` source syntax, generic trait bounds,
  an ECS, a runtime thread pool grant, or region-disjoint parallel execution.

## New pure ECS dependency-planner slice

The compiler-side `EcsAccessPlanner` now accepts system read/write byte spans
with allocation provenance identities, detects overlapping writes, and generates
stable, order-preserving concurrent batches. It rejects invalid ranges and
fails closed on unknown alias provenance. Disjoint columns and nonoverlapping
chunks can be recognized, but the compiler must establish that two allocation
identities really refer to distinct physical storage.

**This is not executable ECS parallelism.** The planner grants no pointer,
borrow, actor-state or thread capability. It does not split an actor's exclusive
world loan or mint worker tickets. Runtime scoped-loan issuance, task
join/cancellation, generation fencing and backend lowering remain mandatory
before any planned batch may execute concurrently. Deterministic ordering of
planned batches is not a guarantee that unordered floating-point reductions
are bitwise deterministic.

## Security hardening / execution-admission requirements

* A compiler-trusted nominal `Send`/`Sync` callback does not excuse unknown,
  non-Send/non-Sync or runtime-capability **generic arguments**. Every generic
  argument is checked transitively before nominal trait proof is accepted.
  Phantom/variance exemptions, if ever needed, require a separately audited
  compiler-internal representation rather than caller-supplied assertions.
* An empty system access list is **unknown effects**, not evidence of purity.
  Both static planners treat it as a serialization barrier. Even a nonempty
  footprint must be emitted only after verifying all reads/writes and external
  effects (FFI, I/O, atomics, global aliases, interior mutation and actor
  callback entry). Any effect not representable as a verified region access
  must force a barrier.
* A single system may legitimately read and write the **same** region. This
  is not a contradictory footprint: conflicts with other systems treat it as
  a write, and the system's own sequential operations retain ordinary
  intra-task borrow checking. Do not reject valid read-modify-write systems.
* The native descriptor constructor validates half-open intervals and access
  mode, then exposes read-only views. Its pointer-shaped allocation identity
  is **not** a provenance proof or authority token. Interpreting two distinct
  pointers as two disjoint allocations requires a trusted compiler-side
  allocation and aliasing proof; identity must not be reused across actor
  generations, rollback or region reclamation.
* Static planner batches grant no runtime access. A later runtime must
  independently issue epoch-bound affine region loans, enforce full worker
  quiescence before reconstitution, and retire all loans on cancellation,
  panic, STOP and hot reload.
* Host smoke/property tests are not real compiler or runtime evidence.
  Keep both PRs as drafts until exact-head Java JUnit and native LLVM CTest,
  plus cross-backend source conformance, execute on authenticated source.

## Audit hardening (2026-10-18)

* An unresolved nominal type is **not** an actor-mailbox transport witness.
  The Java compiler accepts only explicit compiler-recognized scalar actor
  metadata (`ActorId` and `ActorKind`) in that final fallback; generic
  wrappers and unknown host/capability names are rejected. This tightens the
  previous runtime-only rejection path without granting `Send` or `Sync`.
* The pure Java ECS planner now enforces deterministic compile-time admission
  budgets: at most 4096 systems, 4096 declared spans per system, and 16 million
  access-pair checks per planning operation (including conservative zero-span
  barriers). Exhausting proof budget is a **compilation error**, never
  authorization to run conflicting systems concurrently. These limits do not
  constrain the number of world entities, allocations, or ECS chunks a running
  system may process; larger proof graphs require coalesced verified spans.
* Standalone Java 21 host smoke tests, using the exact checked Git blob for
  `EcsAccessPlanner`, ran 245,344 assertions over deterministic randomized
  conflict/ordering graphs, large bounds, and provenance identity. An existing
  exact-blob standalone `ThreadSafetyTraits` harness ran 66 directed and
  12,020 seeded fuzz/depth checks. These tests **do not** execute the Oreslang
  parser, complete type checker, JUnit, actor runtime, GraalVM Native Image or
  the LLVM backend. CI admission is a separate merge gate.

## P0 actor-transport parity follow-up (2026-10-18)

* ActorRef<ConcreteActor> is a **runtime-owned routing capability** whose
  generation and ActorRuntime ownership are validated by the transport, not an
  ordinary class value or automatic Send/Sync implementation. The compiler
  now explicitly admits it only when the target resolves to a declared actor.
  Nested mailbox payloads (e.g., Option<ActorRef<Worker>>) retain the same
  capability checks. Unknown nominal types, forged ActorRef targets, and
  executable host objects remain rejected.
* SharedMutex<T> has a *narrow capability-aware SHARED mailbox path*. It is
  permitted only for a shared destination with a concrete shared-safe Send
  payload. Private/untrusted actors cannot send the capability into a shared
  destination; the runtime still checks sender/receiver policy, originating
  runtime ownership and lock payload safety. Do not replace the mailbox path
  with ordinary data-only ActorRuntime.freeze().
* Actor-callable **completions** are a different boundary: the runtime's
  invoke/invokeAsync paths use ActorRuntime.freeze() on return values.
  ActorRef and SharedMutex (including nested Option, Result, List, Tuple,
  Record and union instances) are therefore *not* legal result types;
  capability transfer requires an explicit mailbox. The checker enforces
  this result-only distinction without removing valid mailbox parameters.
* Added the focused ActorTransportBoundaryCompatibilityTest alongside
  existing ActorMailTypeContractTest, ActorCallableKeywordTest and
  ActorTransportHardeningTest to the concurrency CI matrix. These remain
  unexecuted until a GitHub runner actually starts.
* A source-free capacity probe in a distinct private org
  (`canonical-cloud/canonical-clients`, run `39198466208`) also failed
  with zero executed steps. The probe contained no private Oreslang code.
  No cross-org private-source transfer is authorized while security issue
  #962 remains open and source/destination policy approvals are absent.
  Neither failure proves tests passed or failed; source and candidate
  runner admission require separate investigation.

## Actor transport and CI P0 verification update

* The actor mailbox transport capability contract is distinct from automatic
  `Send`/`Sync` derivation. A concrete `ActorRef<ActorType>` may be admitted
  as a generation-bound, same-runtime routing capability in mailbox paths
  without becoming a general thread-safe, shareable or serializable value.
  Its runtime ownership/epoch validation remains mandatory.
* Actor callable **completions** use the data-only frozen-result path and
  reject `ActorRef` and `SharedMutex`, including nested Option/List/record
  occurrences. Compiler parity tests must cover both the permitted mailbox
  case and rejected frozen-result case, plus unknown actor targets.
* Actor boundary structural recursion is depth-bounded (256 levels) before
  accepting a nested payload. Exceeding the type-complexity limit is a
  compile-time error, not a permission to transport the value.
* For this PR, run the source-level `ActorTransportSendSyncParityTest`,
  `ActorMailTypeContractTest`, `ActorCallableKeywordTest`,
  `ActorTransportHardeningTest`, `ChannelCallableTransportTest`,
  `ThreadSafetyTraitsTest`, and `AsyncChannelBoundaryTest` as part of
  the Java/Maven concurrency contract suite.
* A **zero-step** GitHub Actions failure does not establish a Java test
  failure. Runner capacity/billing/administrative job-admission reasons
  require separate verification. The cross-org private-source CI fallback
  remains disabled until the independently reviewed source provenance,
  pinned private destination repository ID, and end-to-end publishing
  gate tracked by issue #962 are approved and enforced. No public mirror
  or unaudited CI destination may receive source blobs.

## Untrusted actor capability parity audit (2026-10-19)

* The runtime's `rejectUntrustedInboundCapabilities` prohibits live
  `ActorRef` values in UNTRUSTED mailbox messages, even when nested inside
  `Option`, `Result`, lists or other structures. The compiler now rejects
  this at the receiving actor boundary instead of relying solely on runtime.
* The runtime also forbids all actor-message sends from an UNTRUSTED turn,
  including `self.send` and `ActorRef.send`. Source type checking now rejects
  both spellings. This does not alter trusted SHARED/PRIVATE routing or the
  reserved supervisor CONTROL terminal-reporting channel.
* Regression tests cover untrusted nested ingress, outbound send rejection,
  and trusted `ActorRef` delivery. They are added to the focused Actions
  workflow but **not yet JUnit-verified**, because source-org GitHub jobs
  continue to fail before the first step.
* A no-source, no-checkout Actions capacity probe was launched in a separate
  private test organization. Its result must be read directly from GitHub
  before any claim that the organization has working current runner capacity.
  It is not authorization to copy Oreslang's source. Source-mirror approval
  remains blocked by security issue #962 and an empty protected allowlist.

## Planned actor/ECS design

Actor isolation should remain the outer fault and networking boundary.
One actor owns one persistent state schema containing its shard's ECS world,
not one actor object per simulated entity. Entity IDs are versioned handles,
not direct pointers. Within that world, archetypes/chunks store dense SoA
columns with compatible alignment and predictable iteration.

The scheduler builds access sets for each system: READ(component, region) or
WRITE(component, region). Systems A and B may execute in the same batch only
if every overlap is read/read or their memory regions are proven disjoint.
If partition identity or aliasing is not proven, serialize. Structural ECS
modifications (spawn, delete, archetype migration) occur at explicit barriers
or through deterministic deferred command buffers.

**One whole-state critical loan cannot be implicitly shared by workers.**
A compiler/runtime-owned splitter must temporarily turn the exclusive
ECS-world loan into non-overlapping component/chunk loans; all workers must
join or cancel and physically exit before the world loan is reconstituted.
The same guarantee must cover exceptions/panic, async suspension, actor
termination, hot reload, GC/arena reclamation, and pinned native buffers.

Execution permits are non-forgeable *region capabilities*, not OS thread IDs.
They are scoped, affine, generation-bound and must be revoked on actor
restart/migration/hot upgrade. Cross-actor data move remains by message or
explicit verified immutable sharing, never by exchanging raw arena pointers.
A multi-carrier actor (`min=1, max>1`) still needs the same state loan
exclusivity; request/message concurrency settings are independent.

## Must-pass tests before parallel ECS release

1. Compile-fail: non-Send ownership handoff, non-Sync immutable share,
   nested generics, unsound captures, aliasing `&mut` loans, escaped loans,
   over-broad trait spoofing and unknown host resources.
2. Exhaustive/model: region splitting, borrow return/join, actor state loan
   exclusivity, cancellation, hot-reload/epoch fencing, panics and restarts.
3. Differential/thread sanitizer: randomized component read/write schedules,
   false-sharing cases, dynamic archetype transitions, invalidation of stale
   entity IDs, and 1 versus N worker determinism.
4. Backend parity: Java/Graal, native LLVM, Wasm and freestanding core (with
   optional allocator/actor executor). No OS thread dependency in the core
   auto-trait analysis.
5. Performance: benchmark serial AoS, serial SoA and parallel chunk SoA.
   Report memory bandwidth, L1/L2/LLC misses, cache-line invalidation,
   actor-message overhead, wall-time throughput and p95/p99 latency.
   Any optimization must demonstrate net benefit against the serial SoA baseline.

Do not equate `Send + Sync` with lock-freedom or with cache locality:
synchronization, false sharing, scheduler overhead and memory-domain transfer
costs remain independent engineering obligations.
