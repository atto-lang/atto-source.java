----------------------------- MODULE ContextShutdown -----------------------------
EXTENDS Naturals, FiniteSets, TLC

CONSTANT Actors
ASSUME /\ Actors # {}
       /\ IsFiniteSet(Actors)

VARIABLES sealed, cleanupEntered, cleanupPending, actorLive, writerLive,
          claims, completions, preflightFailed, preflightSucceeded, disposed

vars == <<sealed, cleanupEntered, cleanupPending, actorLive, writerLive,
          claims, completions, preflightFailed, preflightSucceeded, disposed>>

Quiescent == /\ cleanupPending = {}
             /\ actorLive = {}
             /\ writerLive = {}

Init == /\ sealed = FALSE
        /\ cleanupEntered = {}
        /\ cleanupPending = Actors
        /\ actorLive = Actors
        /\ writerLive = Actors
        /\ claims = [a \in Actors |-> 0]
        /\ completions = [a \in Actors |-> 0]
        /\ preflightFailed = FALSE
        /\ preflightSucceeded = FALSE
        /\ disposed = FALSE

Seal == /\ ~sealed
        /\ sealed' = TRUE
        /\ UNCHANGED <<cleanupEntered, cleanupPending, actorLive, writerLive,
                      claims, completions, preflightFailed, preflightSucceeded, disposed>>

EnterCleanup(a) ==
        /\ sealed
        /\ a \in actorLive
        /\ a \notin cleanupEntered
        /\ cleanupEntered' = cleanupEntered \cup {a}
        /\ claims' = [claims EXCEPT ![a] = @ + 1]
        /\ UNCHANGED <<sealed, cleanupPending, actorLive, writerLive,
                      completions, preflightFailed, preflightSucceeded, disposed>>

HostCompletesCleanup(a) ==
        /\ a \in cleanupEntered
        /\ a \in cleanupPending
        /\ cleanupPending' = cleanupPending \ {a}
        /\ completions' = [completions EXCEPT ![a] = @ + 1]
        /\ UNCHANGED <<sealed, cleanupEntered, actorLive, writerLive,
                      claims, preflightFailed, preflightSucceeded, disposed>>

ActorExits(a) ==
        /\ a \in actorLive
        /\ a \notin cleanupPending
        /\ actorLive' = actorLive \ {a}
        /\ UNCHANGED <<sealed, cleanupEntered, cleanupPending, writerLive,
                      claims, completions, preflightFailed, preflightSucceeded, disposed>>

WriterExits(a) ==
        /\ a \in writerLive
        /\ sealed
        /\ writerLive' = writerLive \ {a}
        /\ UNCHANGED <<sealed, cleanupEntered, cleanupPending, actorLive,
                      claims, completions, preflightFailed, preflightSucceeded, disposed>>

FailedPreflight ==
        /\ sealed
        /\ ~Quiescent
        /\ ~preflightFailed
        /\ preflightFailed' = TRUE
        /\ UNCHANGED <<sealed, cleanupEntered, cleanupPending, actorLive, writerLive,
                      claims, completions, preflightSucceeded, disposed>>

SuccessfulPreflight ==
        /\ sealed
        /\ Quiescent
        /\ ~preflightSucceeded
        /\ preflightSucceeded' = TRUE
        /\ UNCHANGED <<sealed, cleanupEntered, cleanupPending, actorLive, writerLive,
                      claims, completions, preflightFailed, disposed>>

Dispose ==
        /\ preflightSucceeded
        /\ Quiescent
        /\ ~disposed
        /\ disposed' = TRUE
        /\ UNCHANGED <<sealed, cleanupEntered, cleanupPending, actorLive, writerLive,
                      claims, completions, preflightFailed, preflightSucceeded>>

Next == \/ Seal
        \/ FailedPreflight
        \/ SuccessfulPreflight
        \/ Dispose
        \/ \E a \in Actors: EnterCleanup(a)
        \/ \E a \in Actors: HostCompletesCleanup(a)
        \/ \E a \in Actors: ActorExits(a)
        \/ \E a \in Actors: WriterExits(a)

Spec == Init /\ [][Next]_vars

TypeOK == /\ sealed \in BOOLEAN
          /\ cleanupEntered \subseteq Actors
          /\ cleanupPending \subseteq Actors
          /\ actorLive \subseteq Actors
          /\ writerLive \subseteq Actors
          /\ claims \in [Actors -> Nat]
          /\ completions \in [Actors -> Nat]
          /\ preflightFailed \in BOOLEAN
          /\ preflightSucceeded \in BOOLEAN
          /\ disposed \in BOOLEAN

ExactlyOnce == \A a \in Actors:
                 /\ claims[a] <= 1
                 /\ completions[a] <= 1
                 /\ (completions[a] = 1 => claims[a] = 1)

SafeDisposal == disposed => /\ preflightSucceeded
                             /\ Quiescent
                             /\ sealed

NoFalsePreflight == preflightSucceeded => Quiescent

\* Mutation only for a model checker negative-control run.
\* It intentionally allows disposal while actors/writers still own resources.
UnsafeDispose ==
        /\ sealed
        /\ ~Quiescent
        /\ ~disposed
        /\ disposed' = TRUE
        /\ UNCHANGED <<sealed, cleanupEntered, cleanupPending, actorLive, writerLive,
                      claims, completions, preflightFailed, preflightSucceeded>>

UnsafeNext == Next \/ UnsafeDispose
UnsafeSpec == Init /\ [][UnsafeNext]_vars

=============================================================================