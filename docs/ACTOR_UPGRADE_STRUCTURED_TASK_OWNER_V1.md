# Structured Task ownership in actor hot upgrades (V1)

**Status: DRAFT, host-only SHARED source actor integration.** This is NOT a
second upgrade manager and does NOT enable Oreslang guest `task {}` lowering.
The authoritative freeze and transfer protocol remains in `ActorRuntime` and
`LogicalSourceActorRouterV1`; the bounded proxy remains the only
`LogicalSourceActorProxyBridgeV1` dispatch authority.

## Scope and isolation

The temporary package-private `ActorHotUpgradeTaskOwnerV1` is attached to
each logical source actor proxy. The owner tracks Tasks and nested host child
scopes by their **physical-exit receipt**, not the cancelable result Future.
The scope reserves an ownership slot **before** router lookup, actor
generation pin, and scheduler admission. A failed HashSet growth cannot
leave untracked runnable old-code work. Task continuation/resume on different
carriers retains its original actor generation lease; separately admitted
child Tasks obtain independent pins.

The scheduler's `onQuiescent` hook must fire after the last guest execution
turn has exited. It first closes the ActorRuntime external lease and only
then settles the distinct physical-exit Future. A failed pin release marks
that exit receipt failed; the owner retains the entry, and shutdown does
NOT certify unload. Generation lease release is **never** driven by result
Future completion/cancellation.

On close, the owner iteratively seals the whole child-scope tree, revokes
every task producer with `OresFuture.cancelDeferredNotification()`, delivers
cancellation subscribers only after all revocations, and joins physical
exit with a bounded per-close deadline. A timeout or failed exit preserves
the sealed owner and makes retirement retryable. Successfully joined
records are removed synchronously before close returns, even if the
completion observer runs slightly later.

### Important limitation

This owner is a bridge into the hot-upgrade stack, **not** the canonical
compiler `OresTaskScope` from Java draft PR #1411. After reconciling its
dependencies, the compiler must bind Task state frames, actor instance
identity, code-image digest, capture permissions and child-scope roots
to a single authority. There is currently no compiler-issued frame
affinity/ownership receipt, so the temporary bridge must remain
**host-only**. No guest Task, cross-actor mutable state capture, or
PRIVATE/UNTRUSTED worker migration is authorized by these changes.

## Epoch publication

`LogicalSourceActorProxyBridgeV1.publication()` returns one immutable
pair: current logical physical-route address and mailbox snapshot, sampled
under `dispatchLock`. Read-only `address()` and `snapshot()` now also
take that monitor so neither can independently inspect the intermediate
router publication during the bridge's two-stage upgrade.

This closes the **bridge-visible torn epoch** window; it is **not** a
global atomic code/scheduler/checkpoint publication transaction. An
independent router user or code-loader still needs the eventual single
supervisor linearization authority. On ambiguous partial failure the bridge
continues to fail sealed rather than forge rollback.

## New source regressions (committed, not fully executed)

- Real `.ores` SHARED V1/V2 upgrade with one suspended pinned Task; the
  test now waits for actual exit rather than treating result `get()` as
  retirement.
- Nested child-scope generation pins prevent upgrade until the last
  physically suspended child exits.
- Close cancels an executing Task but cannot return until its carrier exits.
- Concurrent publication observer never sees mismatched proxy/route epochs.
- Fail-fast owner reservation OOME leaves no actor pin or runnable task.
- `OresFutureDeferredCancellationActorScopeTest`: sibling revoke-before-
  notify, delayed registrations, racing one-shot notification, and completed
  results defeating cancellation.

## Executed offline evidence (NOT real source runtime)

Local JDK 21 `javac --release 21 -Xlint:all -Werror` checked a standalone
synthetic actor owner/bridge implementation with stubs for scheduler,
`OresFuture` and router. It executed **2,000** child-ownership,
cancellation/close, last-exit and lease-drain checks, plus **900**
fault-injected failed-generation-retirement checks, and **500**
pre-admission ownership reservation/OOME checks. These checks were
adapted from the implementation but are **not** an exact compiler source
checkout, GraalVM/JUnit receipt, or Native Image/LLVM proof.

No private Git tree or source blob was copied into another organization.
Oreslang platform-conformance P0 security incidents #22/#77 and CI
bootstrap #69 still require independently approved source/destination
binding before any external private runner may execute source.

## Required follow-through

1. Integrate/certify compiler-owned `OresTaskScope` from #1411 and
   replace the host-only provisional owner without duplicating protocols.
2. Make code-image generation, scheduler continuation/image pointer,
   logical ActorRef route, checkpoint ABI, and mailbox epoch publish through
   one supervisor-owned atomic commit.
3. Run exact-head Maven/JDK25/GraalVM JUnit, code-loader/Native Image,
   native CTest with sanitizers, and injected crash/failure matrix.
4. Add proved protected-worker migration for PRIVATE and sandboxed
   UNTRUSTED physical actors (not same-JVM SHARED proxies).
5. Hold old image pages in quarantine until all continuation/FFI/native
   callbacks and shared-memory read loans have physically quiesced.

**Do not mark production hot upgrade complete or merge the draft
without those acceptance receipts.**
