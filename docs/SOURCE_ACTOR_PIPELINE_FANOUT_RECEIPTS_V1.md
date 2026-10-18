# Authenticated actor fan-out receipts — Java/Graal guest phase 2

Status: **draft; exact-head JVM/Native Image execution required**.

This increment stacks on the envelope-bound `PipelineTurnAuthority` implementation in Java actor-routing phase 1. It adds source admission for direct lexical `self.forward_all(value)` in **SHARED** actors, within an authenticated DATA receive only.

## Semantics and result type

`self.forward_all(value)` returns `List<readonly struct>` with one element per published downstream graph edge, in the supervisor's immutable connection order. Each element exposes exactly:

| Field | Type | Meaning |
| --- | --- | --- |
| `edge_index` | `int` | Graph connection index, not a routing capability |
| `target` | `string` | Validated downstream instance name |
| `flow_id` | `int` | Read-only correlation metadata |
| `route_id` | `int` | Read-only branch identity |
| `admitted` | `bool` | Whether the mailbox accepted that branch |
| `status` | `string` | `admitted` or `rejected` |

The returned Java list and every map are runtime-unmodifiable. The item type is a read-only structural record; **the current generic `List` type does not encode a read-only collection qualifier**, so code that tries a mutating List operation must still fail at runtime rather than at the type checker. Adding a native read-only list type is a separate release gate.

A successful receipt means **mailbox admission only**, not processing, exactly-once delivery, or a committed transaction. `forward_all` is **nontransactional**: a later rejection never cancels earlier admitted branches. No throwable, VM route frame, ActorRef, router pointer or turn capability appears in the guest record.

Guest fan-out currently accepts only `int`, `bool` and `string` values: immutable scalars reusable independently by multiple recipient mailboxes. Aggregate/borrowed/moved/dynamic DATA must fail static admission until the ownership checker proves per-edge Sendable copying or sharing. The runtime independently rejects unsupported payload classes before any mailbox publication.

## Authority and lifecycle

1. The trusted supervisor binds the graph to concrete SHARED actors; none of those bindings is source-accessible.
2. Each inbound DATA envelope carries an opaque VM-only `MailFrame` + matching `PipelineTurnAuthority`; both are claimed by the real recipient turn.
3. The compiler lowers only direct lexical `self.forward_all(value)`; aliased actor methods and ordinary DATA cannot borrow the authority.
4. The session preallocates per-edge child frames, host receipts, **both successful and rejected guest records**, lists and wrappers before the first irreversible mailbox send.
5. Each edge independently attempts bounded typed mailbox admission. The resulting preallocated immutable receipt is appended without allocating post-publication. Rejected branches retire their unpublished frame.
6. Completed fan-out consumes the source frame even if some recipients rejected admission. A second fan-out (or any other routed send) from that consumed source frame is rejected.
7. STOP, force-cancel, discarded mailbox envelopes, suspension and generation retirement continue to use phase 1 VM-owned frame lifetime handling. A stale authority cannot be reused.

Compiler caller forms remain sealed: `self.forward`, `self.backward` and `self.reply` are already enabled by the **parent** phase-1 branch. This PR does not add or redefine terminal reply semantics.

## Validation matrix and merge hold

The checked-in `ActorPipelineGuestRoutingBridgeTest` uses real SHARED ActorRuntime mailboxes to assert ordered successful fan-out, partial admission rejection, immutable reports, source-frame replay denial, aggregate payload rejection, branch correlation and terminal reply behavior. `ActorPipelineRoutingAdmissionTest` checks source typing and negative ownership admission. The routing CI workflow pins an exact PR head and includes the new JUnit class.

These are **tests added**, not a statement that the branch has passed them. Do not merge/promote until actual checked-in Maven/JUnit, compiled `.ores` source execution, Graal Native Image, STOP/cancellation, and sanitizer/stress evidence run on an authorized runner with validated HEAD/tree/artifact identities.

The current source compiler still lacks a complete source-declared typed graph binding workflow, so end-to-end compiled guest orchestration beyond this sealed evaluator/runtime slice is a separate prerequisite. Likewise, LLVM/.NET/WASM/Forge guest routing must remain fail-closed until each backend executes the equivalent authenticated implementation.
