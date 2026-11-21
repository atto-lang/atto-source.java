# Oreslang actor/process hot upgrade: execution domains, epoch fences, and backend plan

**Status (2026-10-17): normative design and partial host-side admission primitives; NOT a
production actor hot-reload feature.** Read with the actor physical-boundary,
actor-family, and retirement-barrier documents. In particular, Java main's generic
`HotReloadManager` is a source-generation loader, **not** a mailbox-preserving
actor upgrade. The stacked Java module-contract PR #460 implements more conservative
module loading, but expressly defers actor state migration; it is not merged here.

## Production-readiness audit (2026-10-18)

**Implementation versus target:** Java HotReloadManager can stage checked
Graal contexts, and the experimental logical router migrates simple SHARED
source actors across contexts with an unchanged state/message schema.
A new context or actor-owned arena does not guarantee OS address-space
protection, independent garbage collection or safety from arbitrary native
code. Java PRIVATE and UNTRUSTED upgrades now fail closed in the V1 same-JVM
router until a per-actor protected worker or verified isolate is available.

**Read cancellation is part of quiescence:** a logical output reader registers
a physical output subscription before the routing lock is released. If the
host times out or is interrupted, that subscription must be cancelled.
Otherwise it can strand the old code-image generation and block the next
upgrade. Both cases have Java source regression tests.

**No JIT requirement:** Java/Truffle may interpret new source or optimize it
on JIT-enabled targets. LLVM may use offline AOT-built, signed PIC code or a
trusted ORC JIT generation. AOT code can be hot replaced via checked loader
indirection. Neither a raw FFI pointer nor a JIT resource tracker proves it
is safe to unload code still reachable from a suspended frame, callback,
shared arena, external device, or continuation.

**Production release barrier:** require authenticated artifact provenance,
state-schema and message-ABI receipts, capability non-escalation, actor
execution/continuation/task drain, verified state-loan and shared-region
leases, bounded immutable message transfer, FFI/DMA retirement, and
generation-pinned rollback. Failed preparation reopens the old route only
after candidate cleanup; failure after publication must keep old code
quarantined until its last pin is gone. Never silently resume an invalid
previous physical actor after committing a newer route.

**Erlang comparison:** BEAM processes have separately GC-managed private
heaps but normally share an operating-system address space. Oreslang can
choose a *stronger* protected-worker boundary for isolated and untrusted
actors, but must verify it independently of heap ownership and actor kind.

These additions are draft hardening, not passing guest compiler evidence.
The source-owning GitHub organization must execute the exact-head JDK25 and
GraalVM tests, or independently approve a private carrier on a protected
branch before any source mirror.

## Correct memory and process model

An *actor* is not an OS process. Erlang/BEAM lightweight processes have private
managed heaps but ordinarily inhabit **one OS address space**, not one protected
OS address space per Erlang process. Oreslang must distinguish:

| Kind | Ordinary mutable allocations | Shared data | OS address-space protection | Hot code policy |
| --- | --- | --- | --- | --- |
| SHARED | Actor-owned arena/heap, even for siblings | Only explicit frozen regions or capability-governed, synchronized shared regions | Same process is normal; no hostile-code isolation claim | Same-process generation/code-table switch after all actor and shared-region pins reach the barrier |
| PRIVATE / ISOLATED (trusted) | Actor-owned heap/arena and runtime state | Typed serialized messages, immutable code, explicitly authorized imports | **Target:** separate protected address space for isolated actors; ordinary Java Context alone does not provide that guarantee | Supervisor can replace a worker incarnation, restoring checked state and stable logical ActorRef |
| UNTRUSTED | Separate heap and independently revocable capabilities | Serialized/copy-only by default; no host handles or mutable shared pointer graph | **Required target:** enforceable sandbox (exec worker + OS sandbox, or separately proven isolate). Native C++ arena alone is not containment | Start candidate in quarantined sandbox, validate capabilities, then transfer routing epoch; never dynamically link untrusted machine code into trusted host |

Here `SHARED` means *permitted to access an explicitly shared data domain*, not
that two actor instances allocate normal mutable objects in a common heap. An
arena is an allocation/lifetime strategy, **not** a security boundary. Multiple
arenas can live in the same OS address space. Native Image isolates, WASM
instances, or an independent process each need their own separately proven
pointer/capability and failure boundaries.

A protected actor worker may be **one actor per address space** when that is the
contract; pooling multiple isolated actors in a single worker without full isolate
teardown changes the contract. Shared code pages and read-only artifact caches may
be reused across domains only with identity/tenant authorization and memory-safe
boundaries. Address-space changes are orthogonal to code-generation changes.

## JIT is not required for hot reload

Hot reload means switching **which executable generation** receives newly admitted
work. It does *not* mean patching instructions under a running frame.

* **Java/GraalVM on JVM:** new Oreslang source/AST in a new checked Context,
  versioned call target, and ordinary Truffle interpretation/JIT where permitted.
  Old call targets/contexts remain pinned. An AOT-compiled Java host cannot be
  assumed to load new Java bytecode, but a supported embedded guest interpreter
  may interpret checked source without guest machine-code JIT.
* **LLVM native:** AOT compile a new `PIC` shared object/relocatable image offline,
  verify manifest, symbols, signatures, platform triple, target features, runtime
  ABI, signatures and provenance, then `dlopen`/`LoadLibrary` through a **trusted**
  loader. Alternatively compile/load with LLVM ORC JIT in a trusted executable
  process. Both paths use a stable C-compatible indirection/vtable boundary; no
  arbitrary cross-version raw function pointers or arbitrary `dlclose`.
* **Wasm:** instantiate a checked new module (AOT-compiled or interpreted),
  migrate *owned* state by canonical wire snapshot, then switch routing handles;
  WASI capabilities must be re-granted for the destination instance.
* **Embedded/no_std:** no OS loader or JIT is assumed. Upgrade requires a
  platform-supplied authenticated image/flash bank or a prelinked function-table
  slot; when unavailable, fail closed or reboot into the new image. Core must not
  depend on stdlib, filesystem, dynamic linking, thread APIs or JIT.
* **iOS/locked-down targets:** do not assume executable-memory JIT or runtime
  native library installation. Source interpretation or approved packaged code is
  deployment-policy-dependent; explicit target capability checks are mandatory.

`AOT + JIT` hybrid is a useful server profile, **not** the definition of
hot reload. For native production rollout, prefer offline signed AOT images and
use ORC JIT when dynamic compilation is actually beneficial and authorized.

## Upgrade transaction and invariants

Logical identity is `ActorId`, not address or PID. Every executable incarnation
has `(ActorId, epoch, imageDigest, schemaDigest, capabilityDigest)`; route tickets,
mail envelopes, callbacks, timers, FFI/GPU leases and shared-region handles carry
or are associated with the incarnation epoch. Compiler-proof receipts must bind
the source tree/dependencies, exported message ABI, state-schema field IDs, 
runtime/stdlib ABI, target/platform features and capability manifest.

1. **Admit/stage:** supervisor verifies signer, immutable artifact digest,
   per-tenant authorization, target ABI/CPU, export contracts, exact capabilities,
   quotas and approved dependency closure. A candidate is not visible to traffic.
