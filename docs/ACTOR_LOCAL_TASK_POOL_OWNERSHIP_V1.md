# Actor-local task carrier ownership (Java runtime)

An AttoLang `task { ... }` created during an actor's admitted source turn
must use **that actor's existing mailbox scheduler**, not the root task
scheduler, supervisor CONTROL coordinator, a detached executor, or an I/O
producer's thread. A nested `task { ... }` inherits the same actor scheduler;
each logical task still has its own task identity.

Physical carriers follow the **actor's kind**:
- SHARED actor: shared actor dispatcher.
- PRIVATE actor: private/isolated actor dispatcher.
- UNTRUSTED actor: untrusted actor dispatcher and the same inherited logical
  untrusted fuel/wall quota for nested tasks.

The actor scheduler has logical parallelism one and enqueues every initial
turn and continuation into `ActorCell.sourceTaskMailbox`. A suspended task
may resume on a **different OS thread from the same actor pool**, but cannot
bypass `ActorCell.runBatchEntered()` or its physical single-writer lease.
Neither a root Task nor a supervisor may acquire actor state or supervisor
authority merely by observing a Future.

Every guest source-task turn now checks the runtime, actor ID, actor kind,
incarnation execution-domain identity, actor carrier marker, and active physical
turn lease **immediately before** executing the task. A misrouted turn fails
closed. This guard remains applicable to nested tasks and await continuations,
including ones awoken by foreign I/O completion threads. It does not turn a
host-only unit test into evidence of compiler guest lowering.

## Verification

`ActorSourceTaskPoolIsolationTest` exercises PRIVATE/SHARED/UNTRUSTED
actor-code callbacks, initial execution, foreign-producer `await` completion,
nested task creation, scheduler identity, host producer isolation, and a
CONTROL dispatcher configured to reject any task dispatch. It additionally
verifies actor stop and absence of actor failure.

Run from the Java repo, with the required JDK/Graal/Maven native prerequisites:

```sh
mvn -B -ntp -Dtest=ActorSourceTaskPoolIsolationTest test
```

Release gate: exact-head executed JUnit results, explicit job steps and XML
receipts; separately run compiled guest-source `task { }` + `await` actor
fixtures and native-image tests. Passing host-only assertions is insufficient
to claim guest-source support or five-backend parity. No change here claims
that Wasm or LLVM already implement the same physical-pool behavior.
