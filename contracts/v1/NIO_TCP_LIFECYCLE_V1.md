# NIO TCP lifecycle contract v1

This contract defines the first VM-owned TCP transport built on the Java
readiness reactor.

## Ownership

- TCP listeners are supervisor-owned runtime resources.
- Actor turns cannot create process listeners.
- The source/runtime entrypoint checks scoped `NET` permission before opening
  the server descriptor.
- A listener belongs to exactly one `OresVM` and one
  `NioReadinessGroup`.
- Every accepted connection belongs to exactly one listener.

A VM close fences new listener admission, closes all registered listeners and
connections, retires the readiness reactor, and only then tears down root and
CONTROL carriers.

## Nonblocking I/O

All server and connection channels are configured nonblocking. Accept, read and
write may attempt the host syscall immediately, but a would-block result is
represented by a one-shot selector watch. No operation may fall back to a
blocking worker in this transport.

A readiness callback may perform only bounded host/runtime work. Guest actor
code is never run inline on the selector thread.

## Bounds

Each listener has explicit bounds for:

- connection count;
- admitted operations;
- accept backlog;
- per-read bytes;
- per-write bytes.

The first contract permits at most one outstanding socket operation per
connection. Concurrent read/write and cross-actor connection sharing remain
fail-closed until their ownership protocol is specified.

## Cancellation and close

Cancellation must revoke the current readiness watch and release the logical
operation credit exactly once. A cancelled accept cannot consume a later peer
connection.

Connection close:

1. marks the connection terminated;
2. closes its descriptor;
3. cancels outstanding operations;
4. waits for admitted operation notification drain before publishing its close
   receipt;
5. removes the connection from its listener.

Listener close applies the same rule to accept and all owned connections.
VM close additionally waits for listener termination and physical reactor exit.

## Permission semantics

The public runtime factory checks
`RuntimePermissions.Permission.NET` on the exact requested loopback resource
`127.0.0.1:<port>`.

Port `0` remains fail-closed for port-specific grants: it is admitted only by
an exact `127.0.0.1:0`, host-wide `127.0.0.1`, or global NET grant. The
kernel-selected ephemeral port never retroactively broadens permission.

## Evidence requirements

The focused host tests cover:

- multiple listeners sharing one selector;
- accept cancellation and re-arm;
- bounded single-operation connection ownership;
- read/write echo;
- VM-close cancellation and descriptor retirement;
- malformed bounds and byte values.

These authored tests are not executable evidence until an exact-head
source-org job actually executes them. A workflow/job containing zero executed
steps is runner admission/infrastructure failure, not a failed transport test.

Alternative-org SOURCE-FREE qualification remains a separate runner/security
signal and must not be reported as private-source compiler/runtime evidence.
