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
- entries sorted by unsigned UTF-8 module/name/signature bytes, with
  **explicit stable v1 kind tags** (not Java enum ordinal position);
- nested signature elements use UTF-8 **byte** counts, never UTF-16 code-unit
  counts, including supplementary Unicode characters;
- SHA-256 over the complete canonical envelope (not a signature or MAC).

The decoder rejects unsupported versions, unknown kind tags, invalid UTF-8,
duplicate and conflicting non-callable symbol identities, noncanonical entries,
trailing bytes, overlong strings, and oversized files. Callable overload
names are allowed to repeat only for distinct exact signature records; a
future typed linker must independently verify legal overload selectors.
All returned byte buffers are defensively copied. Decode NEVER constitutes
a trust proof: always validate provenance and compare expected fingerprints.

Export metadata includes callable effect/mutability/ownership-shaped AST
markers and named parameter labels. Public classes/interfaces/aliases and
public bindings are extracted without serializing executable bodies. Private
declarations are not exported and entirely private modules produce no
exported entries. Because the current AST does not safely
represent every native layout contract, **all public classes with private
storage are rejected**, even with explicit public constructors, rather than
exporting a fingerprint that silently ignores private layout changes.
Export types that contain inference markers, `Unknown`, or unsolved generic
arguments are rejected recursively.
Files containing imports are also rejected: v1 does not yet encode resolved
provider interface identities, and must not incorrectly reuse a public ABI
when import resolution changes.

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
- Imports of any kind are not admitted yet. Cyclic imports, nominal overload
  selector identities, native object format, compiler version provenance,
  and cache authentication are not implemented.
- Byte-hashing is not cryptographic authentication; a producer can choose its
  header values. A trusted compiler driver must assert its own actual version,
  target and policy rather than accepting untrusted metadata.
- The v1 schema is for experimentation and may be replaced before stable
  compiler release. Never cache or import from an untrusted `.oresi` source.

## Verified evidence

The exact codec and collector sources compiled against Oreslang's actual
`Ast.java` using JDK 21 in a **private** authorized CI repository; 32
standalone smoke assertions passed across deterministic encoding, frozen
wire tags, Unicode lengths, duplicate identities, nested generic resolution,
private native layout, import rejection, and malformed-wire boundaries.
An additional **1,024 seeded byte-mutation cases** checked that every accepted
payload is canonical:

https://github.com/canonical-cloud/canonical-docs/actions/runs/38621336358

This does NOT verify the full JVM/GraalVM compiler, Java PR #924 or the
LLVM backend; all require their own integration and conformance gates.
