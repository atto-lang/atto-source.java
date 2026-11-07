# AttoLang integrated guest invocation and SourceTask actor-frame ownership

Status: DRAFT, no hosted exact-head compiler-backed execution receipt. This is a Java/GraalVM host/runtime compiler bridge, not independently isolated native stacks.

## Semantic integration

This integration is stacked on Java PR #2631 and combines the complete invocation-descriptor implementation from Java PR #2628. Both branches independently descended from frame-arena PR #2627. All nine #2628 changed files are brought over; its existing evaluator, tests and canonical .atto fixtures are retained.

PR #2628 supplies GuestInvocationFrame integration in OresEvalRootNode.Evaluator.invoke. Each non-tail call reserves one 32-byte actor-owned managed frame and writes a 16-byte primitive descriptor within it. Proper tail transfers rewrite that descriptor. JVM carrier recursion remains guarded.

PR #2631 supplies a 64-byte SourceTask root bootstrap frame bound to one actor incarnation and one scheduler ExecutionContext. The frame remains charged over Await and Cooperate; physical quiescence gates retirement.

Combined behavior: a guarded invocation that executes inside an actor-bound SourceTask now uses the scheduler's exact actor-domain ExecutionContext. Its call frames nest ABOVE the retained SourceTask root in the same actor frame/byte quota. The calls unwind before Await/Cooperate; the SourceTask root survives carrier migration. A guarded synchronous call outside an actor-bound SourceTask still receives an independently minted actor execution identity. With 64 total frame slots, a live SourceTask root leaves 63 for additional nested non-tail invocations. Proper tail transfers remain on the same descriptor.

## Security hardening

The scheduler's trusted quiescence callback must complete BEFORE the SourceTask frame is zeroized. A failing callback quarantines the task and retains frame bytes. Completion of a Future alone is not proof of physical resource retirement. The trusted host-only quiescence hook rejects absent actor context and UNTRUSTED admission, which must use its separately quota-governed path.

A stale or cross-actor invocation descriptor scope now poisons the guest guard and actor arena, then removes its invalid carrier-local binding. A mismatched actor SourceTask allocation domain is rejected and poisons the current actor arena. This does not grant an actor access to any other actor's frame bytes.

## Tests authored

Three additional JUnit tests join the eight SourceTask tests from #2631, five invocation-descriptor tests and three compiler-backed .atto fixture test methods from #2628, and earlier stack-storage tests:

1. A guarded invocation inside SourceTask shares exact ExecutionContext object identity, adds only 32 bytes atop the 64-byte root, survives 1000 tail transfers with bounded frames, unwinds before Await, then reenters under the same identity.
2. The trusted quiescent callback sees the still-owned 64-byte frame; only after it returns is the frame zeroized before Future completion publication.
3. SourceTask nested non-tail calls exhaust the actor frame limit; a sibling actor survives on the shared one-worker carrier. The failed generation's bytes are reclaimed only after scheduler cleanup.

MANDATORY actual CI test command:

    mvn -Dtest=ActorSourceTaskFrameTest,GuestInvocationFrameTest,ActorGuestInvocationLanguageTest,ActorFrameArenaTest,ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest,ProperTailCallTest test

Record exact PR head SHA and Git tree, nonzero runner job steps, JUnit XML, fixture blob IDs, guest output and exit status, and an independent review. Zero-step jobs are runner admission failures, not evidence of compilation.

## Unimplemented requirements

The JVM evaluator still uses recursive host frames for expressions, locals, operand evaluation and lexical exception/cleanup paths. The SourceTask root and invocation descriptors are bootstrap managed byte charges, NOT typed compiler-lowered PC/local/operand/return/ownership frames, process-level fault isolation or portable guest ABI. Real source-level suspension frames and mailbox non-reentrancy still require an integrated compiler-backed acceptance test.

Next: implement typed heap-frame IR, iterative dispatch instead of unsafe recursive interpreter calls, proper tail calls for fnc and methods, SourceTask typed return/unwind state, failure containment and supervisor restart on actual .atto guest programs; then prove the same behavior on LLVM, .NET NativeAOT, Wasm and Forge/Cranelift. Do not catch native stack faults as recoverable errors. Keep cross-backend evidence NOT_EXECUTED until actual guest receipts exist.

Do not promote or merge this draft on host-only evidence.
