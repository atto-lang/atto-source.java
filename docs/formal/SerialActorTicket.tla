-------------------------- MODULE SerialActorTicket --------------------------
EXTENDS Naturals

\* Finite-state safety abstraction of a single scheduler-issued ticket
\* and its actor receive.  It does not model implementation-level locking,
\* scheduler carrier execution, or fairness/liveness.
VARIABLES phase, epoch, lease, retired, cancelled, receiveClosed,
          cleanups, publications

vars == <<phase, epoch, lease, retired, cancelled, receiveClosed,
          cleanups, publications>>

Phases == {"running", "suspended", "queued", "resumed",
           "unwinding", "settled"}

Init ==
    /\ phase = "running"
    /\ epoch = 1
    /\ lease = 1
    /\ retired = FALSE
    /\ cancelled = FALSE
    /\ receiveClosed = FALSE
    /\ cleanups = 0
    /\ publications = 0

\* Issued authority is the original actor incarnation/receive.  A
\* repeated lexical frame number or scheduler task ID cannot reissue it.
Authorized ==
    /\ ~retired
    /\ ~cancelled
    /\ ~receiveClosed
    /\ epoch = 1
    /\ lease = 1

Await ==
    /\ phase = "running"
    /\ phase' = "suspended"
    /\ UNCHANGED <<epoch, lease, retired, cancelled,
                   receiveClosed, cleanups, publications>>

ProducerCompletes ==
    /\ phase = "suspended"
    /\ phase' = "queued"
    /\ UNCHANGED <<epoch, lease, retired, cancelled,
                   receiveClosed, cleanups, publications>>

\* The producer might duplicate delivery; this must have no effect.
DuplicateCompletion ==
    /\ phase \in {"queued", "settled"}
    /\ UNCHANGED vars

DrainQueued ==
    /\ phase = "queued"
    /\ phase' = IF Authorized THEN "resumed" ELSE "unwinding"
    /\ retired' = IF Authorized THEN retired ELSE TRUE
    /\ cancelled' = IF Authorized THEN cancelled ELSE TRUE
    /\ UNCHANGED <<epoch, lease, receiveClosed,
                   cleanups, publications>>

SourceFinishes ==
    /\ phase = "resumed"
    /\ phase' = "unwinding"
    /\ UNCHANGED <<epoch, lease, retired, cancelled,
                   receiveClosed, cleanups, publications>>

CancelTask ==
    /\ phase # "settled"
    /\ ~cancelled
    /\ phase' = "unwinding"
    /\ retired' = TRUE
    /\ cancelled' = TRUE
    /\ UNCHANGED <<epoch, lease, receiveClosed, cleanups, publications>>

CloseReceive ==
    /\ ~receiveClosed
    /\ phase' = IF phase = "settled" THEN "settled" ELSE "unwinding"
    /\ retired' = TRUE
    /\ cancelled' = TRUE
    /\ receiveClosed' = TRUE
    /\ UNCHANGED <<epoch, lease, cleanups, publications>>

RestartActor ==
    /\ epoch = 1
    /\ epoch' = 2
    /\ phase' = IF phase = "settled" THEN "settled" ELSE "unwinding"
    /\ retired' = TRUE
    /\ cancelled' = TRUE
    /\ receiveClosed' = TRUE
    /\ UNCHANGED <<lease, cleanups, publications>>

RunCleanup ==
    /\ phase = "unwinding"
    /\ cleanups = 0
    /\ cleanups' = 1
    /\ UNCHANGED <<phase, epoch, lease, retired, cancelled,
                   receiveClosed, publications>>

PublishOrDiscard ==
    /\ phase = "unwinding"
    /\ cleanups = 1
    /\ phase' = "settled"
    /\ publications' = IF Authorized THEN 1 ELSE 0
    /\ retired' = TRUE
    /\ UNCHANGED <<epoch, lease, cancelled, receiveClosed, cleanups>>

PromoteNextReceive ==
    /\ phase = "settled"
    /\ receiveClosed
    /\ lease = 1
    /\ lease' = 2
    /\ UNCHANGED <<phase, epoch, retired, cancelled, receiveClosed,
                   cleanups, publications>>

Next ==
    \/ Await
    \/ ProducerCompletes
    \/ DuplicateCompletion
    \/ DrainQueued
    \/ SourceFinishes
    \/ CancelTask
    \/ CloseReceive
    \/ RestartActor
    \/ RunCleanup
    \/ PublishOrDiscard
    \/ PromoteNextReceive

Spec == Init /\ [][Next]_vars

TypeOK ==
    /\ phase \in Phases
    /\ epoch \in 1..2
    /\ lease \in 1..2
    /\ retired \in BOOLEAN
    /\ cancelled \in BOOLEAN
    /\ receiveClosed \in BOOLEAN
    /\ cleanups \in 0..1
    /\ publications \in 0..1

NoStaleGuestResume ==
    phase = "resumed" => Authorized

CleanupBeforePublication ==
    publications = 1 => cleanups = 1

NoNextReceiveBeforeCleanup ==
    lease = 2 => (phase = "settled" /\ cleanups = 1)

TerminalWasCleaned ==
    phase = "settled" => cleanups = 1

=============================================================================