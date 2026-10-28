# Actor / supervisor security boundary (draft acceptance contract)

Status: security design and one Java host regression; NOT a claim of guest-compiled,
Native Image, OS, or cross-backend protection. Do not treat this document as proof
that arbitrary native or Java embedder code is sandboxed.

## Threat model

Assume an actor can be malicious, produce malformed and high-volume messages,
crash at any point in start/stop/restart, retain stale references, replay old
receipts, and try to call privileged runtime APIs through captured objects.
Treat data and all actor-provided error strings as untrusted. The attacker may
control a guest actor's .ores code, but must not control the trusted runtime,
OS kernel, supervisor secret material, or compiler binary.

A trusted host Java callback is not a security sandbox: an attacker able to run
arbitrary Java/FFI/native code in the supervisor's address space can escape
ThreadLocal-only context checks (for example by starting an unmarked thread).
Do not advertise in-process actor heaps, Graal isolates, LLVM arenas, or .NET
Native AOT as an OS-enforced boundary against native memory corruption.
For hostile code, use a process/OS sandbox boundary with narrowly brokered IPC
and a separately protected supervisor.

## Invariants

1. CONTROL events and capabilities originate only from the trusted runtime or
   parent supervisor, never from actor DATA or serialized guest objects.
2. An ActorId/SupervisorId is a lookup label, not a capability. Authority is an
   opaque runtime-minted reference bound to runtime, actor incarnation/epoch,
   parent identity, rights, and a revocation generation.
3. All privileged operations (including metadata inspection, stop, restart,
   kill, hot reload, policy changes, quota changes, heap inspection) validate
   the exact caller's authority at the use/commit point. No guest callback or
   scheduler/carrier thread grants implicit host authority.
4. Supervisor CONTROL lanes have bounded, reserved capacity and no DATA fallback.
   DATA cannot starve terminal failure or shutdown notices; admitted CONTROL is
   acknowledged or recorded as an observable, terminal failure.
5. Child failure classification is runtime-derived: an actor's string/serialized
   payload cannot forge PANIC, STOPPED, RECLAIMED, readiness, or parent receipts.
   PANIC bypasses actor-local exception handlers and goes to its parent.
6. Restart/rotation consumes a generation-bound, single-use transition. Stale
   or duplicated receipts and old ActorRefs fail closed. Admission is sealed
   before shutdown is reported; physical reclamation waits for turns,
   continuations, loans, I/O and teardown to become quiescent.
7. Guest-to-guest DATA transfers have closed, recursively validated Sendable
   shapes and resource budgets, with no ActorControlHandle, supervisor authority,
   raw pointer, native fd, code image, or transport codec laundering.
8. Across processes, authenticate both ends and bind integrity/replay protection
   to the exact child incarnation, supervisor, operation, rights, protocol
   version and sequence number. Keep MAC keys/OS credentials outside the actor.
   Never deserialize a received byte sequence into a host capability.
9. Revoke all child grants before restart; child cannot modify its parent's
   restart limits, permissions, trust class, security audit stream, or watchdog.
10. Security telemetry is immutable to guest actors and contains bounded/redacted
    diagnostic data; logging and failure reporting must not execute guest code.

## Adversarial acceptance gates

- Leak a host ActorControlHandle into a Java actor closure: every getter and
  lifecycle action must fail without authority. This PR adds this narrow gate.
- Try to synthesize CONTROL through DATA, including nested collections,
  aliases, messages, FFI and unrecognized nominal types: reject at source
  checker, wire decoder and runtime boundary independently.
- Forge ActorId, SupervisorId, parent, generation, fatality, acknowledgment,
  sequence and restart receipt; send duplicates, reorders and delayed copies:
  reject without lifecycle transition or quota refund.
- Race send/stop/panic/restart with concurrent mailboxes, delayed futures,
  native loans and memory reclamation; verify no admission after sealing,
  exactly one terminal receipt, and no stale handle reuse.
- Flood DATA/CONTROL and malformed messages: bounded resources, reserved
  supervisor progress, explicit rejection, no crash or unbounded queue.
- Attempt actor -> sibling/parent CONTROL, actor -> supervisor private memory,
  supervisor -> another tenant, and process-wide host calls. Test guest source
  under an adversarial OS-isolated worker, not just host callbacks.
- Independently compile and run the canonical .ores fixture on Java/Graal,
  LLVM, .NET Native AOT, WASM and Forge; mark unsupported lowering as NOT_RUN,
  never PASS. Include debug/release, sanitizer and time-budget variants.
- Attach exact commit/tree SHA, compiler binary digest, executed step logs,
  denial reason, and negative-test proof. Zero-step CI is NOT_EXECUTED.

## Current limitations

The mainline Java ActorRuntime has a host-admission check using thread-local
actor/mailman contexts; that check is defense-in-depth for supported guest
execution, **not** an authorization token for untrusted native/Java callbacks.
Full source-level 'define supervisor' CONTROL lowering remains a distinct gate.
Some stronger generation/CONTROL protocols are on unmerged draft branches,
including Java #1950/#1962, LLVM #608 and WASM #159. Their PR descriptions
explicitly require runner-backed exact-head evidence and independent review.
No private source should be exported to an unapproved CI organization.
