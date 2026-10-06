# rx-ores native async/channel integration

rx-ores is the reactive-stream layer above the Oreslang runtime primitives. It does not introduce a second scheduler, Future, channel, actor, or cancellation system.

## Cardinality

- Future<T> represents one eventual result.
- Observable<T> represents zero or more results over time.
- Channel<T> is the native asynchronous transport for a stream of values.
- await consumes one Future-like result.
- select suspends on native asynchronous cases.
- nb readch, nb writech, and nb select register asynchronous readiness without suspending the current actor turn.

The core bridge is Future<T> <-> Observable<T> and Channel<T> <-> Observable<T>.

## Channel -> Observable

The native adapter is Observable.from(channel).

A channel-backed Observable is a view of the existing channel. It is hot/shared at the transport level: multiple subscriptions compete to receive values from the same channel. Multicast/replay semantics must be explicit rather than silently duplicating channel messages.

The mapping is:

| Channel event | Observable event |
| --- | --- |
| successful read | NEXT(value) |
| channel close after buffered data drains | COMPLETE |
| channel failure | failed Future |
| subscription cancellation | cancel pending read / unregister readiness |

Cancellation does not close the caller-owned channel.

The implementation uses ChannelRuntime.Channel.readAsync(), the native registration boundary corresponding to nb readch. No Rx thread is created.

## Observable -> Channel

OresObservableChannelBridge adapts a stream to a caller-owned native channel.

The bridge reads one Observable notification and then calls Channel.writeAsync(value). The native write registration is therefore the backpressure boundary corresponding to nb writech.

The current Java substrate exposes new OresObservableChannelBridge<>(stream, channel).start(). The eventual Oreslang facade should expose the same operation as stream.to_channel(...).

## Select / merge

Channel-backed Observable.merge(...) uses ChannelRuntime.SelectSet, the native nb select registration substrate.

Selection retains native FAIR/PRIORITY/RANDOM semantics and does not create an Rx scheduler.

Closing one merged channel removes that source from the merge set. Closing all sources completes the merged Observable.

## Future / await interop

Observable.fromFuture(future) emits exactly one value and then completes. Observable.first() converts the first stream value back into Future<T>. Observable.collect() converts a finite stream into Future<List<T>>.

The intended language surface is: await users.first(), await stream.collect(), and Observable.from(channel).

await remains the one-result primitive. Subscription remains the repeated-notification primitive. Rx operators are the stream algebra between them.

## Scheduler invariant

A channel reactor, timer, I/O completion, or other producer may settle an OresFuture. It must not execute guest code inline from its producer thread.

The common path is producer/readiness -> settle OresFuture -> owning Ores scheduler continuation -> execute guest operation.

rx-ores owns no independent executor or event loop.

The current Java runtime intentionally keeps guest callback-style subscription out of the raw host API until compiler lowering can bind a guest fnc/lambda to the owning Ores scheduler/actor turn. Host/runtime operators such as map and filter are runtime substrate operations; they are not permission to run guest callbacks on arbitrary completion threads.

## Backpressure

The native channel boundary is the preferred backpressure mechanism. A bounded channel gives: Rx producer -> nb writech -> ready: continue; pending: Future remains suspended.

There is no implicit unbounded queue.

## AOT/JIT

The runtime implementation is ordinary statically linked Java support. No reflective operator discovery, runtime class generation, or second scheduler is required. The eventual std/rx source facade remains an explicit library dependency so programs that do not import RX do not gain an RX reachability root.