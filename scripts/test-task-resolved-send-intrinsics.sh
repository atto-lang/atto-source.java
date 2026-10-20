#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
command -v javac >/dev/null 2>&1 || exit 2
command -v java >/dev/null 2>&1 || exit 2
out="$(mktemp -d)"
javac --release 21 -Xlint:all -Werror -d "$out" \
  src/main/java/dev/oreslang/ast/Ast.java \
  src/main/java/dev/oreslang/types/Types.java \
  src/main/java/dev/oreslang/types/ThreadSafetyTraits.java \
  src/main/java/dev/oreslang/types/TaskCaptureAnalysis.java \
  src/main/java/dev/oreslang/types/TaskTransferProvenance.java \
  src/main/java/dev/oreslang/types/TaskSemanticAdmission.java \
  src/main/java/dev/oreslang/types/TaskAdmissionProof.java \
  src/test/java/dev/oreslang/types/TaskResolvedSendIntrinsicSmoke.java \
  src/test/java/dev/oreslang/types/TaskCaptureAnalysisSmoke.java \
  src/test/java/dev/oreslang/types/TaskTransferProvenanceSmoke.java \
  src/test/java/dev/oreslang/types/TaskAdmissionProofSmoke.java
java -ea -cp "$out" dev.oreslang.types.TaskResolvedSendIntrinsicSmoke
java -ea -cp "$out" dev.oreslang.types.TaskCaptureAnalysisSmoke
java -ea -cp "$out" dev.oreslang.types.TaskTransferProvenanceSmoke
java -ea -cp "$out" dev.oreslang.types.TaskAdmissionProofSmoke
