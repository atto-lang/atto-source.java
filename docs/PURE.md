# Reserved `pure` keyword and effect contract

Status: **draft design / compiler contract**

This document specifies the proposed reserved `pure` keyword for Oreslang. The first implementation target is named `fnc` and `routine` declarations. The keyword is a compiler-enforced semantic contract, not documentation and not an optimizer hint.

This design is intentionally built on an internal effect system so Oreslang can add richer effect declarations later without weakening or redesigning `pure`.

## Goals

A declaration that successfully compiles with `pure` must be unable to:

- observe ambient mutable state;
- mutate state reachable outside the invocation;
- perform I/O;
- observe time, randomness, scheduler state, mailbox state, process state, environment variables, or other nondeterministic ambient inputs;
- spawn actors, send or receive actor messages, or mutate actor/process/singleton state;
- escape through Java/JNI/native/host calls whose effects are not compiler-trusted;
- hide an impure operation behind a helper, closure, callback, dynamic dispatch, generic instantiation, imported package, or recursive call graph.

The guarantee is transitive and fail-closed.

## Non-goals

`pure` does **not** mean:

- total;
- guaranteed to terminate;
- non-throwing, non-raising, or non-panicking;
- allocation-free;
- recursion-free;
- immutable implementation syntax;
- automatically memoizable;
- automatically safe for common-subexpression elimination;
- automatically safe to reorder across exceptions/divergence.

Those properties are separate compiler facts.

## Syntax

`pure` is a globally reserved keyword.

Canonical forms:

```ores
pub pure fnc add(int a, int b): int {
  return a + b;
}

pub pure routine normalize(): void {
  // side-effect-free orchestration is legal;
  // routine's existing no-recursion rule still applies.
  return;
}
```

The canonical modifier order is:

```text
[visibility] pure fnc ...
[visibility] pure routine ...
```

`fnc` retains its existing recursive semantics. `routine` retains its existing prohibition on direct or indirect recursive cycles. Purity is orthogonal to recursion.

The first implementation does not require public syntax for `pure` instance methods. The compiler must still infer method effects so calls from a pure callable cannot escape through an impure method. A later language revision may expose `pure` on methods/interfaces once method effect contracts are specified.

`pure` is not a callable-only compatibility keyword. It cannot be used as an ordinary identifier, parameter, binding, type, class, module, field, bare callable reference name, or unquoted object key.

## Semantic definition

A pure invocation may compute using:

- explicit input values;
- immutable/read-only views of explicit input values;
- locally created state;
- values returned by other compiler-proven pure computations;
- compile-time constants embedded in checked IR.

It may not depend on ambient mutable or nondeterministic state.

A pure invocation may mutate storage only when the ownership checker proves that storage is private to the invocation for the duration of the mutation and that mutable state does not escape.

This makes local imperative implementation compatible with strong purity.

### Allowed local mutation

```ores
pub pure fnc sum(List<int> values): int {
  let int total = 0;

  for value in values {
    total += value;
  }

  return total;
}
```

Local mutation is allowed when the mutated storage is invocation-private.

### Forbidden external mutation

Conceptually, mutation through a caller-visible mutable borrow is rejected:

```ores
pub pure fnc increment(&mut Counter counter): void {
  counter.value += 1; // ERROR: caller-visible mutation
}
```

### Owned/moved inputs

Oreslang's affine ownership model lets the checker be more precise than a blanket "arguments are immutable" rule.

A by-value non-shared value whose ownership has moved into the pure invocation may be mutated as invocation-private storage when the borrow checker proves there are no live aliases. This is equivalent to mutating a local temporary.

An exclusive mutable borrow remains forbidden because its mutation is observable through the caller's retained ownership.

The built-in ownership operations therefore interact with purity as follows:

- `copy`: allowed when the copy operation itself is pure; the copy is invocation-local;
- `take`: allowed as an ownership transfer; subsequent mutation is allowed only under unique ownership;
- `borrow` / `&T`: allowed for read-only access to explicit input state;
- mutable borrow / `&mut T`: forbidden in a pure contract;
- `share`: allowed only for deeply immutable/frozen data; access to mutable shared state is forbidden.

## Escape rule

Mutable state created or uniquely owned inside a pure invocation may not escape in a form that permits later observable mutation.

The checker must reject escape through:

