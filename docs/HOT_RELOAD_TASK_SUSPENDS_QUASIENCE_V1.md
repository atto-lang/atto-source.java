# Oreslang actor/process hot reload — Task and suspends barriers (draft v1)

**Status:** compiler/runtime integration contract, NOT deployed production hot
reload. The current `ActorUpgradeEpochGate` is host-only; it does not wire
ActorRuntime admission, stable ActorRef routing, code loading, supervisor
provenance, state migration, or real physical sandbox placement.

## Canonical memory and fault domains

| Actor kind | Per-actor owned memory | Physical address space | Code update |
| --- | --- | --- | --- |
| SHARED | Own arena + state loan; explicit immutable/frozen shared regions only | Usually same trusted OresVM process | Code-table/pinned generation, shared region grace barrier |
| PRIVATE (trusted) | Own mutable arena/heap; message values copied/moved per ownership checker | Same trusted host **unless a protected worker is explicitly provisioned** | In-place compatible code generation OR protected worker incarnation replacement |
| UNTRUSTED | Own mutable arena, strict caps/quotas and message serialization | Must have an independently enforced process or VM/isolate sandbox | Stage inside sandbox; never link arbitrary guest machine code into trusted host |

Arenas make allocation and reclamation more predictable, **not protected
address spaces**. A separate Java Truffle `Context` or Oreslang
`ActorMemorySlice` also does not by itself prove OS-process isolation.
Like Erlang/BEAM, logical actor/process heaps can be distinct while sharing
one OS process; an Oreslang PRIVATE actor can request a stronger separately
protected worker by policy. A shared actor likewise still owns an arena;
`SHARED` does not grant cross-actor mutable-field access. No OS, JIT,
dynamic loader or collector is required by freestanding core.

The gate's `Placement` record binds actor kind, required protection class,
and the canonical frozen/shared-cell ABI digest. An explicit placement must
be minted/verified by the trusted CONTROL loader. Merely constructing a
`Placement(SANDBOX_*)` value is **not** isolation attestation. The legacy
two-argument gate constructor defaults to host-only SHARED placement for
compatibility and MUST NOT be used to authorize PRIVATE/UNTRUSTED placement.

## Compilation is orthogonal to actor kind, trust and reloadability

**Neither trusted nor untrusted hot reload requires JIT.**
- JVM/Truffle: a newly validated guest source image can run through a
  supported interpreter or Truffle JIT path in a suitable VM; Native Image
  cannot arbitrarily define new unknown JVM classes after closed-world AOT.
- LLVM: load authenticated, PIC **offline AOT** modules through a restricted
  loader in an authorized worker; optionally use LLVM ORC JIT as another
  `CodeMode` in that *same worker trust domain*. A `ResourceTracker` is
  a reclamation mechanism, **not** a proof of safe unload while old pointers
  or frames are live. Avoid raw FFI pointer rebinding.
- Untrusted: do not treat a language-provided capability enum as a process
  sandbox. Enforce workers/isolation, executable-memory policy, signature
  authorization, dependencies, limits and sandbox teardown independently.
- Hardened mobile/freestanding: when JIT or dynamic linking is prohibited,
  signed dual-slot AOT replacement, re-instantiation or restart are allowed.
  Unsupported platforms fail closed.

The gate tracks `CodeMode` as metadata, but its tests with AOT/JIT *labels*
are not executable loader or compiler tests. An old version is kept for as
long as needed rather than forcing Erlang's historical two-version policy;
bound retained generations and refuse a new upgrade under pressure.

## Hot-reload-safe suspends and Task ownership

A `suspends` call stays inside the **current Task**, with the same
cancellation domain, code generation, heap/arena leases and actor execution
authority. It does not start a new Task or take a new actor mailbox identity.
On `await`, the carrier thread is released, **but the owning Task's
`SUSPENDED_TASK` generation lease remains pinned** until the continuation
has physically exited, including `defer`/`finally` and callback retirement.

