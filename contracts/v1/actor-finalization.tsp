import "@typespec/json-schema";
using TypeSpec.JsonSchema;

// Host-observed schema only. TypeSpec/JSON Schema cannot prove temporal order.
// Enforcement belongs to runtime barriers plus formal/conformance tests.
@jsonSchema
namespace Oreslang.Conformance.ActorFinalizationV1 {
  model Event {
    seq: int32;
    kind: "stop_requested" | "carrier_exited" |
          "cursor_waiters_drained" | "owned_waiters_drained" |
          "child_done" | "finalization_claimed" |
          "exit_hook_completed" | "ready_notified" |
          "done_notified" | "parent_notified";
    child_actor_id?: string;
  }

  model Receipt {
    schema_version: "1.0.0";
    actor_id: string;
    incarnation: int64;
    receipt_id: string;
    parent_actor_id?: string;
    events: Event[];
    outcome: "success" | "failed" | "cancelled";
    failure_code?: string;
  }
}