2. **Freeze ingress:** atomically stop *new* admission at the actor routing entry;
   persist bounded messages or return explicit backpressure. Independently
   controlled CONTROL cancellation/failure signaling must remain available.
3. **Quiesce:** wait for every executing actor turn (including configured
   multi-thread actor maxima), all suspended continuations, state loans,
   in-flight request lifetimes, outbound reply writers, FFI/GPU DMA, timers,
   pinning shared-memory holders, and any borrowed code-generation handles.
   Deadline expiry aborts/rolls back or uses an explicit restart policy: it
   must not declare quiescence by timeout. A snapshot must not contain raw
   pointers, JVM objects, closure frames, OS descriptors, outstanding loans
   or unowned region offsets.
4. **State compatibility:** v0 supports only **identical** state-schema,
   message ABI, and capability fingerprints. No implicit schema migration;
   `ActorUpgradeEpochGate` (Java host-only) models this fail-closed rule.
   Future versioned `migrate(oldState)->newState` executes in an authorized
   isolated staging domain with deterministic resource/timeout limits and
   an explicit canonical serialized schema validated before publication.
5. **Initialize candidate:** no externally visible side effects before the
   routing commit unless transactional/idempotent compensation is provided.
   Restore owned state in a *fresh* arena/address space; rebind resources by
   supervisor capabilities, never copy raw host handles or closure pointers.
6. **Commit:** compare original actor/route epoch and authorized identity,
   publish new `(image, state, epoch+1)` as **one atomic routing transition**.
   Reject stale/misbound tickets. Drain/preserve old in-flight replies according
   to explicit versioned wire contracts; never deliver one message to two
   incarnations. ActorRef remains stable; incarnation changes.
7. **Retire:** old image/arena/executable pages persist until every generation
   lease is gone, including unwinder/exception paths and native stack frames.
   Destructors run outside global routing locks. Failed FFI reclamation is
   quarantined (do not `dlclose`/unmap until proven safe); actor DONE and code
   image retirement are separate barriers.
8. **Rollback:** before commit, restore old ingress/active generation, destroy
   only safely closable candidate resources and record a reason. After commit,
   rollback is another explicitly versioned upgrade; arbitrary rewinding of
   delivered messages, database writes or network effects is impossible.

Old queued messages must have one explicit policy: preserve with proven
backward-compatible ABI; transform with a verified migration; drain in old epoch;
or reject with a typed, correlated error. A suspended continuation must never
resume in a newly compiled code generation by pointer substitution. All actor
`get_ready()` and `get_done()` lifecycle promises are incarnation-aware and
cannot be accidentally fulfilled by an older worker's late CONTROL message.

## Fail-closed boundaries and remaining work

* **Already present:** Java generic manager stages contexts and admits guest
  capabilities; this audit adds `reloadAndStart` transactional init-before-route
  publication, an optimistic monotonic routing epoch, failure rollback, and
  host-only `ActorUpgradeEpochGate` pin/freeze/ABI-equality model. LLVM provides
  actor-owned native heaps, versioned cells, and retirement primitives but **not**
  a native dynamic actor-image loader. Existing Graal module-contract draft #460
  is not evidence of actor migration or AOT process swapping.
* **Not integrated:** source actor coordinator, true Java actor-wide
  quiescence/epoch ticket instrumentation, protected source-worker bootstrap,
  signed artifact verification, LLVM PIC/ORC loading, serialized state restore,
  distributed handoff, cross-process FD/capability rebinding, native-image
  isolate validation, and live compiler-generated schema receipts.
* **High risk:** current legacy Java `load(...)` still publishes a staged,
  not-yet-started generation for compatibility; **never** use `active()` from
  that legacy path as a serving-ready routing handle. Remove the loophole only
  with a breaking API transition and caller migration.
* **Safety:** dynamically load only trusted, validated native code. A code
  signature proves provenance, not memory safety. For untrusted Oreslang native
  output, use a verified sandbox/process boundary and narrow broker instead
  of unrestricted host `dlopen`. Untrusted code-image sharing must be explicitly
  tenant-authorized and cannot grant ambient host capabilities.


## Follow-up implementation slice: real ingress fence and trusted PIC fixture (2026-10-17)

The Java `ActorRuntime` now has package-private, supervisor-only
`freezeUserIngressForHotUpgrade(ActorRef)` returning an opaque, idempotent
`HotUpgradeIngressFreeze` handle. It checks the actual runtime's two-phase
user-mailbox admission gates (both the initial slot reservation and final
committed-envelope admission) under `lifecycleLock`, rejecting new user messages
with `ActorSealedException`. Previously accepted messages and CONTROL
continuations remain eligible to finish. A bounded `schedulerDrained()`
observation checks execution-carrier ownership, scheduled dispatch, mailbox
accounting under its own lock, source suspension and registered pending
operations; a stale/closed handle cannot lift another handle's seal. The
reservation is released by `close()` on rollback. Runtime-termination
invalidates it.

**Critical limit:** `schedulerDrained()` is an observation, not a durable
quiescence certificate. It does not pin every timer, filesystem callback,
JNI/FFI frame, GPU/OS resource, external broker or shared-region mapping;
a new callback may become eligible after it returns true. Nothing in this slice
changes a source actor's immutable `codeExecutor`, copies its
`ActorOwnedGuestState`, rebinds a stable `ActorRef` to another address space,
or emits a compiler-attested image receipt. The real code+state commit API
must remain unavailable until those requirements are implemented.

The LLVM backend now includes a POSIX **test-only** AOT/PIC experiment.
CTest compiles three independent shared objects (old, new, incompatible) and
a host binary that exercises `dlopen`/`dlsym`, exact ABI descriptor checks,
stale routing epochs, generation-scoped leases, old-version calls after
publication, and no premature `dlclose`. This explicitly proves neither
source-generated LLVM IR nor trusted artifact admission. Arbitrary native
`dlopen` executes initializers **before** metadata inspection; production must
verify the artifact's actual bytes, signature, authorized dependency closure
and execution domain BEFORE the target can run any code. Untrusted actors must
never enter this loader in the supervisor address space.

Next release gates in dependency order:
1. Compiler-private image/state/message/capability receipts with cryptographic
   byte provenance, plus a trusted supervisor admission API.
2. Scheduler-wide epoch pins for outstanding task lifetimes, late timer
   callbacks, FFI and resource leases; `freeze + schedulerDrained` alone is
   insufficient to prove a safe swap.
3. Typed canonical actor-state snapshot/restore and verifier; strict same-schema
   admission first, explicit state migrations only after that.
4. Stable logical actor routing to fresh worker/arena with a single commit epoch,
   old mailbox version handling, and rollback-before-commit.
5. Production native PIC/ORC loader whose authorization boundary executes
   BEFORE code is dynamically linked, real AOT actor sample, ASan/UBSan and
   cross-process sandbox tests.
6. JDK25/Maven, Graal Native Image, LLVM CMake/CTest and exact-head evidence,
   including nonzero Actions job steps and an authorized private source runner.

## Required executable test matrix

1. **Language:** compiler-produced schema/ABI digests; type-safe migration;
   negative contracts, ownership loans, capabilities, no extra imports/exports.
