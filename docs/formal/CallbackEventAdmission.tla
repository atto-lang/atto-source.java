------------------- MODULE CallbackEventAdmission -------------------
EXTENDS Naturals
CONSTANTS MaxYields, AllowEarlyEvent, RevokeOnStop, AllowInlineEvent
VARIABLES phase, lease, physical, effects, yields, eventReady, eventEntries, stopped, inlineViolation
vars == <<phase, lease, physical, effects, yields, eventReady, eventEntries, stopped, inlineViolation>>

Init == /\ phase = "ready" /\ lease = 0 /\ physical = 0
        /\ effects = 0 /\ yields = 0 /\ eventReady = FALSE
        /\ eventEntries = 0 /\ stopped = FALSE /\ inlineViolation = FALSE

Claim(w) ==
    /\ ~stopped /\ phase = "ready" /\ lease = 0 /\ physical = 0
    /\ lease' = w /\ physical' = w /\ phase' = "running"
    /\ UNCHANGED <<effects, yields, eventReady, eventEntries, stopped, inlineViolation>>
First ==
    /\ ~stopped /\ phase = "running" /\ effects = 0
    /\ effects' = 1
    /\ UNCHANGED <<phase, lease, physical, yields, eventReady, eventEntries, stopped, inlineViolation>>
Yield ==
    /\ ~stopped /\ phase = "running" /\ effects = 1 /\ yields < MaxYields
    /\ phase' = "handoff" /\ yields' = yields + 1
    /\ UNCHANGED <<lease, physical, effects, eventReady, eventEntries, stopped, inlineViolation>>
Await ==
    /\ ~stopped /\ phase = "running" /\ effects = 1
    /\ phase' = "waiting"
    /\ UNCHANGED <<lease, physical, effects, yields, eventReady, eventEntries, stopped, inlineViolation>>
Wake ==
    /\ ~stopped /\ phase = "waiting" /\ physical = 0
    /\ phase' = "ready"
    /\ UNCHANGED <<lease, physical, effects, yields, eventReady, eventEntries, stopped, inlineViolation>>
Tail ==
    /\ ~stopped /\ phase = "running" /\ effects = 1
    /\ phase' = "done" /\ effects' = 2
    /\ UNCHANGED <<lease, physical, yields, eventReady, eventEntries, stopped, inlineViolation>>
Settle ==
    /\ ~stopped /\ ~eventReady /\ eventEntries = 0
    /\ eventReady' = TRUE
    /\ UNCHANGED <<phase, lease, physical, effects, yields, eventEntries, stopped, inlineViolation>>
Exit ==
    /\ physical # 0 /\ (stopped \/ phase # "running")
    /\ physical' = 0 /\ lease' = 0
    /\ phase' = IF phase = "handoff" THEN "ready" ELSE phase
    /\ UNCHANGED <<effects, yields, eventReady, eventEntries, stopped, inlineViolation>>
Event(w) ==
    /\ ~stopped /\ eventReady /\ eventEntries = 0
    /\ (AllowEarlyEvent \/ phase = "done")
    /\ (AllowInlineEvent \/ (lease = 0 /\ physical = 0))
    /\ eventReady' = FALSE /\ eventEntries' = eventEntries + 1
    /\ lease' = w /\ physical' = w
    /\ inlineViolation' = (physical # 0)
    /\ UNCHANGED <<phase, effects, yields, stopped>>
Stop ==
    /\ ~stopped /\ stopped' = TRUE /\ eventReady' = FALSE
    /\ lease' = IF RevokeOnStop THEN 0 ELSE lease
    /\ UNCHANGED <<phase, physical, effects, yields, eventEntries, inlineViolation>>
Next == (\E w \in {1,2}: Claim(w) \/ Event(w))
        \/ First \/ Yield \/ Await \/ Wake \/ Tail \/ Settle \/ Exit \/ Stop
Spec == Init /\ [][Next]_vars
TypeOK == /\ phase \in {"ready","running","handoff","waiting","done"}
          /\ lease \in 0..2 /\ physical \in 0..2 /\ effects \in 0..2
          /\ yields \in 0..MaxYields /\ eventEntries \in 0..1
          /\ eventReady \in BOOLEAN /\ stopped \in BOOLEAN /\ inlineViolation \in BOOLEAN
LeaseMatchesCarrier == lease = physical
NoZalgo == eventEntries = 0 \/ effects = 2
NoInlineEvent == ~inlineViolation
=====================================================================
