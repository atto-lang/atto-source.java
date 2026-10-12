------------------ MODULE ActorCleanupPublication ------------------
EXTENDS Naturals
CONSTANT UnsafePublish
VARIABLES phase, incarnation, cancelled, revoked, remaining,
          done, failed, published, nextAdmitted
vars == <<phase, incarnation, cancelled, revoked, remaining,
          done, failed, published, nextAdmitted>>
Init ==
  /\ phase = "RUN"
  /\ incarnation = 1
  /\ cancelled = FALSE
  /\ revoked = FALSE
  /\ remaining = 2
  /\ done = 0
  /\ failed = FALSE
  /\ published = FALSE
  /\ nextAdmitted = FALSE
Await ==
  /\ phase = "RUN"
  /\ phase' = "SUSPENDED"
  /\ UNCHANGED <<incarnation, cancelled, revoked, remaining,
                 done, failed, published, nextAdmitted>>
Resume ==
  /\ phase = "SUSPENDED" /\ ~cancelled /\ ~revoked
  /\ phase' = "RUN"
  /\ UNCHANGED <<incarnation, cancelled, revoked, remaining,
                 done, failed, published, nextAdmitted>>
Cancel ==
  /\ phase # "TERMINAL" /\ ~cancelled
  /\ phase' = "CLEANUP"
  /\ cancelled' = TRUE
  /\ revoked' = TRUE
  /\ UNCHANGED <<incarnation, remaining, done, failed, published, nextAdmitted>>
BeginUnwind ==
  /\ phase = "RUN" /\ ~cancelled
  /\ phase' = "CLEANUP"
  /\ UNCHANGED <<incarnation, cancelled, revoked, remaining,
                 done, failed, published, nextAdmitted>>
RunDefer ==
  /\ phase = "CLEANUP" /\ remaining > 0
  /\ remaining' = remaining - 1
  /\ done' = done + 1
  /\ UNCHANGED <<phase, incarnation, cancelled, revoked,
                 failed, published, nextAdmitted>>
FailDefer ==
  /\ phase = "CLEANUP" /\ remaining > 0
  /\ remaining' = remaining - 1
  /\ done' = done + 1
  /\ failed' = TRUE
  /\ UNCHANGED <<phase, incarnation, cancelled, revoked,
                 published, nextAdmitted>>
Finish ==
  /\ phase = "CLEANUP"
  /\ (remaining = 0 \/ UnsafePublish)
  /\ phase' = "TERMINAL"
  /\ revoked' = TRUE
  /\ published' = IF UnsafePublish THEN TRUE
                    ELSE ~cancelled /\ ~revoked /\ ~failed
  /\ UNCHANGED <<incarnation, cancelled, remaining,
                 done, failed, nextAdmitted>>
NextReceive ==
  /\ phase = "TERMINAL" /\ ~nextAdmitted /\ remaining = 0
  /\ nextAdmitted' = TRUE
  /\ UNCHANGED <<phase, incarnation, cancelled, revoked,
                 remaining, done, failed, published>>
Restart ==
  /\ phase = "TERMINAL" /\ incarnation = 1
  /\ incarnation' = 2
  /\ UNCHANGED <<phase, cancelled, revoked, remaining,
                 done, failed, published, nextAdmitted>>
Next == Await \/ Resume \/ Cancel \/ BeginUnwind \/ RunDefer
        \/ FailDefer \/ Finish \/ NextReceive \/ Restart
Spec == Init /\ [][Next]_vars
TypeOK ==
  /\ phase \in {"RUN", "SUSPENDED", "CLEANUP", "TERMINAL"}
  /\ incarnation \in 1..2
  /\ remaining \in 0..2
  /\ done \in 0..2
  /\ cancelled \in BOOLEAN /\ revoked \in BOOLEAN
  /\ failed \in BOOLEAN /\ published \in BOOLEAN
  /\ nextAdmitted \in BOOLEAN
CleanupAccounting == done + remaining = 2
NoEarlyOrCancelledPublication ==
  published => remaining = 0 /\ ~cancelled /\ ~failed
NoPrematureNext == nextAdmitted => remaining = 0
RestartRevokes == incarnation = 2 => revoked
===============================================================
