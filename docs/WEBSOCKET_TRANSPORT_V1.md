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

## Implemented opening-handshake boundary

`WebSocketHandshake.admit` now validates the protocol-defining fields before
any connection capability can exist: exact GET + HTTP/1.1, bounded ASCII
Upgrade/Connection token lists, WebSocket version 13, and a canonical padded
Base64 16-byte nonce. It returns the RFC 6455 Sec-WebSocket-Accept value using
the protocol-required SHA-1 construction. SHA-1 here is a protocol transform,
not a password/signature security primitive.

`requireAllowedOrigin` provides a deliberately exact browser-origin allowlist:
missing policy, `null`, wildcard spellings, and unlisted origins fail closed.
Authentication remains application-owned and must be checked alongside origin
policy before actor connection authority is granted.

The helper owns no HTTP exchange or socket. A future transport adapter must
also reject duplicate singleton handshake headers at the raw HTTP-header layer;
the current first-value HttpExchange accessor is not sufficient evidence for
that property.

## Not yet implemented

- Binding the validated upgrade to a native HTTP/socket handoff.
- Native socket/listener ownership and bounded incremental frame reads.
- Source types such as `WebSocketServer`, `WebSocketConnection`, or actor
  dispatch/claim tokens.
- CONTROL-vs-USER queue scheduling, close handshake deadlines and half-close.
- Compression/extensions.
- Java/Graal AOT execution evidence and LLVM parity.

The first source consumer remains
`oreslang-websocket-server-demo.ores`; it must stay bootstrap-only until the
upgrade, connection and actor-lifecycle layers execute end to end.
