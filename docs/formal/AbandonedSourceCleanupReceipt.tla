---------------- MODULE AbandonedSourceCleanupReceipt ----------------
EXTENDS Naturals

\* A deliberately bounded safety abstraction for the intermediate STOP
\* policy: lexical source cleanup cannot be claimed successful when it was
\* registered but no cleanup-only CONTROL execution has settled it.
\* This does not prove guest cleanup execution or scheduler refinement.
CONSTANT UnsafeFalseSuccessfulDone

VARIABLES phase, cleanup, done, lateProducer
vars == <<phase, cleanup, done, lateProducer>>

Init ==
  /\ phase = "running"
  /\ cleanup = "absent"
  /\ done = "pending"
  /\ lateProducer = FALSE

RegisterCleanup ==
  /\ phase = "running"
  /\ cleanup = "absent"
  /\ cleanup' = "registered"
  /\ UNCHANGED <<phase, done, lateProducer>>

Suspend ==
  /\ phase = "running"
  /\ phase' = "awaiting"
  /\ UNCHANGED <<cleanup, done, lateProducer>>

NormalSettleCleanup ==
  /\ phase = "running"
  /\ cleanup = "registered"
  /\ cleanup' = "settled"
  /\ UNCHANGED <<phase, done, lateProducer>>

Stop ==
  /\ phase \in {"running", "awaiting"}
  /\ phase' = "stopped"
  /\ UNCHANGED <<cleanup, done, lateProducer>>

Finalize ==
  /\ phase = "stopped"
  /\ done = "pending"
  /\ done' = IF (cleanup = "registered") /\ ~UnsafeFalseSuccessfulDone
                THEN "incomplete"
                ELSE "success"
  /\ UNCHANGED <<phase, cleanup, lateProducer>>

LateProducer ==
  /\ phase = "stopped"
  /\ ~lateProducer
  /\ lateProducer' = TRUE
  /\ UNCHANGED <<phase, cleanup, done>>

Next == RegisterCleanup \/ Suspend \/ NormalSettleCleanup
     \/ Stop \/ Finalize \/ LateProducer
Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ phase \in {"running", "awaiting", "stopped"}
  /\ cleanup \in {"absent", "registered", "settled"}
  /\ done \in {"pending", "success", "incomplete"}
  /\ lateProducer \in BOOLEAN

NoFalseSuccessfulDone == done = "success" => cleanup # "registered"
NoIncompleteWithoutObligation == done = "incomplete" => cleanup = "registered"
NoLateProducerGuestResume == lateProducer => phase = "stopped"
=============================================================================
