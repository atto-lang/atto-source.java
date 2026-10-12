------------------------ MODULE ChannelDrainWakeup ------------------------
EXTENDS Naturals, Sequences, TLC

(*
 * Bounded one-slot channel with one pending writer. Draining the committed
 * buffered value MUST atomically re-arbitrate and refill available capacity
 * from the waiting writer before exposing a stuck empty channel.
 *
 * Model separately distinguishes producer commit from its Future completion.
 * This models the channel admission boundary, not OS wake delivery or full
 * source-level actor continuation refinement.
 *)
VARIABLES slot, writer, queued, delivered, closed, completionPending
vars == <<slot, writer, queued, delivered, closed, completionPending>>

Init ==
    /\ slot = 1
    /\ writer = "New"
    /\ queued = FALSE
    /\ delivered = <<>>
    /\ closed = FALSE
    /\ completionPending = FALSE

Enqueue ==
    /\ writer = "New"
    /\ ~closed
    /\ slot = 1
    /\ writer' = "Pending"
    /\ queued' = TRUE
    /\ UNCHANGED <<slot, delivered, closed, completionPending>>

Drain ==
    /\ slot # 0
    /\ delivered' = Append(delivered, slot)
    /\ slot' = IF queued /\ writer = "Pending" /\ ~closed THEN 2 ELSE 0
    /\ writer' = IF queued /\ writer = "Pending" /\ ~closed THEN "Committed" ELSE writer
    /\ queued' = FALSE
    /\ completionPending' = IF queued /\ writer = "Pending" /\ ~closed
                            THEN TRUE ELSE completionPending
    /\ UNCHANGED closed

AckCompletion ==
    /\ writer = "Committed"
    /\ completionPending
    /\ writer' = "Settled"
    /\ completionPending' = FALSE
    /\ UNCHANGED <<slot, queued, delivered, closed>>

Cancel ==
    /\ writer = "Pending"
    /\ writer' = "Cancelled"
    /\ queued' = FALSE
    /\ UNCHANGED <<slot, delivered, closed, completionPending>>

Close ==
    /\ ~closed
    /\ closed' = TRUE
    /\ writer' = IF writer = "Pending" THEN "Failed" ELSE writer
    /\ queued' = FALSE
    /\ UNCHANGED <<slot, delivered, completionPending>>

(* Intentionally unsafe transition: missing re-arbitration after drain. *)
UnsafeDrain ==
    /\ slot # 0
    /\ delivered' = Append(delivered, slot)
    /\ slot' = 0
    /\ UNCHANGED <<writer, queued, closed, completionPending>>

TerminalStutter ==
    /\ slot = 0
    /\ writer # "Pending"
    /\ UNCHANGED vars

Next == Enqueue \/ Drain \/ AckCompletion \/ Cancel \/ Close \/ TerminalStutter
UnsafeNext == Enqueue \/ UnsafeDrain \/ AckCompletion \/ Cancel \/ Close \/ TerminalStutter

TypeOK ==
    /\ slot \in {0, 1, 2}
    /\ writer \in {"New", "Pending", "Committed", "Settled", "Cancelled", "Failed"}
    /\ queued \in BOOLEAN
    /\ delivered \in Seq({1, 2})
    /\ Len(delivered) <= 2
    /\ closed \in BOOLEAN
    /\ completionPending \in BOOLEAN

NoStrandedWriter ==
    writer = "Pending" => /\ queued /\ slot = 1 /\ ~closed
NoDoubleDelivery ==
    delivered \in {<<>>, <<1>>, <<1, 2>>}
CommittedExactlyOnce ==
    writer \in {"Committed", "Settled"} =>
        /\ ~queued
        /\ delivered[1] = 1
        /\ Len(delivered) >= 1
NoOrphanCompletion ==
    completionPending => writer = "Committed"
NoStaleWaiter ==
    writer \in {"Settled", "Cancelled", "Failed"} => ~queued
ReadyWriterEventuallyFinishes ==
    writer = "Pending" ~> writer \in {"Settled", "Cancelled", "Failed"}

Spec == Init /\ [][Next]_vars
FairSpec == Spec /\ WF_vars(Drain) /\ WF_vars(AckCompletion)
UnsafeSpec == Init /\ [][UnsafeNext]_vars
=============================================================================
