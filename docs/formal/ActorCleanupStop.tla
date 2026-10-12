------------------- MODULE ActorCleanupStop -------------------
EXTENDS Naturals
CONSTANT UnsafeForeignControl, UnsafeEarlySuccess
VARIABLES phase, userOpen, control, cleanupRuns, cleanupSettled, done
vars == <<phase, userOpen, control, cleanupRuns, cleanupSettled, done>>
Init ==
  /\ phase = "running"
  /\ userOpen = TRUE
  /\ control = "none"
  /\ cleanupRuns = 0
  /\ cleanupSettled = FALSE
  /\ done = "pending"
Stop ==
  /\ phase = "running"
  /\ phase' = "cleanup"
  /\ userOpen' = FALSE
  /\ UNCHANGED <<control, cleanupRuns, cleanupSettled, done>>
EnqueueSource ==
  /\ phase = "cleanup"
  /\ control = "none"
  /\ ~cleanupSettled
  /\ control' = "source"
  /\ UNCHANGED <<phase, userOpen, cleanupRuns, cleanupSettled, done>>
EnqueueForeign ==
  /\ phase = "cleanup"
  /\ control = "none"
  /\ UnsafeForeignControl
  /\ control' = "foreign"
  /\ UNCHANGED <<phase, userOpen, cleanupRuns, cleanupSettled, done>>
RunCleanup ==
  /\ phase = "cleanup"
  /\ control = "source"
  /\ cleanupRuns = 0
  /\ control' = "none"
  /\ cleanupRuns' = 1
  /\ cleanupSettled' = TRUE
  /\ UNCHANGED <<phase, userOpen, done>>
PublishDone ==
  /\ phase = "cleanup"
  /\ (cleanupSettled \/ UnsafeEarlySuccess)
  /\ done' = "success"
  /\ phase' = "terminal"
  /\ control' = "none"
  /\ UNCHANGED <<userOpen, cleanupRuns, cleanupSettled>>
Timeout ==
  /\ phase = "cleanup"
  /\ ~cleanupSettled
  /\ done' = "incomplete"
  /\ phase' = "terminal"
  /\ control' = "none"
  /\ UNCHANGED <<userOpen, cleanupRuns, cleanupSettled>>
Next == Stop \/ EnqueueSource \/ EnqueueForeign \/ RunCleanup \/ PublishDone \/ Timeout
Spec == Init /\ [][Next]_vars /\ WF_vars(PublishDone) /\ WF_vars(Timeout)
TypeOK ==
  /\ phase \in {"running", "cleanup", "terminal"}
  /\ userOpen \in BOOLEAN
  /\ control \in {"none", "source", "foreign"}
  /\ cleanupRuns \in 0..1
  /\ cleanupSettled \in BOOLEAN
  /\ done \in {"pending", "success", "incomplete"}
NoForeignControl == control # "foreign"
SealedUserAdmission == phase # "running" => ~userOpen
NoPrematureSuccess == done = "success" => (cleanupSettled /\ cleanupRuns = 1)
NoFalseGracefulTimeout == done = "incomplete" => ~cleanupSettled
CleanupMustSettleOrFail == phase = "cleanup" ~> phase = "terminal"
=============================================================================
