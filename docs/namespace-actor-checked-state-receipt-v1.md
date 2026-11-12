# Atto namespace actors — checked state receipt and source-bound descriptor (v1)

**Experimental compiler-internal checkpoint.** Stacked on Java #2959 → #2947 → #2892.
Do not enable arbitrary namespace actors in the normal guest compiler.

## Changes

`NamespaceActorCriticalIrCompiler` issues a private-constructor
`VerifiedActorDescriptor` **only after** the production parser,
`ActorReceiverBinder`, `ActorStateBinder`, and the strict closed-world
critical-method IR verification succeed. The descriptor binds the actual opaque
`SharedCodeImageStore.CodeImage` instance, namespace, actor type, the immutable
scalar-field slot map, and the verified private critical method names.
Source code cannot mint the descriptor, change a field slot, or gain a
public `external mtd` ingress reference. The restricted Graal proof now
spawns by that source-derived actor name rather than the fixed test name
`Probe`.

Every actor keeps its mutable `long[]` state **inside its own `Owned` object**.
After each fully successful mailbox turn, the compiler/runtime publishes an
immutable `StateReceipt` containing only the actor incarnation ID,
generation, completed-turn count, and a copy of field names and signed-i64
values. The receipt is **diagnostic**, not an alias, owner transfer,
write authorization, serialized AMQP state, WAL record, or source language
API. Its publication is actor-affine. `receiptAfterDrain` is an internal
read-only host method: callers must first complete `ref.drain()` and verify
the actor has no failure. It checks the exact executable and live actor
generation; retired actors and unrelated compiled code images are denied.
Receipts are removed during actor-affine finalization.

The checked Graal `Context.eval` proof now verifies actual actor state:
after sending 1,000 messages of value 1 to actor A and one message of value
7 to actor B, the difference between their `received` fields must be 993,
and the reverse difference between their `budget` fields must be 993.
The guest evaluation result comes from the frozen `received` field itself,
not from a hard-coded mailbox-message count. A valid initial value of 5
therefore returns 1,005, while a checked but semantically wrong
`budget = budget + delta` source fails the proof.

## Executed test gates

`NamespaceActorCriticalIrCompilerTest` now additionally proves:
- Actual initial and post-turn scalar state and independently owned siblings
- Unmodifiable prior receipts after later state changes
- Retired generation and unrelated code image rejection
- Immutable descriptor namespace, actor name, field slots and critical methods
- Code-image identity remains distinct across different published units

`CheckedNamespaceActorContextEvalTest` proves:
- Real Graal `Context.eval` integration with computed final state
- Valid changed initial counters and namespace/actor type names
- Fail-closed guest lease replay, wrong file identity and source bytes
- Normal **unleased** `spawn Probe()` stays rejected
- No source-visible actor field or write-loan access

Run on funded `voxletra/voxletra-e2e` carrier with **verified exact individual Git
blob identities**, executed nonzero-step Java/JUnit tests, and the earlier
parser/ownership/ingress negatives. The carrier **whole Git tree differs**
from the Java PR head. It is not authoritative full-tree Java PR CI.

## Blockers before general actor guest execution

1. Introduce real linked-package and manifest-authenticated actor descriptors
   rather than one standalone checked source image.
2. Implement typed Graal guest nodes for source-level `spawn`, mail delivery,
   private external ingress and critical-method calls. The current test probe
   still uses the compiler-internal executor and a trusted one-shot admission
   lease; these source-level APIs are **not** enabled.
3. Replace diagnostic host observation with a compiler-checked actor
   request/reply or immutable output-channel protocol accessible by Atto code.
4. Prove nonescaping borrows across awaits, cancellation, generation changes,
   hot-reload, and physical per-actor arenas on each compiler backend.
5. Execute exact-PR-head guest tests, obtain independent approval and
   integrate NIO2 into the first actual AttoRMQ AMQP publish/get/ACK test.

**No true native broker, guest-visible state read, general guest codegen,
NativeAOT, LLVM parity, or AMQP acceptance is claimed.**
