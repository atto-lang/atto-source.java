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
