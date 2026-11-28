# Bounded replayable byte streams v1 — actor-owned source contract

Status (2026-11-01): **initial pure-AttoLang reference implementation and guest test fixture**. NOT a verified native compiler/backend parity result; NOT a production media codec, network transport, or memory-isolation guarantee.

Implementation: `stdlib/replay_stream.atto`; guest fixture: `examples/native-replay-stream-contract.atto`. The same file must be compiled and *executed* using pinned exact-head Java/GraalVM, LLVM, .NET NativeAOT, Wasm/Wasmtime, and Cranelift paths before marking a backend supported. A rejected fixture, source-only check, or zero-step GHA run is **not** a pass.

## Decisions

- Replay is an ordinary reusable **byte stream** capability, not an actor declaration modifier, video-specific keyword, durable log, or substitution for transport ACKs.
- Only one owner actor controls a ReplayWindow. The window has incarnation `generation`, monotonic byte offsets (`[floor_offset,next_offset)`), a sent watermark, and an authenticated contiguous receiver-committed watermark.
- API: `window(generation,capacity_bytes)`, `default_window(generation)` (2,097,152 logical payload bytes), `append(generation,Array<int>) -> Result<int,String>`, `mark_sent(generation,end)`, `acknowledge(generation,end)`, `replay_from(generation,start,maximum) -> Result<Array<int>,String>`, watermark getters, plus immutable `checkpoint()` metadata (generation/capacity/retained/produced/sent/acked). Checkpoint creation does not persist anything.
- This first slice accepts chunks from 1 to 65,536 bytes and capacities from 1 to 2,097,152 bytes. Each element must be in `0..255`; arguments are checked before mutation. The current implementation caps absolute offsets at signed 32-bit positive range and returns `OffsetExhausted` rather than wrapping.
- The logical window is fixed-capacity. **Backpressure is mandatory** if appending would overwrite any unacknowledged byte. Already-ACKed bytes may remain temporarily replayable until displaced by new data. Old offsets produce `ReplayWindowExpired`, future offsets produce `OffsetNotProduced`. An empty successful read at `next_offset` is not EOF.
- `mark_sent` is a trusted transport adapter's *ordered write admission* observation; it is **not** a receiver commitment. ACKs must be authenticated/bound to tenant, stream identity, incarnation and receiver's contiguous committed offset by the transport adapter before `acknowledge`. The numeric `generation` parameter is an anti-stale fence, **not authentication**. A forged ACK could otherwise release data.
- `append` copies the complete byte payload into its own window. `replay_from` produces a fresh owned array. Never return aliases into actor storage or retain a borrowed inbound buffer after its lease.
- Actor state access is non-suspending: freeze/copy the chunk under a short verified `critical` state loan, release the loan, and then `await` I/O. No I/O, codec call, callback or channel wait inside `critical {}`; `critical suspends fnc` remains prohibited. A suspended transport must not keep an actor state loan.
- Cancel, close and actor teardown must release chunk storage, transport credits and outstanding continuations exactly once, while retaining independently authenticated checkpoint metadata as configured.

## Error classification

| Return or event | Meaning | Action |
| --- | --- | --- |
| `Backpressure` | Unacknowledged data fills window | Await bounded receiver credit; never evict uncommitted bytes |
| `ReplayWindowExpired` | Requested offset was overwritten after ACK | Application-level resynchronization, e.g. segment/keyframe |
| `OffsetNotProduced` | Requested offset has never existed | Reject remote claim |
| `StaleGeneration` | Actor incarnation mismatch | Reject, fence former owner |
| `InvalidByte`, `InvalidChunkSize`, `InvalidReadSize` | Invalid caller input | Fail locally, no mutation |
| `OffsetExhausted` | Byte-offset arithmetic/size safety boundary | Start new stream incarnation/protocol epoch |
| Network timeout/disconnect | Uncertain delivery | Keep window; reconnect and reconcile authenticated ACK |
| Codec needs input/output | Nonfatal decoder/encoder progress | Drive codec state machine; not a network retry |
| Codec corruption / lost reference frame | Cannot decode existing sequence | Recover from suitable random-access point, retain codec parameters |
| Cancellation/panic | Terminal control, not `Result.Err` or `trap None` | Supervisor/structured cancellation; physical teardown |