2. **Scheduler:** 1-thread and explicitly configured 3-thread actors;
   waiters, continuations, timers, panic, raised failure, stop/cancel, CONTROL
   during upgrade; thousands of raced freeze/publish/abort/retire operations.
3. **Memory:** shared region lease retained by older incarnation, foreign/stale
   arena handles, FFI active frame, JNI native stack, GPU DMA; sanitizers/heap
   poisoning; bound code-image and arena retention under 100+ upgrade cycles.
4. **Transport:** old/new envelope versions, no duplicated delivery,
   backpressure, reboot/retry, HTTP stream ownership, WS/TCP and proxy routing.
5. **Backends:** exact-head JVM/Graal JIT, Graal Native Image/AOT+hybrid,
   LLVM offline PIC shared library and ORC JIT if enabled, Wasm module instance,
   and embedded dual-image/no-loader rejection. OS-level untrusted isolation
   must be independently demonstrated with real hostile guest programs.
6. **Failure matrix:** worker crash at each stage; invalid signature/import;
   ABI mismatch; failed init; stale/foreign ticket; coordinator crash before
   and after commit; rollback exception; quota/exhausted epoch; power loss;
   misordered late reply; repeated cleanup and double retire.

Only tests executing the **exact checked source tree** and verifying runner
steps/artifact provenance count as backend execution evidence. Source-free
model proofs and host-only Java/C++ tests are useful, but cannot certify
guest actors, native-image execution, protected address spaces or AOT reload.
Never export private source to another GitHub organization without the explicit
reviewed cross-org authorization policy.


## Second audit pass: completion-publication race, canonical checkpoint and router provenance

A production upgrade must never infer quiescence from the absence of a Future
in `pendingOperations` alone. Its completion callback may run after the
Future's removal hook but before the callback enqueues the actor continuation.
**Java's `ActorRuntime.enqueueOnCompletion` now owns a separate bounded
`pendingCompletionPublications` reservation**, created before the Future is
registered and released only after its continuation has been enqueued or its
registration failed. The scheduler drain observation also requires this
reservation count to reach zero. Registration failures release exactly once;
the reservation cannot be exhausted through integer wraparound. A new
real-scheduler JUnit regression stages a pending Future and then completes it
under a frozen ingress gate.

The Java backend also now has `ActorCheckpointEnvelopeV1`, a host-only
canonical envelope for a prevalidated **byte snapshot**:
`ORESCP01` (8 bytes), big-endian u32 version 1, UUID actor identity (16
bytes), big-endian u64 incarnation, four canonical 32-byte SHA-256 digests
(code/state schema/message ABI/capability policy), big-endian u32 payload
length, at most 1 MiB of opaque state bytes, and an unkeyed 32-byte SHA-256
trailer over all preceding bytes. Fixed header is 168 bytes; total length
is exactly 200 + payload length. The decoded record and returned bytes
are defensive copies. Decoder rejects wrong magic/version, foreign actor,
incorrect generation or digests, truncated/trailing data, length overflows,
and checksum modifications. **The checksum is NOT an authentication
signature, and a byte envelope is NOT a serialized guest object graph.**
No executor state is captured, transferred, restored or replaced in this
version; compiler receipts and independent state semantics are still required.

For cross-language conformance, Java JUnit fixes a golden frame digest
`c054268972740370c1be5ff20ceeda21edd17282a69692203acca15b4e6c1793`
for the canonical two-byte sample. An independent, source-free Python model
performs deterministic roundtrips, one-bit corruption and hostile
identity/ABI checks. LLVM CTest registers it in regular and `python -O`
modes, with explicit assertions that remain live under optimization.

The native PIC test router now binds each staged candidate to an opaque
unforgeable per-router authority, *in addition* to its monotonic routing
epoch. A candidate staged by one supervisor cannot be published by another
supervisor merely because their epochs and schemas happen to match. This is
still a **trusted fixture**, not an authenticated artifact loader.

### Evidence for this audit pass

Local checks on exact GitHub blobs:
- Java `ActorCheckpointEnvelopeV1.java` blob
  `885e46f2333d7e89a564db89cb572b5b14076782` compiled using JDK21
  `javac --release 21 -Xlint:all -Werror`. A separately executed
  standalone package-local smoke exercised **2,256** deterministic
  checkpoint assertions and the cross-language golden.
- Source-free Python wire corpus has blob
  `606f9154b8aee9b29fc176ad10b14abc23fa6c24` **identical in Java
  and LLVM repositories**, and the exact local blob passed **1,236 checks
  in each of normal and `python -O` runs**.
- Native PIC test blob
  `54fa36bf9e8b06d0d0bbf2c7e22b0e9c434fe101` and the
  exact four fixture blobs compiled with Clang C++17 warnings-as-errors;
  the test passed once with ASan+UBSan/leak detection and passed in
  **500 additional consecutive optimized executions**. This proves
  host-only PIC generational pinning, stale/foreign candidate rejection
  and safe controlled fixture unloading, *not* LLVM-generated actor code.

The Java ActorRuntime scheduling-race fix and its JUnit integration test
have not been executed through full Maven on the exact PR head. Neither
source repository currently offers usable Actions runner execution; the
private-source cross-org export policy presently authorizes no destination
and must not be bypassed by mirroring a Git tree. Stay in DRAFT.


## Third audit pass: upstream integration, external-work leases, native checkpoint parity

The draft branches have been reconciled with the current source-repository
`main` history through two-parent Git commits preserving every feature-file
blob and unrelated upstream changes. The merge bases were inspected; no
overlapping feature-file edits were present in the newly merged work.

### Java actor runtime

An actor upgrade reservation still blocks new user messages. The runtime now
also provides a **package-private trusted host** API
`pinExternalWorkForHotUpgrade(ActorRef)`. Its idempotent
`HotUpgradeExternalLease` represents one external callback, timer, FFI
operation, device request or shared resource whose owner independently
guarantees that it cannot execute after the lease closes. Under the same
`lifecycleLock` as `freezeUserIngressForHotUpgrade`, a new external lease
is refused after ingress freezes, while existing leases remain accounted.
`schedulerDrained()` now rejects quiescence while any registered lease is
open. Terminated, ending, sealing or force-fenced actors cannot admit new
external work. Tokens are constructed before counters are incremented, and
double-close is safe, including on a foreign completion thread. Stale
supervisor freeze tokens cannot release a later freeze. New real-runtime
JUnit tests cover idle/active/draining actors, freeze versus pin,
concurrent release and foreign-runtime ownership.

**This is not automatic native-resource capture.** A Java callback, JNI
native frame, GPU command or OS registration that has not been wrapped by the
host broker's matching acquire/release discipline is still outside this
proof. Neither actor state nor code image can be safely retired based solely
on `schedulerDrained()`. Outstanding leases may outlive actor shutdown,
and lifetime/cleanup ownership must remain with the registered broker.
Do not expose these supervisor-only APIs as guest methods.

### Native checkpoint wire format

