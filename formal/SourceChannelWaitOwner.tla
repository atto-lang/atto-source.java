--------------------- MODULE SourceChannelWaitOwner ---------------------
EXTENDS Naturals, TLC

(*
 * Bounded safety/liveness model for the SOURCE-OWNED channel wait slot.
 * Host cancellation fences a pending select producer registration; general
 * externally-owned Futures are deliberately outside this model. A select
 * event that committed BEFORE cancellation is permitted to have consumed
 * a value. A cancellation that won first may not consume any later value.
 * A lexical-cleanup select remains admitted until its own event resolves.
 *)
VARIABLES status, cancelled, cleanup, hasMessage, consumed,
          cancelledBeforeWin
vars == <<status, cancelled, cleanup, hasMessage, consumed,
          cancelledBeforeWin>>

Init ==
    /\ status = "Idle"
    /\ cancelled = FALSE
    /\ cleanup = FALSE
    /\ hasMessage = FALSE
    /\ consumed = FALSE
    /\ cancelledBeforeWin = FALSE

Install(isCleanup) ==
    /\ status = "Idle"
    /\ isCleanup \in BOOLEAN
    /\ cleanup' = isCleanup
    /\ status' = IF cancelled /\ ~isCleanup THEN "Detached" ELSE "Armed"
    /\ UNCHANGED <<cancelled, hasMessage, consumed, cancelledBeforeWin>>

Cancel ==
    /\ ~cancelled
    /\ cancelled' = TRUE
    /\ cancelledBeforeWin' = ~cleanup /\ ~consumed
    /\ status' = IF status = "Armed" /\ ~cleanup THEN "Detached"
                 ELSE status
    /\ UNCHANGED <<cleanup, hasMessage, consumed>>

(* This transition deliberately omits producer-side revocation. *)
UnsafeCancel ==
    /\ ~cancelled
    /\ cancelled' = TRUE
    /\ cancelledBeforeWin' = ~cleanup /\ ~consumed
    /\ UNCHANGED <<status, cleanup, hasMessage, consumed>>

Send ==
    /\ ~hasMessage
    /\ hasMessage' = TRUE
    /\ UNCHANGED <<status, cancelled, cleanup, consumed,
                    cancelledBeforeWin>>

Commit ==
    /\ status = "Armed"
    /\ hasMessage
    /\ status' = "Won"
    /\ consumed' = TRUE
    /\ UNCHANGED <<cancelled, cleanup, hasMessage, cancelledBeforeWin>>

TerminalStutter ==
    /\ status \in {"Won", "Detached"}
    /\ UNCHANGED vars

Next ==
    \/ \E x \in BOOLEAN : Install(x)
    \/ Cancel
    \/ Send
    \/ Commit
    \/ TerminalStutter

UnsafeNext ==
    \/ \E x \in BOOLEAN : Install(x)
    \/ UnsafeCancel
    \/ Send
    \/ Commit
    \/ TerminalStutter

TypeOK ==
    /\ status \in {"Idle", "Armed", "Detached", "Won"}
    /\ cancelled \in BOOLEAN
    /\ cleanup \in BOOLEAN
    /\ hasMessage \in BOOLEAN
    /\ consumed \in BOOLEAN
    /\ cancelledBeforeWin \in BOOLEAN

NoLateSteal ==
    cancelledBeforeWin => ~consumed
NoUnrevokedCancelledWait ==
    (cancelledBeforeWin /\ ~cleanup) => status # "Armed"
OneShotCommit ==
    consumed => status = "Won"
CleanupProgress ==
    (cleanup /\ status = "Armed" /\ hasMessage)
        ~> (status = "Won")

Spec == Init /\ [][Next]_vars
FairSpec == Spec /\ WF_vars(Commit)
UnsafeSpec == Init /\ [][UnsafeNext]_vars
=============================================================================
