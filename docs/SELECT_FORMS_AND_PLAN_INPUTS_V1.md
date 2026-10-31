# Select forms and list/map plan inputs — v1 audit contract

**Status (2026-10-31): staged contract; not a five-backend execution
certificate.** This document separates the current Java host/runtime API from
unmerged source-language syntax and LLVM/.NET/Wasm/Forge implementations.

## One arbitration engine, two case-layout categories

- A **static select site** has arms known at compile time. Evaluate dynamic
  channel/value operands each invocation, unless hoisting is proven safe for
  the owning actor, lexical binding, and lifetime. A cached descriptor must
  never retain live wait registrations or illegally hoist a move-only write.
- An **immutable SelectPlan** contains an ordered, owned snapshot of case
  descriptors. Reusing it does not reuse a completed winner or pending
  registrations. Its FAIR cursor belongs to the owning plan/actor domain.
- A **DynamicSelectPlan<K>** supports keyed add, replace and remove. Every
  mutation advances a bounded nonwrapping version and creates a new snapshot
  generation. A pending selection remains bound to its captured generation
  and original keys even when the plan changes. FAIR advancement for an old
  generation must not rotate the new generation.

Every form MUST use the existing atomic ChannelRuntime selection/winner path
(or an independently verified backend-equivalent protocol): exactly one arm
commits, no losing read consumes a value, no losing write publishes data, and
all loser subscriptions detach on winner, cancellation, close or actor STOP.
A new map/list constructor is not permission to implement a second selector.

## List and map inputs

| Plan | List input | Map input | Keyed result |
| --- | --- | --- | --- |
| Immutable host `SelectPlan` | `fromList(List<SelectCase>)` / `from(Iterable)` | `fromMap(Map<K,SelectCase>)` | **No**: current host API returns index-based SelectResult |
| Mutable host `DynamicSelectPlan<K>` | `fromList(List<SelectCase>)` yields `DynamicSelectPlan<Integer>` | `fromMap(Map<K,SelectCase>)` yields `DynamicSelectPlan<K>` | **Yes**: PlannedResult has version, key and selection |

The Java host implementation uses camelCase because it is a bootstrap
runtime. Any eventual Oreslang source API uses snake_case names, e.g.
`SelectPlan.from_list`, `SelectPlan.from_map`,
`DynamicSelectPlan.from_list` and `DynamicSelectPlan.from_map`.
**Do not declare those source constructors implemented until the actual
parser, typechecker, ownership checker and guest execution tests pass.**

1. A list assigns case identity 0, 1, …, N-1 at creation. The input list is
   read once and is never retained as a mutable alias. Later dynamic mutations
   preserve the original integer key, not a sliding array position.
2. A map assigns keys from its iteration order. Use a deterministic ordered
   map (e.g. LinkedHashMap in Java) for reproducible FIRST/PRIORITY/FAIR.
   A backend with unordered maps MUST canonicalize order explicitly or reject
   that map for ordered select; never depend on hash randomization.
3. Key equality must be stable while the plan exists. No null keys, no null
   cases, at most one default, nonempty plan at selection, and no more than
   MAX_SELECT_CASES descriptors. Constructors must reject invalid inputs
   without publishing a partially valid plan to callers.
4. Plan ownership is actor-domain scoped. SelectCase and SelectSet remain
   non-Copy/move-only capabilities in source; retained write cases require
   reusable Copy payloads. A borrowed/guard-bearing or move-only payload
   cannot escape into a reusable descriptor.
5. Snapshot creation copies case references/descriptors, not a live mutable
   list/map. That does not make the underlying captured channel or Future
   immortal; its lifetime and permissions are checked independently.
6. Keyed results are tied to the snapshot version. Index-only results MUST
   NOT be mistaken for stable map identities. The immutable Java map factory
   currently discards map keys: use DynamicSelectPlan.fromMap when keys matter,
   or add a separate typed immutable keyed API with its own conformance tests.
