# Native Oreslang stdin/stdout/stderr channels — implementation review

## Source-facing contract

- `stdio.in` and `stdio.stdin` return the **same** context-owned, read-only
  `Channel<String>` subclass (line-oriented, UTF-8 from the embedder).
- `stdio.out` and `stdio.err` return distinct context-owned, write-only
  `Channel<String>` subclasses. `stdio.stderr` aliases `stdio.err`.
- `readch`/`writech` are suspension operations, **not native-thread-blocking
  language effects**. They do not require `async`. `nb writech` returns a
  `Future<void>` for bounded sink admission.
- `for const line of stdio.in` uses the suspendable source-task lowering:
  **clean channel closure -> natural end of iteration**. Raw `readch` on a
  drained closed channel still throws `ChannelClosedException`, as for other
  channels. I/O close with a cause always propagates failure.
- `pub gen* read_stdin` can iterate stdin and yield without `async`.

```oreslang
import module Stdio from "std/stdio";
import module Observable from "std/observable";

pub gen* read_stdin(): Iterator<String> {
  for const line of stdio.stdin {
    yield line;
  }
  return;
}

pub routine main(): void {
  for const line of Observable.from_ch(stdio.in) {
    Stdio.println_blocking(line);
  }
  Stdio.eprintln_blocking("finished");
  return;
}
```

## Write semantics

`stdio.out` and `stdio.err` are rendezvous channels (capacity 0) with
dedicated descriptor sink workers. `writech stdio.out, text` suspends the
source continuation until the sink worker **accepts** that frame, without
parking a guest execution carrier. `nb writech` immediately returns a
future settling when the sink accepts the frame; awaiting it is optional.

**Accepted does not mean flushed.** A previously accepted frame may still be
in a sink worker's OS write. The context attempts to join sink workers during
graceful close (bounded wait); force kill, a permanently blocked host stream,
or an OS write error can still lose that frame. Strong durable flush
acknowledgment and atomic multi-fd sequencing require an additional barrier
API. A default fire-and-forget `do writech` syntax is intentionally not
introduced without explicit unobserved-error policy.

`std/stdio.ores` implements `Stdio.println` and `Stdio.eprintln` returning
an admission future; `println_blocking` and `eprintln_blocking` use
ordinary `writech` for bounded admission. `println_parts_blocking`
concatenates `List<String>` into **one** channel frame, preventing individual
arguments from interleaving with a concurrent writer. Function parameter
rest syntax is not yet enabled; the native library uses the list form rather
than inventing a working `...args` function declaration.

Older `stdio.println` and `stdio.stdout.write` APIs remain in the
compatibility facade; they have not been silently changed to the new
asynchronous channel semantics. Import `Stdio` to select the new native
Oreslang library.

## Input backpressure and errors

The host stdin worker reads one bounded line at a time and waits for its
rendezvous to complete **before the next read**. As a result, a slow Oreslang
consumer stops further host reads, and a shell producer eventually blocks
once the finite OS pipe buffer fills. Already-buffered bytes are not
retracted. The line limit is 65,536 UTF-16 code units. Oversized lines close
with an error rather than masquerading as EOF.

Only the descriptor bridge performs OS reads/writes; all generator,
Observable, composition, formatting, and cancellation decisions are in
Oreslang source, with no guest Java imports. The current reference
interpreter itself is Java/Truffle, so this **is not** a Java-free runtime
implementation: the OS descriptor boundary has a small Java host adapter.
That adapter is not a general Java interop API.

Closing the context closes the channels and wakes channel waiters. The input
worker uses an interruptible dedicated daemon thread and does not park an
actor's carrier. A generic host-provided `InputStream.read()` is **not**
guaranteed to unblock on interruption. Full forced cancellation under every
embedding requires a pollable native descriptor/readiness backend rather
than a generic Java stream. The current design does not claim this stronger
guarantee.

The existing isolate capability `STDIN` controls stdin; `STDOUT` controls
both output descriptors for backward compatibility. A separate `STDERR`
capability needs an explicit policy migration. Runtime select registration
rejects writes to stdin and reads from output, including dynamic selects.
The subtype relation is enforced at the runtime boundary; current static
type checking still treats stdio descriptors as `Channel<String>` because
`ReadChannel<T>`/`WriteChannel<T>` variance is not yet modeled.

## Observable.from_ch

The shipped `std/observable.ores` module implements `Observable.from_ch<T>`
as a **cold, pull-driven generator** returning `Iterator<T>`: a yielded value
is delivered only when its consumer requests it. EOF completes iteration,
errors propagate, and breaking cancels the generator's pull loop.

This first native Observable adapter deliberately exposes one-consumer
semantics. It is **not** an Rx multicast hot observable: subscription
handles, demand credits (`request(n)`), `onError`/`onComplete` callbacks,
and independent per-subscriber queues are not yet implemented. Use an
explicit EventBus/broadcast stage for multicast rather than pretending
channel messages are copied to all subscribers.

## Validation

`NativeStdioChannelsTest` exercises real embedder-supplied stdin,
stdout/stderr capture, clean EOF, plain non-async for-of, a synchronous gen*
declaration, imported native std modules, output admission futures,
rejection of invalid directions and oversized lines.

**Test execution has not yet been verified.** Source-org GitHub Actions has
returned `runner_id=0, steps=[]` for this PR. Review, actual Maven/GraalVM
25 execution, exact-head CI mirroring, native AOT conformance, and physical
shell-pipe cancellation/backpressure tests remain merge blockers.
