---- MODULE DurableWorkflowV1 ----
EXTENDS Naturals, Integers, TLC

(*
Independent finite TLA+ model for the durable workflow effect/delivery
protocol.  The Java implementation is a refinement target, not an authority
for this specification.

One external effect is sufficient to model the dangerous execution window:
a claim may execute externally, the process may die, and the durable receipt
may still be missing.  Generation numbers prevent stale claim authority from
being confused with a later retry.
*)

CONSTANT MaxAttempts
ASSUME MaxAttempts \in Nat /\ MaxAttempts > 0

Phases == {"ACTIVE", "TERMINAL"}
EffectStates == {"PENDING", "CLAIMED", "AMBIGUOUS", "COMPLETED", "ABANDONED"}
DeliveryStates == {"NONE", "RETRYABLE_REJECTED", "SEALED"}

VARIABLES
    phase,
    effect,
    retryAllowed,
    attempts,
    delivery,
    generation,
    claimGeneration,
    settledGeneration,
    settlementCount,
    revocationSeen

vars ==
    << phase, effect, retryAllowed, attempts, delivery, generation,
       claimGeneration, settledGeneration, settlementCount, revocationSeen >>

Init ==
    /\ phase = "ACTIVE"
    /\ effect = "PENDING"
    /\ retryAllowed = TRUE
    /\ attempts = 0
    /\ delivery = "NONE"
    /\ generation = 0
    /\ claimGeneration = 0
    /\ settledGeneration = 0
    /\ settlementCount = 0
    /\ revocationSeen = FALSE

Claim ==
    /\ phase = "ACTIVE"
    /\ effect = "PENDING"
    /\ retryAllowed
    /\ attempts < MaxAttempts
    /\ effect' = "CLAIMED"
    /\ attempts' = attempts + 1
    /\ generation' = generation + 1
    /\ claimGeneration' = generation + 1
    /\ UNCHANGED << phase, retryAllowed, delivery,
                     settledGeneration, settlementCount, revocationSeen >>

CompleteLiveClaim ==
    /\ effect = "CLAIMED"
    /\ effect' = "COMPLETED"
    /\ retryAllowed' = FALSE
    /\ settledGeneration' = claimGeneration
    /\ settlementCount' = settlementCount + 1
    /\ claimGeneration' = 0
    /\ revocationSeen' = TRUE
    /\ UNCHANGED << phase, attempts, delivery, generation >>

ExpireClaim ==
    /\ effect = "CLAIMED"
    /\ effect' = "AMBIGUOUS"
    /\ UNCHANGED << phase, retryAllowed, attempts, delivery,
                     generation, claimGeneration, settledGeneration,
                     settlementCount, revocationSeen >>

ReconcileSafeToRetry ==
    /\ effect = "AMBIGUOUS"
    /\ effect' = IF retryAllowed THEN "PENDING" ELSE "ABANDONED"
    /\ claimGeneration' = 0
    /\ UNCHANGED << phase, retryAllowed, attempts, delivery,
                     generation, settledGeneration, settlementCount,
                     revocationSeen >>

ReconcileCompleted ==
    /\ effect = "AMBIGUOUS"
    /\ effect' = "COMPLETED"
    /\ retryAllowed' = FALSE
    /\ settledGeneration' = claimGeneration
    /\ settlementCount' = settlementCount + 1
    /\ claimGeneration' = 0
    /\ revocationSeen' = TRUE
    /\ UNCHANGED << phase, attempts, delivery, generation >>

EarlyReject ==
    /\ delivery = "NONE"
    /\ delivery' = "RETRYABLE_REJECTED"
    /\ UNCHANGED << phase, effect, retryAllowed, attempts, generation,
                     claimGeneration, settledGeneration, settlementCount,
                     revocationSeen >>

CausalOutcome ==
    /\ phase = "ACTIVE"
    /\ delivery # "SEALED"
    /\ effect' = IF effect = "PENDING" THEN "ABANDONED" ELSE effect
    /\ retryAllowed' = FALSE
    /\ delivery' = "SEALED"
    /\ revocationSeen' = TRUE
    /\ UNCHANGED << phase, attempts, generation, claimGeneration,
                     settledGeneration, settlementCount >>

RetryRejectedAsOutcome ==
    /\ phase = "ACTIVE"
    /\ delivery = "RETRYABLE_REJECTED"
    /\ effect' = IF effect = "PENDING" THEN "ABANDONED" ELSE effect
    /\ retryAllowed' = FALSE
    /\ delivery' = "SEALED"
    /\ revocationSeen' = TRUE
    /\ UNCHANGED << phase, attempts, generation, claimGeneration,
                     settledGeneration, settlementCount >>

ExactDuplicate ==
    /\ delivery = "SEALED"
    /\ UNCHANGED vars

ConflictingReuse ==
    /\ delivery # "NONE"
    /\ UNCHANGED vars

Cancel ==
    /\ phase = "ACTIVE"
    /\ phase' = "TERMINAL"
    /\ effect' = IF effect = "PENDING" THEN "ABANDONED" ELSE effect
    /\ retryAllowed' = FALSE
    /\ revocationSeen' = TRUE
    /\ UNCHANGED << attempts, delivery, generation, claimGeneration,
                     settledGeneration, settlementCount >>

Next ==
    \/ Claim
    \/ CompleteLiveClaim
    \/ ExpireClaim
    \/ ReconcileSafeToRetry
    \/ ReconcileCompleted
    \/ EarlyReject
    \/ CausalOutcome
    \/ RetryRejectedAsOutcome
    \/ ExactDuplicate
    \/ ConflictingReuse
    \/ Cancel

Spec == Init /\ [][Next]_vars

TypeOK ==
    /\ phase \in Phases
    /\ effect \in EffectStates
    /\ retryAllowed \in BOOLEAN
    /\ attempts \in 0..MaxAttempts
    /\ delivery \in DeliveryStates
    /\ generation \in Nat
    /\ claimGeneration \in Nat
    /\ settledGeneration \in Nat
    /\ settlementCount \in Nat
    /\ revocationSeen \in BOOLEAN

TerminalIsMonotonic ==
    phase = "TERMINAL" => ~retryAllowed

TerminalHasNoRunnableEffect ==
    phase = "TERMINAL" => ~(effect = "PENDING" /\ retryAllowed)

SettlementAtMostOnce ==
    settlementCount <= 1

ClaimAuthorityMatchesGeneration ==
    IF effect \in {"CLAIMED", "AMBIGUOUS"}
    THEN claimGeneration = generation /\ claimGeneration > 0
    ELSE claimGeneration = 0

CompletedHasSettledGeneration ==
    effect = "COMPLETED" => settledGeneration > 0

TerminalEffectCannotRetry ==
    effect \in {"COMPLETED", "ABANDONED"} => ~retryAllowed

RevokedAuthorityNeverRequeues ==
    revocationSeen => effect # "PENDING"

=============================================================================
