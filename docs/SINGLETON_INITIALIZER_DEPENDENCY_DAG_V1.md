# AttoLang application-domain singleton initialization dependency gate

Status: **draft Java/Graal host runtime hardening only**. This PR stacks directly on Java #2778's exact source head. It does not enable normal `define singleton` guest compilation, actor-visible mutable singleton fields, cross-isolate shared pointers or five-backend semantic certification.

## Failure scenario addressed

Before this change, `snapshotOnce(A)` and `snapshotOnce(B)` on distinct Java carrier threads could each start one initializer and then synchronously request and wait for the other pending receipt. A thread-local set only detected recursion on the *same* carrier. Both threads could park forever.

For each application-domain registry, `SingletonDependencyGraph<Key>` conservatively tracks **pending nested `snapshotOnce` lookups while an initializer is running**:

- Source callbacks remain outside the `values` monitor. Only dependency metadata is touched while holding the registry's existing admission monitor.
- Every edge is linked before exposing a newly-created registration future, and before returning an existing pending future.
- Adding an edge that closes a dependency cycle returns a failed future with `singleton initializer cycle`; no newly allocated incomplete cell is retained on denied admission. An existing pending target's publication is also completed exceptionally **outside the registry monitor** so callers cannot swallow the rejected receipt and publish a successful cycle.
- The regular initializer catch/finally path completes its own immutable publication exceptionally, preserving sticky failure and waking all dependents.
- A node retires outgoing and incoming edges after initialization reaches a terminal future, with O(in-degree + out-degree) cleanup.
- The graph retains no serialized principals or actor-private heap addresses and never confers memory ownership or RW access.
- Both per-initializer (**64**) and per-domain (**16,384**) dependency edge budgets fail closed before their limits are exceeded. Complete singleton lookups do not allocate a ThreadLocal stack.

## Audit hardening addendum (2026-11-11)

**Fixed: atomic commit versus cycle-poison race.** Previous code decided to poison a pending future while holding `values`, but executed `completeExceptionally` afterward, while the initializer could still independently execute `complete(frozen)`. Depending on the scheduler, a success could win the race. The new `undecidedSnapshots` ledger is protected by the existing `values` monitor: **exactly one** publication path consumes each key. The winning completion (success, ordinary initializer failure or cycle failure) runs **only after monitor release**, so synchronous `CompletableFuture` dependent callbacks cannot execute while the registry monitor is locked. A poisoning claim leaves the original initializer unable to publish the frozen candidate, including when it ignores the cycle result.

**Fixed: unbounded nested host initializer depth.** The graph now refuses the 65th nested initializer with a stable `singleton initializer depth exceeded` error. `snapshotInternal` places `begin` inside its catch/finally boundary: even if graph admission throws, the already-created registry future becomes a terminal **sticky failure**, rather than hanging forever. The parent thread stack is unwound; a separate singleton can initialize normally afterward. The source compiler still only admits closed literal singleton values.

**Added tests.** The real-host smoke now covers both checked and unchecked paths, 2/3-thread cycles, swallowed-recursion, 65-level depth denial and same-domain recovery, plus deterministic A/B cycle poisoning while A freezes a host-owned map candidate. The standalone graph smoke now checks depth cleanup (10 assertions per invocation).

**Verified local exact-file scope.** OpenJDK 21 `javac -Xlint:all -Werror`, then 100 repeated executions of `SingletonDependencyGraphSmoke`, reported **100/100 passes (1,000 local assertions)**. Exact executed source matches Git blobs `SingletonDependencyGraph.java` = `543a62a4ce5583abd7efa822174f20606cf94862`, `SingletonDependencyGraphSmoke.java` = `1607f7f992af59ca937eed8642a12b94dbcb905e`. The **full integrated registry smoke and JUnit suites have not executed**, so this is a narrow component result only.

**Further security/release blockers:** the host prototype's `principals` and `regions` registries have no complete bounded retirement lifecycle proven against real actor incarnation/STOP epochs. Actor execution-signed principals, physical borrower retirement, actual shared-memory backing, compiler-generated guest singleton reads, Native Image, cross-isolate trusted coordination and all other-backend guest semantics remain **NOT_CERTIFIED**. Do not promote from this narrow graph smoke or source-free CI receipts.

The graph detects **nested requests made through this API**, not arbitrary `CompletionStage.join()` on futures retained outside the initializer or asynchronous distributed wait graphs. Normal compiled singleton initializers are still strictly *closed primitive literals* with no effects, so this protects the host substrate without expanding source capabilities. Cross-native-isolate coordination is a separate design/implementation gate.

## Executable regression cases

`SingletonCrossThreadCycleSmoke` uses the real `SingletonSharedDomain` through both unchecked host-only and **compiler-fingerprinted checked publication** paths: two- and three-carrier bounded rendezvous cycles must fail with the cycle diagnostic, ignored direct recursion cannot publish successfully, failures must be sticky, threads must drain, and an acyclic three-declaration chain must succeed and retain canonical identity.

`SingletonDependencyGraphSmoke` independently checks same-thread 2/3-node cycles, exactly one cross-thread detected edge when two live references form a cycle, duplicate edges, bounded outstanding edges and cleanup. The helper and standalone graph smoke were **actually compiled** locally with JDK 21 `javac -Xlint:all -Werror` and executed **100 rounds, 800 assertions, no failures**. The compiled helper's Git blob matches the pushed source blob. This is **not** evidence that the fully integrated runtime smoke or Maven/JUnit has executed.

## Required execution on exact stacked PR head

```sh
bash scripts/test-singleton-initializer-dependencies.sh
mvn -B -ntp -DfailIfNoTests=true \
  -Dtest=SingletonCrossThreadCycleTest,SingletonSharedDomainHostTest,SingletonSnapshotCompilerV1Test test
mvn -B -ntp verify
```

Require actual assigned runners, nonzero step logs, compiled exact-source dependency identities, the two JDK25 smoke results and Surefire XML. No private source transfer without independently reviewed destination/snapshot approval. Keep this PR **draft** until execution and independent review; a zero-step Actions failure is NOT_EXECUTED.

Related: Java #2749/#2772/#2778, Java sealed actor RW #2796 and #2747, conformance #242/#651/#656/#660/#662. Neither this draft nor any host proof satisfies immutable singleton field lookup through production AttoLang guest actors or the canonical five-backend release gate.
