# Statechart guest V0 — executable Oreslang source

Status: **experimental alloc-backed userland slice**; not freestanding `core`, not `actor/otp/gen_statem`, not LLVM parity. Related: #1522 semantic proposal, #1638 layout/ECS contract.

## Userland API

File: `stdlib/behavior/statechart.ores`.

- `Chart` is a nominal group of five parallel arrays: `states`, `parent_index`, `sources`, `event_keys`, `targets`.
- `checked(...): Chart` verifies nonempty, <=256 declared states; matching parent count; <=1024 source/event/target rules; unique states and state/event rules; source/target membership; parent bounds and absence of cycles.
- `step(&Chart, Symbol active, Symbol event): Symbol` validates the arrays on every call and selects the first matching **nearest ancestor** rule. Unhandled events return the existing state (stutter). An unknown active state fails closed.
- Only one active state leaf, no orthogonal regions/history/guards/effects/timers or async execution; no side effects are invoked by `step`.
- This initial API uses `Array<...>` for its host/userland representation. Arrays are **not** proven deeply frozen by `val`: callers can alias/mutate them, so the implementation never relies on a one-time validation result. Do not share the mutable descriptor arrays concurrently or across actor domains without appropriate isolation.
- No private integer ordinals are treated as Symbol identities or transport keys. All semantic transitions use logical `#symbols` via `eq`. Untrusted actor visibility and immutable code-gen export roots must follow the existing `OresSymbol` policy.

## Example

```ores
import class Chart from '../stdlib/behavior/statechart';
import fnc checked, step from '../stdlib/behavior/statechart';

pub routine main(): void {
  val Chart chart = checked(
    arr[#running, #idle, #busy],
    arr[-1, 0, 0],
    arr[#idle, #running],
    arr[#start, #reset],
    arr[#busy, #idle]
  );
  val Symbol busy = step(&chart, #idle, #start);
  val Symbol idle = step(&chart, busy, #reset);
  if !(idle eq #idle) {
    rt fail("statechart regression");
  } fi
  return;
}
```

## Integration path

1. Confirm the sample and four invalid fixtures run as **real Oreslang guest programs** under the linked Java/Graal compiler/runtime. `StatechartGuestV0Test` invokes `LinkedProgramRunner.run` and asserts guest outputs/failures. Do not call a standalone Java state model test guest execution.
2. Keep the current pure `step` semantics as the refinement oracle while migrating to one authoritative `Region<T>` AoS/SoA/AoSoA layout and optional ECS behavior components. Do not introduce new writable layout copies.
3. Move pure fixed-capacity descriptors into canonical `core/behavior` only once the `core/alloc/std` namespace split is implemented and formally established to have no transitive heap or OS dependency.
4. Port the same canonical symbol transition fixture to LLVM native guest execution, add TLA+/TLC safety and finite-model trace-refinement; do not claim real-time bounds or unbounded liveness until measured and proved.

## Verification

```sh
mvn -B -ntp -P '!native-thread-unix' -Dtest=StatechartGuestV0Test test
```

Use the exact repository commit, verify all Git blobs, and require actual executed runner steps. If the source org cannot schedule Actions, a private, independently authorized carrier may run an exact-source mirrored **subset** with its own read-only token. Passing a subset must not be labeled a full original source-tree pass.
