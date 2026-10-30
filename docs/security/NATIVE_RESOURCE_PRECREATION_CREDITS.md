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
