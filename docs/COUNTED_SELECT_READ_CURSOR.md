# Counted-select scalar read substrate (host-only)

This is a limited Java runtime milestone for select(n). It drives the actual
ChannelRuntime.SelectSet one round at a time from a serialized non-migrating
host owner, not from a guest callback. The count cap bounds retained i64
values per cursor; production integration still requires tenant-wide quotas.
The existing arbiter preserves read registration, fair rotation and
cancellation-versus-commit semantics. The owner retires a round only after
the Future notification-drain receipt; it cannot start another round earlier.
The cursor does not return a guest Future<Array<T>> and does not run arm code.
It supports immutable Long reads only, no Future/write/default arms. A closed
winning source fails the batch, not a complete all-source exhaustion policy.
The thread confinement is deliberately not an actor affinity implementation.
Do not wire this into compiler output until an actor-owned continuation,
typed capture/cleanup, quotas and physical retirement are verified.

Tests: exact three fair reads, no fourth, zero/invalid count, actual pending
read registration and resumption, cancellation on either side of commit,
close after prefix and foreign-thread rejection. Run focused JUnit with
CountedSelectReadCursorTest and FutureChannelSelectTest. No guest or native
image parity claimed.
