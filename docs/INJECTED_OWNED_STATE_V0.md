# Injected owned scalar state, Java/Graal v0

**Status: draft; not executed on exact head.** This first compiler step
supports `@Inject(MutableStateContainer())` on a concrete, non-generic,
non-inheriting, exclusively owned `define class` with one literal-initialized
`state { ... }` block. It lowers private `int`/`bool` state fields
to ordinary class storage before type/ownership checking and guest execution.

## Memory semantics

- Static class functions are rejected for injected-state v0. Static
  initialization and any implicit cross-instance mutable storage require
  separate source, AOT and ownership proofs.
- One class instance owns its private state. The annotation does not allocate
  or inject an additional shared heap object, runtime lock, or capability.
- State field names are private; outside functions cannot read or write them
  through either `borrow` or `borrow mut`. Existing class receiver and
  whole-object loan checks govern mutation and aliasing.
- A mutating method needs an explicitly mutable receiver. Immutable
  `borrow T` must not invoke mutable receiver methods.
- Initializers in this first slice are restricted to exact `int`/`bool`
  literal AST values, not arbitrary constant expressions, method calls,
  field dependencies or `self` references. This avoids effects or escaping
  an incompletely published instance; negative values expressed as unary
  operations require a later verified constant-folding admission stage.
- Initialized primitive-only fields are deliberate: no object graphs, raw
  pointers, uninitialized values, custom constructors, inheritance, duplicate
  blocks, state-field export or cross-isolate publication are admitted.
- This does **not** implement `self.handle_state(critical[read|write])`,
  sealed actor state loans, atomic transactions, concurrent RW queues,
  `Sync`, `Sendable`, runtime scheduler permits, state injection into
  singleton/actor instances, or any hard/soft real-time performance guarantee.
  Those require separate typed callback and runtime-region lowering.

## Annotation capability admission

Only the **exact** `@Inject(MutableStateContainer())` spelling on an
exclusively owned class is lowerable in this Java v0 branch. Other
`@Inject` forms (including provider lookup, bare and generic spellings),
`@StateBacked`, `@MutableStateContainer` and `@StateContainer` fail
closed with `STATE_INJECTION_UNSUPPORTED` rather than surviving as inert
method, callable or class annotation metadata. Tests cover six unsupported
spellings on both classes and callables, with a comment-only control.
These negative tests are not positive dependency injection or guest parity.

## CI source-integrity preflight

The exact-head Graal workflow now checks the expected commit and requires the
repository's filter-free raw Git blob/tree verifier **before** JDK setup,
Maven, or guest execution. It runs the verifier's synthetic adversarial tests.
The verifier no longer invokes `git diff` on untrusted checkout content:
repository `.gitattributes` can configure a clean filter that Git executes.
The committed test suite adds a configured-clean-filter canary and validates
that stage/index mutations reject through the raw manifest check.

This hardens admission only. The original source organization currently has
zero-step runner failures. A same-repository failed-job retry is not a
successful execution receipt. Private source must not be mirrored to an
alternate organization while conformance incident #263 is open.

## Test receipt

`examples/injected-owned-state-v0.ores` is a real guest source, expected
stdout `1|2` with exit 0. A second candidate,
`examples/injected-owned-bool-state-v0.ores`, expects `false|true` and
checks that mutating one owned instance leaves another unchanged. The JDK25 exact-head workflow runs
`InjectedOwnedStateV0Test` and `AnnotatedMutableHeapStateAdmissionTest`.
Negative tests prove private field denial, immutable-borrow rejection, actor/
callable annotation refusal, and unsupported heap/reentrant state constructs.

**No exact-head Java/JUnit or native guest pass is claimed until workflow
steps execute and the output is verified.** This is not the same exact source
as the seven candidate `critical[read/write]` fixtures in the shared
five-toolchain parity PR #24. The Java-owned subset is a migration step,
not a five-backend parity result.