LLVM now has `ActorCheckpointEnvelopeV1.hpp`, an independent C++17 host
encoder/decoder using the same big-endian 168-byte header, maximum 1 MiB
opaque state, 32-byte SHA-256 corruption trailer, and strict
actor/incarnation/code/state-schema/message-ABI/capability receipts as
Java and Python. A **host-supplied SHA-256 provider is mandatory**; this
does not pull OpenSSL, libc, JIT or any OS API into the freestanding core.
The optional OpenSSL-backed CTest fixture verifies the exact independent
Java/Python golden SHA-256 for a 202-byte checkpoint, 1,024 randomized
roundtrips, bit flips, truncations, trailing bytes, foreign IDs/ABIs and the
maximum payload. C++ deliberately rejects unsigned native incarnations
above the maximum positive Java signed 64-bit epoch; cross-backend wire
semantics must not differ at the sign bit.

The checksum is not a signature; authenticated compiler receipts, immutable
state graph validation, source actor state capture, trusted worker image
admission, atomic `ActorRef` routing and publication/rollback remain
future work. Native PIC test-only `dlopen` still cannot load unverified
or untrusted executable code.

### Execution evidence and CI safety

- Byte-for-byte native header Git blob
  `93785beee271551afb44dbd668403e732fd32ae2`.
- Byte-for-byte native CTest Git blob
  `3e7233df8ba3e1c49b68315c3a8aed09c59226f9`.
- Both reconstructed independently in a local Clang 17 host toolchain;
  `-std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -UNDEBUG`
  + OpenSSL Crypto **PASS**; ASan+UBSan with leak detection **PASS**;
  **50 additional optimized executions PASS** on the latest blobs.
- The earlier native checkpoint stage additionally passed 100 repeated
  executions; these are host-only wire tests, not guest LLVM IR execution.
- New Java `ActorRuntime` external lease JUnit tests have **not yet run**
  under Maven/JDK25/Graal; do not infer their success from native testing.
- Source-org Actions jobs continue to report **zero executed steps**.
  The source repo's private CI destination approval allowlist is empty, and
  the review-pinned transfer dispatcher is not independently authorized.
  Never export a private source tree to an unrelated funded organization
  merely to obtain runner minutes. Use an approved in-org/self-hosted runner
  or complete the independent authorization path first.

### Remaining production release gate

In order: (1) authenticated compiler image/ABI receipts;
(2) complete automatic generation pinning in all runtime brokers;
(3) deterministic actor-owned state graph snapshot and restore;
(4) stable logical ActorRef dispatch with audited atomic commit and rollback;
(5) genuine source-generated AOT and optionally ORC-image execution in
OS-protected workers where required; (6) exact-head full compiler, native
image, sanitizer, concurrency and crash-injection suites.


## Fourth audit slice: live host counter transaction and rollback (2026-10-17)

This draft now includes a **trusted-host counter actor hot-upgrade proof** in both
backends, built from the real generation gates and the canonical checkpoint
envelope. It is not compiler-produced Oreslang source hot reloading.

**Java**: `ActorCounterUpgradeProof` creates a real `ActorRuntime`
SHARED actor with a stable physical `ActorRef<Long>`, receives actual
mailbox messages, and calls the trusted Java behavior selected by the
host-controlled version. Each executing counter turn holds an
`ActorUpgradeEpochGate.Lease` while committing its
`VersionedActorCell` state. The supervisor's upgrade attempt atomically
fences user ingress, checks the real scheduler's physical quiescence,
prepares an ABI-compatible image, snapshots the one int64 through
`ActorCheckpointEnvelopeV1`, restores into a fresh state cell, publishes
the next generation and swaps the trusted behavior before reopening ingress.
If an old actor carrier is still active, the attempt returns NOT READY without
blocking. Failed restore, incompatible ABI or same-schema state drift abort
before publication and release the ingress fence. Host observations take the
transaction mutex so they cannot read an incarnation after retirement.
`ActorCounterUpgradeProofTest` exercises 42 -> 42 -> 43, stable
`ActorRef` and address, a meaningfully changed v2 behavior, a busy carrier,
incompatible digests, initializer faults and lost-state rejection.

**LLVM**: `NativeCounterUpgradeProof.hpp` composes
`ActorHotUpgradeGate`, `VersionedActorCell` and the portable 1 MiB-bounded
`ActorCheckpointEnvelopeV1`, requiring an injected SHA-256 provider.
The C++17 test `native_counter_hot_upgrade_proof_test.cpp` covers
ABI refusal, failed restore, explicit restore-value drift, preserved state,
changed host behavior, and a concurrent pinned old-generation worker.
Versioned native state observations are synchronized with routing commit.
CTest registers `native_counter_hot_upgrade_transaction_host_only` when
OpenSSL Crypto is available. It does **not** compile or dynamically load
a source-generated LLVM `.ores` code image.

**Reference-model verification**: the identical source-free file
`tests/actor_hot_upgrade_transaction_model.py` in both repos has Git blob
`eaf52579fc0e27deffc994e2601a3e94c5f8c3cf`.
The byte-identical local model ran **96,000 randomized deterministic
interleavings in ordinary Python and another 96,000 under `python -O`**,
including admitted-message conservation, delivery-ticket ordering,
pending-pin refusal, rollback, 23 successful upgrades per run,
and complete no-loss/no-duplicate draining across 32 seeds.
The model's assertions were deliberately replaced with explicit checks
so optimized Python cannot silently disable invariants.
These tests are **model-level**, not evidence that actual Java/GraalVM or
native actor scheduler integration passed.

**Production remaining**: code fingerprints in these narrow examples are
trusted inputs, not signed/compiler-attested executable bytes. A host
Java function and host native function pointer are not a replaceable
compiled Oreslang guest actor. Compiler-generated owned-state schemas,
automatic resource/FFI pinning, protected worker boundaries, rebindable
stable source `ActorRef`, actual AOT/ORC image admission, and full CI
must all pass before releasing a real source actor upgrade.


## Fifth audit slice: real Java Oreslang source-actor same-context upgrade v1

The Java/Graal evaluator now implements the source-actor upgrade contract
directly; this is no longer limited to a fake host counter behavior.

### Runtime transaction

`ActorRuntime.upgradeSourceActorSameSchema(ref, candidateExecutor)` is a
supervisor-only Java host API and requires the runtime ceiling to grant
`HOT_CODE_LOAD`. V1 preserves the existing logical/physical `ActorRef`,
mailbox, output stream, parent/supervision relationship and actor kind.

The transaction:
1. seals new user ingress with the real actor freeze;
2. requires complete physical scheduler drain, including continuation
   publication and registered external-resource pins;
3. captures the old executor/image/state/epoch under `lifecycleLock`;
4. requires old and candidate executors to share one opaque
   `actorUpgradeDomain()` identity, so an evaluator from another
   `OresContext` cannot be spliced into this actor;
5. requires a different code receipt but identical state-schema and message-ABI
   receipts;
6. releases `lifecycleLock` before compiler-owned serialization/restoration,
   so CONTROL/cancellation is never held behind codec work;
7. wraps the serialized state in `ActorCheckpointEnvelopeV1`, bound to actor
   UUID, source-code epoch, code/schema/message receipts and the effective
   runtime actor policy;
8. restores a fresh candidate-owned state object without running field
   initializers or `on_start`;
9. re-snapshots the candidate and requires byte-for-byte state equality;
10. reacquires `lifecycleLock`, rechecks drain and the complete old
    executor/image/state/epoch tuple, and commits the candidate
    image/executor/state as one monitor-protected transition.

