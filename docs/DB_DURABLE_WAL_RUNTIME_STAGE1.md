# Native DB durable WAL: Java/GraalVM physical host substrate

**Status:** host-only file primitive and internal actor-affine async adapter; no guest ABI, authenticated tenant/shard registry, or
commit ACK or database engine.

`DbDurableWalFile` is package-private and intentionally not exported through
Java interop. The runtime must separately authenticate actor/context/tenant,
enforce scoped READ/WRITE permissions, pin the physical I/O worker/actor
lifetime and supply a private, non-hostile directory. This synchronous
component may run only via bounded `AsyncNativeIo` work, never on a carrier.

It rejects invalid basename/path traversal, final-component symlinks,
hardlinks (on Unix), competing advisory locks, stale generation, zero
operation ID and frames outside WAL-v1 31..1118 bytes. A completed call
writes the whole frame and invokes `FileChannel.force(true)` before returning
an internal receipt with the generation/op ID/byte range. A failed or
uncertain write poisons the open handle and must be reconciled by replay,
never treated as a definitely aborted write.

**Caution:** Java's path-based open/stat checks are not an openat-style
race-free sandbox against an adversarial directory. No guest-supplied
directory is acceptable. Advisory locks are not a hostile-process
exclusion mechanism. A newly created directory entry requires its
separate parent-directory sync before claiming power-loss persistence.
The receipt does not authenticate guest callers or prove logical WAL
frame validity, tenant authority, replay, deduplication or checkpoint.

Test: `mvn -Dtest=DbDurableWalFileTest test`. Host-only local isolated
`javac --release 21` smoke verified write/force/reopen, locking, generation
and symlink denial, but GitHub exact-head Maven/Graal/guest execution is
**NOT_EXECUTED** until a real admitted runner executes test steps.
No cross-organization mirror, merge or release was performed.

The additional package-private `DbDurableWalAuthority` now checks the
currently executing owning ActorRuntime and ActorId, requires scoped
READ+WRITE permission on the trusted file path, schedules open/append+force
via bounded context-owned `AsyncNativeIo`, and uses the existing physical
resource loan registry around a detached copy of frame bytes. Revocation
uses the CONTROL admission lane and seals concurrent operations. If queued
work is discarded or its outcome is unknown, the gate fails closed until
trusted recovery. Neither the host class nor its receipt is exported to
Oreslang guest code. Tenant/shard authorization, capability registry
generation validation, and durable ACK remain separately required. This
adapter has NOT run against the complete exact-head Maven project; a
local isolated compile using narrow API stubs only checks Java syntax.

Next: trusted context-owned capability/handle registry with authenticated
incarnation and storage authority; AsyncNativeIo submission/physical loans;
native guest API lowering; typed `Unknown(op_id)` on uncertainty;
streaming WAL recovery, directory sync, crash injection and LLVM parity.


## Stage 1.1: revocation physical-retirement fence

Package-private `DbWalAuthorityLifecycle` replaces the unsafe
`revoked` boolean in `DbDurableWalAuthority`. The one-way physical lifecycle
is `OPEN -> DRAINING -> CLOSING -> CLOSED`. The first drain request seals
all new writes even when a write is running, but a subsequent owner retry
may begin a *single* close once `physicalWriteRetired()` has been called
by the actual I/O worker. Only after `DbDurableWalFile.close()` and its
I/O resource loan exit does the adapter unregister the file and finalize
`CLOSED`. A never-started/discarded job retains the slot; an asynchronous
CONTROL close that never executes remains CLOSING and is subject to
context-owned physical resource quarantine/retirement. A Future's logical
completion is not physical retirement or proof of disk durability.

