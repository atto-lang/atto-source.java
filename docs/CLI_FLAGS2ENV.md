# Compiler/runtime CLI flags — flags-2-env

Oreslang uses the canonical upstream project
[flags-2-env](https://github.com/flags-2-env/flags-2-env) and a checked-in
`.cli-flags.toml`. Do not copy or vendor the retired
`ORESoftware/flags-2-env` implementation.

## Standalone invocation

Install the upstream `flags2env` executable, then:

```sh
bash scripts/with-flags2env.sh /absolute/path/to/oreslang-binary [guest-args] source.ores
flags2env audit .cli-flags.toml
```

The wrapper calls `flags2env shell-env`, but never evaluates its shell text.
It accepts only the small schema-declared set of non-authority `ORES_*`
configuration values, rejects duplicate/unexpected output, and then executes
the Oreslang binary with the original guest argv.

## Security boundary

Environment variables are configuration, **not authority**. In particular,
ambient `ORES_ALLOW_*` or `ORES_DENY_*` variables are scrubbed and cannot
grant filesystem, network, environment, process, FFI, system, or import access.
Scoped permission grants are validated by `RuntimePermissions` from explicit
argv and are further bounded by the serialized build permission ceiling.

The current upstream flags2env scalar map cannot losslessly represent two
Oreslang CLI shapes that matter for compatibility:

- a Deno-style permission option can be either bare (`--allow-net`) or valued
  (`--allow-net=host:port`);
- some build/host options may be repeated.

Until upstream exposes a lossless representation for those cases, permission
argv remains validated by the Oreslang host after flags2env has parsed the
non-authority configuration flags. This is an explicit migration limitation,
not permission parity. Do not silently collapse repeated authority flags or
convert ambient environment values into grants.

## Required release tests

- `flags2env audit .cli-flags.toml` against the pinned/upstream release;
- the no-eval wrapper adversarial test;
- Java launcher negation/default-deny tests;
- JDK 25/GraalVM and Native Image CLI checks;
- scoped permission and build-ceiling tests.
