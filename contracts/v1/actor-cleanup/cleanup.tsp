/** Source actor cleanup transition contract: diagnostics and cross-backend telemetry. */
namespace OresActorCleanup {
  enum Phase {
    running,
    cleanup_requested,
    cleanup_pending,
    terminal
  }
  enum TicketRole {
    source_task,
    source_completion
  }
  enum Outcome {
    pending,
    success,
    incomplete,
    failed
  }
  model Transition {
    version: "1";
    actor_id: string;
    incarnation: string;
    source_task_id: int64;
    receive_generation: int64;
    phase: Phase;
    ticket_role: TicketRole;
    outcome: Outcome;
  }
}
