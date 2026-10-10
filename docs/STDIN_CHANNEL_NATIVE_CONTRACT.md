# Native stdin as a Channel<String> (draft contract)

## Decision

`stdio.stdin` is to be a read-only, context-owned subtype of `Channel<String>`
(lines, without their newline terminators). **Reading an empty stdin channel
via `readch` suspends the Oreslang continuation and releases its carrier;
it does not require `async` or `await`.** The syntax below is the target
native Oreslang API, not evidence that the stdin backend exists today.

```oreslang
pub gen* read_stdin(): Iterator<String> {
  // Native iteration over Channel<T> must finish normally on clean closure.
  for const line of stdio.stdin {
    yield line;
  }
  return;
}

pub routine main(): void {
  for const line of read_stdin() {
    stdio.println(line);
  }
  return;
}
```

A raw `for { const line = readch stdio.stdin; yield line; }` is **not yet**
EOF-safe: ordinary channel `readch` raises a channel-closed error when
the channel is drained and closed. Either implement native channel iteration
that treats clean closure as completion (recommended), or explicitly match
only a clean-close error inside the generator. **Never swallow I/O errors
as if they were EOF.**

## Physical OS pipe backpressure

- The stdin source is not an unbounded, eager background pump. It performs
  at most one bounded read-ahead unit (or zero) for each outstanding downstream
  demand. Bounded buffering of UTF-8 decoding and line assembly is permitted,
  with a strict maximum line length and byte budget.
- Channel capacity is 0 (rendezvous) or tightly bounded. A slow consumer
  stalls subsequent OS reads. The producer shell process then blocks when
  its kernel pipe buffer fills. This provides *eventual bounded* OS pipe
  backpressure, not immediate feedback for each byte already in the pipe.
- A waiting source-side kernel read must run through OS readiness/nonblocking
  I/O or a designated I/O worker, never on the actor/root guest execution
  carrier. `readch` itself stays suspendable and does not require `async`.
- The source is read-only. Reject `writech stdio.stdin`, including static and
  dynamic select writes. Capability-gate stdin access. No implicit fan-out:
  competing channel consumers distribute messages; multicast requires an
  explicit broadcast stage.
- Exactly one source consumes the host stdin file descriptor. Mixed
  `stdio.stdin.read_line()` and channel reads must not race; migrate the
  legacy operation onto the same source or reject simultaneous use.
- On clean EOF: close the channel normally, drain committed values, and let
  `for ... of channel` end normally. On I/O failure: close with a cause
  propagated to the subscriber, not a normal completion.
- Context cancellation closes the channel and wakes registered waiters.
  A blocked physical OS read must have its own shutdown/cancellation path.
- Raw binary input needs an explicitly separate byte-stream API (e.g.
  `stdio.stdin.bytes()`) and cannot interleave unsafely with line readers.

## Observable.from_ch

`Observable.from_ch(stdio.stdin)` should be **lazy**, demand-controlled
and implemented in native Oreslang using the channel/iterator protocol;
it must not turn one bounded channel into an unbounded subscriber queue.

```oreslang
pub routine main(): void {
  const events = Observable.from_ch(stdio.stdin);
  // An implementation with for-of/subscribe demand must not read ahead
  // faster than downstream requests. Subscription closure cancels reads.
  for const line of events {
    stdio.println(line);
  }
  return;
}
```

The observable must explicitly define subscriber demand (`request(n)`
or an equivalent pull/iterator contract), onNext/onComplete/onError,
cancellation, slow subscriber policy, and single-consumer channel semantics.
A naive fire-and-forget `subscribe` cannot claim backpressure.

## Current implementation status / executable proof boundary

`examples/channel-stdin-sync-generator.ores` and
`NativeChannelGeneratorTest` exercise actual, *non-async* native Oreslang
`gen*` + `readch` against a bounded `Channel<String>` with a pending
writer. They cover the foundational suspension contract.

As of this draft, the interpreter still exposes `stdio.stdin.read_line()`
through a host facade, not an actual `Channel<String>`, and
`Observable.from_ch` is not implemented or tested. Real stdin pipe
integration (including EOF, cancellation, select and shell backpressure)
requires a separate reviewed runtime patch and integration tests. Do not
present the synthetic channel test as an end-to-end stdin proof.
