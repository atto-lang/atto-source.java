# Runtime-only Region ownership transfer admission gate

Status: **IMPLEMENTED HOST PRIMITIVE; NOT SOURCE-GUEST OR ASYNC-GPU INTEGRATION**.

## Trust boundaries

`dev.oreslang.runtime.RegionOwnershipGate` is package-private. Its input
`DopRegionSchedule.Root` is minted inside the trusted runtime and exposes
logical row/field counts, **not physical storage capacity**, an allocation
pointer, a device capability, or authority to transfer arbitrary handles.
A trusted physical storage adapter must tie that root to a stable, verified
allocation descriptor; the gate itself cannot certify a physical allocation.

Each logical Root may mint **at most one live ownership gate**. An
identity-keyed, bounded (65,536 live gates) registry refuses duplicate
ownership gates; explicit retirement releases that root claim. The initial
owner receipt is issued at most once. The registry deliberately holds a
root until explicit retirement rather than reclaiming ownership while a
forgotten resource could still be physically active.

A `RegionOwnershipGate.Owner` is an opaque, incarnation-specific host
receipt. Do not serialize it as an actor message, make raw `Region<T>`
actor-sendable, expose its constructors through interop, or accept ownership
identifiers supplied by guests. The gate has no public constructors or entry
points in an external Java package.

## Implemented linearization

1. Initially exactly one owner receipt is valid. Concurrent read borrows are
   allowed; write borrows are exclusive. A live borrow prevents transfer,
   retirement, or a compute submission lease. A read borrow cannot write.
2. `prepareTransfer(owner, newDomain)` seals the sender's access while the
   mailbox attempts admission. `Transfer.admitted()` is the *single*
   owner-handoff linearization point; the old receipt fails afterward.
   `Transfer.rejected()` restores the sender. Concurrent admission/rollback
   cannot both win. Tokens are identity-checked and one-use.
3. `beginCompute(owner)` reserves the region without changing the owner.
   The owner cannot access, transfer or retire the allocation while that
   compute lease remains in flight.
4. A cancellation request is **not** physical quiescence. Only trusted
   runtime/backend glue may call `trustedBackendQuiescent(success)` after
   a real CPU/device completion receipt. If cancellation was requested or
   `success=false`, the region is poisoned and may only be retired after
   the backend reports quiescence; it cannot be reused or transferred.
5. Compute completion and owner transfer are epoch/identity-bound:
   stale, duplicate and post-transfer completion claims are rejected.

No individual row operation is performed by this primitive. An actual
Region storage implementation must route *all* read/write accesses through
the owner/borrow checks. A runtime mailbox must commit the ticket only after
its own linearizable admission; the checked-in test calls that method as a
trusted host simulation and does not assert a real mailbox connection.

**Critical remaining gap:** The `trustedBackendQuiescent` method is
currently package-private internal authority; it is **not yet connected**
to the preexisting `ComputeAuthority.completePair`/
`TrustedOpenClProvider` hardware-fence lifecycle, nor does this PR
introduce `compute.submit` or `OresFuture` continuation lowering.
The production bridge must require a matching physical fence generation
and enqueue Oreslang actor continuations on the owning
`OresScheduler`; callbacks must never run source actor code inline.
Do not treat a host-caller-invoked quiescence method as proof of GPU
completion. The primitive is not currently wired to ActorRuntime admission.

## Executable host checks

Run at an immutable, clean source commit using Java 21+:

```sh
bash scripts/test-region-ownership-gate.sh
```

The script compiles exactly the repository's `DopRegionSchedule.java`,
`RegionOwnershipGate.java`, and `RegionOwnershipGateCheck.java` using
`javac --release 21 -Xlint:all -Werror` in a temporary directory.
Its Java host test exercises 111 assertions (including 64 concurrent
admission-versus-rejection races, stale owner, live read/write borrow,
in-flight compute, cancelled and failed compute poison/retirement).
It then uses `javac` to verify that a Java class in an outside package
**cannot** mint the package-private ownership gate. This Java compile-negative
test is **not** a compile-negative Oreslang `rt borrow` fixture.

The code and checks were exercised locally with OpenJDK 21 and a
source-compatible reconstruction of the existing logical Root API; the
private repository's full Maven/JUnit/guest build and the current exact
GitHub PR head **must still be executed on an authorized source-org runner**.

## Promotion gates (not yet met)

- Actual Source `Region<T>` ownership lowering: compile-positive unique
  transfer; compile-negative raw writable Region mailbox transport, sender
  use-after-take, live reader/writer alias, frozen-view writable upgrade.
- Actual mailbox admission and closure stress, including failure-atomic
  rollback, receiver incarnation reuse, stale token and per-tenant isolation.
- Trusted physical capacity binding and backend-owned completion fence
  integration; GPU cancellation must not be treated as quiescence until real
  physical acknowledgement. No native/device pointers in guest values.
- Actual async `OresScheduler` suspension releases actor's execution lease;
  even precompleted results resume only after scheduler dispatch, never
  on the device callback thread.
- Run JDK 25, GraalVM Native Image (if claimed), native sanitizer and each
  claimed backend with exact head and root-tree receipts. Runtime issue #236
  remains open until all production integration gates pass.
- No private-source cross-org mirroring without separate transfer approval.