A stop, late continuation, changed generation, altered candidate provenance or
other lifecycle change during staging fails closed rather than publishing a
stale checkpoint. This is intentionally an in-place same-`OresContext`
transaction; cross-context/process migration requires a future logical actor
routing/incarnation layer rather than weakening this domain check.

### Real evaluator state transfer

`OresEvalRootNode.Evaluator` now supplies:
- an opaque upgrade domain equal to its owning `OresContext`;
- deterministic structural code, state-schema and message-ABI receipts;
- source actor kind in schema/message receipts;
- compiler-private snapshot and restore of persistent fields;
- candidate restoration into a fresh `OresObject`/`SourceActorState` with
  the original logical actor id;
- no candidate `on_start` execution during restore.

The structural code digest is deliberately independent of source filename, so
renaming an otherwise identical AST does not manufacture a new code version.
It is a runtime structural receipt, **not** a signed artifact digest.

### ORESAS01 scalar payload

`SourceActorCheckpointCodecV1` is the inner deterministic state serializer.
V1 admits only persistent `int`, `bool` and `string` fields. It preserves
field order/name/type, signed int64 exactly, canonical bool 0/1 and strict
UTF-8. Hidden/missing fields, floating-point coercion, oversized integers,
malformed UTF-8, unsupported object graphs and payloads above 1 MiB fail
closed. Raw ActorRefs, closures, capabilities, OS/JNI handles and arbitrary
JVM objects are not serializable.

`ORESAS01` itself is intentionally **not** a checksum. The outer
`ActorCheckpointEnvelopeV1` supplies the SHA-256 corruption trailer and actor
identity/ABI binding. The independent Python oracle was corrected after audit:
structural corruption must fail, while a changed scalar value may remain a
valid but different serialized state.

The identical Python wire oracle blob in Java and LLVM is
`69c52e82cdf8dc589e00e416542bb7f43940d6db`; exact local execution passed
**123 checks in normal Python and 123 under `python -O`**.

### End-to-end Java source test

`SourceActorHotUpgradeExecutionTest` evaluates two genuine Oreslang programs
inside one restricted language context, retrieves their linked real evaluator
executors, and:
- spawns source actor `Counter` v1 with `count = 1`;
- sends 41 and observes 42;
- upgrades to real source v2 with the same actor reference/address;
- proves v2's `on_start { count = 999 }` is NOT rerun;
- sends -1 / -2 and observes 43 / 45 under changed v2 receive semantics;
- rejects identical structural code, changed field schema, changed receive
  message ABI, changed actor kind and a candidate evaluator from a different
  `OresContext`;
- verifies failed candidates leave v1 operational.

The test retries only nonblocking safe-point admission; it never retries guest
messages or guest computations.

These new Maven/Graal integration tests are **committed but not yet executed**
on the exact source head because source-owned Actions jobs continue to receive
zero runner steps.

### Native parity

LLVM now contains `SourceActorCheckpointCodecV1.hpp`, a C++17 implementation
of the same ORESAS01 scalar payload, plus an OpenSSL-backed golden/parity test.
Current exact native blobs:
- codec header: `715a9e70367aef3afebaf4b081b9a70356696fd4`;
- native test: `9133cdc8560ad75ca03ff9c80413407aec844b6c`.

Those exact blobs compiled with Clang C++17 warnings-as-errors and passed the
golden hash, signed-int64 extremes, truncation, trailing-data, malformed UTF-8,
type mismatch, ASan+UBSan/leak detection, and **100 additional optimized
executions**. This establishes state-wire parity only; LLVM still lacks a real
compiled `.ores` actor image/state restore and routing commit.

### Remaining production gates

V1 still does not provide cross-`HotReloadManager` Context migration,
cross-process stable ActorRef routing, arrays/records/options/results,
explicit schema migrations, compiler-derived required-capability/effect
receipts, signed executable/dependency provenance, or automatic generation
leases in every timer/FFI/GPU/shared-memory broker. These remain mandatory
before production hot upgrade.


## Sixth audit slice: cross-OresContext logical actor routing v1

Java now has a **host/supervisor-only cross-context source-actor transaction**
built on top of the same real evaluator snapshot format. This is intentionally
not yet the public/source-level ActorRef implementation.

### Stable logical route and physical incarnations

`LogicalSourceActorRouterV1` owns one stable logical UUID and monotonic logical
incarnation while each generation owns a distinct physical
`ActorRuntime.ActorRef`, ActorId, runtime and OresContext. The logical handle
is the only supported route surface for this v1 coordinator.

A migration uses this order:

1. validate/link an actor-only candidate generation without executing init/main;
2. pin the candidate HotReloadManager generation lifetime;
3. pause host route admission;
4. freeze and physically drain the old source actor;
5. checkpoint old compiler-owned state under the stable logical UUID/incarnation;
6. restore into a fresh physical actor in the candidate OresContext without
   rerunning actor initialization/on_start;
7. await candidate readiness;
8. atomically publish the new logical route/incarnation;
9. stop the now-stale physical actor;
10. keep the old generation pinned until **physical finalization**, then release
    its context lifetime lease and reopen the logical host route.

Before publication, any failure rolls back by destroying the candidate,
releasing the checkpoint freeze and reopening the exact old physical route.
After publication, failures never restore old ingress: the new route remains
authoritative and the old generation stays pinned until later finalization.

### Host admission semantics

V1 deliberately blocks **host** senders at the route monitor while migration is
in progress and holds that monitor through physical mailbox admission. It does
not buffer arbitrary mutable JVM message graphs outside ActorRuntime's normal
transport/copy/freeze path. Actor and scheduler carriers are forbidden from
calling this blocking route surface.

A future guest-visible logical ActorRef must instead use runtime-owned immutable
transport envelopes and continuation-safe nonblocking suspension.

### HotReloadManager generation lifetime

Actor-only generations can be staged with
`stageActorGeneration(...)` and linked with
`Generation.linkActorGeneration(actorType)` using the same internal
LINK_ONLY convention already used by LinkedProgramRunner. Linking does not run
module init or main.

Each logical route holds an `ActorRouteLease` on its generation. Pin admission
is linearized under the owning HotReloadManager registry lock against
`retire(id)`; either retirement wins and the late pin fails, or the route pin
wins and retirement fails. Direct Generation.close(), HotReloadManager.close()
and Generation.start() all fail while a route pins the context. Manager shutdown
therefore cannot invalidate a live actor behind its logical route.

The logical router retains its HotReloadManager strongly and v1 accepts
candidate generations only from that same manager authority domain.

### Runtime/evaluator affinity and restore rollback

Every real OresContext binds its ActorRuntime to that exact source-executor
domain. Raw `spawnCodeActor` and transfer restore reject an evaluator from a
different context/runtime domain.

Fresh restored physical actors can be seeded with compiler-owned checkpoint
bytes. They are marked ready without executing `initializeActor` or
`on_start`. A failed restored spawn now reclaims all resources allocated
before actor registry publication: actor slot, mailbox/continuation envelopes,
output stream/reservations, source state and private memory arena.

Unpublished candidate actors whose teardown has not physically finalized keep
their generation lease. The reaper uses an explicit physical-finalization
predicate, not `ActorRef.isAlive()`; logical stop alone is insufficient to
release executable/context lifetime.

