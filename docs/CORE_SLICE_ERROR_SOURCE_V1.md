# Freestanding core/slice and core/error — source helpers v1

**Status:** candidate `.atto` modules on a draft branch. Reconciled public window semantics with sibling Java PR #1772; no executed compiler proof yet.
Compiler/JIT execution is tested by `CoreSliceErrorGuestTest` when CI runs;
no test success or cross-backend guest parity is claimed until actual steps pass.

## Imports

```atto
import module slice from "core/slice";
import module error from "atto.core/error";
// ores.core/* remains a compatibility spelling.
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
- `valid_index`, `valid_endpoints`, `valid_range`, `range_len`, `valid_window`,
  `valid_strided_window`: pure overflow-safe validation. A zero-element
  window at the one-past-end offset is valid. The stride helper accepts only
  positive strides and validates the last element by division, not multiplication.

`valid_range(offset,count,length)` means a logical **window**, not start/end.
`valid_endpoints(start,end,length)` checks explicit half-open endpoints.
`get_copy_int`/`first_copy_int`/`last_copy_int` read copyable `Array<int>`
elements as `Option<int>` via checked `get_copy`; no borrowed pointer leaves
this call. `subslice_offset` is an arithmetic receipt only and cannot preserve
a source lifetime. `valid_reverse_window` preflights negative strides, with
special handling for signed MIN_INT. The compiler still must enforce array
ownership even for an out-of-bounds checked read.

**Not implemented:** `Slice<T>` view creation, `slice_mut`, zero-copy
pointer/owner receipts, lifetimes, reverse/negative strides, iterator adapters,
borrow overlap rejection, use-after-free rejection, or actor/await escape gates.
Those require compiler/runtime enforcement. No exported helper authorizes a
host pointer, mutable alias, detached view, or actor transfer. Reconcile the
candidate collection-slice-spread spec (atto-spec PR #31) before enabling them.

## core/error

- `is_known_core_code`, `code_group`, `is_bounds_code`,
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

## Reconciliation and admission details

The sibling source PR atto-source.java#1772 uses `valid_range` as
`(offset,count,length)`; these helpers now agree. Endpoints use a separate
`valid_endpoints` name. Only the first-class canonical `atto.core/*` and
unqualified `core/*` names are preferred for new imports; `ores.core/*` is
still accepted and resolves to the SAME sealed source unit.

New JUnit cases attempt linked Array<int> copy operations, mutate-after-copy,
MIN_INT negative-stride preflight and reject a wrong element type. Tests are
not evidence until the runner executes actual nonempty steps. This does not
change source actor/state borrow checking or allow a generic `Slice<T>`.

## Raw vs normalized indexing

`valid_index(i,n)` checks only raw nonnegative indices in `[0,n)`;
`valid_signed_index(i,n)` accepts `[-n,n)` and applies negative
normalization as in the candidate collection spec. `get_copy_int` uses
`normalize_index`, so negative relative indexes still return owned copies.
`is_empty(count)` and `can_split_at(count,middle)` follow the sibling
slice/iter/cmp logical-window API. Never conflate `valid_range` with
`valid_endpoints` or raw with signed indexing.

## Ownership correction — explicit borrowing

`get_copy_int`, `first_copy_int` and `last_copy_int` now accept
`borrow Array<int>`, not an owned `Array<int>`. Call with
`rt borrow values`: passing an owned array by value consumes its unique
owner under Atto's affine parameter rules. The checked-copy helpers must
never move the owner merely to return `Option<int>` or `None`. First/last
read directly from a validated borrowed array, avoiding an implicit reborrow
or move through an intermediate helper. Access under an outstanding exclusive
mutable loan must be rejected, even on out-of-bounds paths. Native LLVM must
implement these semantics before the unadmitted guest fixture can pass.
