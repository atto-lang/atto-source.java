# Actor TaskScope lease handoff and physical receipt audit — 2026-10-19

**Status: DRAFT.** SHARED actor host-only proof; not guest task lowering, protected PRIVATE/UNTRUSTED migration, or native AOT proof.

## Production finding and fix

The logical router acquires an ActorRuntime external generation pin under routeLock and then constructs the generation-close callback to pass to canonical OresTaskScope. Constructing that method-reference callback can itself fail with an allocation error before TaskScope is invoked, leaving an unowned pin. The router now retains fallback release responsibility through the entire transfer expression, catching RuntimeException/Error and closing the same idempotent lease on a verified pre-admission failure. Release errors are suppressed onto the original error. The safety proof depends on OresScheduler.start never throwing after TaskRunner registration: later errors must settle via the onQuiescent callback, never through admission throw.

## Real-source regression tests committed but unexecuted

LogicalActorTaskLeaseHandoffTest covers cancelled canonical compiler scopes, injected HashSet ownership-reservation OOME, and stale captured route epochs. These use the real SHARED compiled source actor/router/scheduler; the tests were added but not executed in full JDK25/Maven/GraalVM.

ActorRuntimePhysicalMailReceiptTest now covers an active message that fails and a later already-accepted queued successor: both physical terminal receipts must fail; the queued successor must never run. This is local exact-message acknowledgment, not cryptographic remote mail.done or durable exactly-once effects.

## Offline evidence

A separate source-free JDK21 handoff oracle compiled under javac --release 21 -Xlint:all -Werror, passing **84,000 assertions in 32,000 scenarios**: 10,000 rejected scope admissions, 10,000 synthetic method-reference allocation failures, 10,000 result-versus-physical-exit checks, and 2,000 three-contender release/freeze races. These are NOT execution of Oreslang production source, Maven/JUnit, GraalVM, Native Image, or LLVM.

## CI/private-source gate

No private Oreslang source was copied into another GitHub organization. Open platform-conformance security incidents #22/#77 and bootstrap #69 require independently reviewed exact source+destination authorization and source-branch protection, not merely available GitHub Actions minutes. The Java PR remains Draft until exact-head source-owned/approved private CI, atomic code/scheduler/state/route publication, bounded receipt failure tests, and PRIVATE/UNTRUSTED/native AOT checks pass.
