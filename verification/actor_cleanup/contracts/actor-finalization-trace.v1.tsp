namespace Oreslang.ActorLifecycle;

/**
 * Host-owned trace semantics. These are observation records, not bearer
 * capabilities. Never place live scheduler tickets or guest secrets here.
 */
enum FinalizationEvent {
  stopCommitted: "stop_committed",
  shutdownEffectsStarted: "shutdown_effects_started",
  shutdownEffectsSettled: "shutdown_effects_settled",
  carrierQuiesced: "carrier_quiesced",
  childPhysicalBarrier: "child_physical_barrier",
  finalizationClaimed: "finalization_claimed",
  schedulerClosed: "scheduler_closed",
  resourcesReleased: "resources_released",
  physicalTerminationPublished: "physical_termination_published"
}

alias TerminationCause = "graceful" | "cancelled" | "failed";

model FinalizationTraceEvent {
  schemaVersion: "1.0";
  actorId: string;
  incarnation: string;
  sequence: int64;
  event: FinalizationEvent;
  parentActorId?: string;
  terminationCause?: TerminationCause;
  pendingChildren?: int32;
  pendingShutdownEffects?: boolean;
  carrierActive?: boolean;
  finalizationEpoch?: int64;
  taskTicketId?: int64;
}
