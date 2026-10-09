# Polyglot TypeSpec/JSON Schema admission profile for Oreslang

`ORESoftware/ores-clients-core` draft #12 emits a **declaration-only**
`define contract ClientContract as ... end` module-contract surface, using `fnc name(String arg) => String;`. It does NOT emit a type interface or `Unknown` payload types. The Java parser/type-checker tests
here establish language syntax admission for a representative header, **not**
payload serializer or TJSV behavioral conformance.

Contract authority belongs to independently authored TypeSpec and JSON
Schema Draft 2020-12 in each product's `*-interfaces` repository.
`ORESoftware/typespec-json-schema-validator` (TJSV) owns exact-input
parity/Contract IR/runtime evidence. Use `ORESoftware/ores-contracts` additionally
for persistence-bearing contracts. Never generate and commit one authored
authority from another, or rely on `Unknown` headers as proof of payload shape.

Native runtime target: `oreslang/graalvm-jvm`, to be implemented with an actual
adapter validating positive **and negative** fixture corpora at ingress/egress,
correct missing-vs-null / enum / union / integer / UTF-8 / error behavior and
identical canonical wire serialization against Rust/TypeScript/Dart peers.
Receipts must bind exact source, compiler, artifact and fixture input digests.

Future target identities: `oreslang/javascript-browser` and
`oreslang/wasm-browser`; these MUST be separately compiled and tested in a
sandbox without ambient filesystem or Java host-access capabilities. The
transpilers are **planned**, not declared functional by this document or test.

## Exact-source admission sequence

1. Compile this JUnit suite against the actual exact Java compiler PR head. Compilation/test errors block admission; syntax alone is not a runtime receipt.
2. Generate a matching `.ores` declaration with pinned ores-clients-core and byte-compare to the compiler fixture; generator changes must update the compiler fixtures.
3. Compile a conforming module and reject an incomplete implementation; reject injected identifiers and incorrect return-arrow signatures. A String-only header does not validate JSON.
4. Link a native Oreslang JSON/validation adapter against pinned compiler and codec revisions. Execute both positive and negative trusted fixtures and emit only payload-free evidence.
5. Use TJSV verifyRuntimeEvidenceAgainstCurrentInputs() against authored TypeSpec, authored JSON Schema, generated witness, exact Contract IR/parity receipt and trusted corpus digests.

If native source-org Actions starts no steps, validate the exact Git tree in a **private** test repository from an organization with available minutes. Compare original Git blob identities and keep snapshots out of main.
