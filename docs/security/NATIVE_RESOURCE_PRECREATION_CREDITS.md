# Native socket pre-creation admission credits

**Status:** unmerged Java source candidate; native/JDK25/GraalVM and Oreslang
guest execution not yet verified. This is NOT an OS sandbox.

Stack base: Java [PR #2326](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2326)
which depends on [#2324](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2324),
[#2323](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2323),
and the physical native-loan revocation work in #2292.

## Two-phase physical resource admission

1. The trusted host calls `AsyncNativeIo.reserveResourceCreation(bytes)` BEFORE
   constructing an OS-capable resource. The context checks both registered
   resources and outstanding creation tickets. Permission scope ancestry
   reserves the same handle + declared-byte quota.
2. If there is actor authority, creation-credit publication and actor-liveness
   accounting are serialized with STOP using the captured ActorCell lifecycle
   lock. The ticket remains strongly owned by the context during construction.
3. After construction, `registerCreated(resource, credit)` transfers the
   **same** quota and liveness receipt to the registered native resource.
   No release/re-admission gap exists. The ticket's try-with-resources close
   after successful transfer does not refund the registered handle.
4. If construction fails BEFORE a handle exists, closing the unconsumed
   permit releases its credit exactly once.
5. A handle that becomes real after STOP or shutdown cannot be published
   to guest code. It is nevertheless attached to the existing context's
   quarantine/close ledger, with its quota and actor liveness retained.
   The adapter must not close/unregister that quarantined handle itself.
6. `AsyncNativeIo.close` joins outstanding construction permits as well as
   native workers, loans and terminal completion callbacks. A pending permit
   makes a clean physical-retirement receipt impossible.
7. The first real adapter integrated is `NativeIo.connect`: it reserves one
   zero-declared-byte socket credit before `new Socket()`, publishes the
   created socket with the existing receipt, and performs `borrow` checks
   before connecting. A failure only unregisters the socket after successful
   physical close. The existing guest TCP read/write/end-to-end tests must
   be rerun on the exact implementation head.

8. A permit carries its originating actor-incarnation identity, not merely
   inherited permission scope or actor ID. Only the same actor may attach or
   abandon an untransferred permit. An attempted foreign attachment keeps
   the physical handle charged in the quarantine ledger.
9. Actor-registered resources cannot be borrowed or unregistered by a
   different actor. The TCP wrapper checks its originating actor before
   read, write AND physical Socket.close(). Explicitly host-registered
   resources preserve the existing trusted-host sharing semantics.

## What this does NOT yet prove

- This covers one socket-creation path, not all file, server-listener, FFI,
  GPU, HTTP client, mmap, subprocess or network-host allocator paths.
- The `Socket` constructor may defer the actual kernel FD until connect.
  The ticket still pre-reserves an allowed handle before that stage.
- Declared `0L` bytes means unknown socket/buffer allocations; those bytes
  are not measured. All actual pre-allocation buffer adapters need trusted
  precise sizes and OS-level limits for an enforceable total memory budget.
- A permit is authority internal to the Java runtime, not a serializable
  guest capability or a substitute for OS process sandbox boundaries.
- The host source currently uses synchronized monitors, map/set allocation,
  and waiting; it does NOT establish hard-real-time worst-case bounds.
- Native syscall dispatch after revocation remains separately subject to
  `IoLoan` checks and OS cancellation semantics.
- There is no verified .llvm, .wasm, .net, or .forge implementation of this
  precreation contract. The shared semantics should be equivalent but each
  backend needs actual executable negative tests and independent sandboxing.

## Execution gates (unverified at commit creation)

JDK25 Maven:
```sh
mvn test -Dtest=NativeResourceCreationPermitTest,ActorRegisteredResourceQuotaTest,ActorNativeIoIncarnationFenceTest,ActorSubtreeIoQuotaTest,PhysicalIoLoanRevocationTest,AsyncNativeIoTest
```

Eleven JUnit methods are checked in, including an actual actor self-STOP
between creation credit and physical registration, with a live handle retained
until the production actor/native shutdown coordinator joins it. The ninth
method checks sibling-actor denial for foreign abort, attach, borrow and
ownership transfer, without falsely releasing the real handle.
Shared reference-model test bodies currently pass 11/11 in an isolated
JavaScript harness; this is NOT execution of a Java compiler, hosted CI
workflow, or Oreslang guest.

Required edge cases: capacity exhausted before socket creation; constructor
failure refund; successful transfer without early refund; stale/cross-context
ticket rejection; post-STOP quarantine; shutdown waiting for in-flight
creation; failed socket close retention; no deadlock with native physical
retirement; real guest TCP loopback; Native Image AOT and hybrid.

Only CI with **nonzero executed steps** at the exact candidate SHA can
satisfy this verification gate. Source-org runner/admission failures must not
be treated as compiler or test failures. Never mirror private source to a
different organization without a reviewed destination allowlist and
independent authorization.

## Additional exact-head 2026-10-30 hardening

- Actor-owned native `java.net.Socket` resources may not be unregistered
  by their actor until `Socket.close()` has completed. The quota and the
  actor's native-liveness lease remain charged on early-unregister failure.
- `Socket.isClosed()` is overridable, so the actor-side gate accepts only
  exact `java.net.Socket` instances. Subclasses require trusted-host
  cleanup rather than trusting an actor-controlled close flag.
- Two additional JUnit methods cover early-unregister denial and malicious
  subclass close-flag spoofing. Total focused `NativeResourceCreationPermitTest`
  scenarios: 11, **committed but not executed**.
- The same-repo `.github/workflows/native-resource-exact-head.yml`
  uses read-only checkout, pinned source SHA/tree verification, GraalVM
  JDK25 and nonempty Surefire reports. Source-org job admission remains
  unverified; a zero-step CI failure is never proof of executing this suite.
- Real local JDK21 loopback probes independently confirmed ordinary
  `Socket.isClosed()` behavior and demonstrated that a subclass can report
  `true` while a connected base socket remains open. These small probes
  are **not** the Oreslang JVM/JUnit or Native Image build.
- The checked private-source destination allowlist has zero active entries,
  Java main protection remains unresolved in security issue #2300, and
  two examined pinned private carrier runs also recorded zero steps.
  No cross-organization private-source mirror was created.

The actor-side close check covers the native TCP `Socket` path, not
arbitrary actor-created `AutoCloseable` resource types. Those still require
trusted adapter-specific, non-forgeable physical close receipts. Explicit
host-initiated ownership transfers remain a separate trusted operation.

## Generic actor-owned resource close receipts — follow-up hardening

- Direct actor-origin `unregister(generic AutoCloseable)` now fails closed
  without a successful, trusted adapter close receipt. This prevents a generic
  native resource from refunding its ancestor handle/byte quota merely because
  guest-adjacent code drops its registration.
- Trusted host adapters may use `closeAndUnregister(resource)`: it refuses
  outstanding physical loans, blocks new loans while close runs, executes
  `resource.close()` **outside** the native-I/O monitor, then retires the
  original resource receipt without releasing or reallocating an intervening
  quota. A failed close keeps the handle strongly reachable and charged, fences
  further loans, and allows explicit retry. This callback is trusted-host
  evidence, not independent OS-level proof: native buffers/FFI handles need
  adapter-specific unforgeable physical-retirement evidence.
- Context shutdown joins any active trusted close callback before sweeping
  registered resources. A late race between join and sweep cannot issue a
  concurrent second close; retryable close failures are quarantined. Both
  `Exception` and `Error` from the native close callback retain the resource
  for a subsequent explicit trusted retry.
- Native resource accounting now falls back to the captured exact
  `NativeIoAuthority` actor ID when the worker's operation ThreadLocal is
  absent. Conflicting operation, actor-turn, and native-authority IDs are
  rejected instead of accidentally treating a retained native worker as
  privileged host code.
- The `ActorRegisteredResourceQuotaTest` tests exercise generic actor
  unregister denial, close failure/retry without credit refund, shutdown
  racing a blocked close callback, a fatal Error and retry, and authority-only
  native worker attribution. The pre-creation tests remain in
  `NativeResourceCreationPermitTest`. **None of these new JUnit test cases
  have executed on an authorized JDK25/GraalVM CI runner.**

Even with these restrictions, trusted host transfers of native resources are
explicitly allowed and the receiving host must maintain independent ownership
accounting. This is not an OS sandbox, nor proof of zero-GC or hard real-time.

### Physical-retirement receipt ordering (additional fail-closed gate)

The actor/host unregister paths and the trusted close-and-unregister path now
retain their original strongly reachable resource until the quota receipt
retires successfully. An underflow, duplicate/stale receipt or other accounting
exception cannot remove the resource from the context's shutdown sweep before
quarantine. A successful context join also requires the **registered resource
ownership ledger to be empty**, not just the ordinary close-sweep set.

The host shutdown sweep claims each registered resource before invoking its
close callback. It will not race a concurrently executing trusted adapter close,
and cannot invent success when the callback fails with either Exception or Error.
A native callback that closes its physical resource but then fails bookkeeping
is quarantined rather than being closed a second time merely to repair a
logical receipt.

This is fail-closed bookkeeping, not proof that arbitrary user-defined
AutoCloseable code really released all native memory. Buffer/FFI adapters still
need verified physical retirement semantics. Execution of the added JUnit
tests on actual JVM/JDK25/AOT remains the merge gate.
