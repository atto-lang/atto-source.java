# Java M0 bounded synchronous guest-call admission (stacked on PR #3007)

This is a **forward-port** of historical draft #2505's conservative 64-level
non-tail guest invocation guard onto current Java continuation-pump PR #3007.
The original M0 draft remains unmerged; do not merge both implementations.

## Implemented
- `Evaluator.invoke` reserves a carrier-local bounded call entry before
  recursively evaluating non-tail guest invocations, and releases it in `finally`.
  Proper tail-call trampoline hops do not accumulate reserved entries.
- Stackless `GuestCallDepthExceededException` is untrappable and uncatchable
  by the explicit guest failure policy, including wrapped causes.
- Host tests cover exact-budget admission, carrier reuse, trap/catch exclusion,
  one SHARED actor failing while a sibling executes on a single-worker pool,
  and real compiler-backed non-tail guest recursion followed by a healthy context.

## Critical non-claims
- **No actor-owned logical stack/frames** in this slice: the 64-entry guard is
  carrier thread-local. A physical JVM StackOverflowError is never caught or
  treated as actor-recoverable. Recursion through arbitrary Java callbacks,
  deeply nested expression evaluation, native/FFI/Truffle, and other host stacks
  may still escape guard coverage.
- Sibling host-model survival is not source-compiled dual-actor proof, nor does
  it certify supervisor restart, cancellation/epoch fencing, native heap safety,
  or tenant isolation. A healthy subsequent JVM context is not same-carrier
  sibling/CONTROL survival evidence.
- Java original PR #3007 must be merged/rebased and reviewed separately.
  Actor-owned task-root stack accounting from historical #2624/#2626 requires
  semantic integration and its own tests. Untrusted arbitrary JVM/native
  execution needs isolation where no recoverable bound exists.

## Acceptance
Verify complete Git tree identity on an approved private funded carrier; build
actual Java 21 compiler/JUnit, run the host, guest and previous PR #3007
continuation suites; run exact-head native Router golden and crash-repro stress.
A failed source-org zero-step GHA check is a runner-admission failure, not test
evidence. Preserve independent review before any merge.
