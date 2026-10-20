# Build profiles on the Java/Graal backend

The Java/Graal launcher recognizes the Oreslang build-profile v1 selectors:

- `--release`
- `--strict-aot`
- `--hard-rt`
- orthogonal `--freestanding`

Runtime reflection is forbidden by the language/build contract. Prefer compile-time
introspection, generated serializers/registries and explicit reachability roots.

`--build-analysis --release` uses the existing closed-world `TreeShaker`. The strict-AOT
selector forces `--mode=aot` and rejects an explicitly requested JIT/hybrid mode.

The mainline launcher deliberately rejects `--hard-rt` until the active Java realtime
admission work is connected to compiler/build evidence, including WCET/deadline evidence.
It also rejects `--freestanding`: the Java/Graal launcher is currently a hosted runtime.
These are fail-closed behavior, not missing-option fallbacks.
