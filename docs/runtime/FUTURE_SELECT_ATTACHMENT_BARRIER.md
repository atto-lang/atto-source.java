# Future-select attachment and publication barrier

Status: proposed runtime contract for Java/GraalVM PR #971; requires test
execution and review before becoming an accepted implementation contract.

## Atomic winner and cancellation

Every SelectRegistration has at most one winning arm, decided under the
global select coordinator. Once cancellation claims a pending selection,
future channel sends or reads cannot commit to that selection.

Cancellation of a select must not cancel shared source Future objects.

## Three distinct completion boundaries

1. Channel/select admission revocation: channel registrations and retained
   admission credits are unlinked during the atomic cancellation decision.
2. Non-cancelled result or failure publication: if a Future listener
   attachment is in progress, publication is delayed until late losing
   registrations are detached. Subscriber notification and potentially
   expensive producer queue removal occur outside the coordinator lock.
3. Cancellation listener quiescence: a cancelled selection Future may be
   observable before another thread finishes attaching its Future listener.
   That late handle must be detached when the attachment frame unwinds.
   Observing isCancelled() or cancel(false) returning is not sufficient to
   prove that independent attachment frames have finished.

A shutdown protocol demanding zero retained waiter handles must wait for the
attachment work to quiesce; this is a stronger requirement than observing the
selected Future's cancellation bit.

## Deterministic regression coverage

- Cancellation before admission never registers a waiter or consumes data.
- Cancellation during paused listener attachment permits temporary retained
  handles, then releases them as the attachment frame exits.
- Committed channel success, channel closure, producer success and producer
  failure defer selected-result publication until late waiter reclamation.
- A producer failure after successful selection cancellation cannot reactivate
  the selection or consume a channel value.
- Duplicate arms awaiting one producer preserve PRIORITY ordering and fully
  release their registrations.
- At least 100 cancellation/producer-failure races must leave no retained
  Future waiters or channel admission credits.

## Verification and merge requirements

The original publication fix passed exact-tree JVM and native acceptance at
commit 96aa8ab, tree 377d774d20256a0757fb7ad53d7cc3e09830801b.
Those results DO NOT validate this integrated PR #971 or its new tests.

Perform exact-head full Maven JDK 25, repeated stress, GraalVM native AOT,
hybrid and isolate execution with matching source-tree evidence before merge.

Private-source mirroring must follow the repository's deny-by-default
security approval policy. In particular, no public repository is an
authorized destination. Independent review, approved private CI, and
protected-main merge admission remain mandatory.
