------------------ MODULE ActorTerminalObserverOrdering ------------------
EXTENDS Naturals
CONSTANTS UnsafePhysicalFirst, UnsafeNotifyDoneEarly

VARIABLES readySet, doneSet, physicalSet, doneObserverNotified,
          physicalObserverNotified, observerBlocked
vars == <<readySet, doneSet, physicalSet, doneObserverNotified,
          physicalObserverNotified, observerBlocked>>

Init ==
  /\ readySet = FALSE
  /\ doneSet = FALSE
  /\ physicalSet = FALSE
  /\ doneObserverNotified = FALSE
  /\ physicalObserverNotified = FALSE
  /\ observerBlocked = FALSE

PrepareReady ==
  /\ ~readySet
  /\ readySet' = TRUE
  /\ UNCHANGED <<doneSet, physicalSet, doneObserverNotified,
                 physicalObserverNotified, observerBlocked>>

PrepareDone ==
  /\ readySet /\ ~doneSet /\ ~observerBlocked
  /\ doneSet' = TRUE
  /\ UNCHANGED <<readySet, physicalSet, doneObserverNotified,
                 physicalObserverNotified, observerBlocked>>

PreparePhysical ==
  /\ ~physicalSet
  /\ (doneSet \/ UnsafePhysicalFirst)
  /\ physicalSet' = TRUE
  /\ UNCHANGED <<readySet, doneSet, doneObserverNotified,
                 physicalObserverNotified, observerBlocked>>

NotifyPhysical ==
  /\ physicalSet /\ ~physicalObserverNotified /\ ~observerBlocked
  /\ physicalObserverNotified' = TRUE
  /\ observerBlocked' = ~doneSet
  /\ UNCHANGED <<readySet, doneSet, physicalSet, doneObserverNotified>>

NotifyDone ==
  /\ doneSet /\ ~doneObserverNotified /\ ~observerBlocked
  /\ (physicalSet \/ UnsafeNotifyDoneEarly)
  /\ doneObserverNotified' = TRUE
  /\ observerBlocked' = ~physicalSet
  /\ UNCHANGED <<readySet, doneSet, physicalSet, physicalObserverNotified>>

Next == PrepareReady \/ PrepareDone \/ PreparePhysical
        \/ NotifyPhysical \/ NotifyDone
Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ readySet \in BOOLEAN /\ doneSet \in BOOLEAN
  /\ physicalSet \in BOOLEAN
  /\ doneObserverNotified \in BOOLEAN
  /\ physicalObserverNotified \in BOOLEAN
  /\ observerBlocked \in BOOLEAN

PhysicalRequiresOtherTerminalStates ==
  physicalSet => readySet /\ doneSet
NoSynchronousObserverDeadlock == ~observerBlocked
======================================================================
