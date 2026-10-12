---------------------- MODULE SerialActorTurn ----------------------
EXTENDS Naturals, FiniteSets
CONSTANTS Mails, Tickets, NoMail, MaxEpoch
ASSUME /\ Mails # {}
       /\ Tickets # {}
       /\ IsFiniteSet(Mails)
       /\ IsFiniteSet(Tickets)
       /\ NoMail \notin Mails
       /\ MaxEpoch \in Nat
       /\ MaxEpoch > 0

(* Ticket numbers may be reused in a later actor incarnation, but their
   full authority is the pair <<incarnation, ticket>>. *)
VARIABLES owner, pending, issued, stopped, epoch
vars == <<owner, pending, issued, stopped, epoch>>

Init ==
    /\ owner = NoMail
    /\ pending = {}
    /\ issued = {}
    /\ stopped = FALSE
    /\ epoch = 0

Hold(mail, ticket) ==
    /\ ~stopped
    /\ mail \in Mails
    /\ ticket \in Tickets \ issued
    /\ (owner = NoMail \/ owner = mail)
    /\ owner' = mail
    /\ pending' = pending \cup {<<epoch, ticket>>}
    /\ issued' = issued \cup {ticket}
    /\ UNCHANGED <<stopped, epoch>>

Complete(ticketAuthority) ==
    /\ ticketAuthority \in pending
    /\ pending' = pending \ {ticketAuthority}
    /\ owner' = IF pending' = {} THEN NoMail ELSE owner
    /\ UNCHANGED <<issued, stopped, epoch>>

(* Duplicate, forged and prior-incarnation completions are pure no-ops,
   including when the same ticket *number* was reused in a new incarnation. *)
Replay(ticketAuthority) ==
    /\ ticketAuthority \in ((0..MaxEpoch) \X Tickets) \ pending
    /\ UNCHANGED vars

Retire ==
    /\ ~stopped
    /\ stopped' = TRUE
    /\ owner' = NoMail
    /\ pending' = {}
    /\ UNCHANGED <<issued, epoch>>

Restart ==
    /\ stopped
    /\ epoch < MaxEpoch
    /\ epoch' = epoch + 1
    /\ stopped' = FALSE
    /\ owner' = NoMail
    /\ pending' = {}
    /\ issued' = {}

Next ==
    \/ \E mail \in Mails : \E t \in Tickets : Hold(mail, t)
    \/ \E authority \in ((0..MaxEpoch) \X Tickets) : Complete(authority)
    \/ \E authority \in ((0..MaxEpoch) \X Tickets) : Replay(authority)
    \/ Retire
    \/ Restart

Spec == Init /\ [][Next]_vars

TypeOK ==
    /\ owner \in Mails \cup {NoMail}
    /\ epoch \in 0..MaxEpoch
    /\ stopped \in BOOLEAN
    /\ issued \subseteq Tickets
    /\ pending \subseteq ({epoch} \X issued)

SingleFlight == (owner = NoMail) <=> (pending = {})
StoppedFenced == stopped => (owner = NoMail /\ pending = {})
DistinctBarriers == Cardinality(pending) = Cardinality({p[2] : p \in pending})
OnlyCurrentIncarnation == \A p \in pending : p[1] = epoch

===============================================================
