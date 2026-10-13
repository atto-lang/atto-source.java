------------------- MODULE OwnershipCarrierHandoff -------------------
EXTENDS Naturals
CONSTANTS FuelLimit, Budget
ASSUME /\ FuelLimit \in Nat
       /\ Budget \in Nat
       /\ Budget > 0
       /\ FuelLimit >= Budget

(* This is one actor's *physical* execution lane. Other actors are not
   represented. It checks the suspension admission barrier and monotonic
   accounting, not a complete language borrow checker or a scheduler liveness
   theorem. *)
LoanKinds == {"Read", "Write", "State", "Native"}
Phases == {"Running", "Handoff", "Ready", "StopPending", "Stopped"}

VARIABLES phase, loan, carrier, fuel, sinceYield, resumes, effects
vars == <<phase, loan, carrier, fuel, sinceYield, resumes, effects>>

Init ==
    /\ phase = "Running"
    /\ loan = "None"
    /\ carrier = TRUE
    /\ fuel = 0
    /\ sinceYield = 0
    /\ resumes = 0
    /\ effects = 0

Borrow(kind) ==
    /\ phase = "Running"
    /\ loan = "None"
    /\ kind \in LoanKinds
    /\ loan' = kind
    /\ UNCHANGED <<phase, carrier, fuel, sinceYield, resumes, effects>>

Release ==
    /\ phase = "Running"
    /\ loan \in LoanKinds
    /\ loan' = "None"
    /\ UNCHANGED <<phase, carrier, fuel, sinceYield, resumes, effects>>

Reduction ==
    /\ phase = "Running"
    /\ fuel < FuelLimit
    /\ fuel' = fuel + 1
    /\ sinceYield' = IF sinceYield < Budget THEN sinceYield + 1 ELSE sinceYield
    /\ UNCHANGED <<phase, loan, carrier, resumes, effects>>

Effect ==
    /\ phase = "Running"
    /\ effects = 0
    /\ effects' = 1
    /\ UNCHANGED <<phase, loan, carrier, fuel, sinceYield, resumes>>

(* The scheduler MUST NOT interpret budget exhaustion as permission to
   publish a continuation with any outstanding live loan. *)
Yield ==
    /\ phase = "Running"
    /\ carrier
    /\ loan = "None"
    /\ sinceYield = Budget
    /\ resumes = 0
    /\ phase' = "Handoff"
    /\ UNCHANGED <<loan, carrier, fuel, sinceYield, resumes, effects>>

ExitCarrier ==
    /\ phase = "Handoff"
    /\ carrier
    /\ phase' = "Ready"
    /\ carrier' = FALSE
    /\ UNCHANGED <<loan, fuel, sinceYield, resumes, effects>>

Resume ==
    /\ phase = "Ready"
    /\ ~carrier
    /\ resumes = 0
    /\ phase' = "Running"
    /\ carrier' = TRUE
    /\ resumes' = 1
    /\ sinceYield' = 0
    /\ UNCHANGED <<loan, fuel, effects>>

(* Cancellation does not revoke a carrier or silently drop its active loan.
   The terminating physical turn must retire resources before DONE. *)
StopActive ==
    /\ phase \in {"Running", "Handoff"}
    /\ carrier
    /\ phase' = "StopPending"
    /\ UNCHANGED <<loan, carrier, fuel, sinceYield, resumes, effects>>

RetireStopLoan ==
    /\ phase = "StopPending"
    /\ loan \in LoanKinds
    /\ loan' = "None"
    /\ UNCHANGED <<phase, carrier, fuel, sinceYield, resumes, effects>>

ExitStoppedCarrier ==
    /\ phase = "StopPending"
    /\ carrier
    /\ loan = "None"
    /\ phase' = "Stopped"
    /\ carrier' = FALSE
    /\ UNCHANGED <<loan, fuel, sinceYield, resumes, effects>>

StopReady ==
    /\ phase = "Ready"
    /\ ~carrier
    /\ phase' = "Stopped"
    /\ UNCHANGED <<loan, carrier, fuel, sinceYield, resumes, effects>>

OtherNext ==
    \/ \E kind \in LoanKinds : Borrow(kind)
    \/ Release
    \/ Reduction
    \/ Effect
    \/ ExitCarrier
    \/ Resume
    \/ StopActive
    \/ RetireStopLoan
    \/ ExitStoppedCarrier
    \/ StopReady

Next == OtherNext \/ Yield
Spec == Init /\ [][Next]_vars

TypeOK ==
    /\ phase \in Phases
    /\ loan \in LoanKinds \cup {"None"}
    /\ carrier \in BOOLEAN
    /\ fuel \in 0..FuelLimit
    /\ sinceYield \in 0..Budget
    /\ resumes \in 0..1
    /\ effects \in 0..1

LoanSafeAtHandoff ==
    (phase \in {"Handoff", "Ready", "Stopped"}) => loan = "None"

CarrierOwnership ==
    carrier <=> (phase \in {"Running", "Handoff", "StopPending"})

FuelContinuity ==
    (resumes = 1) => (fuel >= Budget)

NoSideEffectReplay == effects <= 1

(* Mutation deliberately removes the loan-free yield admission. TLC must
   find a counterexample to LoanSafeAtHandoff with UnsafeSpec. *)
UnsafeYield ==
    /\ phase = "Running"
    /\ carrier
    /\ sinceYield = Budget
    /\ resumes = 0
    /\ phase' = "Handoff"
    /\ UNCHANGED <<loan, carrier, fuel, sinceYield, resumes, effects>>
UnsafeNext == OtherNext \/ UnsafeYield
UnsafeSpec == Init /\ [][UnsafeNext]_vars

(* Deliberately unsafe model of double-publication of an external effect.
   This is separate from the loan-handoff mutation, so each counterexample
   identifies one invariant and one bug class. TypeOK is intentionally
   omitted from the replay mutation's config: effects = 2 is expected to
   violate the exactly-once bound and must be reported by that invariant. *)
ReplayEffect ==
    /\ phase = "Running"
    /\ effects < 2
    /\ effects' = effects + 1
    /\ UNCHANGED <<phase, loan, carrier, fuel, sinceYield, resumes>>
ReplayNext == (OtherNext \/ Yield) \/ ReplayEffect
ReplaySpec == Init /\ [][ReplayNext]_vars

=============================================================
