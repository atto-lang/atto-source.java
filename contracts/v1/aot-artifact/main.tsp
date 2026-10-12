import "@typespec/json-schema";
using JsonSchema;

namespace Oreslang.Aot.V1;

model CompilerIdentity {
  backend: "java-graalvm" | "llvm";
  @minLength(1)
  version: string;
  @pattern("^[0-9a-f]{40}([0-9a-f]{24})?$")
  git_commit: string;
}

model BinaryArtifact {
  @pattern("^[0-9a-f]{64}$")
  file_sha256: string;
  @minValue(1)
  @maxValue(9007199254740991)
  size_bytes: safeint;
  format: "native-executable" | "static-library" | "wasm-module";
  @minLength(1)
  target_triple: string;
  @minLength(1)
  abi: string;
}

@minLength(1)
scalar CapabilityName extends string;

model RuntimeGenerationPolicy {
  runtime_macro_expansion: false;
  runtime_annotation_generation: false;
  runtime_guest_compilation: false;
  @uniqueItems
  required_capabilities: CapabilityName[];
}

model ArtifactSignature {
  algorithm: "ed25519";
  @minLength(1)
  key_id: string;
  @pattern("^[A-Za-z0-9+/]{85}[AQgw]==$")
  value_base64: string;
}

model BuilderProvenance {
  @minLength(1)
  builder_id: string;
  signature: ArtifactSignature;
}

@jsonSchema
model AotArtifactManifestV1 {
  schema_version: "oreslang.aot-artifact.v1";
  compiler: CompilerIdentity;
  @pattern("^[0-9a-f]{64}$")
  source_sha256: string;
  @pattern("^[0-9a-f]{64}$")
  expanded_sha256: string;
  @pattern("^[0-9a-f]{64}$")
  generated_ast_sha256: string;
  artifact: BinaryArtifact;
  policy: RuntimeGenerationPolicy;
  provenance: BuilderProvenance;
}
