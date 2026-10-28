# Async and suspends contracts

The Java/Graal frontend recognizes an explicit `suspends` callable modifier on functions and methods.

| Declaration | Public result | Permitted operations |
| --- | --- | --- |
| `fnc work(): T` | `T` | Ordinary calls and future creation |
| `suspends fnc work(): T` | `T` after potential task suspension | Direct suspends calls and `await` |
| `async fnc work(): T` | `Future<T>` immediately | Direct suspends calls and `await` |

The modifier pair `async suspends` is invalid. The synchronous-looking `*_blocking` spelling requires a `suspends` declaration rather than `async`; it describes logical blocking of the task, not the carrier thread. The type checker rejects direct calls to explicitly suspending declarations from ordinary function/method bodies. Ownership checking treats those calls as borrow-sensitive suspension boundaries.

**Compatibility caveat:** Legacy unannotated `fnc` bodies containing an explicit `await`, `readch`, or blocking `select` are still accepted; strict enforcement of no-suspension for all ordinary functions requires migration of existing source files and tests. Dynamic callable effects, imported signature effects, and callbacks are not fully enforced yet.

**Runtime caveat:** Java/Graal source task lowering recognizes explicit `suspends` metadata as well as actual `await`/channel/select suspension and recursively checks called function bodies. A declared suspension effect permits suspension; it does not require an admission-only yield when a critical ticket is immediately available. Creating the source task can itself schedule a dispatch, so this is not a promise of execution on the caller's carrier. Host I/O can currently be offloaded to virtual threads and is not guaranteed kernel-level nonblocking. The native LLVM frontend still rejects unsupported suspension constructs. No end-to-end native LLVM support is implied by this change.
