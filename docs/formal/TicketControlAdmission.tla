---------------------- MODULE TicketControlAdmission ----------------------
EXTENDS Naturals

\* Runtime-issued continuation authority must be checked against the exact
\* actor execution domain before reserving any bounded CONTROL mailbox slot.
\* This is a deliberately small model of queue-capacity admission, not Java
\* scheduler refinement or an actor lifecycle liveness proof.
CONSTANT UnsafeOriginAdmission
VARIABLES actor, incarnation, slotTaken, rejected

vars == <<actor, incarnation, slotTaken, rejected>>

\* The captured ticket was issued by actor 1, incarnation 1.
OriginMatches == actor = 1 /\ incarnation = 1

Init ==
  /\ actor = 1
  /\ incarnation = 1
  /\ slotTaken = FALSE
  /\ rejected = FALSE

Restart ==
  /\ incarnation = 1
  /\ incarnation' = 2
  /\ slotTaken' = FALSE
  /\ rejected' = FALSE
  /\ UNCHANGED actor

SwitchActor ==
  /\ actor = 1
  /\ ~slotTaken
  /\ actor' = 2
  /\ UNCHANGED <<incarnation, slotTaken, rejected>>

AttemptEnqueue ==
  /\ ~slotTaken
  /\ LET permitted == UnsafeOriginAdmission \/ OriginMatches
     IN /\ slotTaken' = permitted
        /\ rejected' = ~permitted
  /\ UNCHANGED <<actor, incarnation>>

Drain ==
  /\ slotTaken
  /\ slotTaken' = FALSE
  /\ rejected' = FALSE
  /\ UNCHANGED <<actor, incarnation>>

Next == Restart \/ SwitchActor \/ AttemptEnqueue \/ Drain

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ actor \in 1..2
  /\ incarnation \in 1..2
  /\ slotTaken \in BOOLEAN
  /\ rejected \in BOOLEAN

\* No foreign/stale ticket may ever spend another incarnation's control
\* slot. Unlike a post-enqueue run check this prevents capacity exhaustion.
NoForeignControlSlot == slotTaken => OriginMatches

NoRejectedSlot == rejected => ~slotTaken
=============================================================================