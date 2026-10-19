# Oreslang OTP dual-lane supervision and transactional upgrades

Status: DRAFT host-only CONTROL command transport; not a complete actor or OTP runtime.

## Reuse existing foundations, do not duplicate

- Java draft #1223: pre-reserved no-drop terminal-failure CONTROL, root escalation, parent carrier execution, 64:1 fairness and separate DATA mailbox capacity.
- Java merged #1372: ActorUpgradeEpochGate host-only image/schema/capability check and generation pins.
- Java drafts #1270/#1367/#1432: ingress fences, bounded logical DATA proxy, quiescence, Task pins, and **two competing atomic publisher prototypes that must converge to one**.
- Java draft #1216: six partial OTP behavior modules and bounded GenServer correlation.

## Two logical lanes, one actor-state execution lease

PRIORITY CONTROL is runtime-owned, generation fenced and separate from DATA credits. Fatal child notices retain their pre-reserved #1223 slots and outrank upgrade commands. The new OtpSupervisorCommandLaneV1 is an independent bounded subquota for PREPARE_UPGRADE, QUIESCE_CHECK, ABORT_UPGRADE, COMMIT_NOTICE and GRACEFUL_STOP. It rejects overflow rather than consume a fatal-reserved slot. Messages carry immutable runtime ID, actor ID, incarnation, route epoch, monotonically increasing command sequence, and a canonical code SHA-256 for upgrade commands. No raw Throwable, guest object, callback, host resource or reply token crosses CONTROL.

REGULAR DATA uses typed ActorRef calls/casts/info/events, FIFO per logical route, independent queued+in-flight+reserved credit limits and explicit backpressure. GenServer reply capabilities belong to the privileged per-envelope runtime sidecar, never guest DATA. During upgrades, bounded immutable ingress can continue while dispatch is frozen; no implicit replay of ambiguous accepted requests.

Both lanes are multiplexed onto the same serialized actor execution domain. Existing #1223 selection serves CONTROL first, then yields a safe ordinary turn after 64 consecutive notices. An outstanding suspended state loan/continuation, executing handler or critical section cannot be arbitrarily preempted. No guest callbacks execute on the sender thread; no per-actor OS thread is created.

Command delivery returns an opaque one-shot acknowledgement lease. Ack means the notice was handled, NOT that a route was published. Unacked close retries the exact same command. Abort requires the trusted upgrade coordinator to roll back/quarantine separately before retiring the command; it returns the exact command for audit.

## Better-than-OTP target: typed transactional hot reload

1. STAGE: verify authenticated compiler receipt, exact source/dependency closure, state/message schema, ABI/target, signer/tenant and capability non-escalation. Support AOT-to-AOT and AOT/JIT where permitted; JIT is never mandatory.
2. PREPARE: trusted supervisor issues generation- and epoch-bound CONTROL. Freeze old dispatch at a precise message watermark; admitted DATA finishes on old image, while new immutable DATA is bounded or rejected.
3. QUIESCE: verify all actor turns, source awaits and Tasks, state loans/borrows, reply writers, timers, FFI/GPU leases, external readers and physical code pins are drained. Deadline expiration is not permission to publish.
4. MIGRATE: optional future source callback code_change(oldVersion, canonicalOwnedSnapshot) -> Result<NewState, MigrationError> executes inside quarantined candidate state with bounded resources and verified schemas. No raw pointers/handles, host object graphs or unowned side effects are checkpointed.
5. COMMIT: exactly ONE trusted atomic CAS publishes route, code image, owned state and routing epoch while preserving logical ActorRef and fencing old physical incarnation. Separate image and mailbox publishes do not prove atomicity.
6. NOTIFY: only after actual commit send COMMIT_NOTICE via priority CONTROL; ACK does not commit. Old GenServer replies remain generation-fenced or explicitly fail by declared compatibility policy.
7. RETIRE: old code/image/arena remain pinned until every physical continuation, Task, FFI and executable lifetime lease is gone. Quarantine unsafe unload.
8. ROLLBACK: pre-commit restores old FIFO and route. Post-commit rollback is a NEW versioned transaction; never rewind external effects or blindly replay messages.

If the supervisor fails during staging, abort/quarantine the candidate through trusted authority first, settle upgrade commands explicitly, and separately transfer terminal failures to root fallback.

## OTP behavior parity gates

GenServer: init/call/cast/info, one-shot reply, timeout/cancel, code_change.
Supervisor: all 3 restart strategies, full-span intensity, CONTROL escalation and hot-upgrade ordering.
GenStateM: event deferral/timers/code_change.
Application: ordered dependency start/rollback/upgrade.
EventManager: isolated handlers and bounded delivery separate from subscription count.
Registry: scope/tenant/incarnation identity and generation-safe rebinding after commit.

## Delivered in this branch and not yet delivered

Delivered: bounded runtime command transport, strict command validation, one outstanding command per actor incarnation, FIFO with tail retry fairness, retry-on-lease-close, explicit rollback-abort, exact epoch checks, physical-stop quarantine and adversarial host smoke. ActorRuntime now admits PREPARE_UPGRADE / QUIESCE_CHECK / ABORT_UPGRADE only from a trusted host context and delivers them on trusted host-backed actor's serialized carrier **after fatal child CONTROL and before regular DATA**, with the existing 64:1 fairness. Source-compiled, untrusted, foreign and actor-originated submissions are rejected. COMMIT_NOTICE and GRACEFUL_STOP are explicitly unavailable until a real atomic route publisher and lifecycle receipt exist.

NOT delivered: authenticated external supervisor grants distinct from trusted host-context admission, live actor route switch, physical quiescence, guest OTP API, actual GenServer dispatch, or Java/Graal/LLVM end-to-end execution. Control ACK is notice handling, **not** code/state publication. Actor termination quarantines unfinished notices instead of silently acknowledging or pretending to roll back. Source organization CI may fail before runner allocation. Do not merge without executed exact-head tests, security review and one converged publisher.

Next integration: rebase the now-used #1223 CONTROL scheduler onto merged #1372, then bind prepared commands to #1367's bounded proxy and exactly one #1432 publisher. Replace trusted-host-only authority with explicit supervisor grants and validate successful publication receipts before enabling COMMIT_NOTICE. Test full DATA mailbox plus child failure plus in-progress upgrade, foreign authority, ABA, dropped ACK, old reply tokens, and physical Task/FFI teardown. No unapproved cross-org private source export.
