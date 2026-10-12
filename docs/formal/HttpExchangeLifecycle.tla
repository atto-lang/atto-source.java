---------------- MODULE HttpExchangeLifecycle ----------------
EXTENDS Naturals, FiniteSets
CONSTANTS Tickets, Capacity, MaxEpoch, UnsafeDuplicateRelease
ASSUME /\ Tickets # {}
       /\ IsFiniteSet(Tickets)
       /\ Capacity \in Nat
       /\ Capacity > 0
       /\ MaxEpoch \in Nat
       /\ MaxEpoch > 0
       /\ UnsafeDuplicateRelease \in BOOLEAN

(*
  An intentionally finite abstraction of the unclaimed HTTP transfer lifecycle.
  "published" includes actor-mail tombstones, which retain one reservation
  until consumed or revoked. Claimed exchanges release their transfer slot
  immediately; the separate HTTP connection admission is abstracted away.

  Shutdown fencing has one linearization point: after Close, no Reserve or
  Publish transition is enabled. Restart changes actor incarnation authority.
*)
VARIABLES phase, held, releases, responses, closed, epoch, ticketEpoch
vars == <<phase, held, releases, responses, closed, epoch, ticketEpoch>>

States == {"new", "reserved", "published", "claimed",
           "expiredPublished", "expiredClaimed",
           "released", "responded", "finished", "revoked"}

Init ==
    /\ phase = [t \in Tickets |-> "new"]
    /\ held = {}
    /\ releases = [t \in Tickets |-> 0]
    /\ responses = [t \in Tickets |-> 0]
    /\ closed = FALSE
    /\ epoch = 0
    /\ ticketEpoch = [t \in Tickets |-> 0]

Reserve(t) ==
    /\ ~closed
    /\ phase[t] = "new"
    /\ Cardinality(held) < Capacity
    /\ phase' = [phase EXCEPT ![t] = "reserved"]
    /\ held' = held \cup {t}
    /\ ticketEpoch' = [ticketEpoch EXCEPT ![t] = epoch]
    /\ UNCHANGED <<releases, responses, closed, epoch>>

Publish(t) ==
    /\ ~closed
    /\ phase[t] = "reserved"
    /\ phase' = [phase EXCEPT ![t] = "published"]
    /\ UNCHANGED <<held, releases, responses, closed, epoch, ticketEpoch>>

Claim(t) ==
    /\ ~closed
    /\ phase[t] = "published"
    /\ ticketEpoch[t] = epoch
    /\ phase' = [phase EXCEPT ![t] = "claimed"]
    /\ held' = held \ {t}
    /\ releases' = [releases EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<responses, closed, epoch, ticketEpoch>>

(* An untransferred request may be rejected without leaving a mailbox token. *)
AbortUnpublished(t) ==
    /\ phase[t] = "reserved"
    /\ phase' = [phase EXCEPT ![t] = "released"]
    /\ held' = held \ {t}
    /\ releases' = [releases EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<responses, closed, epoch, ticketEpoch>>

(* Expiration of queued actor mail retains a bounded tombstone. *)
ExpirePublished(t) ==
    /\ phase[t] = "published"
    /\ phase' = [phase EXCEPT ![t] = "expiredPublished"]
    /\ UNCHANGED <<held, releases, responses, closed, epoch, ticketEpoch>>

ConsumeExpiredMail(t) ==
    /\ phase[t] = "expiredPublished"
    /\ phase' = [phase EXCEPT ![t] = "released"]
    /\ held' = held \ {t}
    /\ releases' = [releases EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<responses, closed, epoch, ticketEpoch>>

(* An already-claimed request can expire while guest execution suspends. *)
ExpireClaimed(t) ==
    /\ phase[t] = "claimed"
    /\ phase' = [phase EXCEPT ![t] = "expiredClaimed"]
    /\ UNCHANGED <<held, releases, responses, closed, epoch, ticketEpoch>>

Respond(t) ==
    /\ ~closed
    /\ phase[t] = "claimed"
    /\ ticketEpoch[t] = epoch
    /\ phase' = [phase EXCEPT ![t] = "responded"]
    /\ responses' = [responses EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<held, releases, closed, epoch, ticketEpoch>>

Finish(t) ==
    /\ phase[t] = "responded"
    /\ phase' = [phase EXCEPT ![t] = "finished"]
    /\ UNCHANGED <<held, releases, responses, closed, epoch, ticketEpoch>>

(* Actor termination / shutdown revokes any still-unclaimed ticket. *)
Revoke(t) ==
    /\ phase[t] \in {"reserved", "published", "expiredPublished"}
    /\ phase' = [phase EXCEPT ![t] = "revoked"]
    /\ held' = held \ {t}
    /\ releases' = [releases EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<responses, closed, epoch, ticketEpoch>>

Close ==
    /\ ~closed
    /\ closed' = TRUE
    /\ UNCHANGED <<phase, held, releases, responses, epoch, ticketEpoch>>

(* Stop an old incarnation's claimed work before exposing the successor. *)
Restart ==
    /\ epoch < MaxEpoch
    /\ ~closed
    /\ epoch' = epoch + 1
    /\ phase' = [t \in Tickets |->
          IF phase[t] = "claimed" THEN "expiredClaimed" ELSE phase[t]]
    /\ UNCHANGED <<held, releases, responses, closed, ticketEpoch>>

(* Negative control: deliberately replay an already consumed release. *)
ReleaseAgain(t) ==
    /\ UnsafeDuplicateRelease
    /\ releases[t] = 1
    /\ releases' = [releases EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<phase, held, responses, closed, epoch, ticketEpoch>>

Next ==
    \/ \E t \in Tickets : Reserve(t)
    \/ \E t \in Tickets : Publish(t)
    \/ \E t \in Tickets : Claim(t)
    \/ \E t \in Tickets : AbortUnpublished(t)
    \/ \E t \in Tickets : ExpirePublished(t)
    \/ \E t \in Tickets : ConsumeExpiredMail(t)
    \/ \E t \in Tickets : ExpireClaimed(t)
    \/ \E t \in Tickets : Respond(t)
    \/ \E t \in Tickets : Finish(t)
    \/ \E t \in Tickets : Revoke(t)
    \/ \E t \in Tickets : ReleaseAgain(t)
    \/ Close
    \/ Restart

Spec == Init /\ [][Next]_vars

TypeOK ==
    /\ phase \in [Tickets -> States]
    /\ held \subseteq Tickets
    /\ releases \in [Tickets -> Nat]
    /\ responses \in [Tickets -> Nat]
    /\ closed \in BOOLEAN
    /\ epoch \in 0..MaxEpoch
    /\ ticketEpoch \in [Tickets -> (0..MaxEpoch)]

CapacityBound == Cardinality(held) <= Capacity

ReservationsAccounted ==
    held = {t \in Tickets :
        phase[t] \in {"reserved", "published", "expiredPublished"}}

ReleaseAtMostOnce == \A t \in Tickets : releases[t] <= 1

NoPostTerminalResponse ==
    \A t \in Tickets :
        /\ responses[t] <= 1
        /\ (responses[t] = 1 => phase[t] \in {"responded", "finished"})

NoStaleClaimedAuthority ==
    \A t \in Tickets : phase[t] = "claimed" => ticketEpoch[t] = epoch

==============================================================