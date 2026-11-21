# Actor pipeline I/O, DATA bypass and supervision — v1 audit contract

**Status:** design hardening / source-only; **no new executable actor I/O ports**. This is an additive contract on the Java actor-local `io` draft (#2111), directional pipe draft (#2039), and actor I/O umbrella issue (#2109). It does not certify Java, LLVM, .NET, WASM, JS, or native-image parity.

## Decision: bypass supervisor *DATA mail*, not supervisor *authority*

A supervisor **configures**, authorizes, monitors and retires the graph. After a graph epoch and its capabilities are installed, each data item **must not** traverse the supervisor's user mailbox. The scheduler/router may enqueue into the next stage's authenticated mailbox DATA lane, or a bounded directional channel linked to the next actor's readiness ledger. Any guest handler invoked on arrival/resume still requires the actor's **exclusive logical execution lease**, including across suspension: no concurrent handler/reentrancy, even if the stage has several channels.

```text
                        CONTROL (separate bounded/reserved lane)
                   ┌─────────────────────────────────┐
                   │ supervisor: admit / stop / fail │
                   └───┬─────────────────────────┬───┘
           graph grant ↓                         ↑ terminal / fault receipt
  input owner -> [A] -- bounded DATA channel --> [B] -- bounded DATA --> sink
                 |                                 |
                 +---- diagnostics / events -------+--> authorized side subscribers
```

An actor pipeline MUST NOT use a supervisor's ordinary mailbox as a proxy for every `stdin` chunk, `stdout` write, error/event record, or continuation wake. Nor may a stage bypass actor-owned mailbox/ledger admission to invoke another actor handler directly. A reserved CONTROL lane is **not** an unbounded bypass for normal application data.

## Preserve two existing transport modes

1. **Authenticated actor-to-actor DATA path.** Existing `A pipe B` / `ActorStream<T>` uses the scheduler's authenticated ActorMail path, single-flow route frames, reverse/reply correlation, and physical `get_done()` semantics. Preserve exact ActorMail domain, replay fencing, publication-before-source-retirement and nontransactional fanout receipts. No implicit refactor to ordinary channels.
2. **Directional channel/port DATA path (not yet executable in general).** `ReadChannel<T>` and `WriteChannel<T>` are non-escalating views over one bounded Channel backing store. A graph builder may connect one stage's `output: ReadChannel<T>` to another stage's `input: WriteChannel<T>` only after checking exact types, ownership, generation and end-to-end quotas. The adapter must be scheduler-owned, non-parking, cancellation-aware, and must not start hidden threads or create a second unbounded buffer. Legacy all-actor pipe may not silently switch transport.

`io.in`/`io.out` are actor-relative logical endpoints; they do not mean OS stdin/stdout. The VM's single physical descriptor owner controls any mapping to OS stdin/stdout/stderr. An `ActorRef` must not itself be sufficient to forge graph edges, subscribe to another actor's streams, or acquire supervisor CONTROL authority.

## Four logical ports and metadata

| Port | Payload / direction | Authority and delivery rule |
| --- | --- | --- |
| `io.in` | bounded byte chunks into actor; optional explicit text decoder | single active reader lease by default; explicit, bounded authenticated fanout if requested; EOF and half-close scoped to edge |
| `io.out` | bounded byte chunks out of actor; optional explicit text encoder | explicit output edge or authorized sink; `write` Future documents **logical admission**, not OS flush/durability |
| `io.err` | typed, sanitized application diagnostic records out of actor | independently bounded side port, not raw process stderr and **not** authoritative supervisor failure/control |
| `io.events` | versioned, typed application events out of actor | subscribers authorized by grant; delivery policy explicitly `reliable`, `latest` or `lossy`; no silent security/audit loss |
| `io.meta` | bounded immutable/versioned snapshot | reads are capability-scoped; updates only through authorized control/version transitions, not an arbitrary writable channel |

**Critical split:** an application diagnostic (`io.err`) is DATA. An unhandled throw/panic, child death, forced cancellation, security breach or physical-drain failure is a *trusted runtime-generated* terminal CONTROL receipt to the owning supervisor, never a guest-forgeable `io.err`/`io.events` record. A telemetry event may additionally describe a failure, but cannot replace the CONTROL receipt. Ordinary application events may bypass the supervisor completely. No implicit stdout/stderr mapping; a privileged router may opt in.

## Graph creation, ownership and failure protocol

1. Supervisor checks graph shape (including all downstream edges before activating any), exact domain types, actor incarnation/generation, actor/tenant policy, endpoint capability provenance, quotas and downstream sink authority. Reserve resources atomically or roll back every unpublished reservation. A topology with a missing bridge must fail closed **before guest execution**.
2. Freeze graph epoch and edge handles; publish a single admission pointer. Port handles contain opaque VM-authenticated `(runtime, tenant, actor, generation, edge, direction, rights)`, not a user-constructible public record. Only the owning router can unwrap the backing Channel or mutate endpoint/supervisor policy.
3. `write` reserves bytes, element slots, pending-writer credits and aggregate tenant/context budget **before** publishing a payload. Preserve per-edge FIFO; no cross-port total order is implied. Backpressure suspends a task without blocking a carrier, or fails with an explicit bounded-admission error. Quota refunds are exactly once, on success, cancellation and all failure paths.
4. `read` is confined to the authorized reader/lease. A ready signal may request scheduler admission, **never** call a guest actor handler from a channel, reactor, OS callback or unrelated carrier. Resumption uses generation-checked actor-ledger admission, including `critical` state-loan and `handle_state` discipline; nonreentrancy holds across `await`.
5. Errors/EOF and cancellation have typed terminal receipts. Cancellation wins or completion wins **exactly once**, and subscribers/waiters see one terminal outcome. A late old-generation completion cannot deliver to the successor incarnation (ABA). Ordered per-edge data accepted before EOF is handled according to explicit drain policy. No infinite writer wait under a stopped consumer.
6. Supervisor STOP fences **new** edge admissions and new guest turns, but preserves scheduler-owned cleanup/control receipts, then drains or cancels bounded accepted DATA, closes physical transports, and waits for **physical** worker/pump/task/continuation retirement before reporting `get_done()`. A logical Future settlement, timer firing or channel close alone is not proof of retirement. A failed physical cleanup must remain observable to CONTROL, not vanish into a lossy event queue.
7. At graph hot reload, retain the prior immutable generation lease for in-flight traffic. New traffic uses the successor only after authorization; reject revoked handles and late callbacks. No source or wire pointer/FD crosses actors or tenants; payloads are data-only and `Send` ownership checked.

## Separation of quotas and priority

Maintain independent bounded DATA and CONTROL capacity. CONTROL must have reserved terminal-delivery capacity (or a durable, bounded, pre-reserved child terminal slot) **before** admitting each child, so a full DATA lane cannot suppress STOP/panic/child-down reporting. Do not use the supervisor's user mailbox as emergency overflow storage. When a terminal/control slot cannot be reserved, **refuse spawn/admission**; on catastrophic invariant failure use a defined containment/escalation path, never silent drop.

Share an aggregate resource ledger among mailbox DATA, per-actor output, side ports, waiter slots, pinned buffers and route frames; independent local caps may not each spend the full actor/tenant budget. Child output/error/event subscriptions consume finite credits, and lagging subscribers cannot pin retired heaps/generations indefinitely. Enforce fair scheduling under noisy neighbor stalls.

## Minimum adversarial exact-head conformance

| ID | Check and required outcome |
| --- | --- |
| PIPE-IO-01 | A -> B -> C streams 100k bounded items; supervisor *user mailbox* receives **zero** per-item forwards; B/C still execute under exclusive actor admission. |
| PIPE-IO-02 | DATA mailbox full; child panic/STOP still reaches reserved CONTROL once; if CONTROL receipt cannot be pre-reserved, admission fails. |
| PIPE-IO-03 | A suspended on `io.out.write` while another edge wakes; actor handlers do **not** reenter; `handle_state(critical ...)` remains serialized. |
| PIPE-IO-04 | Incorrect downstream `WriteChannel<U>`, inactive bridge or forged ActorRef edge fails closed before any initial flow publication. |
| PIPE-IO-05 | Sibling ActorRef cannot read sibling input/output/errors/events or recover raw backing channel; wrong direction and tenant/epoch reject. |
| PIPE-IO-06 | Full queues + pending waiter cancellation vs write completion; reservation refunded **once**; eventual physical `get_done()`. |
| PIPE-IO-07 | Slow/unsubscribed `io.err`/`io.events` consumers cannot silently drop security failure CONTROL; data overflow follows declared policy. |
| PIPE-IO-08 | EOF, half-close, broken pipe and shutdown race deliver one terminal outcome, accepted FIFO prefix or documented abort. |
| PIPE-IO-09 | Old generation handle/wake/selector/FD callback cannot enqueue after hot reload; stale tickets cannot corrupt new actor. |
| PIPE-IO-10 | Single stdin reader lease; second reader rejects unless explicit authorized fanout with independent finite credits. |
| PIPE-IO-11 | Split UTF-8, binary null bytes, CRLF across chunks, invalid UTF-8 policy; diagnostic payloads sanitized and typed. |
| PIPE-IO-12 | 100+ active actors under mixed stdout/error/event stalls: bounded aggregate memory, fair progress and zero carrier-blocking I/O. |
| PIPE-IO-13 | Logical write admission is distinguishable from transport write/OS flush; no false durability acknowledgment. |
| PIPE-IO-14 | PIPE-IO-01..13 execute under Java JVM+native-image, LLVM native sanitizers, .NET NativeAOT, WASM and JS guest parity where implemented; unsupported semantics fail explicitly at compiler/VM boundary. |

Record for every backend: exact commit SHA, Git root tree, relevant blob IDs, actual executed job steps, guest/native vs host-only test scope, test counts, compilation/AOT mode, and evidence of source-privacy authorization. Zero-step runner failures = **NOT_EXECUTED**; source-free runner admission is not a native compilation result.

## Current implementation and non-goals

At the audited Java `actor-io-intrinsic-v1` head `348871ba9c0ebe8b3f29af8f3644ca84a6f2bd5b`, only `io.out.write` is an executable, data-only, bounded admission intrinsic. `io.in`, `io.err`, `io.events` and `io.meta` are reserved **fail-closed**. Existing `ActorStream` authenticated mailbox routing is separate; generic non-actor channel bridges and four ActorRef logical ports are not executable on the directional pipe draft. This document must not be used to claim those features exist.

**Next implementation slices:** (1) generic bounded edge adapter + actor-exclusive wake ledger, (2) sealed producer/reader/subscriber grants with single-reader stdin, (3) independent typed `err`/`events` with reliable runtime CONTROL receipts, (4) quota/terminal/hot-reload tests, (5) authorized exact-head stepful CI and cross-backend parity. Keep stacked PRs draft until all applicable gates pass.

References: Java #2111, #2039, #2045, issues #2109/#2108; LLVM #345, #436, #864, #871. No private-source export or merge authorization is implied.
