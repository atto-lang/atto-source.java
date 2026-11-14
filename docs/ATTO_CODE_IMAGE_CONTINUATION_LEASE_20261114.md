# AttoLang actor continuation -> exact code image leases (2026-11-14)

Status: **candidate host-runtime primitive, exact-head tests authored but not executed**.
This patch stacks on code-image quota/lease PR #3067 and does not merge it.

## Implemented contract

`ActorContinuationLedger` remains the single actor-owned authority for
callback admission, RUNNING transitions, STOP-discard, and termination. The
existing `admit(lane, callback)` and all current ActorRuntime call sites retain
their previous behavior. A new trusted `admit(lane, callback, AutoCloseable)`
takes ownership of a physical lifetime resource only **after** successful
queue-capacity and STOP admission. It holds the resource while QUEUED or RUNNING
and releases it exactly once when the callback actually exits or is discarded.

`ActorCodeImageContinuationV1` composes that existing ledger with
`HotCodeGenerationRetentionV1.pinCode(exactImage)`. A rejected/exceptional
admission closes the newly acquired image pin in a finally clause. A STOP
cannot fake the release of a RUNNING callback's pin, and the queued callback
cancel path detaches both the guest closure and the code-resource handle.
Terminal code-resource releases occur **outside** the actor ledger monitor;
they never invoke callbacks under the image-loader monitor. If guest callback
execution throws and terminal resource release also throws, the guest exception
remains primary and the cleanup failure is attached as a suppressed cause.
Neither failure is silently converted into successful reclamation.

Host JUnit `ActorCodeImageContinuationV1Test` exercises:
- actual code retention through overlapping STAGED/SERVING/DRAINING quotas;
- a STOP-discarded ordinary callback that never runs guest code;
- a concurrently running callback whose foreign close cannot retire its image;
- a SOURCE_TASK lexical cleanup callback admitted after ordinary STOP;
- quota failure, stale/foreign ticket, callback exception and idempotent close;
- 64 STOP-vs-run-vs-close interleavings with exactly one terminal image release;
- separate terminal-resource JUnit cases for thrown checked drop failures,
  retained guest-exception causality and rejected admission ownership.

The exact-head GraalVM 25 workflow compiles the four real production
host primitives under `javac --release 21 -Xlint:all -Werror`, then runs the
existing and new Maven/JUnit suites and rejects missing or skipped reports.

## Explicit, important limitations

**This is not yet an authoritative physical-image unload fence for all real
actors.** Existing `ActorRuntime` call sites still call the 2-argument
continuation `admit` overload and do not automatically supply code-image
leases. The new `ActorCodeImageContinuationV1` bridge is wired to the real
ledger type, but it has not been bound to an authenticated actor source image
by the actual compiler/loader. The owner of a currently running actor turn,
suspended SourceTask, guest callback, and FFI/native loan still needs one
physical image authority. Code-image numeric generations and textual IDs are
not cryptographic identity proof. The loader must prove the exact same
`SharedCodeImageStore.CodeImage` identity is authenticated to the image
reservation before this bridge may become production actor admission.

A `CodeLease` still allocates a Java object per admitted continuation. Avoid
using this host receipt as a claimed near-zero-GC guest hot path: compiled
owned frame slots, suspension lifetime, and deterministic compiler-generated
drop glue remain future requirements.

**Do not** release an actor's lifetime pin on mere STOP, Future completion,
timeout, or mailbox cancellation. `ActorRuntime.finalizeStopLocked()` checks
actual scheduler, native I/O loans, active turns, child workers, memory-slice
cleanup and group deregistration; if any physical cleanup fails, the loader
must preserve/quarantine the image rather than treating ledger count zero as
an unload witness.

## CI and next real integration

Source-org Actions can fail with zero job steps because of exhausted runner
capacity. Such runs are **NOT_EXECUTED**, regardless of the failure icon.
Private source mirroring is forbidden without exact independent approval in
the repository's protected allowlist and policy review, issue #962.

Next implementation slice (requires authoritative link-time image identity):
1. Mint a nonforgeable image-binding receipt at the real compiler/loader
   boundary, tying the linked actor `CodeImage` object to the exact retention
   `Ticket` and unmodified executable artifact.
2. Acquire an actor-lifetime image pin **before** exposing the actor to the
   runtime mailbox, then use image-bound pins for its continuation/FFI lanes.
3. Retain that lifetime pin through authenticated physical actor finalization;
   quarantine on failed native cleanup or incomplete group deregistration.
4. Validate real `.atto` actor mailbox, await/suspend/STOP and hot-upgrade
   flows, followed by Native Image and sustained per-actor heap measurements.

This work adds no source-language keywords, Java guest collections, or
`.ores` guest fixtures. It does not certify full guest machine-code AOT,
adaptive JIT compilation, OTLP export, zero-GC, or low-latency performance.
