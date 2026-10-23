------------------------ MODULE SupervisorEpochReceipts ------------------------
EXTENDS Naturals

\* Independent finite model of CONTROL's partial tree-fence and child
\* incarnation receipts. A parent may only accept a receipt for the
\* immutable child epoch captured AFTER the child's admission fence.
\* The regular SupervisorTree.tla models sibling/depth-three ordering.
CONSTANT MaxEpoch
ASSUME MaxEpoch \in Nat /\ MaxEpoch >= 2

VARIABLES parentPhase, childPhase, childEpoch, childFenced,
          pinnedEpoch, childAck, actorsDone, failed, timedOut,
          rejectedReceipt

vars == <<parentPhase, childPhase, childEpoch, childFenced,
          pinnedEpoch, childAck, actorsDone, failed, timedOut,
          rejectedReceipt>>

ParentPhases == {"Running", "Fencing", "Pre", "WaitChild",
                  "WaitActors", "Stopped"}
ChildPhases == {"Running", "Restarting", "Stopped"}

Init ==
    /\ parentPhase = "Running"
    /\ childPhase = "Running"
    /\ childEpoch = 1
    /\ childFenced = FALSE
    /\ pinnedEpoch = 0
    /\ childAck = FALSE
    /\ actorsDone = FALSE
    /\ failed = FALSE
    /\ timedOut = FALSE
    /\ rejectedReceipt = FALSE

\* CONTROL admission begins: partial fence is a distinct state.
StartFence ==
    /\ parentPhase = "Running"
    /\ parentPhase' = "Fencing"
    /\ UNCHANGED <<childPhase, childEpoch, childFenced, pinnedEpoch,
                   childAck, actorsDone, failed, timedOut, rejectedReceipt>>

\* A restart reservation can happen before the child fence.
RequestRestart ==
    /\ childPhase = "Running"
    /\ ~childFenced
    /\ childEpoch < MaxEpoch
    /\ childPhase' = "Restarting"
    /\ UNCHANGED <<parentPhase, childEpoch, childFenced, pinnedEpoch,
                   childAck, actorsDone, failed, timedOut, rejectedReceipt>>

CommitRestart ==
    /\ childPhase = "Restarting"
    /\ ~childFenced
    /\ childEpoch < MaxEpoch
    /\ childEpoch' = childEpoch + 1
    /\ childPhase' = "Running"
    /\ UNCHANGED <<parentPhase, childFenced, pinnedEpoch,
                   childAck, actorsDone, failed, timedOut, rejectedReceipt>>

\* An in-flight restart cannot commit once the fence is applied.
FenceChild ==
    /\ parentPhase = "Fencing"
    /\ ~childFenced
    /\ childFenced' = TRUE
    \* A reserved (not committed) restart is cancelled by the fence.
    /\ childPhase' = IF childPhase = "Restarting" THEN "Running"
                     ELSE childPhase
    /\ UNCHANGED <<parentPhase, childEpoch, pinnedEpoch,
                   childAck, actorsDone, failed, timedOut, rejectedReceipt>>

\* Freeze records the currently fenced child epoch, never a prior version.
PublishFence ==
    /\ parentPhase = "Fencing"
    /\ childFenced
    /\ pinnedEpoch = 0
    /\ parentPhase' = "Pre"
    /\ pinnedEpoch' = childEpoch
    /\ UNCHANGED <<childPhase, childEpoch, childFenced,
                   childAck, actorsDone, failed, timedOut, rejectedReceipt>>

ChildStop ==
    /\ childFenced
    /\ childPhase \in {"Running", "Restarting"}
    /\ childPhase' = "Stopped"
    /\ UNCHANGED <<parentPhase, childEpoch, childFenced, pinnedEpoch,
                   childAck, actorsDone, failed, timedOut, rejectedReceipt>>

PreComplete ==
    /\ parentPhase = "Pre"
    /\ parentPhase' = "WaitChild"
    /\ UNCHANGED <<childPhase, childEpoch, childFenced, pinnedEpoch,
                   childAck, actorsDone, failed, timedOut, rejectedReceipt>>

