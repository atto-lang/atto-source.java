# Native DB durable WAL: Java/GraalVM physical host substrate

## Stage 1.9 — deterministic maximum-frame/delete replay regressions

`DbWalReplayV1Test.maximumPutAndDeleteTornTailDifferentialCorpus` adds a
portable reference corpus shared byte-for-byte in construction rules with the
LLVM test: tenant 7/generation 4, PUT(1,41,key=1,value=0), maximum
PUT(2,42,key=64,value=1024), and DELETE(3,43,key=64,value=0).
The 1,118-byte PUT checksum bytes MUST equal `42 1d 02 86`; the 94-byte
DELETE checksum bytes MUST equal `23 1f df 0a` (independent golden values).
Reopen scan expects exactly 3 records, 1,243 bytes, sequence 3 and operation
43. All 1,117 nonempty incomplete maximum-frame prefixes retain the durable
first record without an automatic truncate/ACK; complete corrupted checksum,
illegal DELETE payload and 1,025-byte oversize value are rejected read-only.
This is an authored host regression, not exact-tree JUnit/guest execution.



## Stage 1.8 — partial WAL header validation (host-only)

Recovery now checks every available prefix byte of ODB1 magic/version,
tenant, generation and next sequence, and validates the complete kind,
operation ID, key length and value length whenever each is present.
A valid torn final frame is still reported as `incompleteTail`; a malformed
short/header-only tail is a hard failure instead of a repair invitation.
In particular, a full 26-byte header with the wrong owner or out-of-order
operation can no longer become an apparently benign truncated payload.

The read-only Java regression iterates all 30 valid truncated frame
prefix lengths, exercises nine malformed truncated headers, and asserts
that scanner failure never changes file length. This is **source-authored**;
it requires executing exact HEAD JUnit, native compiler and crash recovery
gates before promotion.


## Parent-directory entry sync (stage 1.7, host-only)

After taking the exclusive WAL file lock, `openHostOnly` now opens the
same trusted parent directory without following a final-component symlink
and requires `FileChannel.force(true)` on that directory before returning
a writable WAL owner. A failure to open or force the directory closes the
new file descriptor and propagates the error; the caller may NOT treat a
failed open as a granted durable WAL authority. A newly created file name
is not secured by forcing only the WAL contents. The host must support
directory channels; unsupported hosts fail closed. The existing path-based
open/force sequence is NOT a secure filesystem sandbox in an attacker-
writable directory, nor a power-loss/persistent-device durability proof.

Added host regression checks for symlinked parent refusal and directory
force availability. These require an executing Maven/JUnit runner for
exact-tree evidence. No native guest `Committed` path or directory-rename
checkpoint publication is added.



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

## Stage 1.4 — trusted issuance and post-callback physical retirement

WAL grant issuance is now **DENY ALL by default**. The internal
`DbWalTrustedGrantRegistry.HostTenantAdmission` policy must be supplied
by a trusted host with an authenticated actor-to-tenant/shard mapping;
production `OresContext` currently installs NO permitting policy. The
new host-only test injects an explicit synthetic permit and verifies that
the same actor cannot issue an unapproved tenant/shard grant. This does
NOT claim a production tenant identity provider or usable guest database API.

A shard grant must bind once to the exact `DbDurableWalFile` reference
created during trusted open; a second or drained binding fails closed.
The grant can retire only if the *same bound file* reports closed and
`DbWalAuthorityLifecycle` has reached CLOSED. Most importantly, the
`DbDurableWalAuthority.revoke` path no longer retires the grant in its
file-close callable. Instead it registers an internal
`AsyncNativeIo.submitControlWithRetirementHook`. That hook runs only
after the owning I/O worker has finished the callable, completion
subscribers have drained, and the worker's physical operation loan has
retired. An unadmitted or cancelled-before-start CONTROL close receives
no physical-retirement callback and must stay fenced/quarantined.

`DbWalAsyncAdmissionTest` now exercises a blocked terminal subscriber
and verifies that physical close retirement cannot overtake it, as well
as no forged retirement hook for a rejected CONTROL task. The shard
registry test checks real file identity binding, refuses early
retirement with an open file, then tests a closed-file lifecycle.

Remaining P0: runtime installation of authenticated tenant policy;
directory-fd/race-free filesystem validation; binding WAL frame
tenant/shard and checksum to grant; closing/discarded-open cleanup and
a per-grant open-admission reservation; guest-language lowering and
real WAL durability/recovery. **Exact-head Maven/JDK25, Java host tests,
Graal Native Image and compiled .ores guest tests are NOT EXECUTED.**
Suggested first runner gate:
`mvn -Dtest=DbWalTrustedGrantRegistryTest,DbWalAsyncAdmissionTest,DbWalAuthorityLifecycleTest,DbDurableWalFileTest test`.


## 31 October exact-blob local host-only execution

