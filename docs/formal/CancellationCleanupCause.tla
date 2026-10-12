-------------------- MODULE CancellationCleanupCause --------------------
EXTENDS Naturals

\* Observable cancellation diagnostics for an abandoned source cleanup.
\* Suppression capability is nondeterministic: Java Throwable may silently
\* discard addSuppressed(), in which case the incomplete receipt must become
\* primary and preserve the original cancellation as its nested cause.
\* This is a diagnostic safety model, NOT guest cleanup execution.
CONSTANT UnsafeDropIncompleteSuppressedReceipt
VARIABLES cleanup, cause, finalCause, receipt, suppressionEnabled, retainsOriginal
vars == <<cleanup, cause, finalCause, receipt, suppressionEnabled, retainsOriginal>>

Init ==
  /\ cleanup = "absent"
  /\ cause = "none"
  /\ finalCause = "pending"
  /\ receipt = "none"
  /\ suppressionEnabled \in BOOLEAN
  /\ retainsOriginal = FALSE

RegisterCleanup ==
  /\ finalCause = "pending"
  /\ cleanup = "absent"
  /\ cleanup' = "registered"
  /\ UNCHANGED <<cause, finalCause, receipt, suppressionEnabled, retainsOriginal>>

Cancel ==
  /\ finalCause = "pending"
  /\ cause = "none"
  /\ cause' = "cancel"
  /\ UNCHANGED <<cleanup, finalCause, receipt, suppressionEnabled, retainsOriginal>>

Finalize ==
  /\ finalCause = "pending"
  /\ finalCause' = IF cause = "cancel"
                   THEN IF cleanup = "registered" /\ ~suppressionEnabled
                        THEN "incomplete" ELSE "cancel"
                   ELSE IF cleanup = "registered" THEN "incomplete"
                   ELSE "success"
  /\ receipt' = IF cleanup = "absent" THEN "none"
                ELSE IF cause = "cancel" /\ suppressionEnabled THEN
                       IF UnsafeDropIncompleteSuppressedReceipt
                       THEN "none" ELSE "suppressed"
                ELSE "primary"
  /\ retainsOriginal' = cause = "cancel" /\ cleanup = "registered"
                        /\ ~suppressionEnabled
  /\ UNCHANGED <<cleanup, cause, suppressionEnabled>>

Next == RegisterCleanup \/ Cancel \/ Finalize
Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ cleanup \in {"absent", "registered"}
  /\ cause \in {"none", "cancel"}
  /\ finalCause \in {"pending", "cancel", "incomplete", "success"}
  /\ receipt \in {"none", "primary", "suppressed"}
  /\ suppressionEnabled \in BOOLEAN
  /\ retainsOriginal \in BOOLEAN

PreserveCancelCause ==
  (cause = "cancel" /\ finalCause # "pending") =>
    (finalCause = "cancel" \/
      (finalCause = "incomplete" /\ retainsOriginal))

NeverLoseIncompleteCleanupReceipt ==
  (cleanup = "registered" /\ finalCause # "pending") =>
    receipt \in {"primary", "suppressed"}

ReceiptCorrelatesWithPrimary ==
  finalCause # "pending" =>
    (IF cleanup = "absent" THEN receipt = "none"
     ELSE IF finalCause = "cancel" THEN receipt = "suppressed"
     ELSE receipt = "primary")
=============================================================================
