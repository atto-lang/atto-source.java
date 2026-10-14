# Oreslang typed export interface: early .oresi v1 prototype

Status: **experimental local interface artifact**, not a production linker ABI,
not a stable on-disk incremental cache, and not LLVM import compatibility.

## Safe production path

The opt-in Java API `OresCompiler.emitPublicInterface(source, header)`
runs parsing, type checking, ownership admission, and annotation expansion
before collecting export contracts. No Oreslang guest bodies execute.

`ExportInterfaceCollector.collect(checkedProgram, header)` assumes its
input was checked by that trusted pipeline. Direct callers MUST NOT use it
to elevate untrusted ASTs to verified interfaces.

## Envelope format

`ExportInterfaceCodec` encodes a binary envelope:

- fixed `ORESI\0\1` magic and wire schema version;
- UTF-8, big-endian, 32-bit length-prefixed namespace, compiler version,
  target ABI, and feature policy strings;
- one bounded entry count followed by entries (module, kind, name,
  typed signature), each length-prefixed;
- entries sorted by unsigned UTF-8 module/name/signature bytes, then a stable
  enum kind tag;
- SHA-256 over the complete canonical envelope (not a signature or MAC).

The decoder rejects unsupported versions, invalid UTF-8, duplicate or
noncanonical entries, trailing bytes, overlong strings, and oversized files.
All returned byte buffers are defensively copied. Decode NEVER constitutes
a trust proof: always validate provenance and compare expected fingerprints.

Export metadata includes callable effect/mutability/ownership-shaped AST
markers and named parameter labels. Public classes/interfaces/aliases and
public bindings are extracted without serializing executable bodies. Private
declarations are not exported and entirely private modules produce no
exported entries. Because the current AST does not safely
represent every native layout contract, synthesized constructors with
private fields are **rejected** rather than exposing private field data.
Classes with private fields annotated `FromJson` are likewise rejected
pending an opaque wire-layout contract.
Public inferred signatures likewise fail closed until they can be expressed
from a fully resolved typed descriptor.

## Limitations and remaining gates

- No import/linker consumes this envelope yet. Java `IncrementalCompiler`
  continues to use its own ABI digest, and PR #924 remains independent.
- A general source-AST signature is not yet a fully resolved type identity.
  Aliases, nominal ownership, generic defaults, structural type IDs, effects,
  checked class layout, compile-time inputs, and macro environment still
  require dedicated canonical lowering.
- Module visibility is not represented in `Ast.ModuleDecl`; the compiler
  must prove module visibility at export time before treating this prototype
  as a distributable interface.
- Private fields annotated for serialization can alter observable wire
  contracts; this v1 collector rejects such classes until an explicit
  opaque policy exists.
- Cyclic imports, nominal overload selector identities, native object format,
  compiler version provenance, and cache authentication are not implemented.
- Byte-hashing is not cryptographic authentication; a producer can choose its
  header values. A trusted compiler driver must assert its own actual version,
  target and policy rather than accepting untrusted metadata.
- The v1 schema is for experimentation and may be replaced before stable
  compiler release. Never cache or import from an untrusted `.oresi` source.

## Verified evidence

The exact codec and collector sources compiled against Oreslang's actual
`Ast.java` using JDK 21 in a **private** authorized CI repository; 22
standalone smoke assertions passed across deterministic encoding, wire
validation, ABI sensitivity, public/private filtering, and namespace checks:

https://github.com/canonical-cloud/canonical-docs/actions/runs/38614132614

This does NOT verify the full JVM/GraalVM compiler, Java PR #924 or the
LLVM backend; all require their own integration and conformance gates.
