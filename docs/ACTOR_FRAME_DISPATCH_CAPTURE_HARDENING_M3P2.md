# AttoLang M3.2 actor-frame lease and Task capture hardening

**Status:** source fixes and eleven adversarial JUnit methods authored on an
isolated child branch of [Java #2643](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2643).
No compiler-backed guest, Maven/JUnit or native process-stack proof has run.
The parent branch remains untouched.

## Audited failure paths and fixes

### Stale return-record capability on reused actor carrier

The M3.2 48-byte `ActorGuestReturnRecord` previously authenticated
`Thread.currentThread()` and the shared `ActorFrameArena` but not the
**specific physical actor dispatch** or **scheduler SourceTask identity**.
Both a later mailbox turn on the same carrier and another task of the same
actor share those weaker attributes. A leaked record therefore had a stale
capability reuse path even without cross-actor access.

The record now captures `ActorRuntime.currentMutexExecutionSegment()` by
**object identity** and `OresScheduler.currentExecutionContext()` by
identity at allocation, and requires both to match on read/close. These
comparisons are in addition to same thread, frame capability, actor arena,
generation, owner and byte-record execution ID. Stale/foreign reads or closes
throw `SecurityException` without mutating or poisoning the rightful
owner; only physical actor quiescence may reclaim a leaked record.

This is an ABI **prototype only**. It does not implement guest return-PC
dispatch or stack switching, and it cannot be used as a credential to
migrate an active record across carriers.

### Actor-owned scalar shadow must not leak via Task capture

`Evaluator.Env` still keeps JVM `Slot.value` as a compatibility shadow
for an actor-owned scalar lexical slot. `preflightTaskCaptures` and
`detachedTaskSnapshotUnchecked` had read the shadow directly and could
have copied or moved a value without consulting the owning arena/SourceTask
generation. These are independent Task capture paths, not safe frame
transfers.

Both methods now deny COPY/MOVE of a slot marked `actorScalarBound`
with a `SecurityException` before detached-task creation; the second
guard is defense in depth if upstream preflight is bypassed. A rejected
transfer must not mark the original slot moved or create a detached
capability. Unmanaged ordinary scalar Task capture remains supported.
This is a conservative fail-closed rule, **not** general compiler-lowered
Task captures or a new implicit conversion.

### Corrupted frame bytes

The prior scalar slot and return-record implementations already pin typed
tags and ABI metadata, so this branch adds **active negative mutation
tests** to validate those checks. An authorized *test harness* mutates
frame bytes, then verifies invalid but otherwise valid type tags,
noncanonical Boolean payloads, magic headers and execution IDs cause
actor-local fail-stop without poisoning a sibling actor. No byte-writing
or mutation capability is exported to guest code.

## Additional tests — all AUTHORED_NOT_EXECUTED

- **4** extra `ActorSourceTaskLexicalFrameTest` methods: changed
  canonical type tag, invalid Boolean payload, Float raw NaN bits
  surviving `cooperate`, invalid lexical scope/name rejected before
  mutation.
- **4** extra `ActorGuestReturnRecordTest` methods: reused physical
  actor carrier, another actor SourceTask, tampered ABI magic and changed
  execution ID.
- **3** `ActorLexicalTaskCaptureFenceTest` methods: managed scalar MOVE
  denial without parent mutation, managed scalar COPY and direct
  materialization denial, and a normal unmanaged COPY control.

A local JDK 21 is available but the project Maven tool is missing and
GitHub cannot be resolved from the local working environment, so no
project or source JUnit run is claimed. GitHub's Actions jobs also require
a real runner with nonempty executed steps.

Focused exact-head command after runner admission:

```sh
mvn -B -ntp -Dtest=ActorLexicalTaskCaptureFenceTest,ActorSourceTaskLexicalFrameTest,ActorGuestReturnRecordTest,ActorLexicalScalarFrameLanguageTest,SourceTaskScalarParameterBindingTest,ActorSourceScalarParameterLanguageTest,ActorScalarSourceTaskIntegrationTest,ActorSourceTaskFrameTest,ActorFrameArenaTest,GuestScalarCallFrameTest test
```

Require full commit/tree, toolchain, runner and attempt IDs, JUnit XML,
test stdout/negative mutation receipts and independent approval.
`steps=[]`, `steps=null` or `runner_id=0` are runner admission failures.

## Remaining non-promotable gaps

These fixes authenticate **host ABI primitives**, not general recursive
guest-frame semantics. `Env.snapshot` still shares JVM `Slot` references
for lexical closures and asynchronous callbacks, but frame-backed scalar
`Env.lookup` requires an authenticated owning task and cannot be silently
rehydrated from the shadow. Audit callback/closure lifecycle before
removing this fallback or enabling capture transfer.

The interpreter still performs recursive JVM non-tail guest calls;
reference roots, operand stack, real return PCs, defer/unwind and actor
supervisor restart are not lowered into independent stacks. A 48-byte
record with no dispatcher integration is not evidence of stacklessness.
Physical low-native-stack process survival and five-backend guest parity
remain **NOT_EXECUTED**.
