# Oreslang compiler options

Oreslang compiler policy is intentionally split into three layers:

1. **Language invariants** — safety properties that cannot be disabled.
2. **Compiler checks** — strictness and diagnostics that projects may tune.
3. **Backend/build controls** — optimization, debug information, target, instrumentation, and emitted artifacts.

The first implementation lands the option spine plus the two dynamic-type checks that motivated it. The CLI contract is described by the repository's `.cli-flags.toml`, so the same names can be consumed by `flags-2-env`, shell wrappers, CI, and future native launchers.

## Implemented compiler checks

| Oreslang option | CLI | Environment | Meaning |
| --- | --- | --- | --- |
| `strict` | `--strict` | `ORES_COMPILER_STRICT=true` | Umbrella for strict semantic checks. |
| `noImplicitAny` | `--no-implicit-any` | `ORES_NO_IMPLICIT_ANY=true` | Reject compiler-created `any`; explicit source-level `any` remains legal. |
| `noImplicitUnknown` | `--no-implicit-unknown` | `ORES_NO_IMPLICIT_UNKNOWN=true` | Reject compiler-created `unknown` when it escapes through an inferred declaration; explicit source-level `unknown` remains legal. |

`strict` enables both implicit-type checks unless an individual option is explicitly overridden. This gives migrations a useful shape:

```text
ores --strict --implicit-any app.ores
```

The internal type system distinguishes four states: explicit `any`, explicit `unknown`, implicit `any`, and implicit `unknown`. This is required so a strictness flag never rejects an intentional annotation merely because the compiler uses the same runtime representation.

The flags-2-env contract uses positive boolean names `implicit-any` and `implicit-unknown` because its normal boolean negation syntax then produces the desired `--no-implicit-any` and `--no-implicit-unknown` forms. The compiler also accepts direct `ORES_NO_IMPLICIT_*` environment variables.

## Language invariants, not switches

Oreslang should not copy every switch from another compiler. The following stay mandatory:

- ownership / borrow checking;
- actor memory and capability isolation;
- `UntrustedActor` sandbox restrictions and execution budgets;
- exhaustive checking for closed pattern-match domains;
- explicit returns for non-void callables on all reachable paths;
- capability admission for FFI, shared memory, GPU, process, filesystem, network, and similar effects;
- safe actor-message transport rules.

A compiler flag must never turn one of those into "unsafe mode".

## Lint model to add next

Rust's lint-level model is a better fit than dozens of unrelated booleans. Oreslang should use a separate lint namespace because `--allow=CAP,...` already means **capability admission**.

Proposed levels:

```text
allow < warn < deny < forbid
```

Proposed CLI forms:

```text
--lint-allow=unused_imports
--lint-warn=dead_code
--lint-deny=unused_result
--lint-forbid=unsafe_escape
--deny-warnings
```

Initial lint set:

- `unused_imports`
- `unused_locals`
- `unused_parameters`
- `dead_code`
- `unreachable_code`
- `unused_result` / must-use results
- `deprecated`
- `shadowed_binding`
- `fallthrough` if a future switch form can fall through
- `unchecked_cast`
- `redundant_pattern`
- `unhandled_actor_message`

These cover the useful overlap of Rust, Erlang, Java linting, and TypeScript's `noUnused*` / fallthrough checks without polluting the top-level option namespace.

## Backend/build controls to add when the backend consumes them

Do not expose flags that are parsed but ignored. When the corresponding backend stages exist, add:

| Oreslang | Inspiration | Intended shape |
| --- | --- | --- |
| `opt_level` | Rust, Go | `0`, `1`, `2`, `3`, `s`, `z` |
| `debug_info` | Rust, Go, Java | `none`, `line`, `full` |
| `emit` | Rust, Go, Java | `ast`, `typed_ast`, `ir`, `asm`, backend artifact |
| `incremental` | Rust/modern build systems | enable ABI-aware incremental compilation |
| `jobs` | Go/Cargo-style drivers | maximum parallel compilation jobs |
| `target` / `platform` | Rust, Java, Go | backend target/platform selection |
| `release` | Java | language/runtime compatibility target |
| `verbose` / `print_commands` | Java, Go | compiler action diagnostics |
| `race` | Go | runtime race instrumentation where shared execution exists |
| `sanitizer` | Go/Rust toolchains | supported host/backend sanitizer instrumentation |
| `coverage` | Go | coverage instrumentation |
| `trim_paths` | Go/Rust | reproducible diagnostic/artifact paths |

The existing `--mode=aot|jit|hybrid` is an **execution profile**, not an optimization level, and should remain separate.

## Actor diagnostics inspired by Akka

Akka's useful contribution here is not a language compiler-flag vocabulary; it is operational verification. Oreslang can add CI/test diagnostics such as:

- `verify_actor_messages`: prove/validate every transported message against Oreslang's sendable contract;
- `verify_actor_creators`: validate actor construction payloads;
- `warn_unhandled_actor_messages`;
- `warn_dead_letters`.

These are diagnostics around an already-safe actor model. They must not create a mode that disables actor isolation, message bounds, ownership, capability checks, or untrusted-actor containment.

## Recommended presets

Once the lint engine is present:

- **local**: strict type checks, normal warnings, debug information, incremental compilation.
- **ci**: strict type checks, deny warnings, unused/dead-code lints, message verification, reproducible paths.
- **release**: strict type checks, optimized backend, minimal debug information as configured.
- **cloud**: release semantics plus the strict deployment/isolate policy.

Presets expand to ordinary options; explicitly supplied individual options win. This keeps configuration inspectable instead of hiding behavior in magic modes.
