# AOT artifact admission V1 (contract only)

This folder defines a **fail-closed, offline-produced** Oreslang binary artifact
manifest for the Java/GraalVM and LLVM backends. The normative admission
structure is `manifest.schema.json`; `main.tsp` is the matching TypeSpec
authoring model. This PR does **not** implement or claim a verified loader,
a compiler emitter, a signed artifact, or guest AOT parity.

## Required admission algorithm (future loader)

1. Parse JSON with **duplicate-key rejection**, bounded input size and no
   unrecognized fields. Validate against JSON Schema Draft 2020-12.
2. Require the exact manifest schema version and enforce `false` for all
   runtime macro expansion, annotation generation and guest compilation flags.
3. Recalculate the compiled **artifact bytes** SHA-256 and size; reject any
   mismatch, unsupported native format, target triple or ABI.
4. Match the compiler backend, version, source Git commit, source/expanded
   token digests and generated-AST digest to a separately authenticated build
   attestation. A digest on its own does not authenticate provenance.
5. Resolve the signature `key_id` using an **external trusted-key policy**,
   never a public key supplied solely by the untrusted manifest. Verify
   Ed25519 over the RFC 8785 canonical JSON manifest with the
   `provenance.signature` member omitted. Reject noncanonical encodings
   and signatures that fail verification. The test fixture's signature is
   **syntactic dummy data**, NOT a valid trusted release signature.
6. Admit only the explicit `required_capabilities` allowed by the actor
   runtime policy; refuse unknown capabilities or escalated actor authority.

The JSON Schema tests validate structural admission only. Signature verification,
compiler proof, immutable AOT binaries and runtime artifact loading are separate
release blockers. `tsp compile .` validates TypeSpec syntax, but generated
schema/fixture differential parity must pass before the contract becomes normative
for more than its JSON Schema admission layer.

## Developer checks

- `python -m pip install jsonschema==4.26.0`
- `python test_contract.py`
- `npm install --ignore-scripts` (versioned TypeSpec dependencies)
- `npx tsp compile .`
- `python test_typespec_parity.py ./tsp-output` (requires generated
  JSON Schema from the pinned emitter; it compares positive and adversarial
  admission cases to the normative schema)


Ed25519 signatures use **canonical padded standard Base64** of exactly 64 bytes;
the second significant character of the final quantum is restricted to
`A`, `Q`, `g`, or `w` to avoid alternative encodings of the same bytes.
Structural Base64 validation alone is **not** cryptographic verification.

Hash fields are lowercase hexadecimal SHA-256 (exactly 64 characters).
`compiler.git_commit` accepts 40- or 64-digit Git object hashes.
`size_bytes` is a positive JSON safe integer.
