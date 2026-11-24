# Expression trap and extraction

`trap! expression` and `trap expression` evaluate the operand once. Success
produces `Ok(value)`. An escaping ordinary throw runs lexical cleanup
and produces `Err(error)`. An existing Result passes its success or error
through, and the error type widens when needed to accommodate `Error`.
An optional value stays optional within `Ok`.

`trap` binds as a prefix unary operator, including member calls.
`trap! (left() + right())` covers the complete sum evaluation.

| Operand | Trap type | Result when the operand is absent |
| --- | --- | --- |
| `try_lock()` | `Result<Option<Guard>, Error>` | `Ok(None)` |
| `try_lock().unwrap()` | `Result<Guard, Error>` | Panic bypasses trap |
| `try_lock().unwrap_safe()` | `Result<Guard, OptionUnwrapError \| Error>` | Original Err passes through |

The table's `Guard` is illustrative. Where the checker lacks a proven loan
lifetime, it rejects using `trap` to wrap an existing borrow.

`unwrap()` panics for both `None` and `Err`. Raise, panic, cancellation,
forced termination and unclassified host failures bypass trap. A successful
evaluation retains its side effects, and cleanup runs once on unwind.
A trap stays in its admitted execution domain and has no authority to
capture a detached task's exception.

See the [callable and effect contract](TRAP.md) for ownership, suspension
and coverage requirements.

## Cleanup and continuation invariants

Trap preserves completed writes and I/O. A successful owned guard transfers
exactly once. Each resumed expression delivers one terminal completion,
and the block walker finalizes its environment once. The shared failure
policy traverses a bounded set of causes and suppressed failures.
Explicit guest catch and trap use their respective admission rules.
