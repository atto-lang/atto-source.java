------------------- MODULE RetiredTicketProvenance -------------------
EXTENDS Naturals

\* A small finite-state protocol for Java SerialExecutionTicket's carrier
\* authorization order. A ticket belongs to actor=1, incarnation=1.
\* A stale/foreign destination must be rejected even if the ticket retired
\* or its issuing actor is stopped. Source-level cleanup is NOT modeled here.
CONSTANT UnsafeRetirementFirst

VARIABLES actor, incarnation, retired, alive, outcome
vars == <<actor, incarnation, retired, alive, outcome>>

OriginMatches == actor = 1 /\ incarnation = 1
Revoked == retired \/ ~alive

Init ==
  /\ actor = 1
  /\ incarnation = 1
  /\ retired = FALSE
  /\ alive = TRUE
  /\ outcome = "unexamined"

Stop ==
  /\ outcome = "unexamined"
  /\ alive
  /\ alive' = FALSE
  /\ UNCHANGED <<actor, incarnation, retired, outcome>>

Retire ==
  /\ outcome = "unexamined"
  /\ ~retired
  /\ retired' = TRUE
  /\ UNCHANGED <<actor, incarnation, alive, outcome>>

SwitchActor ==
  /\ outcome = "unexamined"
  /\ actor = 1
  /\ actor' = 2
  /\ UNCHANGED <<incarnation, retired, alive, outcome>>

Restart ==
  /\ outcome = "unexamined"
  /\ incarnation = 1
  /\ incarnation' = 2
  /\ UNCHANGED <<actor, retired, alive, outcome>>

Check ==
  /\ outcome = "unexamined"
  /\ outcome' = IF UnsafeRetirementFirst /\ Revoked
                THEN "retired"
                ELSE IF ~OriginMatches
                     THEN "foreign"
                     ELSE IF Revoked THEN "retired" ELSE "allowed"
  /\ UNCHANGED <<actor, incarnation, retired, alive>>

Next == Stop \/ Retire \/ SwitchActor \/ Restart \/ Check
Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ actor \in 1..2
  /\ incarnation \in 1..2
  /\ retired \in BOOLEAN
  /\ alive \in BOOLEAN
  /\ outcome \in {"unexamined", "retired", "foreign", "allowed"}

NoForeignBenignDiscard == outcome = "retired" => OriginMatches
NoForeignAdmission == outcome = "allowed" => (OriginMatches /\ ~Revoked)
CorrectClassification ==
  outcome # "unexamined" =>
    (IF ~OriginMatches THEN outcome = "foreign"
     ELSE IF Revoked THEN outcome = "retired"
     ELSE outcome = "allowed")
=============================================================================
