--------------------- MODULE TicketReservationRace ---------------------
EXTENDS Naturals

\* This models a split fast-path probe followed by a CONTROL reservation.
\* The safe implementation RECHECKS liveness/origin atomically with reserve.
\* UnsafePrecheckOnly models the pre-hardening TOCTOU admission.
CONSTANT UnsafePrecheckOnly

VARIABLES live, originMatches, checked, attempted, capacityFull,
          reserved, overflowed, staleEffect
vars == <<live, originMatches, checked, attempted, capacityFull,
          reserved, overflowed, staleEffect>>

Init ==
  /\ live = TRUE
  /\ originMatches = TRUE
  /\ checked = FALSE
  /\ attempted = FALSE
  /\ capacityFull = FALSE
  /\ reserved = FALSE
  /\ overflowed = FALSE
  /\ staleEffect = FALSE

Precheck ==
  /\ live /\ originMatches /\ ~checked
  /\ checked' = TRUE
  /\ UNCHANGED <<live, originMatches, attempted, capacityFull,
                 reserved, overflowed, staleEffect>>

Retire ==
  /\ live
  /\ live' = FALSE
  /\ UNCHANGED <<originMatches, checked, attempted, capacityFull,
                 reserved, overflowed, staleEffect>>

ReplaceIncarnation ==
  /\ originMatches
  /\ originMatches' = FALSE
  /\ UNCHANGED <<live, checked, attempted, capacityFull,
                 reserved, overflowed, staleEffect>>

FillControl ==
  /\ ~capacityFull
  /\ capacityFull' = TRUE
  /\ UNCHANGED <<live, originMatches, checked, attempted,
                 reserved, overflowed, staleEffect>>

ReserveControl ==
  /\ checked /\ ~attempted
  /\ LET allowed == UnsafePrecheckOnly \/ (live /\ originMatches)
         stale == ~live \/ ~originMatches
     IN
       /\ attempted' = TRUE
       /\ reserved' = allowed /\ ~capacityFull
       /\ overflowed' = allowed /\ capacityFull
       /\ staleEffect' = allowed /\ stale
  /\ UNCHANGED <<live, originMatches, checked, capacityFull>>

Next == Precheck \/ Retire \/ ReplaceIncarnation \/ FillControl
        \/ ReserveControl

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ live \in BOOLEAN
  /\ originMatches \in BOOLEAN
  /\ checked \in BOOLEAN
  /\ attempted \in BOOLEAN
  /\ capacityFull \in BOOLEAN
  /\ reserved \in BOOLEAN
  /\ overflowed \in BOOLEAN
  /\ staleEffect \in BOOLEAN

\* A stale callback cannot claim any CONTROL slot OR fail-stop the actor
\* due to saturated capacity. A legitimate live full-lane attempt may fail.
NoStaleEffect == ~staleEffect
NoContradictoryOutcome == ~(reserved /\ overflowed)
=============================================================================
