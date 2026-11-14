# Canonical enums and Copy-safe named case aliases — Java/Graal V1

**Status:** IMPLEMENTATION DRAFT / GUEST TESTS AUTHORED NOT EXECUTED.
**Specification:** `attolang-language-spec.md#60`.
**Stacked on:** `oreslang-source.java#907` (`feat/nominal-message-enums-20261013`), itself depending on value match #901.

## Implemented in this branch

- Canonical `.atto` source `define enum Messages { Reset { reset: int }, Delta { delta: int }, Ready }` parses to the existing nominal enum declaration. Local tags are deterministic and derived from declaration order; this is **not** a versioned transport-wire tag allocation. Legacy explicitly tagged `define enum ... as ... end` remains valid.
- The parser and type checker resolve `on Ready` relative to a statically known `Messages` scrutinee. `on Messages.Ready` remains legal. Bare variant spelling does not authorize cross-enum case matching.
- `on Ready as r` and `on Messages.Ready as r` preserve a whole-case alias in the AST. Whole-variant aliases now also work on **Copy-safe named payload cases** such as `on Run { job_id, payload } as r` provided EVERY field on EVERY variant of the nominal enum is an immutable, nonrecursive scalar. Type-checker stores the lexically narrowed case-field types, and the evaluator binds the SAME authenticated `EnumValue` without cloning its payload. This is a Copy-only implementation slice, **not** a general proof of borrowed `Bytes` aliases.
- `on Run { job_id, payload } as r` validates field names and duplicates against the nominal case payload, binds Copy-safe fields by name, allows `r.job_id` static projection, and rejects field-renaming patterns (deferred feature).
- The ownership checker independently restricts enum copyability to closed scalar-only payloads and rejects recursive, owned, actor, dynamic, pointer and `Bytes` payloads for named-field matching/whole aliases. Exhaustiveness and unreachable-arm checking include the new irrefutable named-field patterns.
- `do match` remains the statement spelling; `match` remains the value expression. Existing enumerated constructor payload patterns such as `on Reset(payload)` can appear unqualified.
- Unknown variant, foreign enum, duplicate/unreachable arm and missing-variant exhaustiveness diagnostics are checked before guest execution.
- Positive and negative `.atto` fixtures and JUnit guest tests:
  - `CanonicalEnumDeclarationGuestTest`
  - `BareEnumPatternGuestTest`
  - `NullaryEnumCaseAliasGuestTest`
  - `NamedEnumCaseFieldsGuestTest`

## Explicitly unsupported (fail closed)

- **Named non-Copy fields:** `on Run { job_id, payload } as r` with `payload: Bytes` is deliberately rejected. This still needs case-refined readonly borrows, per-field ownership proof, actor-turn invalidation, and suspension escape analysis. Copy-only scalars are supported in the new partial implementation.
- **Whole-case alias of non-Copy payloads:** `on Run as r` is rejected if any enum variant is move-only. We cannot turn an actor-owned `Bytes` field into an implicitly copied JVM object.
- **Typed actor receiver and transport:** `mtd (Worker self) receive(msg: WorkerMsg)`, `ActorRef<WorkerMsg>`, send admission, capability lifetimes, safe local transfer and versioned wire codecs are separate, NOT IMPLEMENTED by this change.
- Untrusted/cross-process deserialization, authenticated supervisor CONTROL, lifetime across suspension/parallel turns and allocation benchmarks require distinct proof.

## Required execution evidence

On the exact immutable head SHA, run
```sh
mvn -B -ntp -Dtest=CanonicalEnumDeclarationGuestTest,BareEnumPatternGuestTest,NullaryEnumCaseAliasGuestTest,NamedEnumCaseFieldsGuestTest -DfailIfNoTests=true test
```

The workflow `.github/workflows/enum-case-alias-java-v1.yml` requires exact checkout and retains Surefire receipts. **No JUnit, Graal, or Native Image execution is claimed until a runner actually executes test steps.** Source org may fail before runner admission; a zero-step failure is **NOT EXECUTED**, not a passing or failing assertion.

## Security and branch integration

This is a stacked implementation against older PR #907, not a safe blind cherry-pick onto current main. Before merging, rebase/reconcile new parser/match changes against current main and newer nullary enum PRs (#2847/#2860) without overwriting ownership, actor or symbol security fixes. Test existing legacy explicit-tag programs for compatibility. No cross-organization private source-tree export without independently authorized exact source snapshot/destination and private-source export gates.

The compiler frontend is host Java by necessity; guest-visible programs/tests are `.atto`, not host-language emulations.