Source PR head at the time of local tests:
`d3a5b4588250abe0dd7ab09a258db2a00d2f256b`.
The isolated `DbWalAuthorityLifecycle.java` source had Git object SHA
`9a3a650d4fd35a1cf882fcfffc3951a106ec8f37`
and `DbDurableWalFile.java` had SHA
`1a031f417e2ed4db2634c1e687b12978b47e7ef5`.
Both were independently reconstructed in the local container, checked
with `git hash-object`, and compiled under Java 21 with
`javac --release 21 -Xlint:all -Werror`. A separate host-only lifecycle
harness passed **1,000 concurrent drain/physical-worker retirement
rounds**, as well as never-started reservation retry checks. A separate
physical file smoke harness passed bounded append + `FileChannel.force(true)`,
sync receipt offsets, stale-generation rejection, exclusive file lock,
physical bytes/size after close and reopen. These ARE executions of
the exact isolated source blobs, but NOT Maven/JUnit/Java 25/Truffle/
Graal Native Image or source-generated .ores execution.

Private runner capacity was probed without copying Oreslang source:
`akrion-sim/akrion-sim-sync` canary
`41231418786` and `agent-pontifex/agent-pontifex-docs` canary
`41231508338` both failed before runner admission with zero steps
and `runner_id=0`. Recent other private carrier runs had equivalent
pre-execution failures. Neither a private exact-tree mirror nor full
source compilation was run. Private source remains in the source org.

**Caution:** `openHostOnly` is not race-free under a hostile
directory; Java path-based file attribute checks are not a descriptor
fstat. Parent directory sync and a file-backed, actor-authenticated
commit/ACK protocol are still missing. A returned host SyncReceipt
is not DB durability proof.


## Stage 1.6 — strict ODB1 frame-to-grant matching

`DbWalFrameV1.requireMatching` now validates an already detached frame:
ODB1 version/kind, positive signed-31-bit tenant/generation/sequence/op ID,
key/value limits, total length, DELETE value restriction, and Adler32
trailer. It rejects a tenant, generation or operation ID that differs
from the context-issued shard grant and requested operation. Validation
occurs on a copied buffer **before** reserving an async physical write.
The checksum detects corruption but does not authenticate the sender.

Added JUnit `DbWalFrameV1Test` for positive limits, wrong owner, mutated
bytes, invalid delete/sequence/high bit. Exact Java source blobs
`DbWalFrameV1.java` = `ec1a23fadeaeff0e88b71fe62ddb46f004a9ba31`
and `DbDurableWalFile.java` =
`1a031f417e2ed4db2634c1e687b12978b47e7ef5`
were reconstructed locally and independently checked with `git hash-object`.
Both compiled under Java 21 `--release 21 -Xlint:all -Werror`; a
standalone host harness passed 5,000 generated frames, deliberate byte
corruption rejections, and 100 validated physical appends with
`FileChannel.force(true)` and successful reopen.

This is **host execution, NOT exact-head full Maven/JUnit/JDK25/GraalVM
or .ores guest evidence**. The live actor does not yet call this runtime
binding, and authenticated tenant issuance, sequence/idempotency persistence,
parent directory sync and WAL replay/ACK remain absent.


## Stage 1.7 — locked-host single-generation recovery preflight (31 Oct)

`DbWalReplayV1.scan` performs read-only bounded ODB1 v1 scanning of
a single-generation segment with one reused 1118-byte buffer and one
reused `ByteBuffer`. It validates every complete frame's tenant,
generation, Adler32 and bounds; sequence must start at 1 and increase
without gaps; operation IDs must increase strictly. A final short
header or incomplete frame returns an `incompleteTail` report with
last complete byte offset, **without truncation or state publication**.
Complete frame corruption, mixed generation, duplicate or reordered
operation IDs, and gaps raise an error. There is no transaction replay,
checkpoint or guest durable ACK. The physically locked
`DbDurableWalFile.scanRecovery(tenant,segmentGeneration)` path requires
an opened, non-uncertain file and an independently authenticated,
exclusive shard-recovery fence held by its host caller.

`DbWalReplayV1Test` covers fresh file reopening, partial terminal
record detection without mutation, full-record checksum corruption,
duplicate op ID, sequence gap and tenant/generation mismatch through
both scanner and locked owner. **Tracked JUnit authored, not executed**.

For independently executed **local Java 21** source-exact evidence,
`DbWalFrameV1.java` Git blob `dfe689a40cca7c52f3134d4ad23bfab634c572b5`,
`DbWalReplayV1.java` `7aa518e3525cc70dbbd2bb36f554a025c6e42351`,
and `DbDurableWalFile.java` `21b8fc43478c4852cc3c4833adf13f9fabf826f5`
were checked by local `git hash-object` and compiled with
`javac --release 21 -Xlint:all -Werror`. A separate executable host
harness completed fresh reopen, valid records, truncated tail,
checksum corruption, duplicate/reordered op, sequence gap,
cross-tenant denial and locked owner scan: `JAVA_WAL_REPLAY_HOST_PASS`.
This is isolated host evidence, NOT complete exact-tree Maven,
JDK25, Graal Native Image or guest .ores execution.
