---------------------- MODULE SelectPlanGeneration ----------------------
EXTENDS Naturals, FiniteSets, TLC

(*
 * Bounded three-arm, three-generation semantics for a reusable FAIR select.
 * The immutable SelectPlan persists; a new registration is created each
 * iteration. A cancelled generation cannot consume messages or rotate the
 * shared cursor. The winner consults the *current* cursor at commit time.
 * This is a specification, not a proof of Java/LLVM refinement or fairness
 * of arbitrary producer/scheduler interleavings.
 *)
CONSTANTS Arms, MaxGenerations, HasDefault
ASSUME /\ Arms = {0, 1, 2}
       /\ MaxGenerations \in Nat
       /\ MaxGenerations > 0
       /\ HasDefault \in BOOLEAN

NoWinner == 3 \* numeric sentinel outside Arms; TLC membership stays type-homogeneous
NextArm(a) == IF a = 0 THEN 1 ELSE IF a = 1 THEN 2 ELSE 0
Preferred(rs, c) ==
    IF c \in rs THEN c
    ELSE IF NextArm(c) \in rs THEN NextArm(c)
    ELSE NextArm(NextArm(c))

VARIABLES generation, phase, cursor, startCursor, ready,
          registered, winner, consumed, cancelled
vars == <<generation, phase, cursor, startCursor, ready,
          registered, winner, consumed, cancelled>>

Init ==
    /\ generation = 0
    /\ phase = "New"
    /\ cursor = 0
    /\ startCursor = 0
    /\ ready = {}
    /\ registered = {}
    /\ winner = NoWinner
    /\ consumed = {}
    /\ cancelled = FALSE

Start ==
    /\ phase = "New"
    /\ generation < MaxGenerations
    /\ generation' = generation + 1
    /\ phase' = "Waiting"
    /\ startCursor' = cursor
    /\ ready' = {}
    /\ registered' = Arms
    /\ winner' = NoWinner
    /\ consumed' = {}
    /\ cancelled' = FALSE
    /\ UNCHANGED <<cursor>>

MakeReady(a) ==
    /\ phase = "Waiting"
    /\ a \in Arms
    /\ a \notin ready
    /\ ready' = ready \cup {a}
    /\ UNCHANGED <<generation, phase, cursor, startCursor,
                    registered, winner, consumed, cancelled>>

Commit ==
    /\ phase = "Waiting"
    /\ ready # {}
    /\ LET chosen == Preferred(ready, cursor) IN
        /\ phase' = "Committed"
        /\ winner' = chosen
        /\ consumed' = {chosen}
        /\ cursor' = NextArm(chosen)
    /\ registered' = {}
    /\ UNCHANGED <<generation, startCursor, ready, cancelled>>

DefaultSelect ==
    /\ HasDefault
    /\ phase = "Waiting"
    /\ ready = {}
    /\ phase' = "Defaulted"
    /\ registered' = {}
    /\ UNCHANGED <<generation, cursor, startCursor, ready,
                    winner, consumed, cancelled>>

Cancel ==
    /\ phase = "Waiting"
    /\ phase' = "Cancelled"
    /\ registered' = {}
    /\ cancelled' = TRUE
    /\ UNCHANGED <<generation, cursor, startCursor, ready,
                    winner, consumed>>

NextGeneration ==
    /\ phase \in {"Committed", "Cancelled", "Defaulted"}
    /\ generation < MaxGenerations
    /\ phase' = "New"
    /\ UNCHANGED <<generation, cursor, startCursor, ready,
                    registered, winner, consumed, cancelled>>

Finish ==
    /\ phase \in {"Committed", "Cancelled", "Defaulted"}
    /\ generation = MaxGenerations
    /\ phase' = "Done"
    /\ UNCHANGED <<generation, cursor, startCursor, ready,
                    registered, winner, consumed, cancelled>>

Next ==
    \/ Start
    \/ \E a \in Arms : MakeReady(a)
    \/ Commit
    \/ Cancel
    \/ DefaultSelect
    \/ NextGeneration
    \/ Finish
    \/ (phase = "Done" /\ UNCHANGED vars)

TypeOK ==
    /\ generation \in 0..MaxGenerations
    /\ phase \in {"New", "Waiting", "Committed", "Cancelled", "Defaulted", "Done"}
    /\ cursor \in Arms
    /\ startCursor \in Arms
    /\ ready \subseteq Arms
    /\ registered \subseteq Arms
    /\ (winner \in Arms \/ winner = NoWinner)
    /\ consumed \subseteq Arms
    /\ cancelled \in BOOLEAN

SingleWinner == Cardinality(consumed) <= 1
DetachedAfterSettlement == phase # "Waiting" => registered = {}
ExactlyOneWinner ==
    phase = "Committed" => consumed = {winner}
CancelledCannotConsume ==
    cancelled => /\ consumed = {} /\ winner = NoWinner
NoEarlyWinner ==
    phase = "Waiting" => /\ winner = NoWinner /\ consumed = {}
FairCursorRotatesOnWin ==
    phase = "Committed" => cursor = NextArm(winner)
CancellationPreservesCursor ==
    phase = "Cancelled" => cursor = startCursor
ActiveRegistration ==
    phase = "Waiting" => registered = Arms
DefaultPreservesCursor ==
    phase = "Defaulted" =>
        /\ cursor = startCursor
        /\ consumed = {}
        /\ winner = NoWinner


Spec == Init /\ [][Next]_vars
FairSpec == Spec /\ WF_vars(Commit)
ReadySelectionEventuallySettles ==
    (phase = "Waiting" /\ ready # {})
      ~> (phase \in {"Committed", "Cancelled", "Done"})
=============================================================================
