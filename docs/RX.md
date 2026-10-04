# rx-ores core-library linking

`rx-ores` is part of the Oreslang core library surface, but it is **not an
implicit runtime dependency**.

The core import is explicit and Unix-path-shaped:

```ores
import module rx from "std/rx";
```

## Packaging invariant

“Core” means Oreslang owns the API, compatibility contract, tests, and release
version. It does **not** mean every application carries RX code.

The linker follows these rules:

1. `std/...` paths are compiler-owned and cannot be shadowed by project or
   `ORESLANG_PATH` files.
2. A core source unit enters the linked program graph only when an explicit
   import reaches it.
3. No `std/rx` import means no RX source unit in the linked graph.
4. Per-application builds run language-level reachability/tree shaking over the
   linked graph before JVM/native emission.
5. AOT/native emission therefore has no reason to retain RX implementation
   code when the program does not import RX.
6. JIT and AOT keep the same source semantics. JIT may compile linked RX code
   lazily, but it does not make unimported RX code visible.

This is intentionally **not** runtime plugin loading or reflective dynamic
linking. Oreslang keeps the dependency graph closed and AOT-friendly.

The compiler/toolchain distribution may ship the RX source package so it is
always available to import. That toolchain payload is separate from the
application executable payload.

## Fine-grained RX layout

`std/rx` is the minimal reactive nucleus: `Observable<T>`,
`Observer<T>`, `Subscription`, and construction primitives.

Operators should live in separate units such as:

```text
std/rx/operators/map
std/rx/operators/filter
std/rx/operators/merge
std/rx/operators/debounce
std/rx/async
```

That keeps operator-level linking granular. A future `std/rx/all` convenience
unit may intentionally import the full operator set for applications that want
a batteries-included bundle.

## Initial API

```ores
import module rx from "std/rx";

val rx.Observable<int> stream = rx.create<int>(subscribe_impl);
```

The initial core unit is deliberately small. Operator and Future/async bridges
can grow as separate opt-in units while the base linking contract remains
stable.
