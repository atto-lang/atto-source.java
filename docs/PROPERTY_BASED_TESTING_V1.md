# Property-based testing v1 (seeded generation + deterministic shrinking)

Status: **draft / first executable slice** (Java compiler, pure AttoLang stdlib).
Feature 2 of the 15 "additional features" proposals; design-by-contract
(feature 1) is atto-spec #211 / atto-source.rs #516.

## Goal

Let AttoLang tests state a property once and have the toolchain search for a
counterexample, then shrink it to the simplest failing input, with every
failure replayable from the printed seed. No interpreter, reflection, wall
clock or hidden global RNG, so the same source works under full AOT on every
backend.

## Shipped in this slice (`stdlib/testing.ores`)

```atto
fnc below_one_hundred(int value): TestOutcome {
  return new TestOutcome(value < 100, "value must stay below 100");
}

val TestOutcome o = check_int(
  "below100",
  new IntConfig(42 /*seed*/, 200 /*runs*/, 0 /*lo*/, 1000000 /*hi*/, 256 /*max_shrinks*/),
  below_one_hundred);
// o.message ==
// "property below100 falsified: seed=42 run=1 original=1000000 shrunk=100 shrink_steps=11"
```

* `IntConfig(seed, runs, lo, hi, max_shrinks)`: all bounds are validated
  before the property runs (fail closed): `lo <= hi`, `hi - lo < 2^31 - 2`,
  `runs` in `[1, 100000]`, `max_shrinks` in `[0, 4096]`.
* **Generation:** run 0 is `lo`, run 1 is `hi`, run 2 is the shrink target
  (0 when in range, otherwise the nearest bound). After that come MINSTD
  (Park–Miller, multiplier 48271) draws. Every product fits in i64, so the
  generator can never hit a checked-arithmetic trap.
* **Shrinking:** greedy and bounded. The value moves toward the target by
  power-of-two steps and keeps a step only while the property still fails.
  For monotone properties this finds the exact boundary. AttoLang int `/`
  yields float, so the algorithm uses no division.
* **Replay:** the report includes `seed` and `run`; rerunning with the same
  config yields the identical counterexample (tested).
* `seed_state`, `next_state`, `draw_int` and `shrink_target` are public so
  that later generators reuse one deterministic stream.

## Tests

`PropertyShrinkingStdlibTest` runs `examples/prop/property-shrinking.atto`
through the real Java compiler/runtime (`LinkedProgramRunner`) and checks:

* a passing property passes,
* the shrunk boundary is exact (`100`),
* a shrink toward the nearest bound works when 0 is out of range (`-42`),
* the same seed replays the identical counterexample,
* an invalid config fails without calling the property.

## Known limitation found on main (not introduced here)

On Java `main` at 8249f34365, `NativeTestingFrameworkTest` already fails with
`unknown Future instance member passed`: a callable whose body returns an
*imported* function's result (e.g. `return expect_true(...)`) yields an
unjoined Future when invoked through a function value from another unit.
The example's predicates therefore construct `TestOutcome` directly. Once
that runtime regression is fixed, `expect_true`-style predicates and
`run_suite` integration work unchanged.

## Next slices (proposed)

1. Generators for `bool`, `String` (bounded length, ASCII/UTF-8 classes),
   `List<int>`, with list shrinking (drop chunks, then shrink elements).
2. `IntConfig` defaults and an `ATTO_PROP_SEED` replay override supplied by
   `atto test` (the CLI owns the env read; the stdlib stays pure).
3. Language sugar, gated on spec review:
   `test property "name" for int x in 0..1000000 { ... }` lowering to the
   same stdlib calls.
4. Cross-backend parity: the module is pure AttoLang, so rs/llvm/js/wasm
   only need the same `Integer.mod` semantics plus callable invocation; add
   the example to the parity corpus.
5. Actor properties: combine with deterministic actor scheduling
   (atto-spec #207), so a failing interleaving shrinks to a minimal schedule.
