# PR #538 reconciliation — process stdio ownership and streams

Historical head: `ea0cae263a2772b4542642095a6fcac3ea55caed`, current-main baseline: `1f3912a772f6aa46aacb8a745f4c3cba1015fb73`.

Keep the current-main process and stdio output paths, scheduler, actor capability constraints, mutable guest buffer semantics and newer `ChannelRuntime`; do not restore historical versions of `NativeStdio`/actor pooling or introduce a second stdout writer. In the original stacked feature, descriptor channels, global stdin/out/err handles, native ReadableStream, Observable, tee and input generators were added together. Their state/ownership integration is not present as an independently executable and reviewed unit on the target main. **Mechanical merging must not advertise support for those spellings**.

The original design contracts `docs/NATIVE_STDIO_CHANNELS.md` and `docs/STDIN_CHANNEL_NATIVE_CONTRACT.md` remain in historical first-parent ancestry. A separate one-owner design review must reconcile them with Java #616's queued async log/flush channel, existing direct writes and #539/#540 competing stream backends. A sole sequencer must linearize direct and queued writes; do not create competing pumps or let a borrowed input handle close the ambient descriptor.

Forward-ported a runnable compatibility floor `StdioOwnerForwardPortTest`: sequential guest writes remain ordered and require no unrestricted Java host access. It is **not** a proof of Rx/WHATWG APIs, input flow control, stdout flush semantics or actor cancellation. Release requires exact-head JVM/native runs, independent review, descriptor ownership stress and cross-backend tests before enabling any global stream API.