An explicit `task { ... }` / `nb task { ... }` launches a new owned Task
and must obtain its own lease from the actor's generation before admission.
`do task` discards the handle, not the Task or its lease. Old and new Tasks
must never migrate implicitly across the same actor generation during a code
swap. A continuation may only resume code tied to its pinned old image, or
be cancelled+physically joined and rebuilt from a *checked durable state
snapshot* by a new generation. Do not rewrite the program counter/stack/FFI
pointer to new code.

At a supervisor-controlled freeze:
1. Reject new mailbox turns and newly launched child Tasks; CONTROL/shutdown
   must continue making bounded progress.
2. Retain leases for **already admitted** mailbox turns, suspended Tasks,
   output readers, FFI callbacks, shared cell readers and external operations.
3. Request cooperative cancellation as policy requires, but do not count
   `Future.isDone()` as quiescent.
4. Detach pending await/select/channel callbacks, wait for physical
   Task `get_done`/scope join and all external/native pins. Do not block
   a guest carrier or hold CONTROL/routing locks during arbitrary callbacks.
5. Check actual actor state-loan closure, heap snapshot validity, mailbox
   version/transfer reservation, shared read grace period, and code image
   retention before `publish`.
6. Fence callbacks with actor incarnation **and** routing epoch so late
   delivery cannot mutate the successor or send to a retired ActorRef.

The host `ActorUpgradeEpochGate.LeaseKind` distinctions make pending
resource classes explicit for visibility and fault injection. They are
not yet instrumented in compiled guest source.

## Transactional upgrade state machine (target)

Stage signed artifact + transitive dependency closure → validate actor ABI,
placement and capabilities → isolate candidate and initialize in quarantine
→ freeze ingress → drain or cancel+join old owned Tasks/output/FFI/shared
leases → snapshot the **single** persistent actor `state { ... }` via a
canonical versioned wire schema (no raw pointers, OS handles, borrow tokens)
→ restore/migrate under quotas in candidate → verify state/schema digest
and new runtime health → atomically CAS stable logical ActorRef route +
generation code image + state version → reopen admission → retire old code
and old arena only after every retained pin exits.

- **Code-only compatible upgrade** keeps actor identity and persistent heap
  values; no `migrate` callback runs. Every schema/message/capability ABI
  digest must match. Shared-cell layout changes require an explicitly safe
  dual-version grace/migration protocol, not a memory reinterpret cast.
- **State-changing upgrade** is separate and must be explicitly proved,
  typed, bounded and atomic with publication. A snapshot encoder/decoder
  receipt does not prove that guest-owned memory is safely copied.
- **Pre-commit failure** aborts and resumes old ingress after candidate cleanup.
  **Post-commit failure** cannot silently restore stale code/state: rollback is
  another new, generation-fenced upgrade.
- **Untrusted downgrade/capability expansion**, off-domain code images,
  unverifiable source/JIT provenance, stale tickets, unbounded generation
  retention, leaked output subscriptions and missing FFI pins all fail closed.

## PR integration targets and nonclaims

- Java gate: https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1372
- Java actor routing/checkpoint draft: https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1270 (v1 restricted to SHARED)
- Java Task source/runtime: PR #1377 / #1379 (still not guest-executable)
- LLVM native code/epoch gate: https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/602
- LLVM checkpoint code-slot coordinator: https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/627
- Shared model: https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/89

**Release gates:** physical placement attestation, signed code-loader
authorization, actor+Task/FFI generation instrumentation, shared-region
read/epoch proofs, stable logical ActorRef routing, checked snapshot/migrate,
nested owner/child scope upgrade policy, exact-head guest-source tests,
LLVM PIC executable hot replacement, optional ORC JIT execution, sanitizer
stress, Java Graal Native Image, and crash/restart rollback proofs. Source-org
zero-step Actions failures are not passing test evidence. Private code must
not be mirrored across organizations without specific reviewed authorization.
