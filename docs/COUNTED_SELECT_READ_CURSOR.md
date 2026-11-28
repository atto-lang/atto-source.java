# Counted-select scalar read substrate (host-only)

This is a limited Java runtime milestone for select(n). It drives the actual
ChannelRuntime.SelectSet one round at a time from a serialized non-migrating
host owner, not from a guest callback. The count cap bounds retained i64
values per cursor; production integration still requires tenant-wide quotas.
The existing arbiter preserves read registration, fair rotation and
cancellation-versus-commit semantics. The owner retires a round only after
the Future notification-drain receipt; it cannot start another round earlier.
The cursor does not return a guest Future<Array<T>> and does not run arm code.
It supports immutable Long channel reads and one-shot Future results; no write/default arms.
Graceful EOF retires all aliases of a channel without counting an event. Future
aliases retire on a successful commit. All-source exhaustion fails without a
partial array; error-close and failed Future propagate failure. Ordinary select
closed-channel behavior is unchanged. FAIR rotation survives source retirement.
The thread confinement is deliberately not an actor affinity implementation.
Do not wire this into compiler output until an actor-owned continuation,
typed capture/cleanup, quotas and physical retirement are verified.

Tests: exact three fair reads, no fourth, zero/invalid count, actual pending
read registration and resumption, cancellation on either side of commit,
close after prefix, graceful EOF, alias retirement, producer errors and foreign-thread rejection. Run focused JUnit with
CountedSelectReadCursorTest and FutureChannelSelectTest. No guest or native
image parity claimed.

Actor-owned host cursor variant: binds to the exact actor cell/code generation and active carrier lease instead of a physical thread. Round retirement is enqueued only after the notification drain receipt; actor-owned pending Future is revoked at STOP. This is scheduler plumbing only: no guest arm execution, aggregate Future, or quota admission. A pending actor round must use beginActorRound; foreign callers cannot drive it. STOP fences late continuation execution; batch-level cancellation/cleanup settlement still needs a structured task owner.
