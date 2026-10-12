------------------ MODULE HttpListenerRetirement ------------------
EXTENDS Naturals, FiniteSets
CONSTANTS Tokens, Capacity, OrdinaryMail, UnsafeUserMailDrop
ASSUME /\ Tokens # {}
       /\ IsFiniteSet(Tokens)
       /\ Capacity \in Nat
       /\ Capacity > 0
       /\ OrdinaryMail # {}
       /\ IsFiniteSet(OrdinaryMail)
       /\ UnsafeUserMailDrop \in BOOLEAN

(*
  Listener-scoped shutdown: published HTTP-origin mail remains distinct from
  unrelated actor user mail, even if both have the same guest String value.
  Only physically queued mail may be removed by listener retirement.
  Dequeued but unclaimed transfers retain a one-use lease until actor teardown.
*)
VARIABLES stopped, published, queued, inFlight, claimed,
          retired, expired, leased, releases, ordinary
vars == <<stopped, published, queued, inFlight, claimed,
          retired, expired, leased, releases, ordinary>>

Init ==
    /\ stopped = FALSE
    /\ published = {}
    /\ queued = {}
    /\ inFlight = {}
    /\ claimed = {}
    /\ retired = {}
    /\ expired = {}
    /\ leased = {}
    /\ releases = [t \in Tokens |-> 0]
    /\ ordinary = OrdinaryMail

Publish(t) ==
    /\ ~stopped
    /\ t \in Tokens \ published
    /\ Cardinality(leased) < Capacity
    /\ published' = published \cup {t}
    /\ queued' = queued \cup {t}
    /\ leased' = leased \cup {t}
    /\ UNCHANGED <<stopped, inFlight, claimed, retired, expired, releases, ordinary>>

Dequeue(t) ==
    /\ t \in queued
    /\ queued' = queued \ {t}
    /\ inFlight' = inFlight \cup {t}
    /\ UNCHANGED <<stopped, published, claimed, retired, expired, leased, releases, ordinary>>

Claim(t) ==
    /\ ~stopped
    /\ t \in inFlight
    /\ t \notin claimed \cup retired
    /\ inFlight' = inFlight \ {t}
    /\ claimed' = claimed \cup {t}
    /\ leased' = leased \ {t}
    /\ releases' = [releases EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<stopped, published, queued, retired, expired, ordinary>>

Timeout(t) ==
    /\ t \in (queued \cup inFlight) \ expired
    /\ expired' = expired \cup {t}
    /\ UNCHANGED <<stopped, published, queued, inFlight, claimed, retired,
                    leased, releases, ordinary>>

Shutdown ==
    /\ ~stopped
    /\ stopped' = TRUE
    /\ UNCHANGED <<published, queued, inFlight, claimed, retired, expired,
                    leased, releases, ordinary>>

(* An actor mailbox dequeue races with this action; only one can win. *)
RetireQueued(t) ==
    /\ stopped
    /\ t \in queued
    /\ queued' = queued \ {t}
    /\ retired' = retired \cup {t}
    /\ leased' = leased \ {t}
    /\ releases' = [releases EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<stopped, published, inFlight, claimed, expired, ordinary>>

(* A dequeued token is never physically removed; actor teardown fences it. *)
ActorTerminated(t) ==
    /\ stopped
    /\ t \in inFlight
    /\ inFlight' = inFlight \ {t}
    /\ retired' = retired \cup {t}
    /\ leased' = leased \ {t}
    /\ releases' = [releases EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<stopped, published, queued, claimed, expired, ordinary>>

(* Deliberately unsafe shutdown mutation for negative-control verification. *)
UnsafeDropOrdinary ==
    /\ UnsafeUserMailDrop
    /\ stopped
    /\ ordinary # {}
    /\ ordinary' = {}
    /\ UNCHANGED <<stopped, published, queued, inFlight, claimed, retired,
                    expired, leased, releases>>

Next ==
    \/ \E t \in Tokens : Publish(t)
    \/ \E t \in Tokens : Dequeue(t)
    \/ \E t \in Tokens : Claim(t)
    \/ \E t \in Tokens : Timeout(t)
    \/ \E t \in Tokens : RetireQueued(t)
    \/ \E t \in Tokens : ActorTerminated(t)
    \/ Shutdown
    \/ UnsafeDropOrdinary

Spec == Init /\ [][Next]_vars

TypeOK ==
    /\ stopped \in BOOLEAN
    /\ published \subseteq Tokens
    /\ queued \subseteq published
    /\ inFlight \subseteq published
    /\ claimed \subseteq published
    /\ retired \subseteq published
    /\ expired \subseteq published
    /\ leased \subseteq published
    /\ releases \in [Tokens -> Nat]
    /\ ordinary \subseteq OrdinaryMail

LeaseAccounting ==
    /\ leased = published \ (claimed \cup retired)
    /\ queued \cap inFlight = {}
    /\ (claimed \cup retired) \cap (queued \cup inFlight) = {}

ReleaseExactlyOnce ==
    \A t \in Tokens :
        /\ releases[t] <= 1
        /\ (releases[t] = 1) <=> (t \in claimed \cup retired)

CapacityBound == Cardinality(leased) <= Capacity
OrdinaryMailPreserved == ordinary = OrdinaryMail

==============================================================