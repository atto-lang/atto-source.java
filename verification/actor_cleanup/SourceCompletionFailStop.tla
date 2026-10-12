---------------- MODULE SourceCompletionFailStop ----------------
EXTENDS Naturals
CONSTANTS UnsafeEarlyRelease, UnsafeNestedFailure, UnsafeLatePoison

VARIABLES actor, receive, completion, failed, callbackUnderLock,
          latePoison, nextUserMail
vars == <<actor, receive, completion, failed, callbackUnderLock,
          latePoison, nextUserMail>>

Init ==
  /\ actor = "LIVE"
  /\ receive = "SUSPENDED"
  /\ completion = "PENDING"
  /\ failed = FALSE
  /\ callbackUnderLock = FALSE
  /\ latePoison = FALSE
  /\ nextUserMail = FALSE

RejectCompletion ==
  /\ actor = "LIVE"
  /\ completion = "PENDING"
  /\ completion' = "REJECTED"
  /\ receive' = IF UnsafeEarlyRelease THEN "OPEN" ELSE receive
  /\ UNCHANGED <<actor, failed, callbackUnderLock, latePoison, nextUserMail>>

SuccessfulCompletion ==
  /\ actor = "LIVE"
  /\ completion = "PENDING"
  /\ completion' = "COMPLETED"
  /\ receive' = "OPEN"
  /\ UNCHANGED <<actor, failed, callbackUnderLock, latePoison, nextUserMail>>

OrdinaryStop ==
  /\ actor = "LIVE"
  /\ actor' = "STOPPED"
  /\ receive' = "DRAINED"
  /\ UNCHANGED <<completion, failed, callbackUnderLock, latePoison, nextUserMail>>

FailIfRunning ==
  /\ actor = "LIVE"
  /\ completion = "REJECTED"
  /\ actor' = "STOPPED"
  /\ receive' = "DRAINED"
  /\ failed' = TRUE
  /\ callbackUnderLock' = UnsafeNestedFailure
  /\ UNCHANGED <<completion, latePoison, nextUserMail>>

LateRejection ==
  /\ actor = "STOPPED"
  /\ completion = "REJECTED"
  /\ ~latePoison
  /\ latePoison' = UnsafeLatePoison
  /\ failed' = IF UnsafeLatePoison THEN TRUE ELSE failed
  /\ completion' = "SETTLED"
  /\ UNCHANGED <<actor, receive, callbackUnderLock, nextUserMail>>

AdmitNextMessage ==
  /\ actor = "LIVE"
  /\ receive = "OPEN"
  /\ ~nextUserMail
  /\ nextUserMail' = TRUE
  /\ UNCHANGED <<actor, receive, completion, failed,
                 callbackUnderLock, latePoison>>

Next == RejectCompletion \/ SuccessfulCompletion \/ OrdinaryStop
        \/ FailIfRunning \/ LateRejection \/ AdmitNextMessage
Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ actor \in {"LIVE", "STOPPED"}
  /\ receive \in {"SUSPENDED", "OPEN", "DRAINED"}
  /\ completion \in {"PENDING", "REJECTED", "COMPLETED", "SETTLED"}
  /\ failed \in BOOLEAN
  /\ callbackUnderLock \in BOOLEAN
  /\ latePoison \in BOOLEAN
  /\ nextUserMail \in BOOLEAN

NoEarlyUserMail ==
  nextUserMail => completion = "COMPLETED"
NoCallbackCancellationUnderCallerLock ==
  ~callbackUnderLock
NoLatePoison ==
  ~latePoison
====================================================================