- return values containing mutable reachable storage;
- returned closures that capture mutable storage;
- assignment to module/global/singleton/actor state;
- actor messages;
- process/global registries;
- callbacks retained by impure runtime services;
- foreign/native handles;
- any other longer-lived alias.

Returning a deeply immutable/frozen value is allowed.

Immutable allocation may still carry runtime identity. Therefore `pure` alone is not permission for identity-changing optimizations such as memoization or allocation CSE. Those require a separate value-semantics/identity analysis.

## Ambient reads

Read-only syntax does not imply purity.

These are ambient reads and are forbidden even when the returned value is not mutated:

- mutable module/global state;
- singleton state;
- actor state not supplied as an explicit value snapshot;
- environment variables;
- current working directory;
- process metadata;
- current time;
- randomness;
- scheduler/thread identity;
- mailbox state;
- lock/atomic state;
- external files, sockets, databases, devices, or host state.

The conservative v1 exception is a compile-time constant whose value is embedded in checked IR. Arbitrary runtime `const` objects are not assumed pure merely because their binding cannot be reassigned.

## Closures

Closures are allowed. They are not a purity escape hatch.

A closure used by or returned from a pure callable must have compiler-proven effects compatible with the pure contract.

Allowed:

- capture of immutable/value-semantic data;
- immutable borrowing of explicit input state while the borrow is valid;
- mutation of non-escaping closure-local state when the closure itself does not escape the pure invocation.

Rejected:

- mutation of an outer caller-visible binding;
- capture of mutable shared/module/singleton/actor state;
- escaping closure state that remains mutable;
- calls through a closure whose effect summary is unknown or impure.

## Actors, isolates, and scheduling

The following effects are forbidden in a pure callable:

- `spawn`;
- mailbox send;
- mailbox receive;
- actor lookup with observable mutable state;
- actor state mutation;
- actor state reads unless the state was first copied/frozen and passed as an explicit value;
- scheduler queries;
- thread identity;
- sleep/yield;
- locks;
- mutable atomics;
- isolate/process capability operations.

The initial syntax should reject `pure actor fnc`. Actor entrypoints are effectful boundaries. Pure helpers may be called from actor code normally.

A common pattern is:

```ores
val snapshot = copy state.snapshot();
val result = calculate(snapshot);

pub pure fnc calculate(StateSnapshot snapshot): ResultValue {
  // deterministic/value-only work
}
```

## I/O and runtime capabilities

Pure callables cannot perform:

- `stdio`;
- filesystem I/O;
- network I/O;
- HTTP/socket operations;
- database operations;
- environment/process reads;
- device access;
- capability acquisition;
- externally visible logging/tracing.

A pure callable may construct or transform ordinary data representing a request, path, packet, query, log record, etc. It simply cannot execute the external effect.

## Exceptions, trap control flow, and divergence

Purity is separate from totality and from Oreslang's error-control channels.

A deterministic guest-language `throw` may occur in a pure callable when constructing and throwing it performs no forbidden external effect. A `trap` boundary may convert that throw into its explicit `[Option<[T]>, Option<TrapError>]` result without changing purity.

`raise` and `panic` are distinct control effects. They bypass `trap` and unwind to the nearest `recover` boundary. They are not automatically impure merely because they alter control flow; purity depends on whether constructing/propagating the signal performs a forbidden external effect.

A recursive `fnc` may also diverge and still be pure.

The compiler must therefore track at least `throw`, `raise`, `panic`, and `diverge` separately from forbidden external effects. Optimizations must not assume that `pure` means `nothrow`, `noraises`, `nopanic`, or `terminates`.

See `docs/TRAP.md` for the trap/recover contract.

Host/FFI failures are not automatically trusted as deterministic/pure and remain covered by foreign-effect rules.

## Java, JNI, native, and host interop

Foreign code is fail-closed.

A call into Java/JNI/native/host code has effect `ffi_unknown` unless one of the following is true:

1. the operation is a compiler/runtime-owned intrinsic with audited effect metadata; or
2. a future trusted ABI/manifest mechanism establishes a purity contract.

There is no user-level `assume_pure` escape hatch in v1.

Examples of compiler-owned classifications:

```text
integer.add        pure
string.length      pure
math.sin           pure
socket.write       io
clock.now          clock
random.next        random
thread.yield       scheduler
unknown JNI call   ffi_unknown
```

## Internal effect model

Every callable receives an internal effect summary whether or not source code declares `pure`.

