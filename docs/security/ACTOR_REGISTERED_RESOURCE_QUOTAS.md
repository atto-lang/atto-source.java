# Actor-owned native registration quotas — exact-head draft

**Status:** implementation candidate, NOT executed JDK25/GraalVM guest proof,
NOT an OS-level security sandbox. Stacked after Java #2324 -> #2323 -> #2292
and its permission/delegation predecessors. Shared reference: platform PR #246.

## Enforced admission model

- `ActorPermissionScope` accounts registered **handle count** and
  **trusted host-declared byte attribution**, separately from the existing
  in-flight native operation quota. Every reservation charges the actor's
  delegated scope and all ancestor scopes.
- Root fallback ceilings are 65,536 registered handles and 64 MiB declared
  bytes. A parent can delegate a narrower ceiling using the trusted
  actor-only `delegatePermissions(restrictions, maxIo, maxHandles, maxBytes)`.
  A child cannot increase any cap; values are immutable after delegation.
- `AsyncNativeIo` independently bounds registered handles per context, with
  an explicit four-argument host constructor and a bounded default.
  The existing native worker/queue capacity continues to constrain
  operations separately. The context rejects registrations whose declared
  accounted bytes would exceed its 64 MiB admission ceiling.
- `register(resource)` preserves source compatibility but gives **zero
  accounted bytes**: count is charged, unknown physical size is not magically
  measured. Trusted adapters handling known buffers should use
  `register(resource, accountedBytes)` with truthful retained-byte figures.
- For actor-origin registrations, the exact actor-incarnation lifecycle
  lock is held across authority validation, scope quota reservation,
  registration publication and physical-liveness counter increment.
  STOP uses the same lock: no actor can finalize between resource
  admission and publication.
- Each registered handle remains charged until **successful native close**
  in the context's post-worker shutdown sweep, or explicit trusted-host
  `unregister` ownership transfer. If a resource is unregistered while
  borrowed, accounting remains charged until the last physical loan ends.
- `unregister` is an ownership transfer to trusted host code. It is not a
  proof that the underlying OS descriptor was closed: the receiving owner
  must close/account it under its own policy. It is NOT a guest escape API.
- Resource-close failure retains the handle, its registered quota receipt
  and original actor physical-liveness lease for retry or quarantine.
  Duplicate or underflowing receipts fail closed. Actor finalization must
  not pass its installed native loan gate while a registered actor-owned
  handle is still tracked by this context.
- After context shutdown begins, a previously admitted native operation may
  still produce a handle. The context **retains/quarantines** this physical
  handle even though registration fails. If it crosses the resource
  admission ceiling, the context records an incomplete security receipt
  and does NOT falsely report clean physical retirement. This is NOT
  preemptive prevention of an already-issued OS allocation.

## Resource and threat-model limitations

- **Bytes are declared, not measured**: zero-byte legacy registration can
  underreport physical memory. Do not claim actual memory-quota enforcement
  until all buffer/socket/FFI allocators route through pre-allocation
  reservations using measured, nonzero sizes, and transfers retain accounting.
- **Registration is post-creation** for some native resources: it is too late
  to guarantee a hard number of created OS handles when native syscalls run
  before admission. An OS-level process limit (e.g. descriptors) and
  pre-creation credits must be integrated in the trusted broker.
- No global runtime-wide descriptor cap or physical cgroup/RLIMIT sandbox
  is claimed. Host-side owners that take unregistered handles need their own
  resource accounting. Network file descriptors, socket buffers and shared
  arena buffers can require different or overlapping budget dimensions.
- This patch does not add compiler syntax, guest-level direct host access,
  untrusted FFI, per-actor kernel sandboxing, fuel preemption or memory proof.
- The quota lock is shared across a root scope; admission cost and lock-order
  interactions must be measured for hard-real-time usage.
- Registered physical resources are counted in the existing
  `actorOperations` liveness ledger, not reclaimed when Futures merely
  complete. Failed native cleanup quarantines actor finalization.
- Context shutdown is now a bounded two-pass physical join: STOP is requested,
  native I/O workers/handles retire, and actor finalization is rechecked
  after native retirement. Otherwise actor finalization could wait forever
  for a registered handle that the old coordinator did not close until after
  waiting for the actor.
- The shutdown coordinator **does not** declare success after a failed
  retirement pass. It only succeeds if subsequent independent actor and I/O
  join attempts both complete; unreconciled failures remain quarantine gates.

## Verification matrix / merge block

1. Exact-head source and SHA readback; distinguish stacked base changes.
2. JDK25 Maven targeted JUnit:
   `ActorRegisteredResourceQuotaTest,ActorNativeIoIncarnationFenceTest,
   ActorSubtreeIoQuotaTest,PhysicalIoLoanRevocationTest`.
3. Nine JUnit methods including a production-coordinator shutdown regression.
   Host resource lifecycle regressions: duplicated registration,
   unregister-with-live-loan, scope revocation, handle/byte exhaustion,
   exact-once close, failed close and retry, context shutdown races,
   real child registration after STOP and ancestor/sibling isolation.
4. Native Image AOT and hybrid plus native socket/file handle tests.
   Test actual OS descriptor limits and out-of-memory/fuel behavior in
   sandboxed subprocesses, not merely modeled counts.
5. Execute compiled .ores guest positive/negative fixtures for Java,
   LLVM, .NET, Wasm and Forge. Cross-backend parity is **0/5 verified**
   for this additional registration contract until execution receipts exist.
6. No unreviewed cross-organization private-source transfers.
   A CI workflow reporting failure and `steps=[]` proves neither
   compiler failure nor test success.

Do not merge this draft until the stacked dependency chain, security owner
review, exact-head executable evidence and branch-protection gates are met.
