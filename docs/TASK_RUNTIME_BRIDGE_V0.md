# Runtime Task handle bridge (draft)

This branch adds **host runtime scaffolding** for Oreslang's proposed Task blocks. It does not enable new source syntax.

- `AsyncRuntime.submitTask(Task<T>) -> OresTask<T>`: awaitable, cancelable completion handle. `OresTask<T>` implements `Awaitable<T>` by exposing its runtime-owned `OresFuture<T>`.
- `AsyncRuntime.submitDetached(Task<?>, Consumer<Throwable>) -> void`: no caller handle, supervised failure/cancellation delivery. A non-null error handler is mandatory.
- `AsyncRuntime` owns and drains both forms on close. Neither form introduces actor identity, state access or a mailbox.
- `Task<void>` corresponds to Java `OresTask<Void>` with a null completion payload and can be awaited.

The existing compatibility executor uses Java virtual threads, **not a proven custom M:N continuation scheduler**. These entrypoints intentionally share existing task execution and cancellation code. Further lowering must supply actor-affine dispatch, safe ownership captures, cleanup ordering, Task type admission, nonparking waits, and structured parent/child lifetime before user source `nb` becomes executable. Do not allow a source Task to capture actor heap pointers just because this Java API exists.

The detached error sink is a bridge to the owner/supervisor, not permission to silently ignore its failures. Tests exercise nonblocking launch, result await, void completion, owner error delivery, and cancellation on context close. Full exact-head CI still required.
