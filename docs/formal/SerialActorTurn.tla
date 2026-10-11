---------------------- MODULE SerialActorTurn ----------------------
EXTENDS Naturals, FiniteSets
CONSTANTS Mails, Tickets, NoMail
ASSUME /\ Mails # {}
       /\ Tickets # {}
       /\ IsFiniteSet(Mails)
       /\ IsFiniteSet(Tickets)
       /\ NoMail \notin Mails

VARIABLES owner, pending, issued, stopped
vars == <<owner, pending, issued, stopped>>

Init ==
    /\ owner = NoMail
    /\ pending = {}
    /\ issued = {}
    /\ stopped = FALSE

Hold(mail, ticket) ==
    /\ ~stopped
    /\ mail \in Mails
    /\ ticket \in Tickets \ issued
    /\ (owner = NoMail \/ owner = mail)
    /\ owner' = mail
    /\ pending' = pending \cup {ticket}
    /\ issued' = issued \cup {ticket}
    /\ UNCHANGED stopped

Complete(ticket) ==
    /\ ticket \in pending
    /\ pending' = pending \ {ticket}
    /\ owner' = IF pending' = {} THEN NoMail ELSE owner
    /\ UNCHANGED <<issued, stopped>>

(* Duplicates, late completions and foreign tickets carry no authority. *)
Replay(ticket) ==
    /\ ticket \in Tickets \ pending
    /\ UNCHANGED vars

Retire ==
    /\ ~stopped
    /\ stopped' = TRUE
    /\ owner' = NoMail
    /\ pending' = {}
    /\ UNCHANGED issued

Restart ==
    /\ stopped
    /\ stopped' = FALSE
    /\ owner' = NoMail
    /\ pending' = {}
    /\ UNCHANGED issued

Next ==
    \/ \E m \in Mails : \E t \in Tickets : Hold(m, t)
    \/ \E t \in Tickets : Complete(t)
    \/ \E t \in Tickets : Replay(t)
    \/ Retire
    \/ Restart

Spec == Init /\ [][Next]_vars

TypeOK ==
    /\ owner \in Mails \cup {NoMail}
    /\ pending \subseteq issued
    /\ issued \subseteq Tickets
    /\ stopped \in BOOLEAN

SingleFlight == (owner = NoMail) <=> (pending = {})
StoppedFenced == stopped => (owner = NoMail /\ pending = {})
NoReuse == Cardinality(issued) >= Cardinality(pending)

===============================================================