7. Zero-GC/native targets should store owned plan arrays/maps in actor arenas
   or explicit regions, with deterministic retirement, no mandatory
   per-selection plan allocation and bounded fresh wait registrations.

## Source spelling and result/return matrix

| Form | Intent | Required result/return semantics | Current Java main caveat |
| --- | --- | --- | --- |
| `select { ... }` | static value selection | `T`; arm return is select-local | Main still lowers static statements, not general value expressions |
| `select over ... end` | same as braced | exact delimiter parity | Staged on draft #2079, not main |
| `do select { ... }` | procedural static select | `void`; winning arm return may exit enclosing callable | Existing procedural path |
| `do select over ... end` | procedural delimiter parity | same as braced | Staged on draft #2079 |
| `nb select { ... }` | nonblocking static value select | `Future<T>`; resolves after winner arm, failure and cleanup | Main's detached statement behavior is transitional |
| `nb select over ... end` | nonblocking delimiter parity | same typed Future as braced | Staged on draft #2079 |
| `do nb select` | legacy detached procedure | deprecated/reject in canonical grammar; migrate to observed `Future<void>` | Java/formatter still accept legacy form |
| `select from cases` | dynamic, suspending result select | `Option<SelectResult>` until typed source promotion | Exists in Java guest subset |
| `nb select from cases` | dynamic async submission | `Future<Option<SelectResult>>` until typed source promotion | Exists in Java guest subset |
| `do select from cases` | unsafe dynamic outcome discard | reject: no arm body to process result | Rejected in Java parser |

Policy spellings `first`, `fair` and `random` are independent of
`over` versus braces and of a plan's input container. FAIR should select
by forward circular distance at the **commit** point and advance only
after a real committed non-default winner, never after a cancellation,
failed admission, default-only result, stale completion or try miss. Do
not conflate `nb select` (returns Future) and `try select` (immediate probe).

An actor guest select must suspend the continuation rather than park a
bounded physical actor carrier. The selected arm executes only in an
authorized current actor incarnation; cleanup/defer/finally executes once,
including returns, cancellation, STOP and panic/raise paths.

## Required gating tests before declaring parity

- Static spelling × braces/over × FIRST/FAIR/RANDOM × typed return/void,
  including nested `do select`, lambda return boundaries and bad arm types.
- Immutable and dynamic plans built from list and ordered map; empty/null,
  duplicate default, MAX+1 arm quota, unstable-key and mutable-input negatives.
- Reuse 10,000 times without stale registrations; dynamic versioned snapshot
  pending before a replacement/removal, then completing after it.
- Two simultaneously ready channels, losing read nonconsumption and losing
  write nonpublication; Future loser detachment without canceling producer.
- Default must not advance FAIR state; unrelated actor/domains must not
  share fairness cursor or select admission quota.
- Cancellation/winner/close/actor-stop races and hot reload, exactly one
  terminal settlement and no use-after-free, including native ASan/UBSan/TSan.
- Compiler-owned source parse/type/ownership/execution evidence on exact
  heads for Java, LLVM, .NET, Wasm and Forge, plus Rust formatter idempotence.
  Negative unsupported-source rejection is not positive runtime parity.
- Evidence must pin commit/tree and executed test steps. A zero-step Actions
  run or source-free reference model is not compiler/runtime evidence.
  Do not export private backend sources to a different organization without
  independent security authorization and protected release controls.

## Existing integration points

- Java current source: `ChannelRuntime.SelectPlan`,
  `ChannelRuntime.DynamicSelectPlan`,
  `DynamicSelectPlanGenerationTest`.
- Java [#2079](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2079)
  stages `over ... end`; [issue #2085](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/2085)
  tracks static value/Future expression integration.
- [Conformance #17](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/17)
  tracks FAIR compiled-plan reuse.
- LLVM `docs/SELECT_PLAN_PARITY.md` explicitly states that native select
  plan source lowering is not yet implemented; preserve fail-closed tests.