`DbWalAuthorityLifecycleTest` checks running drain, duplicate close,
never-started operation and 250 concurrent races; execute with
`mvn -Dtest=DbWalAuthorityLifecycleTest,DbDurableWalFileTest test`.
Local Java 21 compile and 500-iteration standalone state-machine stress
passed against a corresponding local source copy, but exact-head Maven,
compiler guest, JDK25 and CI runner are **NOT EXECUTED**. There is no
physical-worker race test of the complete `DbDurableWalAuthority`
in this stage.

## Stage 1.2: exact physical non-start callback and shutdown ownership

`AsyncNativeIo.submitWithNonStartHook` invokes a trusted, short,
nonblocking callback *only* when its operation is rejected before admission
or wins the `ADMITTED -> CANCELLED_BEFORE_START` race during shutdown. A
worker that has entered `RUNNING` never invokes the callback; only the
actual I/O worker retires its WAL reservation. This removes a stranded
capacity/queue-discard reservation without assuming an unknown physical
write was rolled back. Callback failures quarantine the I/O context.

`DbDurableWalAuthority.appendAndSync` uses this hook to call
`DbWalAuthorityLifecycle.neverStartedWrite()`, while actual physical
completion calls `physicalWriteRetired()`. The `openForActor` failure
path checks if `AsyncNativeIo.register` retained a late resource after
shutdown and **does not** close that resource before the context-owned
physical join. This addresses a premature-close race distinct from logical
future cancellation. `DbWalAsyncAdmissionTest` covers bounded admission,
queue discard under shutdown interruption, no release of an actually running
worker, and post-shutdown registered-resource quarantine. The test's
noncooperative running worker intentionally ignores interruption until an
explicit release, modelling in-progress kernel I/O.

These are host-only primitives: no source guest authority/ABI, WAL replay,
parent dirsync, filesystem power-loss or commit ACK. Exact-head full
Java/JUnit integration and runtime permissions must pass on an executing
runner before promotion; local state-machine models are not a substitute.
Command: `mvn -Dtest=DbWalAsyncAdmissionTest,DbWalAuthorityLifecycleTest,DbDurableWalFileTest test`.

## Stage 1.3: context-issued shard grant registry (host-only)

`OresContext` now owns exactly one `DbWalTrustedGrantRegistry`, sealed before
context shutdown joins. The trusted issuer (not a guest message) must first
authenticate the logical tenant, shard, actor, and desired generation. It may
then issue an opaque, private-constructor, non-serializable grant within the
current ActorRuntime turn. The registry reserves the tenant/shard for one
physical actor identity and generation. A draining grant remains reserved
until physical file close and native resource-loan release; an older
incarnation or another actor cannot claim the same shard during that window.
An independently created registry cannot authorize `openForActor` because
the authority checks the grant's issuing registry against the exact
`OresContext.dbWalGrants()` instance.

`DbDurableWalAuthority.openForActor` now REQUIRES an issuing-context grant.
Its append path verifies current actor, grant liveness, and exact generation
before scheduling physical I/O. Revocation seals the shard grant before
CONTROL close; the grant is retired only after the file/physical-loan
retirement path. No guest can construct or transport this host-internal Java
class through any explicitly published DB guest API, because such an API
does not exist yet. The storage file's WAL frame tenant/shard/sequence and
operation digest are NOT validated against the grant in this stage, and
authorization of the tenant-to-actor mapping remains a prerequisite trusted
host responsibility.

Added `DbWalTrustedGrantRegistryTest` to check foreign actor denial,
a second registry's rejection, duplicate shard issuance, blocked
replacement during DRAINING, generation replacement only after trusted
retirement, stale old-grant denial and registry seal. This is
**source-authored host test evidence**, not an executed test or a security
proof. Executable exact-head command:
`mvn -Dtest=DbWalTrustedGrantRegistryTest,DbWalAsyncAdmissionTest,DbWalAuthorityLifecycleTest,DbDurableWalFileTest test`.
A real runner must then validate actor context lifecycle, full Maven/Graal,
native guest lowering, and filesystem fault/crash injection. No DB durable
commit ACK is implemented.
