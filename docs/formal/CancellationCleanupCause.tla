-------------------- MODULE CancellationCleanupCause --------------------
EXTENDS Naturals

\* Models the observable termination receipt when a scheduler source task
\* has registered cleanup, but actor STOP/CANCEL cannot execute it.
\* The primary cancellation cause must remain intact and the missing cleanup
\* must still be discoverable as a single supplemental diagnostic receipt.
\* This is a safety model, NOT a proof of guest cleanup execution.
CONSTANT UnsafeDropIncompleteSuppressedReceipt
VARIABLES cleanup, cause, finalCause, receipt
vars == <<cleanup, cause, finalCause, receipt>>

Init ==
  /\ cleanup = "absent"
  /\ cause = "none"
  /\ finalCause = "pending"
  /\ receipt = "none"

RegisterCleanup ==
  /\ finalCause = "pending"
  /\ cleanup = "absent"
  /\ cleanup' = "registered"
  /\ UNCHANGED <<cause, finalCause, receipt>>

Cancel ==
  /\ finalCause = "pending"
  /\ cause = "none"
  /\ cause' = "cancel"
  /\ UNCHANGED <<cleanup, finalCause, receipt>>

Finalize ==
  /\ finalCause = "pending"
  /\ finalCause' = IF cause = "cancel" THEN "cancel"
                   ELSE IF cleanup = "registered" THEN "incomplete"
                   ELSE "success"
  /\ receipt' = IF cleanup = "absent" THEN "none"
                ELSE IF cause = "cancel" THEN
                       IF UnsafeDropIncompleteSuppressedReceipt
                       THEN "none" ELSE "suppressed"
                ELSE "primary"
  /\ UNCHANGED <<cleanup, cause>>

Next == RegisterCleanup \/ Cancel \/ Finalize
Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ cleanup \in {"absent", "registered"}
  /\ cause \in {"none", "cancel"}
  /\ finalCause \in {"pending", "cancel", "incomplete", "success"}
  /\ receipt \in {"none", "primary", "suppressed"}

PreserveCancelCause ==
  (cause = "cancel" /\ finalCause # "pending") => finalCause = "cancel"

NeverLoseIncompleteCleanupReceipt ==
  (cleanup = "registered" /\ finalCause # "pending") =>
    receipt \in {"primary", "suppressed"}

ReceiptCorrelatesWithPrimary ==
  finalCause # "pending" =>
    (IF cleanup = "absent" THEN receipt = "none"
     ELSE IF finalCause = "cancel" THEN receipt = "suppressed"
     ELSE receipt = "primary")
=============================================================================
