# AttoLang `std/channel`

Implementation: `stdlib/channel.atto`. Use `import module channel from "std/channel";`.
The existing compiler-owned `ores.std/channel` spelling resolves to the same
packaged source. **Do not introduce** `ores.std` in new Atto programs.

## Domain and ownership

`Channel<T>` is a local execution-domain capability: it is **not** an
`ActorRef` or actor-mailbox transport. The compiler must reject borrowed and
callable payloads, including nested callable fields. Passing a channel across
an actor or tenant boundary must fail closed unless an independently verified
capability/codec contract explicitly allows it. Noncopy payloads must be moved
exactly once; use explicit `rt take` at call sites.

The Atto module is source-only. It calls the existing runtime/compiler-owned
`Channel<T>` and `readch`, `writech`, `nb`, `try` instructions.
It never uses Java interop or creates another host queue.

## API

| Function | Return type | Contract |
| --- | --- | --- |
| `bounded<T>(capacity)` | `Channel<T>` | Finite capacity, including zero rendezvous. Invalid negative capacity fails closed |
| `rendezvous<T>()` | `Channel<T>` | Exact capacity zero, no hidden buffer |
| `send<T>(ch,value)` | `void` (async) | Suspends until write commits, transferring ownership |
| `receive<T>(ch)` | `T` (async) | Suspends until a value is consumed |
| `send_pending<T>(ch,value)` | `Future<void>` | Registers a write and returns immediately |
| `receive_pending<T>(ch)` | `Future<T>` | Registers a receive and returns immediately |
| `try_send<T>(ch,value)` | `TryWriteResult<T>` | One immediate, non-registering admission attempt |
| `try_receive<T>(ch)` | `Option<T>` | One immediate, non-registering read, empty means not ready |
| `close<T>(ch)` | `void` | Idempotent; buffered values remain readable before EOF |
| `is_closed<T>(ch)` | `bool` | Lifecycle observation; not a substitute for atomic admission |

`TryWriteResult<T>` has `Accepted`, `Full(T)` and `Closed(T)`. The
latter variants **return the original payload**. The caller must not discard
this result or accidentally use the moved binding after admission. The
ownership checker, not a library Boolean flag, must enforce this rule.

`try_receive` returns `None` only when currently not ready. Once closed
and drained, the runtime's closed error is distinct from `None`. After
close, new sends fail. Already committed buffered messages drain FIFO.
A pending operation must be removed/settled by the runtime under the same
arbitration as regular channel/select operations. Never busy-spin or park an
actor carrier to emulate a blocked send/receive.

## Source example

```atto
import module channel from "std/channel";

pub async routine main(): void {
  val Channel<int> ch = channel.bounded<int>(2);
  await channel.send<int>(ch, 10);
  await channel.send<int>(ch, 20);
  stdio.stdout.write(await channel.receive<int>(ch));
  stdio.stdout.write(await channel.receive<int>(ch));
  channel.close<int>(ch);
  return;
}
```

## Unsupported or separately owned API

- `unbounded<T>()`: intentionally absent from Java/Graal until a
  genuinely quota-safe growable implementation is verified. A huge
  bounded queue must never be advertised as unbounded.
- `stream()` / `async_iter()`: compiler/runtime channel iterator
  integration remains separately tracked in Java PR #550; it must not
  be reimplemented by an Atto busy-polling helper.
- Directional read/write-only channel endpoints, `select` cancellation,
  cross-tenant transport and actor mailbox admission remain compiler/runtime
  capability concerns, not wrapper methods that can mint extra authority.

## Verification

Compile and run actual guest fixtures, not just parse the `.atto` file:

```sh
mvn -Dtest=ChannelStdlibAttoSourceTest test
```

Expected fixtures: `buffered_fifo`, `rendezvous_pending`,
`immediate_probe`, `negative_capacity`, `owned_payload`,
`close_drain`, `try_read` and `closed_move`. Guest execution, native
AOT, race/cancellation, and cross-compiler parity must remain marked
NOT_EXECUTED until a stepful exact-head CI or local compiler receipt exists.

## Independent backends

Different backends may own different native Atto implementations, with
compatible public behavior but not byte-identical files. LLVM's current
`Channel<int>` production guest gate rejects unsupported native lowering;
the unmerged native i64 channel ABI PR is not source-level channel support.
The Rust/Wasm v2 `.atto` prelude (source.rs PR #262) implements its own
single-guest-memory channels, including a runtime `-1` unbounded sentinel;
this must not be confused with Java support or used as cross-tenant
transport. The .NET, Wasm-worker, Forge, JS, HDL and Cranelift backends need
independent capability and execution audits before channel parity claims.
