--------------------- MODULE SerialTypedMatchCleanup ---------------------
EXTENDS Naturals

\* Bounded protocol for a serial source receive suspended inside
\* match<ret=T>. Task identity, actor incarnation, and match-frame owner are
\* deliberately distinct. Does not prove the real guest executes cleanup.
CONSTANTS UnsafeSkipCleanup, UnsafeEarlyRestart, UnsafeStalePublish
VARIABLES phase, actorEpoch, taskEpoch, frameOwner, pending,
          cancelled, cleanupRuns, published, laneHeld

vars == <<phase, actorEpoch, taskEpoch, frameOwner, pending,
          cancelled, cleanupRuns, published, laneHeld>>

Init ==
  /\ phase = "RUNNING"
  /\ actorEpoch = 1
  /\ taskEpoch = 1
  /\ frameOwner = 1
  /\ pending = FALSE
  /\ cancelled = FALSE
  /\ cleanupRuns = 0
  /\ published = 0
  /\ laneHeld = TRUE

Await ==
  /\ phase = "RUNNING"
  /\ phase' = "WAITING"
  /\ UNCHANGED <<actorEpoch, taskEpoch, frameOwner, pending,
                 cancelled, cleanupRuns, published, laneHeld>>

ProducerDone ==
  /\ phase = "WAITING"
  /\ ~pending
  /\ phase' = "QUEUED"
  /\ pending' = TRUE
  /\ UNCHANGED <<actorEpoch, taskEpoch, frameOwner, cancelled,
                 cleanupRuns, published, laneHeld>>

Resume ==
  /\ phase = "QUEUED"
  /\ taskEpoch = actorEpoch
  /\ frameOwner = 1
  /\ ~cancelled
  /\ phase' = "CLEANUP"
  /\ pending' = FALSE
  /\ UNCHANGED <<actorEpoch, taskEpoch, frameOwner, cancelled,
                 cleanupRuns, published, laneHeld>>

Stop ==
  /\ phase # "SETTLED"
  /\ ~cancelled
  /\ cancelled' = TRUE
  /\ phase' = "CLEANUP"
  /\ pending' = FALSE
  /\ UNCHANGED <<actorEpoch, taskEpoch, frameOwner,
                 cleanupRuns, published, laneHeld>>

RunCleanup ==
  /\ phase = "CLEANUP"
  /\ cleanupRuns = 0
  /\ cleanupRuns' = 1
  /\ UNCHANGED <<phase, actorEpoch, taskEpoch, frameOwner,
                 pending, cancelled, published, laneHeld>>

Publish ==
  /\ phase = "CLEANUP"
  /\ ~cancelled
  /\ taskEpoch = actorEpoch
  /\ (cleanupRuns = 1 \/ UnsafeSkipCleanup)
  /\ phase' = "SETTLED"
  /\ published' = 1
  /\ laneHeld' = FALSE
  /\ UNCHANGED <<actorEpoch, taskEpoch, frameOwner, pending,
                 cancelled, cleanupRuns>>

DiscardCancelled ==
  /\ phase = "CLEANUP"
  /\ cancelled
  /\ (cleanupRuns = 1 \/ UnsafeSkipCleanup)
  /\ phase' = "SETTLED"
  /\ laneHeld' = FALSE
  /\ UNCHANGED <<actorEpoch, taskEpoch, frameOwner, pending,
                 cancelled, cleanupRuns, published>>

Restart ==
  /\ actorEpoch = 1
  /\ (phase = "SETTLED" \/ (UnsafeEarlyRestart /\ cancelled))
  /\ actorEpoch' = 2
  /\ UNCHANGED <<phase, taskEpoch, frameOwner, pending,
                 cancelled, cleanupRuns, published, laneHeld>>

LateCallback ==
  /\ cancelled
  /\ phase \in {"CLEANUP", "SETTLED"}
  /\ published = 0
  /\ published' = IF UnsafeStalePublish THEN 1 ELSE 0
  /\ UNCHANGED <<phase, actorEpoch, taskEpoch, frameOwner,
                 pending, cancelled, cleanupRuns, laneHeld>>

Next == Await \/ ProducerDone \/ Resume \/ Stop \/ RunCleanup
        \/ Publish \/ DiscardCancelled \/ Restart \/ LateCallback

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ phase \in {"RUNNING", "WAITING", "QUEUED", "CLEANUP", "SETTLED"}
  /\ actorEpoch \in 1..2
  /\ taskEpoch = 1
  /\ frameOwner = 1
  /\ pending \in BOOLEAN
  /\ cancelled \in BOOLEAN
  /\ cleanupRuns \in 0..1
  /\ published \in 0..1
  /\ laneHeld \in BOOLEAN

NoPrematurePublication == published = 1 => cleanupRuns = 1
NoCancelledPublication == cancelled => published = 0
NoEarlyRestart == actorEpoch = 2 => phase = "SETTLED" /\ ~laneHeld
NoSecondCleanup == cleanupRuns <= 1
NoEarlyLeaseRelease == phase # "SETTLED" => laneHeld
=============================================================================
