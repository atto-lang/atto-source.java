---------------- MODULE FinalizationFailureReceipts ----------------
EXTENDS Naturals
CONSTANT UnsafeOverwrite
VARIABLES first, seen, suppressed, terminated
vars == <<first, seen, suppressed, terminated>>
Failures == {"scheduler", "guest", "output"}
Init ==
  /\ first = "none"
  /\ seen = {}
  /\ suppressed = {}
  /\ terminated = FALSE
Record(f) ==
  /\ f \in Failures
  /\ ~terminated
  /\ first' = IF first = "none" \/ UnsafeOverwrite THEN f ELSE first
  /\ seen' = seen \cup {f}
  /\ suppressed' = IF first = "none" THEN {}
                   ELSE IF f = first THEN suppressed ELSE suppressed \cup {f}
  /\ UNCHANGED terminated
Stop ==
  /\ ~terminated
  /\ terminated' = TRUE
  /\ UNCHANGED <<first, seen, suppressed>>
Next == (\E f \in Failures : Record(f)) \/ Stop
Spec == Init /\ [][Next]_vars
TypeOK ==
  /\ first \in (Failures \cup {"none"})
  /\ seen \subseteq Failures
  /\ suppressed \subseteq Failures
  /\ terminated \in BOOLEAN
NoOverwrittenFirst == first = "none" \/ first \in (seen \ suppressed)
NoMissingFailure ==
  IF first = "none" THEN seen = {}
  ELSE seen = suppressed \cup {first}
===============================================================