### Explicit v1 topology/output limits

Cross-context v1 is restricted to **root actors only**. Transfer fails if the
physical source actor has a parent, is managed by an ActorGroup, belongs to a
cooperating ActorGroup, or owns actor-created groups. Parent/supervisor/group
lineage is not silently discarded.

Output routing is also not rebound yet. Transfer requires the old physical
output channel to contain neither queued outputs nor registered pending readers.
Unread output or a pending `next()` therefore blocks migration until the
parent consumes/cancels it.

These restrictions are deliberate correctness fences, not claimed production
features.

### Cross-context tests and model evidence

`LogicalSourceActorCrossContextUpgradeTest` stages two genuine Oreslang source
generations in distinct OresContexts/runtimes. Its intended assertions include:

- v1 state reaches 42;
- logical UUID stays stable while logical incarnation advances 1 -> 2;
- physical runtime id and ActorId change;
- candidate on_start/main do not execute during migration;
- restored v2 produces 43/45 and continues after retiring v1 generation;
- state-schema incompatibility rolls back to the exact old route;
- a foreign HotReloadManager candidate is rejected;
- a foreign OresContext executor is rejected at raw source-actor spawn;
- live generation retire/close/manager-close/main execution are rejected while
  a logical route pins the context;
- unread outputs, pending output readers and ActorGroup membership block
  migration rather than being lost;
- injected candidate restore failure restores the old actor and returns the
  target ActorRuntime to its previous actor-count/memory accounting.

`HotReloadGenerationRoutePinRaceTest` races route-pin acquisition against
generation retirement repeatedly and requires exactly one linearization winner.

The identical source-independent route model in Java and LLVM has Git blob
`937cb7c894006894be8cef4f9cbbe0ea69bbf10f`. Exact local executions passed:

- **160,000** deterministic transitions in ordinary Python;
- **160,000** deterministic transitions under `python -O`;
- 44 committed modeled migrations and 18 rollbacks in each run;
- 160,000 active-generation retirement denials;
- host sends injected during modeled route pauses;
- final accepted-message conservation with no lost/duplicate ticket.

The model remains reference evidence only. The new Java/Graal cross-context
JUnit tests have not executed on a repository runner.

### Remaining production gates

The next mandatory work is: a guest-visible/nonblocking stable ActorRef and
transport envelope; parent/ActorGroup/supervision topology rebinding; output
stream rebinding; compiler-derived required-effect/capability/dependency
receipts; aggregate/cyclic persistent state; signed executable provenance;
and a real LLVM compiler-generated actor image using the same route/checkpoint
transaction. Exact-head Maven/Graal/native integration CI remains required
before merge.


## Sixth audit slice: stable logical route across distinct OresContext generations

Java now has a host/supervisor-only cross-context source actor route built on
HotReloadManager generations. This is the first draft path that keeps one
logical actor identity while replacing its physical ActorRuntime/ActorRef and
OresContext.

### Logical identity versus physical route

`LogicalSourceActorRouterV1` owns a stable UUID and logical actor
incarnation. A transparent code hot replacement **does not** increment actor
incarnation. Instead it advances a separate monotonic `routeEpoch`.
Physical ActorRef generation/runtime ids still change and remain stale after
publication. Crash/restart semantics must use incarnation later; code-route
replacement must not be conflated with restart identity.

V1 is deliberately a blocking **host** surface. `LogicalActorRef.send`
linearizes route lookup and physical mailbox admission under the route monitor.
If migration owns the route, host callers wait until rollback or publication.
Actor/scheduler carriers are rejected from this blocking API. No raw mutable
message graph is buffered outside ActorRuntime transport.

### HotReloadManager actor-only staging

`stageActorGeneration` validates source into a fresh restricted Context
without making it the generic active route. `Generation.linkActorGeneration`
uses the repository's existing internal LINK_ONLY control path to link
declarations without module initialization/main. A linked actor generation
cannot later run generic init/main while a logical actor route pins it.

Each live logical route owns an `ActorRouteLease`. Pin acquisition is
linearized under manager -> generation lock order against retire. Therefore:
- `HotReloadManager.retire(id)` cannot close an active actor generation;
- direct `Generation.close()` cannot close a pinned generation;
- manager shutdown refuses while logical actor route pins remain;
- old generation pins are released only after physical actor finalization;
- unpublished candidate actors that fail rollback remain quarantined/pinned
  until their physical finalization can be proved.

### Cross-context transfer transaction

The old runtime's `tryBeginSourceActorTransfer` freezes user ingress, proves
scheduler/resource quiescence, validates that v1 has no parent/supervision
lineage, ActorGroup ownership/membership, unread output, or pending output
reader, snapshots compiler-owned state, and wraps it in the canonical actor
checkpoint bound to stable logical UUID/incarnation and old receipts.

The target runtime claims the transfer exactly once, validates code changed but
state/message/capability receipts remain compatible, restores the checkpoint
into a fresh physical actor owned by the target OresContext, and marks that
physical actor ready **without rerunning on_start**. The logical route is then
published to the candidate. Only after publication is the old physical actor
stopped.

Rollback is fail-closed: old ingress does not reopen until any staged candidate
has physically terminated. After publication, failure of stale-actor stop no
longer releases the old ingress fence in a finally block; stale physical
ingress remains sealed for supervisor recovery. The new logical route remains
authoritative after commit even if old-generation cleanup is delayed.

### Parent-visible outputs

V1 does not rebind output subscriptions. Migration refuses while the old
physical output queue contains values or while the channel has pending reader
registrations. Logical output registration itself occurs while holding the
route monitor, preventing a reader from registering against the old stream
after migration has observed zero registrations.

### Real Java cross-context tests

`LogicalSourceActorCrossContextUpgradeTest` stages genuine Oreslang Counter
v1/v2 source in distinct HotReloadManager OresContexts and checks:
- distinct physical runtime/context and ActorRef identities;
- stable logical UUID + incarnation across transparent replacement;
- route epoch 1 -> 2;
- state 42 restored into v2 without v2 on_start setting 999;
- changed v2 behavior produces 43/45;
- old generation can retire after physical finalization while the logical
  route continues;
- schema incompatibility rolls back to the exact old route;
- unread outputs and pending output readers block migration;
- ActorGroup membership blocks migration until released;
- foreign HotReloadManager candidates are rejected;
- active generation retire/close/manager-close and generic start are denied.

These JUnit/Graal tests are committed but still require an actual exact-head
Maven/JDK21/Graal runner.

### Independent route oracles

Two source-independent models now enforce the route contract with explicit
checks that remain active under `python -O`:

1. `tests/logical_actor_route_upgrade_model.py`,
   exact Git blob `699c010d1402e5702e1a80628241c0dbf93e6df9`.
   Exact local execution PASS in normal Python and PASS under `python -O`:
   **320,000 transitions per mode**, 12 committed upgrades, 21,407 rollbacks
   and 5,090 stale-physical send rejections.

2. `tests/logical_actor_cross_context_route_model.py`,
   exact Git blob `225c7fa36399fb4cc543d8a4d8afc54a102cb130`.
   Exact local execution PASS in normal Python and PASS under `python -O`:
   **160,000 transitions per mode**, 44 commits, 18 rollbacks and 160,000
   active-generation retirement denials.

