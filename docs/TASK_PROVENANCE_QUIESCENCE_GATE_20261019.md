# Task provenance + physical quiescence gate — 2026-10-19

Status: **draft compiler-analysis integration; Task execution remains disabled**.

This branch stacks on #1526 and recovers the useful scoped-loan ideas from
closed/unmerged #1448 without reviving its raw `quiescent(ticket)` API.

## Separation of authorities

There are now three deliberately different questions:

1. **Detached owned transfer** — handled by
   `ThreadSafetyTraits.isOwnedSend(type, proof)` on #1526. A detached capture
   must own its value; nested borrows and readonly alias capabilities fail closed.
2. **Scoped shared loan** — handled here as `Mode.READ`. The resolved referent
   requires structural `Sync`, plus compiler-minted allocation/owner/generation
   provenance and a scope/outlives proof.
3. **Scoped exclusive loan** — handled here as `Mode.WRITE`. The resolved
   referent requires structural `Send`, exclusive access in the loan ledger,
   mutable referent authority, provenance, and a scope/outlives proof.

A scoped loan is not an owned move. `admitScopedTyped` intentionally does not
call `isOwnedSend`.

## Provenance model

A place is rooted in compiler-owned provenance:

- trust domain: one of `PROCESS`, `ACTOR_SHARED`, `ACTOR_PRIVATE`,
  `UNTRUSTED`;
- owner identity, including actor/tenant epoch where applicable;
- allocation ID and generation;
- whether distinct allocation identity is actually proven unique;
- zero or more layout-proven field/range projections.

Unknown domain strings, mixed owner identities, stale generations, unproven
disjointness and ambiguous aliases all fail closed. Bounds remain conservative:
4,096 live children, 1,024 claims per child, 65,536 tracked allocations and
64 projection levels.

The resolved type supplied with a place is still a **trusted compiler input**.
Future MIR/place integration must mint it from the same place-resolution pass;
guest strings/annotations must never construct provenance.

## Quiescence capability

Admission returns an opaque `Admission` containing:

- a `Ticket` for cancellation observation; and
- one `PhysicalCompletion` capability tied to that exact ticket/verifier.

There is no public/raw `quiescent(ticket)` method. Cancellation only marks
intent and leaves the loan live. `seal()` stops new admissions but also leaves
all loans live. `finishScope()` requires both an explicitly sealed scope and
zero live loans.

Trusted lowering must bind:

`TaskHandle.getDone() / TaskScope.getDone() -> PhysicalCompletion.releaseAfterPhysicalQuiescence()`

and **must not** bind:

`cancel() -> release`

or:

`TaskHandle.result().isDone() -> release`.

#1407 already provides the runtime-side physical barrier: `getDone()` publishes
only after terminal outcome, scheduler-confirmed physical exit and owned
descendant retirement. The actual cross-branch adapter is deliberately not
implemented here because #1407 lives on a different runtime stack.

## Additional fail-closed rules

- Empty loan sets cannot mint an admission capability.
- Nested borrowed referent types are rejected until explicit multi-region
  outlives composition exists.
- A readonly record capability cannot mint a WRITE loan merely because its
  structural value type is Send.
- List-like mutable storage can qualify for scoped WRITE when its element type
  is Send, but not scoped READ while its Sync contract remains unproven.
- Unsupported capabilities such as Future remain denied even if a permissive
  nominal callback is supplied.
- Erased `dyn` witnesses are independently rejected by the scoped-loan
  verifier, even if a future trait engine grants `dyn I + Send/Sync`; scoped
  dyn requires its own concrete-implementation/layout and outlives proof.

## Tests and evidence boundary

Committed:
- 11 `TaskAccessVerifierQuiescenceTest` JUnit methods;
- dependency-free `TaskAccessVerifierSmoke`;
- strict Java 21 `javac --release 21 -Xlint:all -Werror` runner;
- dedicated `task-access-verifier-kernel` workflow.

No production Maven/JUnit/GraalVM pass is claimed until an Actions runner
actually executes these exact sources. The source organization has repeatedly
created zero-step jobs. Cross-org exact-tree execution remains forbidden until
#1430 is independently reviewed/merged and the reviewed allowlist blob,
destination repository ID and private visibility all match.

## Remaining integration gates

1. Mint Root/Place/type provenance from the canonical compiler place/borrow
   analysis, not ad-hoc caller values.
2. Rebase onto the final #1467 dyn-aware trait authority; erased dyn remains
   fail-closed unless compiler-minted Send/Sync bounds exist.
3. Bind each PhysicalCompletion capability to #1407's exact TaskHandle/TaskScope
   physical-quiescence future with one-to-one child identity.
4. Prove cancellation, panic/raise/throw, nested descendants, FFI/device pins,
   defers/finally and code-version leases cannot release a loan early.
5. Execute exact-tree Java/JUnit/GraalVM and LLVM/native sanitizer/race suites
   before enabling source Task loans.
