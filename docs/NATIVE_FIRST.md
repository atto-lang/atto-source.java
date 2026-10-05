# Native-first Oreslang core implementation

Oreslang's core implementation is **Oreslang-first**.

The host JVM and Graal/Truffle remain implementation substrates for parsing, AST/Truffle nodes,
embedding, compilation, context management, and bootstrapping. They are not the preferred place to
implement Oreslang standard-library semantics.

## Layering

1. **Oreslang source owns language/library semantics**
   - packaged core modules live under `std/*`;
   - functions, collections algorithms, streams, protocol logic, networking state machines, file APIs,
     synchronization APIs, and user-visible behavior should be written in Oreslang where the language
     can express them safely;
   - importing a core module must not implicitly grant Java host authority.

2. **Native boundary owns OS/device primitives**
   - low-level syscalls, polling, sockets, files, clocks, memory mapping, native carrier operations,
     and accelerator launch primitives should cross a narrow capability-checked native ABI;
   - JNI is the current preferred JVM bridge where a host transition is required;
   - the Oreslang ABI must not depend on JNI object identity or Java class layouts so another bridge
     can replace JNI without changing source semantics.

3. **Java owns Truffle/embedding glue**
   - parser/compiler/Truffle nodes and Graal context integration may remain Java;
   - Java may adapt native results into runtime-internal handles;
   - Java must not become a second stdlib implementation hidden behind Ores APIs.

4. **Java interop is application interop, not core implementation**
   - `java { ... }`, `do java { ... }`, and `java:` imports remain explicitly capability-gated;
   - packaged `std/*` modules reject all three forms;
   - untrusted/adversarial actors never gain Java authority through stdlib imports.

## Migration priorities

Highest priority migrations are runtime surfaces that currently depend on Java semantics:

- actor/scheduler carriers -> native carrier/JNI backend while preserving Ores scheduler policy;
- blocking I/O -> native readiness/proactor layer that completes Ores Futures;
- filesystem/network/socket/fd -> Oreslang APIs over narrow native handles;
- mutex/rwlock/timer primitives -> Ores scheduler/native primitives rather than Java locks/timers;
- ECS/Region storage -> primitive/native columns and device buffers rather than `Object[]`;
- numerical kernels -> Ores-facing contracts over native CPU/GPU kernels instead of exposing Java math objects;
- rx/stream operators -> `.ores` modules over OresFuture/Awaitable rather than Java callback libraries.

## Transitional Java code

Existing Java runtime code is technical debt, not the semantic target. A PR may temporarily use Java
for a missing primitive only when it:

- documents the missing native primitive;
- keeps the guest-visible contract Ores-owned;
- does not expose the Java object to guest code;
- preserves AOT/native-image behavior;
- includes a migration point to the native ABI;
- never uses Java interop capability as a shortcut for core behavior.

## Built-in core modules

`LinkedProgramRunner` resolves imports beginning with `std/` from packaged Oreslang source
resources. These modules are parsed, type-checked, linked, tree-shaken, and executed like ordinary
Oreslang units.

Core modules fail closed if they contain:

- a `java:` import;
- `java { ... }`;
- `do java { ... }`;
- a non-`std/*` dependency.

This makes "native-first" mechanically enforceable rather than a style recommendation.
