// Draft v1 conformance surface, not yet an emitted host wire receipt.
// Mirrors cleanup-receipt.schema.json. Runtime exceptions currently carry
// the fail-closed incomplete result; supervisor receipts remain future work.
namespace Oreslang.Cancellation;

enum CleanupOutcome {
  none,
  settled,
  incomplete,
  failed,
}

enum StopMode {
  graceful,
  force,
}

model CleanupReceipt {
  version: "1";
  actorId: string;
  incarnation: string;
  sourceTaskId: int64;
  stopMode: StopMode;
  outcome: CleanupOutcome;
  doneSuccess: boolean;
  cleanupExecuted: boolean;
}
