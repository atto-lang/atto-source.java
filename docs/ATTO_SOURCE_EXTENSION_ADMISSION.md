# Java/GraalVM AttoLang .atto source admission (first implementation slice)

This change supports `.atto` as a second native guest source extension, preserving `.ores` and mixed `.java` file compatibility. The source still goes through the same bounded UTF-8 reader, `MixedSourceUnit`, transitive import closure, parser, compiler/type+ownership gates, and runtime linking; it does **not** skip or wrap the frontend.

## Scope and safety

- CLI `--check`, `--build-analysis` and direct execution now recognize `.atto` and retain `.ores` compatibility.
- Both explicit `./file.atto` and extensionless imports work. Existing `.ores` and `.java` extensionless resolution retains priority to avoid silent import retargeting; write an explicit `.atto` suffix if sibling files share a basename.
- `.atto` is not Java source and does not grant mixed-Java interop capabilities. Native Truffle MIME/language identity remains unchanged for compatibility.
- Invalid UTF-8, oversized inputs, transitive import budgets, dependency capabilities, and owner paths must obey the same checks as legacy guest source.
- JUnit `AttoSourceAdmissionTest` constructs actual `.atto` entry/helper files and runs an `answer() == 42` import through the linked Java/Graal guest pipeline, plus a colliding legacy-name control.
- The full private `lru-cache-atto/lru-cache.atto` source tree is **not** mirrored into this repo. Those seven native fixtures and their compiler-dependent language syntax still require separate source-authorized exact-head guest verification.

This is source admission only: it is not proof of the LRU cache's `critical fnc`, `InvertedArray`, zero-GC, native-image, or multi-backend parity. Keep the PR draft until exact-head executed JUnit and guest receipts.

## Threat-model regressions

- A `.atto` file containing a mixed `java { ... }` declaration is subjected to the same `JAVA_SOURCE_INTEROP` capability gate as `.ores`; extension renaming must never grant ambient host compilation.
- A malformed UTF-8 **transitive** `.atto` import fails through the bounded reader, even when the entrypoint itself is valid. This disallows source-admission bypass by dependencies.

These tests are committed but **not executed** while GitHub jobs report `steps=null`.