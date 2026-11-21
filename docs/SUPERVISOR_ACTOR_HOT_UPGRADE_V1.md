# AttoLang actor and supervisor hot upgrades — v1 audit and execution contract

**Status: host-only foundation; not live guest-code hot upgrade.** The new `SupervisorHotUpgradeGateV1` enforces a versioned CONTROL authority for one supervisor. It does not yet change live `ActorRuntime` routes, compile `define actor`, load native images, migrate actor heaps or prove executable supervisor replacement. This status must accompany all test reports.

## Decision: upgrade both actors and supervisors

- Every actor has **one authoritative parent supervisor**. A single runtime-wide child registry prevents dual ownership; changing the supervisor code generation does not change its identity.
- A compatible supervisor upgrade preserves its child identities, existing ActorRefs and actor DATA execution. Supervisor CONTROL must freeze new admission while already queued and in-flight CONTROL reservations settle.
- The root supervisor can be upgraded only by a separate bootstrap/runtime coordinator. A self-supplied UUID or guest code digest is not an authorization credential. This host gate uses an identity-only coordinator seal; the live integration must bind it to authenticated runtime provenance.
- Physical old/new code images may coexist. They may not simultaneously hold mutable actor state or competing supervisor CONTROL authority. Existing actor turns, suspended tasks, FFI callbacks, timers and borrowed shared regions must retain code and ownership pins until quiescence.
- ABI, policy or capability drift is **not** an ordinary code-only replacement. A verified migration protocol is required. This v1 gate conservatively rejects changed policy ABI, CONTROL ABI or capability fingerprint. Matching SHA string formats alone do not authenticate artifacts.
- Before commit: rollback leaves the old image and children untouched. After commit: a failure triggers a new monotonic recovery transaction, never reuse of old/ABA candidate authority.

## Required live runtime transaction

1. Trusted parent/bootstrap verifies immutable signed image bytes, compiler origin, complete import closure, state/wire/control ABI and capability grant.
2. Stage new code and state converter off actor carriers, under time/memory budgets; perform readiness checks with no visible side effects.
3. Freeze new CONTROL admission, without consuming the fatal child-failure priority lane. For actor replacement, freeze logical mailbox new admissions without losing queued mail.
4. Retain and drain queued/in-flight CONTROL reservations and ALL physical actor generation leases, including async continuation, FFI, timers, arena borrows and external operations. Observe deadlines without blocking carriers.
5. Under the runtime's single authoritative publication boundary, validate actor/supervisor incarnation, expected epoch, membership and candidate. Publish route, code image, migrated state owner and epoch as one atomic event.
6. Resume ingress; retire old code and actor arenas only after every old-generation lease quiesces. Quarantine any resource lacking a valid retirement proof.
7. Precommit failure aborts without publication. Postcommit failure is settled by a **new** monotonic transaction, never by replaying stale tickets.

The host-only `publish` in this PR is a gate transition, **not** step 5 of a live runtime upgrade.

## Current repository audit / non-overlapping branches

- Java main `ActorUpgradeEpochGate` supplies host-only actor code/image epochs; it is not bound to complete physical execution and mailbox state.
- Java draft [#1967](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1967) holds logical mailbox, preflight and route publication work. Integrate rather than replace that draft.
- Java draft [#2351](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2351) repairs transactional generic loader staging and failed-start ordering; do not duplicate it here.
- Java draft [#1476](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1476) introduces a nonfatal supervisor command lane, distinct from reserved fatal child-failure delivery. Its ACK is not publication.
- LLVM draft [#602](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/602) experiments with code-image pins and PIC host loading. It is not evidence of a working guest compiler-to-AOT hot swap.

## Executable host-only demo and JUnit

JDK 21 or later, from the Java repo root:

```sh
mkdir -p /tmp/atto-supervisor-gate-classes
javac --release 21 -Xlint:all -Werror \
  -d /tmp/atto-supervisor-gate-classes \
  src/main/java/dev/oreslang/runtime/SupervisorHotUpgradeGateV1.java \
  examples/hot-upgrade/SupervisorHotUpgradeHostDemo.java
java -ea -cp /tmp/atto-supervisor-gate-classes dev.oreslang.runtime.SupervisorHotUpgradeHostDemo
mvn -Dtest=SupervisorHotUpgradeGateV1Test test
```

The checked-in host demo verifies old CONTROL draining before v1→v2 switch, unchanged supervisor ID and child ownership, frozen admission, reject incompatible v3 and precommit rollback. Eight JUnit methods exercise those properties plus foreign coordinator/candidate, stale epoch, ABA, duplicate parent, close/retire, snapshot immutability and drain races. This is **not** a guest execution test.

## Mandatory real .atto guest demo matrix before production claims

| Scenario | Pass condition |
| --- | --- |
| Counter actor v1→v2, stable ActorRef | New mail uses v2; state/messages coherent |
| Suspended DATA/FFI/shared borrow during swap | Old image pinned; no early reclamation |
| Mail queued at freeze boundary | FIFO/receipt correctness; no dropped/duplicate mail |
| Stateful schema v1→v2 | Valid migration or reject and preserve old state |
| Supervisor v1→v2 with running children | Stable parent identity, no child respawn |
| Root supervisor upgrade | External bootstrap coordinator required |
| Child failure while CONTROL frozen | Fatal CONTROL still delivered in reserved lane |
| Forged digest, ABI, grant or code image | No guest execution / publication / escalation |
| STOP/parent death/retry/stale callback/ABA | Exact incarnation fence and one settlement |
| Postcommit runtime failure | New recovery generation, not stale rollback |
| PRIVATE/UNTRUSTED tenant migration | No change in protection domain or state leakage |

Do not add speculative guest `.atto` code-change syntax to the working test corpus until the corresponding compiler-to-ActorRuntime API is implemented. Once available, compile and run real v1/v2 actor and supervisor fixtures across Java, LLVM, .NET NativeAOT, Wasm and Forge, with exact-head compiler/guest evidence.

**Merge criteria:** independent approval; exact SHA/tree; nonzero executed CI steps; focused JUnit + full Maven/Graal; Native Image and cross-backend compiler-backed execution as applicable. Zero-step Actions failures are runner admission failures, not test passes.
