# AttoLang Supervisor CONTROL V2 — incremental reconciliation

**Status: STAGED LEDGER PORT; NOT WIRED TO LIVE ActorRuntime / GUEST SUPERVISOR; NOT MERGE-READY.**

## Authoritative implementation lineage

- Java #1223 originally introduced \`ActorSupervisorControlLane\`, a bounded, generation-fenced intrusive terminal CONTROL queue with reserve/publish/poll/ack/retry/retire/transfer. This branch **ports that existing class** onto the current \`main\` tree rather than creating a second competing mailbox abstraction.
- Java #1320 contains a useful *pre-publish child reservation and causal metadata* contract, but its PR is based on a historical NIO branch with broad compiler/runtime/native thread changes. It is **not** a safe wholesale cherry-pick onto current Java main.
- Java #2838 independently hardens ordinary Channel DATA mailbox capability boundaries and CI selectors. The two PRs must be semantically reconciled when integration is ready.
- Java #2785 adds a logically ordered DATA admission permit against ancestor supervisor failure fencing; that is an additional gate and must **not** be confused with the terminal CONTROL queue.

## What this branch implements

1. Reuses the original \`ActorSupervisorControlLane\` from #1223 and its exact value-only \`ActorFailureNotice\`/failure-class definitions. The DTO is immutable and excludes raw Throwable/guest objects.
2. Keeps \`reserve(child,generation)\` bounded; no terminal event is publishable without a pre-existing generation-bound reservation. Duplicate or stale generations fail closed.
3. Keeps a polled notice reserved until explicit \`ack\`, \`retry\`, or authorized transfer. A retry does not create another copy.
4. **New safety fix:** root transfer performs a whole-batch preflight for *every* published parent notice under both lane monitors, checking that the exact root reservation exists and is unused. If any destination is missing/stale/occupied, no notice moves.
5. **New lock-order fix:** a shared transfer coordinator is acquired before either lane monitor. Opposite-direction handovers cannot wait on each other's lane monitor in the old ABBA pattern.
6. Adds focused JUnit tests: insufficient destination capacity => zero moved; stale/occupied root => zero moved; in-flight transfer => ACK cannot happen from old owner, exact root retry/ACK; concurrent opposing handovers => no deadlock and no lost notices.

### Not yet integrated

There is **no new live ActorRuntime call path** in this slice that reserves child/root slots before actor spawn, delivers failure CONTROL onto the parent's exclusive serialized turn, or makes real parent STOP transfer its notices to root. Merely compiling the ledger and DTO does not provide those semantics. The eventual integration must use **this one ledger** and reconcile the existing competing draft code rather than instantiate a second independently authoritative failure queue.

There is no \`.atto\` compiler lowering, no source-level privileged supervisor handler API, no LLVM/.NET/Wasm/Forge parity, no Graal native-image proof and no production deployment approval.

## Required integration gates

- **Admission atomicity:** before exposing child ActorRef, reserve generation slots in both parent and root, register parentage and effective policy in one rollback-safe transaction; failure refunds all slots. Handle nested spawn, supervisor crash and hot-upgrade generation.
- **Terminal propagation:** on first terminal failure, mint a cause-free runtime notice, publish once in the child's pre-reserved parent slot; cancel/graceful termination releases unused reservation. No guest may construct/forge system envelopes.
- **Single-flight delivery:** supervisor callbacks run only under the parent's exclusive logical turn/continuation lease. Control is higher priority than ordinary DATA but capped at a 64-notice burst before one ready healthy turn; no carrier blocking or guest reentrancy.
- **STOP/restart:** drain/transfer pending or in-flight child notices to root using the preflighted two-lane handover. Root ACK/retry must be explicit, and physical actor reclamation waits on outstanding published CONTROL/transport loans.
- **Admission vs failure fence:** reconcile \`SupervisorFailureEscrowV1\` from #2785 with DATA Channel credit transfer and stop. A permit granted before a failure fence can enqueue physically later but remains logically pre-fence and prevents unsafe restart.
- **Backpressure:** when reserved root capacity is exhausted, deny new actor admission before publishing a new actor; do not drop accepted failure notices or borrow USER mailbox space.
- **Source and backend proof:** compile real \`.atto\` actor/child supervisor programs and negative fixtures on Java/Graal/NativeAOT, LLVM, .NET, Wasm and Forge; check exactly-once failure notices, provenance, stale-generation denial, fairness, cancellation, and sibling survival.

## Evidence / CI

\`.github/workflows/supervisor-control-lane-port.yml\` invokes
\`scripts/verify-supervisor-control-lane-port.sh\`: full JDK25 Maven compile plus
four direct JUnit methods, with exact Surefire report assertions and no skipped
tests. This proves the **isolated ledger** only, even when executed. Full actor
lifecycle integration and guest execution have different mandatory gates.

A GitHub Actions run with \`steps=[]\`, \`steps=null\`, or \`runner_id=0\` is
\`NOT_EXECUTED\`; it is neither a regression pass nor a compiler failure.
Do not move private source to another organization to obtain minutes without
an independently reviewed source-allowlist and destination authorization.