Initial effect kinds should include at least:

| Effect | Meaning | Allowed by `pure` |
| --- | --- | --- |
| `local_mutation` | mutation of invocation-private storage | yes |
| `allocation` | local allocation | yes |
| `throw` | deterministic ordinary guest exception/control effect | yes |
| `raise` | deliberate trap-bypassing unwind to `recover` | yes |
| `panic` | invariant/runtime trap-bypassing unwind to `recover` | yes |
| `diverge` | possible nontermination | yes |
| `ambient_read` | read mutable/ambient runtime state | no |
| `external_mutation` | mutate caller/global/shared reachable state | no |
| `io` | stdio/filesystem/network/database/device I/O | no |
| `environment` | env/process/cwd/host configuration | no |
| `clock` | wall/monotonic time observation | no |
| `random` | nondeterministic random source | no |
| `actor` | actor/mailbox/process-state interaction | no |
| `scheduler` | thread/scheduler/sleep/yield state | no |
| `synchronization` | locks/mutable atomics/external synchronization | no |
| `ffi_unknown` | foreign code with no trusted summary | no |
| `unsafe_external` | raw externally reachable memory/capability effect | no |

The implementation may use a bitset, enum set, or richer structure. The source-language contract is defined by semantics, not the internal representation.

## Transitive effect inference

Purity must be proven over the complete resolved call graph.

Example:

```ores
pub pure fnc a(): int {
  return b();
}

fnc b(): int {
  return c();
}

fnc c(): int {
  return stdio.stdin.read_int();
}
```

Compilation must fail even though the direct body of `a` contains no obvious I/O.

The compiler must compute a fixed point over call-graph strongly connected components so recursive `fnc` groups receive complete effect summaries.

A declared pure callable succeeds only when every reachable operation is compatible with the pure contract.

## Indirect calls and higher-order functions

Purity must be part of callable type metadata internally from the first implementation.

A pure callable may invoke a function value/callback only when the compiler can prove that the target callable is pure.

This covers:

- closures;
- function values;
- generic callable parameters;
- interface dispatch;
- trait dispatch;
- virtual/class dispatch;
- imported callable values.

If the target effect is unknown, a declared pure caller fails closed.

The exact source spelling for a "pure callable type" is intentionally left for the callable-type syntax design. The compiler metadata must support it now so higher-order calls cannot punch a hole through v1 purity.

## Dynamic dispatch

For a direct/final target, the compiler may use the inferred target effect summary.

For open dynamic dispatch, a pure caller may invoke the slot only if every legal runtime target is proven compatible with purity.

If the dispatch set cannot be closed, the contract must eventually live on the interface/trait/method slot itself. Until that surface exists, unknown/open dispatch from a pure caller is rejected.

A future `pure` method/interface contract must obey variance by guarantee:

- a pure interface/base slot may only be implemented/overridden by a pure implementation;
- an implementation may strengthen an impure/unspecified slot by being pure.

## Imports and compiled metadata

Effect summaries are part of the checked interface of a compiled Oreslang unit.

The compiler must serialize enough effect metadata to prove cross-file/package calls without re-parsing implementation bodies.

Rules:

- exported `pure` is part of the public contract;
- removing `pure` from an exported callable is a contract weakening and must be treated as an API compatibility change;
- adding `pure` is a strengthening of the guarantee;
- imported artifacts lacking trusted effect metadata are `unknown`, never silently assumed pure;
- metadata version skew fails closed for explicit pure callers.

## Diagnostics

Purity diagnostics must report the effect path rather than only the final declaration.

Example:

```text
error[E-PURE-004]: pure fnc 'a' reaches an I/O effect

  a
  └── calls b
      └── calls c
          └── calls stdio.stdin.read_int
              └── effect: io
```

Proposed stable diagnostic families:

- `E-PURE-001`: direct forbidden effect;
- `E-PURE-002`: ambient mutable/nondeterministic read;
- `E-PURE-003`: externally reachable mutation;
- `E-PURE-004`: transitive impure call;
- `E-PURE-005`: unknown foreign/imported effect;
- `E-PURE-006`: escaping mutable state;
- `E-PURE-007`: impure/unknown callback or dynamic target;
- `E-PURE-008`: invalid modifier combination;
- `E-PURE-009`: purity contract override/implementation mismatch.

Diagnostics should identify the first useful source location and include a shortest effect trace when the violation is transitive.

