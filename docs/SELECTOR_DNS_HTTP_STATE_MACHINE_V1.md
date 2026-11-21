# Selector DNS + nonblocking HTTP state machine v1

Status: **implemented for plaintext HTTP transport and explicit DNS resolver injection; guest hostname egress remains disabled**.

This layer closes the two blocking-I/O gaps that remained after the pinned-address
transport work:

- DNS can now run on a request-owned nonblocking datagram channel/selector
  instead of `InetAddress.getAllByName()`.
- plaintext HTTP connect, bounded request-head writes, response-head reads and
  response-body socket reads now run on `SocketChannel` + `Selector` with
  phase deadlines.

The current guest `http.request` entry point is still restricted to literal
loopback HTTP. That path now uses the selector transport, so the production
guest lifecycle exercises the nonblocking state machine without granting new
network authority.

## State machine

Each request owns this physical state sequence:

`RESOLVING -> CONNECTING -> WRITING_HEADERS -> READING_HEADERS -> STREAMING_BODY -> RETIRING -> RETIRED`

Cancellation is valid from every phase. A foreign/CONTROL thread may close the
active DNS datagram channel, selector and socket channel, but it does **not**
publish physical retirement or release the shared connection permit. The
physical owner releases the permit exactly once after the cancelled operation
has actually unwound.

The existing `AsyncNativeIo` operation still owns the authoritative actor
physical-retirement receipt. An actor cannot reach DONE merely because its
guest Future settled.

## DNS

`SelectorDnsResolver` is an explicit-nameserver UDP resolver. It does not call
the Java system resolver.

One resolution:

- owns a nonblocking `DatagramChannel` and request-local `Selector`;
- sends A and AAAA questions with independent transaction IDs;
- inherits the request absolute deadline in addition to its DNS-specific
  timeout;
- is physically cancellable by channel close + selector wakeup;
- has no cache, so every physical connection gets a fresh resolution;
- bounds packet size, answer count, address count and CNAME depth;
- requires exactly one matching echoed question;
- accepts address records only for the requested address family;
- follows only a bounded CNAME chain rooted at the requested hostname;
- rejects unrelated-owner address records as poisoning;
- fails closed on UDP truncation instead of falling back to a blocking resolver.

The resulting address list is passed to
`OutboundEgressPolicy.authorizeResolved(...)`. That policy entrypoint performs
the same mixed-answer and special-use classification as the original resolver
path but performs **no DNS lookup itself**.

Literal IP targets cannot be rebound by supplying a conflicting external
address list.

## HTTP selector ownership

`SelectorHttpTransport` is plaintext HTTP/1.1 v1.

The physical TCP connection is opened as a nonblocking `SocketChannel` to an
already authorized `InetSocketAddress`. The transport never connects TCP by
hostname.

The selector owns:

- connect readiness;
- bounded request-header writes;
- response-header readiness;
- body socket reads.

Every wait is capped by both the relevant phase timeout and the request overall
deadline.

Request admission uses the same context-wide `ConnectionBudget` introduced by
the pinned transport. Capacity is acquired **before DNS**, so exhausted
connection capacity cannot be turned into a resolver stampede.

## Guest response backpressure

The guest body pipe remains bounded to 32 KiB, but its producer wait is now
deadline-aware. A native worker that fills the pipe because the guest stops
consuming cannot wait forever: the request absolute deadline expires the
backpressure wait, after which normal request failure/retirement runs.

Cancellation still wakes the pipe immediately.

## HTTP framing parity

The selector transport preserves the hardened response contract:

- CRLF-only heads;
- HTTP/1.0 and HTTP/1.1;
- bounded informational responses before the final head;
- 101 upgrades rejected;
- bounded field count/header bytes;
- no obs-fold;
- TE + Content-Length ambiguity rejected;
- one numeric Content-Length;
- exactly `chunked` Transfer-Encoding in v1;
- bounded strict chunk framing and trailers;
- HEAD, 1xx, 204, 205 and 304 bodyless semantics;
- repeated end-to-end headers preserved;
- standard and `Connection`-nominated hop-by-hop fields removed before guest
  publication.

## Tests

`SelectorDnsResolverTest` covers:

- A + AAAA resolution through a local fake DNS server;
- policy authorization of externally resolved pins;
- physical cancellation and resolver retirement;
- randomized DNS transaction IDs and split-family NXDOMAIN fail-closed behavior;
- bounded timeout;
- unrelated-owner poisoning rejection;
- UDP truncation fail-closed behavior;
- literal target rebinding rejection.

`SelectorHttpTransportTest` covers:

- hostname -> selector DNS -> policy -> pinned loopback socket;
- raw request-target preservation;
- DNS cancellation before authorization/connect;
- response-header deadline retirement;
- bounded 1xx advancement to the final response;
- strict chunk framing and hop-by-hop filtering;
- absolute deadline on a completely full guest body pipe;
- context-wide capacity rejection before a second DNS query;
- foreign-thread revocation followed by physical-owner retirement.

The existing `OutboundHttpRequestGuestTest` now exercises the selector
transport for the real loopback guest path.

## Deliberate remaining blockers

This PR does **not** enable hostname egress yet.

1. **HTTPS needs SSLEngine parity.** #2123 proves pinned-IP TLS with SNI and
   hostname verification using SSLSocket. The nonblocking state machine still
   needs an `SSLEngine` handshake/wrap/unwrap layer before HTTPS can move onto
   it without reintroducing blocking writes/reads.
2. **Production nameserver discovery/configuration is not selected yet.** The
   resolver takes already-resolved nameserver addresses explicitly. This avoids
   hidden system DNS, but the runtime still needs a reviewed cross-platform way
   to obtain/configure those addresses before the guest hostname surface opens.
3. **Request-body streaming is not implemented.** V1 remains GET/HEAD with
   transport-owned framing.
4. **Idle pooling remains intentionally absent.** `Connection: close` keeps
   one DNS authorization scoped to one physical connection.

Until items 1 and 2 are complete, arbitrary hostname HTTP/HTTPS admission should
remain fail-closed.
