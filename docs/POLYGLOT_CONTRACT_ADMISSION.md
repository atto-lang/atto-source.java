# Polyglot TypeSpec/JSON Schema admission profile for Oreslang

`ORESoftware/ores-clients-core` draft #11 emits a **declaration-only**
`define interface ClientContract` surface. The Java parser/type-checker tests
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