The second model was corrected during audit to keep logical incarnation stable
and advance a route epoch; older modeling that incremented incarnation for a
transparent code replacement was semantically wrong.

### Bounded logical proxy integration gate (2026-10-18)

The stacked `LogicalActorProxyMailboxV1` is a **trusted runtime
substrate**, not yet the live `LogicalSourceActorRouterV1.send(Object)`
implementation. Its immutable byte envelopes carry monotonic sequences,
and its bounded message/byte credits remain charged across queueing,
dispatch and incomplete actor delivery. A route-upgrade freeze pauses
**dispatch** while retaining bounded admission; its ticket can commit
only after the old dispatched prefix has completed.

The audit reconciled the mailbox branch with the latest Java router,
including timeout/interruption cancellation and fail-closed protected
PRIVATE/UNTRUSTED placement admission. The proxy now also supports a
package-private `requeueProvenUnsent(seq)` for an unequivocally failed
physical send, provided that send was the latest outstanding dispatch
and no freeze is active. It preserves FIFO sequence and all credits;
it is **not** a retry after ambiguous transport delivery. If a broker
cannot prove an actor did not accept the message, it must retain and
resolve the outstanding delivery rather than redeliver potentially
duplicated work. The companion JUnit cases cover FIFO/credits,
disallowed stale requeues, sparse completions and competing freeze
versus unsent rollback.

The remaining live integration must atomically bind the proxy's
dispatch reservation to `ActorRuntime` mailbox admission, and bind
logical completion to an authenticated runtime `mail.done()`/terminal
receipt. An arbitrary host `complete(sequence)` call is not proof
that the actor processed a message. Code may acknowledge a send only
after physical mailbox acceptance. Before promotion, drain the exact
old-route dispatch watermark and preserve queued post-freeze bytes
for the new route. The compiler must produce canonical typed immutable
wire envelopes, not retain mutable `Object` graphs or raw
cross-context pointers. Actor/scheduler threads may not be parked
behind a host monitor.

These additions are committed in the stacked draft but have **not**
been confirmed by an executed JDK25/Maven/Graal workflow. Do not claim
exactly-once effect semantics, live proxy integration, or fully
migratable private/untrusted worker processes from this mailbox proof.

### Java real source-actor proxy bridge v1 (host-only, scalar)

A narrow proof adapter, `LogicalSourceActorProxyBridgeV1`, now integrates
`LogicalActorProxyMailboxV1` with `LogicalSourceActorRouterV1` and actual
`ActorRuntime.ActorRef.send()` plus `drain()` under the logical route monitor.
It is deliberately **serialized**: one host thread reserves, sends, awaits
the physical drain observer, and only then records `complete(sequence)`.
A failed or ambiguous physical send/drain leaves the message retained and
seals the proxy. There is **no automatic replay** after ambiguous delivery.

The first canonical wire form is precisely eight big-endian bytes encoding
a signed 64-bit actor message; only `admitLong(long)` can enqueue messages.
The bridge exposes output observation and an immutable logical address but
**does not expose the underlying ActorRef**, preventing bypass of the proxy's
bounded admission. Distinct admission and dispatch locks allow bounded new
bytes while an upgrade freezes dispatch. A physical successful code migration
commits the proxy's matching route epoch; exception paths stay fail-sealed.
Ordinary close refuses queued or outstanding mail instead of falsely marking
it completed. The bridge currently only starts `SHARED` actors, using the
existing same-JVM source-actor path.

Committed real-runtime JUnit fixtures exercise a Counter v1/v2 Oreslang
actor across separate checked Graal contexts: 42 -> 43 -> 45, route epoch
1 -> 2 with stable logical id/incarnation, bounded proxy credits,
physical completion-barrier accounting, cross-upgrade queued mail,
racing admission, and fail-sealed incompatible source upgrades.
**None of these newly authored JVM tests has an executed exact-head
Maven/JDK25 receipt yet.**

This is *not* a general source-level actor proxy. In particular, the
host-only scalar codec is NOT a compiler-generated typed message ABI, a
`drain()` barrier is NOT a cryptographically authenticated remote
`mail.done()` receipt, and the v1 adapter does not support parallel
dispatch, direct guest-side mailboxes, durable retries, complete
multi-actor supervision migration or cross-process PRIVATE/UNTRUSTED
physical isolation. The production route must replace scalar encoding
with compiler-owned immutable frames, add authenticated per-message
accept/done proof tied to physical incarnation, preserve independent
CONTROL flow and introduce a dedicated worker/isolate backend.

### Close/retirement retry audit (2026-10-18)

The V1 router's logical `closed` bit seals further DATA admission, but
does **not** mean every physical actor/code-generation lease has been
reaped. The earlier `close()` implementation sealed the router and then
called `ActorRef.stop()`, which can throw on the bounded host wait:
subsequent `close()` calls returned early even while the old generation
remained pinned. Such a timeout cannot license unload or claim a
successful shutdown.

`LogicalSourceActorRouterV1` now serializes supervisor close attempts
under a dedicated close monitor; a stop exception records the active
physical actor in the deferred-retirement ledger before propagating.
Repeated close calls on an already-sealed router continue physical
retirement until every lease is finalized. Reaping closes each generation
lease before removing its owner from the ledger and tracks whether the
active lease has already been released to prevent duplicate closure.
The ordinary fast path remains idempotent. Added a real source-actor
JUnit regression with eight concurrent host closers that asserts a
fully retired generation is unpinned and further closure is harmless.

The bounded `LogicalSourceActorProxyBridgeV1` now mirrors that
lifecycle distinction: closing seals admission immediately, but the
bridge only records physical retirement after the router closes and the
mailbox is released. A failed physical close leaves the bridge sealed
and retryable; it must not silently become an idempotent no-op while
a code generation remains pinned. A race regression checks that
`admitLong` versus `close` either rejects admission or preserves
accepted mail for actual delivery before shutdown. Multiple host
closers, and repeated completed shutdowns, must be idempotent.

These are committed **source-level changes and test cases, not yet
executed JDK25/GraalVM proof**. A separate independent source-free
Python model exercised 500,000 deterministic retirement/close transitions
including 125,153 simulated stop failures, ensuring no early release,
at-most-once release and eventual retirement following physical
finalization. The model does not instantiate the Java router or
substitute for exact-head CI.

### Remaining production gates

This remains v1 and intentionally excludes:
- guest-visible/nonblocking logical routing;
- parent/child supervision lineage migration;
- ActorGroup ownership/membership migration;
- output-stream rebinding;
- cross-manager/process/network routing;
- compiler-derived required effect/capability receipts;
- aggregate/cyclic actor state beyond the current canonical scalar subset;
- schema migrations;
- authenticated source/native executable + dependency provenance;
- automatic generation leases in every timer/FFI/GPU/shared-memory broker;
- real LLVM-generated .ores actor restoration/publication through the same
  logical route protocol.

Current source-owned Actions still create jobs with zero executed steps, so
those jobs are neither pass evidence nor meaningful code-failure evidence.
Private cross-org source mirroring remains disallowed while the reviewed
destination allowlist is empty.

