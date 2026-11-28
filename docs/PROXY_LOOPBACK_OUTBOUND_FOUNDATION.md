# Proxy loopback outbound transport foundation

Status: **trusted host-only proof on current main; not a guest proxy transport**.

This branch rebases the useful host-side portion of draft PR #1943 onto the
current Java/Graal runtime. PR #1943 diverged from `main` by more than one
thousand commits and its old `AsyncNativeIo` integration predates the current
physical-operation, actor-attribution, resource-loan, shutdown-join and
quarantine model. That bridge is therefore deliberately **not** copied.

## What this branch proves

`ProxyOutboundLoopback` is a narrow HTTP/1.1 client fixture with these bounds:

- literal `http://127.0.0.1:<port>/...` only;
- GET and HEAD only;
- no DNS, TLS, redirects, credentials, query targets, encoded paths, dot
  segments, CONNECT or proxy environment;
- bounded in-flight requests;
- final response status restricted to 200..599; upgrades/informational status
  are not admitted as a final proxy response;
- response header count and aggregate byte budget applied before hop-by-hop
  stripping, using overflow-safe arithmetic;
- `Connection`-nominated fields and transport/framing fields are stripped;
- repeated `Set-Cookie` fields remain distinct;
- fixed 8 KiB streaming chunks with an explicit total response-byte ceiling;
- cancellation/owner close closes the response body and returns admission only
  after a physically active body-copy worker leaves the stream handoff;
- the owning transport closes the JDK HttpClient pool.

The default response ceiling is 64 MiB. Trusted package tests may choose a
smaller ceiling to exercise the fail-closed path.

## Tests

`scripts/test-proxy-outbound-loopback.sh` compiles the production class plus
six real-socket/lifecycle/race checks and executes each five times.

The exact-head GitHub workflow uses SHA-pinned checkout and GraalVM setup
actions and JDK 25. A local JDK21 independent smoke was also used while
authoring the rebase to exercise final-status rejection, header filtering,
response-byte overflow, and admission reclamation after a byte-budget failure.
Local evidence is supporting evidence only; it is not a Maven/Graal/native
acceptance result.

## Explicitly not implemented

This branch does **not** add or claim:

- `http.request` or any guest-visible outbound response object;
- integration with current `AsyncNativeIo` / actor physical I/O loans;
- an actor-owned response continuation or request-slab receipt;
- arbitrary DNS/IP egress, DNS rebinding protection, redirects or HTTPS;
- request-body streaming;
- response idle/deadline enforcement after headers;
- CONNECT or duplex/half-close semantics;
- reverse-proxy application wiring;
- LLVM parity.

The next runtime step must integrate an outbound operation with **current**
`AsyncNativeIo.admit/Operation` and `IoLoan` semantics rather than reviving
the stale custom executor/permit accounting from PR #1943. Actor termination,
context close and request-generation retirement must not publish success until
the physical network/body worker has retired.

Production readiness remains tracked by issue #1942.
