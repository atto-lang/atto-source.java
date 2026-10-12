// Cross-backend executed conformance report contract, version 1.
// Byte provenance and guest-runtime observations must be independently verified.
import "@typespec/http";

namespace Oreslang.PipelineDifferential {
  enum Status { passed: "passed" }
  enum Direction { forward: "forward", backward: "backward" }
  enum Actor { Source: "Source", Transform: "Transform", Sink: "Sink" }

  model SourceRevisions {
    java: string;
    llvm: string;
  }
  model GuestHop {
    direction: Direction;
    actor: Actor;
    value: int64;
  }
  model NativeAdmission {
    target_index: int32;
    direction: int32;
    variant_index: int32;
    value: int64;
  }
  model ThreeActorFlow {
    input: int64;
    forward: int32;
    backward: int32;
    result: int64;
    java_guest_hops: GuestHop[];
    llvm_data_admissions: NativeAdmission[];
    llvm_finalized_turns: int32[];
  }
  model DifferentialReport {
    schema_version: "1.0";
    source_revisions: SourceRevisions;
    three_actor_flow: ThreeActorFlow;
    status: Status;
  }
}