`Result.Err` values are not traps, exceptions, or authenticated cancellation. Typed `StreamError`/codec error ADTs are a follow-up once verified source-level error-effect lowering is available across backends; this initial string enum intentionally does not claim such parity.

## Receiver and codec recovery

1. The transport retains an immutable stream/session identifier, tenant capability and generation. It tracks *contiguously committed* receiver byte offsets (not TCP bytes written to a socket).
2. On reconnect, the receiver submits authenticated session + last committed offset. Sender validates it against its sent watermark, then requests `replay_from` at that offset. Receiver deduplicates previously committed chunks by stream ID and offset; do not execute non-idempotent side effects twice.
3. If the offset is below `retained_from`, fail explicitly and negotiate a fresh independently decodable media boundary. 2 MiB is a byte budget, **not** a fixed time or number of frames: at 8 Mbit/s it is approximately 2.1 seconds, and keyframe intervals may be longer.
4. H.264/H.265/AV1 coded frame replay requires correctly framed access units, codec parameter sets, timestamps, reference dependencies and sometimes decoder reset. Byte replay alone is insufficient after loss of decoder state. A future `std/media` adapter may expose `NeedsKeyframe`, `NeedMoreInput`, `UnsupportedCodec` and `CorruptAccessUnit`; no codec implementation is shipped by this patch.
5. Use an async/nonblocking host codec adapter or an explicitly bounded blocking worker pool. Never perform blocking FFmpeg/OS/network calls on an actor scheduler carrier. Native handles must remain capability-scoped and generation-fenced; Wasm requires explicitly granted host imports.

## Memory and durability limits

This first implementation uses an `Array<int>` ring with a fixed maximum logical capacity and lazy slot growth. **2 MiB means a maximum of 2 MiB of logical payload bytes, NOT 2 MiB of resident process memory.** The integer array, allocator, per-actor heap, copied replay reads and transport queues can cost significantly more. Native byte-addressed `ByteBuffer`/u8 storage, quotas across actor groups/tenants, admission and measured RSS are separate required milestones. The ring grows lazily to at most capacity logical byte slots; construction does not prefill 2 MiB. No zero-copy, SIMD, hardware acceleration or throughput claims are made.

An immutable `ReplayCheckpoint` is a coherent copy of the current offsets only: it does not contain payload bytes, codec state or verified storage receipts. The in-memory window survives a temporary network disconnection only if the actor and process remain alive. Actor/process crash recovery needs a **durable or replicated segment store** plus metadata. Use `stdlib/actor_persistence.ores` as a starting watermark contract; preserve the difference between an accepted I/O write, receiver commit and durable write. Treat unknown storage outcomes as ambiguous until reconciled by idempotency key.

## Minimum conformance gates

- Run the exact native Oreslang fixture and collect each emitted `REPLAY-PASS|name` plus one terminal `REPLAY-CONTRACT-DONE`. Exit zero alone is insufficient.
- Reject missing, duplicate or out-of-order receipts; record exact source tree SHA, guest binary/toolchain hash, executed CI steps and runtime exit status.
- Add randomized/property tests for wraparound, duplicate/stale/forged ACK, corrupt byte, concurrent cancellation and offset/overflow boundaries; bound generator iterations and memory.
- Add separate multi-actor transport integration: simulate disconnection between write admission and ACK, reconnect, duplicate delivery and replay expiry; ensure transport only ACKs contiguous committed data.
- Add encoder/decoder differential integration with an explicitly selected codec and native adapter (FFmpeg first), packet fragmentation, frame loss, IDR/keyframe resync, bitrate changes and 2 MiB quota stress. Record unsupported backends as unsupported, never passing by fallback to a host-only model.

**Release gate:** no production designation until the same guest semantics, ownership and failure outcomes have stepful exact-head evidence on Java/GraalVM, LLVM, .NET NativeAOT, Wasm/Wasmtime and Cranelift. The reference file currently has no verified such result.

## Additional v1 admission checks (source-only, not executed)

The shared guest fixture now contains 80 unique `REPLAY-PASS|...` assertions. The seven added checks cover regressive/duplicate transport send markers, idempotent repeated ACKs, negative and oversized read requests, and invalid trailing byte rejection without advancing the produced watermark. These are candidate Oreslang guest checks, **not** compiler, actor, codec, or NativeAOT execution evidence. No private-source CI mirroring is authorized under the current incident hold.
