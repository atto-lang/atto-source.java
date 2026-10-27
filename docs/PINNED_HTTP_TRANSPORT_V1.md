# Pinned HTTP/HTTPS transport v1

Status: **pinned TLS/reference transport retained; guest hostname egress remains disabled**.

The child selector-state-machine work moves the current plaintext loopback guest
path onto `SelectorHttpTransport`. This document remains the contract for the
exact-address policy, TLS identity proof, and blocking transport reference
behavior used to validate the nonblocking successor.

This layer is the first transport that consumes the per-connection authorization
from `OutboundEgressPolicy` without handing an authorized hostname back to a
lower TCP stack for another DNS lookup.

## Connection invariant

For every new physical connection:

1. canonicalize and authorize the target through `OutboundEgressPolicy`;
2. resolve DNS exactly once for that connection;
3. reject the entire DNS result if any returned address class is denied;
4. connect the TCP socket directly to one of the authorized `InetAddress`
   values;
5. for HTTPS, wrap that already-connected socket and use the canonical hostname
   only for SNI and HTTPS endpoint identification;
6. close the connection when the response is retired.

The physical TCP connect path never receives the hostname.

The transport uses `Connection: close` in v1. There is intentionally no idle
pool, so a DNS authorization cannot outlive one physical connection. Every
later request therefore obtains a new resolution generation. Pooling is a
separate follow-up and must preserve that rule whenever it creates a new socket.

Connection admission is held in a context-shareable `ConnectionBudget`.
Individual request transports remain independently revocable, while
per-origin/global limits cannot be bypassed by creating another request
transport inside the same context.

## TLS

HTTPS uses the process/default `SSLContext` in production construction.

The TLS wrapper:

- starts from an already-connected pinned TCP socket;
- sends SNI for DNS hosts;
- enables the JDK HTTPS endpoint-identification algorithm;
- validates the certificate against the original canonical hostname;
- never substitutes the pinned IP address as the certificate identity.

Tests use a local test CA/certificate for `origin.example` and prove that:

- `origin.example` can be resolved by the policy to `127.0.0.1`, connected
  by exact IP, and still passes hostname verification;
- the server observes SNI `origin.example`;
- the same pinned IP fails closed when the requested hostname is
  `wrong.example`.

## HTTP/1.1 framing

V1 is bodyless-request GET/HEAD only.

Request construction owns `Host` and `Connection`; callers cannot supply
transport/framing headers. Existing request header count/value/byte validation
is reused.

Response parsing is strict and bounded:

- CRLF-only status/header lines;
- HTTP/1.0 or HTTP/1.1 final status;
- at most 8 informational responses;
- at most 100 response fields and 32768 header bytes;
- no obs-fold;
- no simultaneous `Transfer-Encoding` and `Content-Length`;
- one canonical numeric `Content-Length` only;
- `Transfer-Encoding` is accepted only as exactly `chunked`;
- strict chunk sizes with no extensions in v1;
- bounded trailers with framing/control trailer rejection;
- response-body byte budget for fixed, chunked, and close-delimited bodies.

Repeated ordinary response headers remain separate entries.

## Deadlines and capacity

The transport enforces:

- connect timeout;
- TLS-handshake timeout using the connect/overall budget;
- response-header timeout;
- body-idle timeout;
- overall deadline checks throughout connect, TLS, header parsing and socket
  body reads;
- per-origin concurrent-connection limits;
- global concurrent-connection limits.

A response owns its connection permit until the response socket is physically
closed. Closing or fully streaming the body releases both the socket and the
origin/global capacity exactly once.

The request header set is capped at 32 KiB. Java blocking sockets do not expose
a portable independent write timeout, so v1 does **not** claim a hard
write-side deadline while flushing that bounded request head. Likewise, the
overall deadline cannot currently fire while the native worker is blocked
writing into the bounded guest body pipe because the guest has stopped
consuming; cancellation still revokes that wait correctly. These phases remain
merge blockers for adversarial arbitrary-egress enablement unless writes move
behind independently cancellable/joined operations or to a nonblocking
transport.

## Shutdown

The transport tracks all published responses and live sockets. Closing it:

- closes published responses, releasing their connection leases;
- closes sockets still between connect and response publication;
- causes blocked body reads to fail;
- prevents new admission.

On the stacked selector-state-machine branch, the current literal-loopback
guest `http.request` path uses `SelectorHttpTransport` instead. The pinned
transport remains the HTTPS/SNI/hostname-verification proof and shares the same
context-owned connection budget.

The `AsyncNativeIo` operation remains the authoritative physical worker
receipt. Guest admission still runs the existing loopback-only target validator,
so neither transport change enables hostname or arbitrary egress.

## Focused tests

`PinnedHttpTransportTest` proves:

- an otherwise unresolvable DNS hostname is connected through the exact
  resolver-provided IP and the raw request target is preserved;
- every new physical request resolves again;
- pinned-IP HTTPS with original-host SNI and hostname verification;
- hostname mismatch rejection after the pinned TCP connect;
- per-origin capacity held until response close;
- header deadline cleanup;
- body-idle deadline cleanup;
- TE + Content-Length ambiguity rejection;
- strict chunked framing/body budget;
- transport shutdown interrupting a blocked body read and retiring the lease.

The focused JDK25 workflow includes this suite, but nonzero-step exact-head
execution is still required before promotion.
