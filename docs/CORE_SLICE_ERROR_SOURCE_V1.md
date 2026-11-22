# Freestanding core/slice and core/error — source helpers v1

**Status:** new candidate `.atto` helper modules on a feature branch.
Compiler/JIT execution is tested by `CoreSliceErrorGuestTest` when CI runs;
no test success or cross-backend guest parity is claimed until actual steps pass.

## Imports

```atto
import module slice from "ores.core/slice";
import module error from "ores.core/error";
```

Both modules use signed 64-bit `int`, no host imports, no heap,
no runtime scheduler, and snake_case exported functions.

## core/slice

- `normalize_index(index,length) -> int`: signed negative indexing, `-1` for
  invalid inputs; the returned nonnegative index is safe to use only with the
  **same live backing storage** after compiler-enforced owner/lifetime checks.
- `normalize_bound(bound,length) -> int`: explicit half-open endpoint in
  `[0,length]` or `-1` if invalid. Explicit `-1` is normalized to the final
  element; it must **not** be confused with the omitted reverse-end sentinel.
- `valid_index`, `valid_range`, `range_len`, `valid_window`,
  `valid_strided_window`: pure overflow-safe validation. A zero-element
  window at the one-past-end offset is valid. The stride helper accepts only
  positive strides and validates the last element by division, not multiplication.

**Not implemented:** `Slice<T>` view creation, `slice_mut`, zero-copy
pointer/owner receipts, lifetimes, reverse/negative strides, iterator adapters,
borrow overlap rejection, use-after-free rejection, or actor/await escape gates.
Those require compiler/runtime enforcement. No exported helper authorizes a
host pointer, mutable alias, detached view, or actor transfer. Reconcile the
candidate collection-slice-spread spec (atto-spec PR #31) before enabling them.

## core/error

- `is_known_core_code`, `is_bounds_code`,
  `is_retry_advisory_code`, `is_actor_api_code` are **data-only** predicates
  for the in-flight 99-class error catalog (Java PR #662).
- `valid_message_length` checks nonnegative byte-count budgets but does not
  inspect or sanitize message data.
- Error codes are **not** nominal class identity. Never use a code predicate
  to authorize `throw`, `raise`, `panic`, catch, capability checks or retries.
  An opted-in retry code is advice only; idempotency and policy still rule.
- This module does **not** define a second `Error` root: source Error,
  subclasses, cause/trace and native failure lowering remain in existing
  Java #662/#703 and LLVM #327/#328 PRs. Do not merge a conflicting root.

## Required follow-up

Run `mvn -B -ntp -Dtest=CoreSliceErrorGuestTest test` and material full suite
against the exact PR head. Then build compiler-enforced immutable/unique mutable
views and typed `Result<T,Error>` accessors, add negative fixtures for escaping
loans, overlapping mutable slices, actor transport and suspension, and port to
independent backend-owned modules.