### Runtime-minted exact physical message receipts — 2026-10-19

The trusted SHARED source-actor proxy now uses
`ActorRuntime.sendWithPhysicalReceipt(ref, message)` instead of pairing
`ActorRef.send` with a cumulative `ActorRef.drain()`.

The normal `send(ref, message)` and the tracked send share ONE physical
mailbox acceptance implementation. For a tracked send, the runtime allocates
an opaque `PhysicalMailReceipt` and non-cancellable terminal
`OresFuture` **before** physical admission. Under the actor's lifecycle
monitor it reserves an entry in a separate bounded
`pendingPhysicalReceipts` map, attempts mailbox admission, and rolls back
that entry if the mailbox rejects the message. The exact-message receipt
ledger is independent of cumulative public `pendingDrains` waiters.
The accepted sequence is assigned by the runtime at the same linearization
point, never taken from the logical proxy or guest source. A terminal witness
settles only once this physical sequence is marked processed, or fails if
the physical actor stops/fails before it is processed.

`LogicalSourceActorRouterV1.ProxyDrainReceipt` binds that opaque physical
receipt to the exact authoritative physical `ActorRef`, copied logical
envelope, owning mailbox, and logical route epoch. The bridge refunds
credits only after the runtime-minted terminal receipt succeeds; a failed,
cancelled, or timed-out physical receipt leaves outstanding mail fail-sealed
without automatic replay. Physical sequence numbers are **not** logical
message sequence numbers and restart from the new physical actor on upgrade.

This is an in-process trusted terminal acknowledgment, **not** a remotely
authenticated `mail.done()` receipt, transactional persistence, exactly-once
side effects, or a compiler-generated typed envelope. A future PRIVATE /
UNTRUSTED network/IPC transport must bind worker attestation, incarnation,
route epoch, message ABI digest, and opaque accepted-message identity in
independently authenticated receipts.

Receipt-map keys and the completion callback list are allocated before
physical mailbox admission and before publishing a processed-message
watermark, respectively. A failed reservation cannot silently turn into an
acknowledged send. The trusted receipt entry point also rejects scheduler,
actor and async carrier contexts, including carrier-only callback markers.

Regression sources include real `ActorRuntime` physical receipt tests
for exact sequential and concurrent admissions, per-actor sequence namespaces,
non-cancellable completion observers, paused physical execution, faulted actor
receive, carrier admission rejection, and existing
source-compiled SHARED proxy upgrade tests. These remain authored test code,
**not an exact-head JDK25/GraalVM execution pass** while source-owned Actions
are runner-blocked.

Related code-image accounting hardening: `Generation.pinActorRoute()`
allocates the lease before charging its counter; `ActorRouteLease.close()`
checks the counter before consuming its release token. Failed pin accounting
remains retryable. Dedicated generation race tests cover corrupted accounting,
16 simultaneous releases, and concurrent code retirement.

### Exact-receipt single-claim timeout hardening — 2026-10-19

A follow-up audit found that `ProxyDrainReceipt.awaitAndComplete` previously
marked its receipt consumed only **after** awaiting a successful physical
terminal Future. The host bridge fail-sealed a failed wait, but a caller of
the lower-level trusted router receipt could time out (or be interrupted)
and then invoke the same receipt again after late physical completion.
That crossed the quarantine boundary without a supervisor reconciliation.

The receipt now obtains an exclusive one-shot claim under the physical
`routeLock`, checking active route and bound proxy identity and marking
its one-shot capability consumed **before** allocation-bearing mailbox
epoch/envelope validation or waiting. That claim is never reset, including
on validation fault, timeout, interruption, terminal exception, route
change, or mailbox completion rejection. The terminal wait stays
outside the route lock; a successful wait revalidates publication and uses
`completeAfterPhysicalReceipt` to refund credits once. A second caller
fails immediately without waiting; no timeout/retry loop is permitted.

A real source-compiled SHARED Counter regression was added with deliberate
test-only replacement of its receipt's terminal observation Future. This
makes a timeout deterministic without relying on guest scheduler timing
and verifies: the original physical message was submitted, the timeout
retains logical credits, reusing the same receipt is rejected even after
the synthetic terminal settles, and replaying the physical send is rejected.
The test is committed but **not confirmed executed by an exact-head
JDK25/GraalVM Maven job**. An independent Java 21 source-free one-shot
receipt concurrency oracle compiled with `javac --release 21 -Xlint:all
-Werror` and executed **31,000 assertions PASS** (10,000 timeout/retry
cases and 500 eight-contender claim races). This is a proof-model probe,
not a compilation or execution of the Oreslang source branch. The
exact-head source-org fast-ci run 39308727188 completed FAILURE with a
test job reporting **steps: null** (not a JVM/JUnit assertion failure).

Private source export remains blocked by the EMPTY
`.github/private-ci-approved-repositories.txt` allowlist and open
security bootstrap issue #962. Do not copy a private Git tree into a
foreign Actions repo merely because its budget has capacity.

This is a local in-process receipt safety property, not a remote
authenticated `mail.done`, durable deduplication, or exactly-once effects.

### ABI-bound scalar hot-path audit — October 19, 2026

The SHARED bridge's new opt-in `SourceActorScalarWireV1` is the single
canonical 72-byte fixed-length i64 wire format. A concurrent duplicate
`LogicalActorTypedI64WireV1` implementation was removed; independently
invented magic/version layouts cannot be allowed to diverge.

The production codec now validates its canonical lowercase compiler
message-ABI digest once in the immutable `Binding`, then encodes/decodes
all 32 digest bytes directly from that validated string. The decode path
checks the complete logical UUID/incarnation and all digest bytes without
constructing temporary UUID or digest byte arrays. It rejects changed
magic/version/type/reserved fields, foreign actor or incarnation, different
compiler ABI and noncanonical frame lengths before physical send attempts
are reserved. The bounded mailbox still clones and owns admitted bytes.

**Executed product-source codec evidence (isolated, not full runtime):**
the Java file's local Git blob SHA was
`442260316ef1f4db313ca0b1001ea44e37b6e6db`, exactly the GitHub
committed source blob. OpenJDK 21
`javac --release 21 -Xlint:all -Werror` passed, followed by a
standalone deterministic driver reporting **50,513 / 50,513 checks PASS**:
512 header-bit mutations rejected, 25,000 signed-i64 encode/decode
round trips with length checks, and a canonical initial fixture.
The new repository JUnit source contains equivalent exhaustive mutations
and randomized round trips; it still needs Maven/JUnit execution on an
approved JDK25/Graal runner. This evidence covers only the self-contained
codec, **not** the compiler, actor router, state transfer, Task scheduler,
or Native Image.

**Remaining compiler gate:** the ABI fingerprint is emitted by the linked
actor executor, but the transport still accepts only host-supplied signed
i64 values. General actor-mail payload types (structs, records, unions,
tuples, optionals) require compiler-generated typed encoders and decoders,
closed-world type checking, payload bounds and ownership/capability proofs.
The i64 frame is not a remotely authenticated message or worker attestation.

**Private-source CI hold:** the destination allowlist is empty and
source security review #962 is unresolved; no private code or Git tree was
mirrored to a foreign GitHub Actions organization on the basis of available
minutes alone.
