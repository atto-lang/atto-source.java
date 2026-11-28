# AttoLang alloc/box, alloc/vec, alloc/string (backend-local source candidate)

These .atto modules are implemented in this backend's own `stdlib/alloc/` tree.
They are NOT a claim that imports, generic method receivers, heap allocation
and deterministic cleanup all execute on the current compiler head.

## API and safety contracts

- `alloc_box.OwnedBox<T>`: single-slot unique heap object, consuming target `take! T` formals, `boxed`,
  `boxed_copy`/`replace_copy` for verified Copy values, `empty`, `replace`,\n  `take_value`, `is_full`, `is_empty`.
  A Copy-only constructor is not a shallow alias-copy constructor for non-Copy T. This deliberately does not declare
  compiler-reserved intrinsic `Box<T>`; mapping to `Box<T>` requires verified
  unique-ownership and drop lowering.
- `alloc_vec.Vec<T>`: `new_vec`, `push` (ownership transfer), `len`,
  `is_empty`, `push_copy` (only proven Copy payloads), `get_copy` (only proven Copy payloads), `clear`,
  `into_array` and overflow-safe `next_capacity`. `next_capacity` is a
  planning function, **not** an allocation/reserve operation. No unsafe
  `pop`, mutable slice, raw pointer exposure or silent clone is defined.
- `alloc_string.Utf8String`: heap-backed byte vector, Unicode scalar encoder,
  strict UTF-8 validator, character count, character-boundary check and
  staged, receiver-transactional scalar append. `String` conversion requires a separate trusted
  strict codec. If allocation fails, the staged buffer must unwind and be\n  dropped; full OOM-transactionality and deterministic cleanup need guest proof.

Required backend proof: resolve `atto.alloc/*` imports, execute the positive
fixture with real guest code, reject the use-after-take negative fixture,
prove consuming generic formals (`take! T`) and no copied mutable aliases and exact-once drop across replace, clear,
return, throw/raise/panic and cancellation, validate OOM behavior and
sanitizer-backed storage bounds. Preserve existing backend-specific
allocators and arenas; do not couple these sources to atto-common-source.

Until all gates execute against an exact compiler SHA, status is
`SOURCE_CANDIDATE / GUEST_NOT_VERIFIED`, not parity-passed.
