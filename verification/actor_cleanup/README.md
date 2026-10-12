# Serial actor cancellation and typed result publication protocol

Check `ActorCleanupPublication.tla` with TLC under both configs. The unsafe mutant must generate a `NoEarlyOrCancelledPublication` counterexample. Compile/run `ActorCleanupPublicationModel.java` standalone with Java 21+; it requires no dependencies.

This is an abstract protocol with two defer actions, cancellation, suspension, publication and restart. It is **not** evidence that the current guest runtime actually executes all deferred cleanup on cancellation, nor a completed merge of PR #656 and PR #661. Both require separate exact-source executable proof.
