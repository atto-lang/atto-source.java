# Experimental Java/Graal static facts v0 exporter

Status: **first real .ores -> admitted typed-AST -> JSON adapter**. This is not a full source-level security analyzer, proof of termination, compiler-issued borrowing/callback safety certificate or parity claim with LLVM.

## Run (with a supported GraalVM/JDK Maven environment)

```sh
mvn -B -ntp -Dtest=StaticFactsV0ExporterTest,StaticFactsCliTest test
mvn -B -ntp -DskipTests compile exec:java -Dexec.args="--static-facts path/to/scalar.ores"
```

`OresMain --static-facts path.ores` reads source through the normal bounded .ores source reader, parses and performs **the full OresCompiler type- and ownership-admission sequence**, then exports `ores-static-facts/v0` JSON without executing the guest. `--static-facts` is mutually exclusive with `--check` and `--build-analysis`.

The `unit` identifier is the SHA-256 of the exact UTF-8 source bytes. It is an **identity, not authenticated provenance**; any caller could still handcraft JSON. Consumers must not treat a hash inside a file as signed evidence of a trusted compiler run.

## Admitted subset

- One flat module or the implicit `__root__`, no import/namespace/annotations/classes/interfaces/actors.
- Nongeneric, synchronous primitive `int` / `bool` functions returning `int`, `bool` or `void`, with immutable primitive parameters.
- Scalar constants, variable uses/bindings, unary and binary operations, scalar assignments, if/else, blocks, returns, explicit break/continue.
- `while` (lowered by Parser to `ForStmt`), for, do-while and unconditional loop AST nodes.
- Only statically resolvable same-module function name/arity calls. Overloads ambiguous at name/arity, imports, function aliases, indirect/member/generic dispatch are rejected.

Unsupported or effectful constructs fail closed with `ORES-ANALYZE-UNSUPPORTED`: this includes foreign/native calls, host I/O, actor or Task spawning, async/await, callbacks, closures, dynamic dispatch, arbitrary guest objects, select, catch/throw and inter-module calls. A symbol cannot be misresolved as a direct function if a parameter or local binding shadows its name.

## Hardened producer limits (aligned with Rust draft #16)

The first scalar bridge enforces at most **512 procedures, 256 loops in one
procedure, 4,096 loops across the document, 8,192 distinct direct-call edges,
and 128 nested AST statement/expression traversal frames**. These are
conservative importer budgets, not general Oreslang language limits. Oversized
input fails closed with `ORES-ANALYZE-UNSUPPORTED`.

This fact schema does not yet distinguish multiple source callsites with the
same caller and callee. Instead of silently collapsing them into one edge,
the producer rejects repeated same-pair calls until callsite identities,
multiplicity and enclosing-loop semantics can be exported faithfully.

The exporter is not a whole-program source parser with independently verified
line mappings. Runtime budget claims and hot-reload effects remain unknown.

## Evidence limits

- **Every loop is currently `exit=unknown` and `safepoint=false`**. This is intentionally conservative. No rank/termination or scheduler-cooperation proof is inferred from an exit edge or a name.
- Direct call edges use `kind=call`, `fanout=1`, `guard=unproved`. This makes recursive SCCs discoverable in the common analyzer. Loop-nested calls are not yet cost-attributed.
- Empty security/taint arrays mean *not modeled in this closed scalar subset*, not a general security clearance.
- The AST lacks stable, checked expression/statement source spans, effect provenance for callbacks, and backend lowering evidence. The exporter **does not fabricate source line locations, permission ceilings, field identity or safety proofs**.
- Builds must never grant strict AOT, hard real-time or security admission from this experimental JSON. Future versions require signed/trusted producer identity, compiler binary ID, schema/profile negotiation and a complete HIR/MIR proof chain.
- Source-octet SHA alone does not authenticate the compiler or bind its backend execution tree.

## Next integration acceptance gates

1. Compile the exact Java branch using JDK/GraalVM 25; execute the seven source-facing JUnit tests and CLI smoke on real .ores files.
2. Feed its JSON to the shared `oreslang-common-source` reference checker; detect recursive `call` cycles and unproved loops with actual source input. Do not claim cross-repository source checkout is available via a test organization's `GITHUB_TOKEN`.
3. Introduce a single versioned backend-neutral typed-CFG/effect representation with source spans, direct/indirect dispatch and interprocedural summaries.
4. Add real actor spawning, zero-copy domain/borrow and Zalgo callback facts only after authoritative lowering and exact backend identity validation are available. Continue to fail closed on unsupported syntax.

Source repository CI jobs that fail with **zero executed steps** are not passing tests. Cross-organization mirroring of private source requires independent security approval for the specific private destination and exact-tree transfer.
