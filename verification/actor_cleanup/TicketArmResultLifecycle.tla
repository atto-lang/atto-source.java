---------------- MODULE TicketArmResultLifecycle ----------------
EXTENDS Naturals
CONSTANTS UnsafeAdmission, UnsafePublication
VARIABLES epoch, ticketEpoch, phase, attempted, reserved, badReservation,
          cancelled, remaining, cleanups, published, nextAdmitted
vars == <<epoch, ticketEpoch, phase, attempted, reserved, badReservation,
          cancelled, remaining, cleanups, published, nextAdmitted>>

Init ==
  /\ epoch = 1
  /\ ticketEpoch = 1
  /\ phase = "NEW"
  /\ attempted = FALSE
  /\ reserved = FALSE
  /\ badReservation = FALSE
  /\ cancelled = FALSE
  /\ remaining = 2
  /\ cleanups = 0
  /\ published = FALSE
  /\ nextAdmitted = FALSE

Await ==
  /\ phase = "NEW"
  /\ phase' = "WAIT"
  /\ UNCHANGED <<epoch, ticketEpoch, attempted, reserved, badReservation,
                 cancelled, remaining, cleanups, published, nextAdmitted>>

CompleteProducer ==
  /\ ~attempted /\ phase \in {"WAIT", "CLEANUP", "TERMINAL"}
  /\ LET authorized == phase = "WAIT" /\ ~cancelled /\ epoch = ticketEpoch
         admit == UnsafeAdmission \/ authorized
     IN /\ attempted' = TRUE
        /\ reserved' = IF admit THEN TRUE ELSE reserved
        /\ badReservation' = (badReservation \/ (admit /\ ~authorized))
        /\ phase' = IF admit /\ phase = "WAIT" THEN "READY" ELSE phase
  /\ UNCHANGED <<epoch, ticketEpoch, cancelled,
                 remaining, cleanups, published, nextAdmitted>>

Cancel ==
  /\ phase \in {"NEW", "WAIT", "READY"} /\ ~cancelled
  /\ cancelled' = TRUE
  /\ reserved' = FALSE
  /\ phase' = "CLEANUP"
  /\ UNCHANGED <<epoch, ticketEpoch, attempted, badReservation,
                 remaining, cleanups, published, nextAdmitted>>

BeginUnwind ==
  /\ phase = "READY" /\ reserved /\ ~cancelled
  /\ phase' = "CLEANUP"
  /\ UNCHANGED <<epoch, ticketEpoch, attempted, reserved, badReservation,
                 cancelled, remaining, cleanups, published, nextAdmitted>>

RunDefer ==
  /\ phase = "CLEANUP" /\ remaining > 0
  /\ remaining' = remaining - 1
  /\ cleanups' = cleanups + 1
  /\ UNCHANGED <<epoch, ticketEpoch, phase, attempted, reserved,
                 badReservation, cancelled, published, nextAdmitted>>

Finish ==
  /\ phase = "CLEANUP"
  /\ (remaining = 0 \/ UnsafePublication)
  /\ phase' = "TERMINAL"
  /\ published' = IF UnsafePublication THEN TRUE
                   ELSE (~cancelled /\ reserved /\ epoch = ticketEpoch)
  /\ reserved' = FALSE
  /\ UNCHANGED <<epoch, ticketEpoch, attempted, badReservation,
                 cancelled, remaining, cleanups, nextAdmitted>>

NextReceive ==
  /\ phase = "TERMINAL" /\ remaining = 0 /\ ~nextAdmitted
  /\ nextAdmitted' = TRUE
  /\ UNCHANGED <<epoch, ticketEpoch, phase, attempted, reserved,
                 badReservation, cancelled, remaining, cleanups, published>>

Restart ==
  /\ phase = "TERMINAL" /\ epoch = 1
  /\ epoch' = 2
  /\ UNCHANGED <<ticketEpoch, phase, attempted, reserved, badReservation,
                 cancelled, remaining, cleanups, published, nextAdmitted>>

Next == Await \/ CompleteProducer \/ Cancel \/ BeginUnwind
        \/ RunDefer \/ Finish \/ NextReceive \/ Restart
Spec == Init /\ [][Next]_vars
TypeOK ==
  /\ epoch \in 1..2
  /\ ticketEpoch = 1
  /\ phase \in {"NEW", "WAIT", "READY", "CLEANUP", "TERMINAL"}
  /\ attempted \in BOOLEAN /\ reserved \in BOOLEAN
  /\ badReservation \in BOOLEAN /\ cancelled \in BOOLEAN
  /\ remaining \in 0..2 /\ cleanups \in 0..2
  /\ published \in BOOLEAN /\ nextAdmitted \in BOOLEAN
CleanupExactlyOnce == cleanups + remaining = 2
NoForeignOrRetiredControlReservation == ~badReservation
NoEarlyOrCancelledArmResult == published => remaining = 0 /\ ~cancelled
NoNextBeforeCleanup == nextAdmitted => remaining = 0
===============================================================
