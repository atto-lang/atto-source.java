------------------- MODULE ActorTwoPhaseFinalization -------------------
EXTENDS Naturals
CONSTANTS UnsafeEarlyPublish, UnsafeCallbackUnderLock,
          UnsafeDoubleClaim, UnsafeSkipChildBarrier,
          UnsafeChannelCloseUnderLock
VARIABLES phase, turnActive, stopEffects, childPending,
          claims, channelsClosed, schedulerClosed, resourcesDone,
          published, callbackUnderLock
vars == <<phase, turnActive, stopEffects, childPending,
          claims, channelsClosed, schedulerClosed, resourcesDone,
          published, callbackUnderLock>>

Init ==
  /\ phase = "RUNNING"
  /\ turnActive = TRUE
  /\ stopEffects = FALSE
  /\ childPending = TRUE
  /\ claims = 0
  /\ channelsClosed = FALSE
  /\ schedulerClosed = FALSE
  /\ resourcesDone = FALSE
  /\ published = FALSE
  /\ callbackUnderLock = FALSE

Stop ==
  /\ phase = "RUNNING"
  /\ phase' = "STOPPING"
  /\ stopEffects' = TRUE
  /\ UNCHANGED <<turnActive, childPending, claims, channelsClosed, schedulerClosed,
                 resourcesDone, published, callbackUnderLock>>
CarrierLeaves ==
  /\ phase # "RUNNING" /\ turnActive
  /\ turnActive' = FALSE
  /\ UNCHANGED <<phase, stopEffects, childPending, claims, channelsClosed, schedulerClosed,
                 resourcesDone, published, callbackUnderLock>>
CloseChannels ==
  /\ phase = "STOPPING" /\ stopEffects /\ ~channelsClosed
  /\ channelsClosed' = TRUE
  /\ callbackUnderLock' = UnsafeChannelCloseUnderLock
  /\ UNCHANGED <<phase, turnActive, stopEffects, childPending,
                 claims, schedulerClosed, resourcesDone, published>>

CancelPendingEffects ==
  /\ phase = "STOPPING" /\ stopEffects /\ channelsClosed
  /\ stopEffects' = FALSE
  /\ UNCHANGED <<phase, turnActive, childPending, claims,
                 channelsClosed, schedulerClosed, resourcesDone, published, callbackUnderLock>>
ChildStops ==
  /\ phase # "RUNNING" /\ childPending
  /\ childPending' = FALSE
  /\ UNCHANGED <<phase, turnActive, stopEffects, claims,
                 channelsClosed, schedulerClosed, resourcesDone, published, callbackUnderLock>>
ClaimFinalization ==
  /\ (phase = "STOPPING" \/
         (UnsafeDoubleClaim /\ phase = "CLAIMED"))
  /\ ~turnActive /\ ~stopEffects
  /\ (~childPending \/ UnsafeSkipChildBarrier)
  /\ claims' = claims + 1
  /\ phase' = "CLAIMED"
  /\ UNCHANGED <<turnActive, stopEffects, childPending,
                 channelsClosed, schedulerClosed, resourcesDone, published, callbackUnderLock>>
CloseScheduler ==
  /\ phase = "CLAIMED" /\ ~schedulerClosed
  /\ schedulerClosed' = TRUE
  /\ callbackUnderLock' = UnsafeCallbackUnderLock
  /\ UNCHANGED <<phase, turnActive, stopEffects, childPending, claims,
                 channelsClosed, resourcesDone, published>>
CleanupResources ==
  /\ phase = "CLAIMED" /\ schedulerClosed /\ ~resourcesDone
  /\ resourcesDone' = TRUE
  /\ UNCHANGED <<phase, turnActive, stopEffects, childPending, claims,
                 channelsClosed, schedulerClosed, published, callbackUnderLock>>
PublishTermination ==
  /\ phase = "CLAIMED"
  /\ (resourcesDone \/ UnsafeEarlyPublish)
  /\ phase' = "TERMINAL"
  /\ published' = TRUE
  /\ UNCHANGED <<turnActive, stopEffects, childPending, claims,
                 channelsClosed, schedulerClosed, resourcesDone, callbackUnderLock>>

Next == Stop \/ CarrierLeaves \/ CloseChannels \/ CancelPendingEffects \/ ChildStops
        \/ ClaimFinalization \/ CloseScheduler \/ CleanupResources
        \/ PublishTermination
Spec == Init /\ [][Next]_vars
TypeOK ==
  /\ phase \in {"RUNNING", "STOPPING", "CLAIMED", "TERMINAL"}
  /\ turnActive \in BOOLEAN
  /\ stopEffects \in BOOLEAN
  /\ childPending \in BOOLEAN
  /\ claims \in Nat /\ claims <= 2
  /\ channelsClosed \in BOOLEAN
  /\ schedulerClosed \in BOOLEAN
  /\ resourcesDone \in BOOLEAN
  /\ published \in BOOLEAN
  /\ callbackUnderLock \in BOOLEAN

OneFinalizationOwner == claims <= 1
NoPhysicalPublicationBeforeCleanup ==
  published => resourcesDone /\ schedulerClosed /\ channelsClosed /\ ~stopEffects
              /\ ~turnActive /\ ~childPending
NoCallbacksUnderLifecycleMonitor == ~callbackUnderLock
NoClaimWhileChildAlive ==
  claims > 0 => ~childPending
=======================================================================
