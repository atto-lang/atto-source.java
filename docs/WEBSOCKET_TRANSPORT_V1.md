# WebSocket transport v1

Status: **frame-codec foundation only**. No source-level listener, upgrade, actor
connection handle, or production WebSocket server is claimed by this change.

## Layering

The transport is intentionally split so wire parsing cannot accidentally grant
socket or actor authority.

1. **HTTP upgrade admission** validates an origin-form HTTP/1.1 GET upgrade,
   version 13, a canonical base64 key, configured origin/auth policy, and scoped
   NET authority before a connection becomes WebSocket state.
2. **Frame codec** parses already-bounded bytes. Client frames must be masked,
   RSV bits are rejected unless a future negotiated extension owns them,
   reserved opcodes are rejected, extended lengths must use canonical encoding,
   and control frames are final and at most 125 bytes.
3. **Message assembler** enforces an independent reconstructed-message byte
   limit. Fragmented text is UTF-8 validated only after final reassembly so a
   multi-byte scalar may legally cross frame boundaries. Control frames do not
   consume or reset user-message assembly state.
4. **Connection transport** must prioritize close/ping/pong processing over a
   bounded USER-message queue and expose scheduler-owned async read/write
   Futures. No socket/JDK object may reach guest code.
5. **Actor ownership** gives one actor generation the connection capability.
   Close, cancellation, actor failure, deadline and supervisor shutdown revoke
   it exactly once; stale generations cannot read, write, or revive the handle.

## Implemented frame boundary

`WebSocketFrameCodec.decodeClientFrame` requires exactly one complete client
frame and rejects trailing bytes. It checks the configured frame limit before
allocating the decoded payload. Unfragmented text and close reasons use strict
UTF-8 decoding. Close payloads reject the one-byte form and reserved/invalid
status codes.

`MessageAssembler` accepts only text/binary data plus continuations, rejects
continuations without a predecessor and overlapping fragmented messages, and
resets after a size violation. Its message limit is distinct from the frame
limit.

`encodeServerFrame` emits unmasked canonical server frames and applies the same
control-frame and payload bounds.

All returned payloads are defensive copies. The implementation uses only
closed-world JDK primitives and has no reflection requirement, preserving the
Native Image/AOT direction.

## Not yet implemented

- HTTP upgrade and Sec-WebSocket-Accept calculation.
- Native socket/listener ownership and bounded incremental frame reads.
- Source types such as `WebSocketServer`, `WebSocketConnection`, or actor
  dispatch/claim tokens.
- CONTROL-vs-USER queue scheduling, close handshake deadlines and half-close.
- Compression/extensions.
- Java/Graal AOT execution evidence and LLVM parity.

The first source consumer remains
`oreslang-websocket-server-demo.ores`; it must stay bootstrap-only until the
upgrade, connection and actor-lifecycle layers execute end to end.
