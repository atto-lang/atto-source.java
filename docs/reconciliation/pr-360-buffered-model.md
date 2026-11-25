# PR #360 — buffered-channel model replay reconciliation

Historical head `d03698dcc651eba1f7e8dc25801dae903458b4c9`; current Java main `d63f2611fb77fdda361807ab608da6f6656d1e8e`.

The earlier PR's bounded explicit-state `FormalBufferedChannelModelCheckTest` is already present in current main, but the newer version replaces a narrow `closeIsAOneWayAdmissionFence` case with the stronger `closeFencesWritesButPreservesBufferedFifoDrain` contract. Preserve the full current model and formal README, including later select readiness, ownership, deferred drain and actor lifecycle constraints. Do not overwrite the model with an earlier fixed-capacity checker.

Invariant to preserve: closing a buffered channel fences all *new writes*, but existing FIFO buffered items remain drainable in original order; the terminal CLOSED observation only follows the drain/lease rules and never invents message loss or an extra successful write. Interleavings should be enumerated with bounded queues, explicit close, competing writers/readers and physical resource retirement.

This historical integration is superseded in runtime test content; the diff intentionally adds only semantic reconciliation documentation. It does **not** certify the newer model has passed on this exact PR head. Require complete Maven/JUnit formal model, stress and schedule-fuzz evidence before release.