\* The only accepting path for a child retirement.
AcceptReceipt(e) ==
    /\ parentPhase = "WaitChild"
    /\ childPhase = "Stopped"
    /\ ~childAck
    /\ pinnedEpoch > 0
    /\ e = pinnedEpoch
    /\ e = childEpoch
    /\ childAck' = TRUE
    /\ parentPhase' = "WaitActors"
    /\ UNCHANGED <<childPhase, childEpoch, childFenced, pinnedEpoch,
                   actorsDone, failed, timedOut, rejectedReceipt>>

\* An arbitrary hostile/stale receipt can be observed and rejected.
\* It MUST NOT alter the lifecycle or acknowledge child retirement.
RejectReceipt(e) ==
    /\ ~rejectedReceipt
    /\ e \in 1..MaxEpoch
    /\ ~(parentPhase = "WaitChild" /\ childPhase = "Stopped" /\
         ~childAck /\ pinnedEpoch > 0 /\ e = pinnedEpoch /\
         e = childEpoch)
    /\ rejectedReceipt' = TRUE
    /\ UNCHANGED <<parentPhase, childPhase, childEpoch, childFenced,
                   pinnedEpoch, childAck, actorsDone, failed, timedOut>>

WorkersDone ==
    /\ parentPhase = "WaitActors"
    /\ childAck
    /\ ~actorsDone
    /\ actorsDone' = TRUE
    /\ UNCHANGED <<parentPhase, childPhase, childEpoch, childFenced,
                   pinnedEpoch, childAck, failed, timedOut, rejectedReceipt>>

ParentStop ==
    /\ parentPhase = "WaitActors"
    /\ childAck
    /\ actorsDone
    /\ parentPhase' = "Stopped"
    /\ UNCHANGED <<childPhase, childEpoch, childFenced, pinnedEpoch,
                   childAck, actorsDone, failed, timedOut, rejectedReceipt>>

\* Watchdog and CONTROL failures are orthogonal; neither fakes STOPPED.
Deadline ==
    /\ parentPhase \notin {"Running", "Stopped"}
    /\ ~timedOut
    /\ timedOut' = TRUE
    /\ UNCHANGED <<parentPhase, childPhase, childEpoch, childFenced,
                   pinnedEpoch, childAck, actorsDone, failed, rejectedReceipt>>

CoordinatorFailure ==
    /\ ~failed
    /\ failed' = TRUE
    /\ UNCHANGED <<parentPhase, childPhase, childEpoch, childFenced,
                   pinnedEpoch, childAck, actorsDone, timedOut, rejectedReceipt>>

Next ==
    \/ StartFence
    \/ RequestRestart
    \/ CommitRestart
    \/ FenceChild
    \/ PublishFence
    \/ ChildStop
    \/ PreComplete
    \/ WorkersDone
    \/ ParentStop
    \/ Deadline
    \/ CoordinatorFailure
    \/ (\E e \in 1..MaxEpoch : AcceptReceipt(e) \/ RejectReceipt(e))

Spec == Init /\ [][Next]_vars

TypeOK ==
    /\ parentPhase \in ParentPhases
    /\ childPhase \in ChildPhases
    /\ childEpoch \in 1..MaxEpoch
    /\ pinnedEpoch \in 0..MaxEpoch
    /\ childFenced \in BOOLEAN
    /\ childAck \in BOOLEAN
    /\ actorsDone \in BOOLEAN
    /\ failed \in BOOLEAN
    /\ timedOut \in BOOLEAN
    /\ rejectedReceipt \in BOOLEAN

NoAckBeforeChildStop ==
    childAck => (childPhase = "Stopped" /\ childFenced /\
                 pinnedEpoch = childEpoch /\ pinnedEpoch > 0)

NoPrematureParentStop ==
    (parentPhase = "Stopped") =>
      (childAck /\ actorsDone /\ childPhase = "Stopped")

NoUnfencedPublishedEpoch ==
    pinnedEpoch > 0 => (childFenced /\
        parentPhase \in {"Pre", "WaitChild", "WaitActors", "Stopped"})

NoRestartAfterChildFence ==
    childFenced => childPhase # "Restarting"

AcceptedGenerationIsImmutable ==
    (parentPhase \in {"Pre", "WaitChild", "WaitActors", "Stopped"})
        => pinnedEpoch = childEpoch

Safety ==
    TypeOK /\ NoAckBeforeChildStop /\
    NoPrematureParentStop /\ NoUnfencedPublishedEpoch /\
    NoRestartAfterChildFence /\ AcceptedGenerationIsImmutable

=============================================================================