## Compiler pipeline

Recommended implementation order:

1. reserve and parse `pure`;
2. record purity declaration on callable AST/symbols;
3. add internal `EffectSet` metadata to every callable;
4. classify built-ins/runtime primitives;
5. perform intraprocedural effect collection;
6. integrate ownership, alias, borrow, and escape facts;
7. compute transitive call-graph effects to a fixed point;
8. validate explicit `pure` contracts;
9. add higher-order/dynamic-dispatch effect constraints;
10. serialize effect summaries into compiled-unit metadata;
11. add foreign/native fail-closed metadata;
12. only after correctness is established, consume purity in optimizers.

## Required ownership-checker integration

The purity pass must not duplicate or guess aliasing facts that belong to the ownership/borrow checker.

The passes must share enough information to determine:

- allocation origin;
- ownership;
- move state;
- immutable vs mutable borrowing;
- alias count/reachability;
- shared/frozen status;
- capture origin;
- escape paths;
- return reachability.

Purity decisions based only on syntax are insufficient.

## Required tests

### Compile-pass

- arithmetic-only pure `fnc`;
- pure `routine`;
- recursive pure `fnc`;
- local loop accumulator mutation;
- local temporary object/buffer mutation with no escape;
- read-only borrow of explicit input;
- uniquely owned moved input mutated locally;
- immutable closure capture;
- pure helper called transitively;
- mutually recursive pure `fnc` SCC;
- deterministic guest exception;
- immutable/frozen return value;
- compiler-trusted pure intrinsic.

### Compile-fail

- use `pure` as an identifier;
- `pure actor fnc`;
- `pure async fnc` in v1;
- stdout/stderr/stdin;
- file/network/database/socket I/O;
- environment variable read;
- cwd/process metadata read;
- wall/monotonic clock read;
- randomness;
- actor spawn;
- mailbox send/receive;
- actor/singleton/module mutable state read;
- actor/singleton/module state write;
- lock/mutable atomic/thread/scheduler query;
- mutable borrow parameter mutation;
- mutable local state escaping in return value;
- mutable local state escaping through returned closure;
- transitive impure helper;
- indirect impure callback;
- unknown callback effect;
- open dynamic dispatch with unknown targets;
- imported callable with missing effect metadata;
- Java/JNI/native call with unknown effect;
- foreign callback retention;
- unsafe external memory access.

### Regression/adversarial

- effect hidden behind several helper layers;
- effect hidden inside generic instantiation;
- effect hidden in mutually recursive SCC;
- effect hidden in closure capture;
- effect hidden in virtual dispatch;
- effect hidden across package boundary;
- effect metadata version mismatch;
- alias created before a `take`;
- mutable state frozen before explicit input transfer;
- pure result containing nested mutable reachable storage;
- exception path that performs I/O;
- deferred cleanup that performs I/O;
- finally block that performs I/O;
- unreachable source branch containing forbidden effects is still type/effect checked before optimizer removal unless the language explicitly proves compile-time unreachability under the checked-build-define rules.

## Optimizer contract

`pure` is useful compiler information, but it is only one prerequisite. In particular, `pure` does not collapse Oreslang's `throw`, `raise`, `panic`, or `trap`/`recover` control-flow distinctions.

A compiler may not infer any of the following from `pure` alone:

- call elimination when the result is unused, because the call may throw, raise, panic, or diverge;
- memoization, because identity-bearing allocations may be returned;
- common-subexpression elimination across identity-sensitive results;
- arbitrary reordering, because throw/raise/panic/divergence ordering may change;
- speculative duplication, because cost/divergence behavior may change.

Stronger optimizations require additional proven facts such as `nothrow`, `noraises`, `nopanic`, termination, value semantics, identity irrelevance, and cost constraints.

## Compatibility with a future effect system

The source language should remain simple in v1:

```ores
pub pure fnc ...
pub pure routine ...
```

Internally, however, all callables carry effect summaries. This permits future explicit capabilities/effects such as I/O, actor operations, clock, or environment access without redefining `pure`.

The invariant to preserve is:

> If an Oreslang `fnc` or `routine` successfully compiles with `pure`, no execution path—direct, transitive, recursive, generic, dynamically dispatched, captured, imported, actor-mediated, Java-mediated, JNI-mediated, or native-mediated—may observe forbidden ambient state or produce a forbidden externally observable effect.
