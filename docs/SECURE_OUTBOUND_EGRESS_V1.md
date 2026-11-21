# Secure outbound egress policy v1

Status: **policy + connection-pin substrate implemented, transport widening intentionally blocked**.

This layer sits above the current loopback-only `http.request` transport and
defines the authorization boundary required before arbitrary outbound targets,
reverse-proxy upstreams, or forward-proxy destinations are enabled.

## Core rule

DNS authorization is **per physical connection**.

A caller must invoke `OutboundEgressPolicy.resolveForConnection(...)` before
opening each new network connection. The result contains:

- a canonical HTTP/HTTPS URI and authority;
- the exact resolved `InetAddress` set authorized for that connection;
- the request purpose (general HTTP, forward proxy, or reverse proxy);
- an immutable resolution generation;
- deadline and connection-pool limits inherited from the selected policy.

The transport must connect to one of those exact pinned addresses. It must never
authorize a hostname and then let another networking layer silently resolve the
hostname again.

This means a `ResolvedTarget` is **not** a reusable DNS authorization token for
a later pooled connection. A new physical connection requires a new resolution
and a new policy decision.

## Default policy

Forward proxy and ordinary outbound HTTP default to public addresses only.

The classifier fails closed for:

- RFC1918 / IPv6 ULA private space;
- loopback;
- link-local and metadata-style space;
- carrier-grade NAT;
- documentation ranges;
- benchmark ranges;
- multicast;
- unspecified addresses;
- IPv4/IPv6 transition mechanisms;
- reserved/unallocated address families.

A DNS answer containing a mixture of allowed and denied addresses rejects the
entire target. The transport does not select the public member and ignore the
private member, because that would leave a DNS rebinding / address-selection
surface below policy.

Reverse proxies may explicitly opt into private addresses and may separately
opt into loopback. Private opt-in does not automatically authorize link-local,
CGNAT, multicast, documentation, benchmark, or reserved addresses.

## Redirects

Every redirect is canonicalized, resolved, and authorized again.

HTTPS-to-HTTP downgrade is denied by default. Redirect count is bounded by the
policy. A redirect to a private or otherwise denied address fails even when the
original target was public.

## Canonical authority

The parser:

- accepts only hierarchical `http` and `https` URLs;
- rejects userinfo and fragments;
- validates canonical ASCII DNS names through STD3 IDNA rules and lowercase form;
- rejects ambiguous integer-form IPv4 hosts and noncanonical dotted IPv4
  forms such as leading-zero octets;
- rejects IPv6 zone identifiers;
- assigns explicit canonical ports (80 or 443 when omitted);
- preserves already-valid raw path/query octets rather than double-escaping
  percent encodings.

Literal IP targets bypass DNS but are still classified and authorized.

## Limits carried by policy

The v1 policy includes bounded:

- DNS answer count;
- redirect count;
- connect timeout;
- header timeout;
- body-idle timeout;
- overall request timeout;
- per-origin connection count;
- global connection count.

This PR defines and validates those limits. Enforcement by the production
connection pool belongs to the transport implementation that consumes the
policy.

## Current integration

`ProxyOutboundLoopback.validateTarget` now runs its literal
`127.0.0.1` admission through this shared policy with a loopback-only profile.
The existing loopback restrictions remain in place, so this change does **not**
enable DNS names or arbitrary egress.

That integration is intentionally safe because the physical JDK `HttpClient`
receives the same literal `127.0.0.1` address that was authorized. There is no
second DNS lookup underneath the pin.

## Required transport work before widening

Do not enable hostname egress merely by passing a hostname to the current JDK
`HttpClient`. That would re-resolve below the authorization layer and defeat
connect-time IP pinning.

The next transport must:

1. resolve through this policy on the native-I/O worker;
2. connect the socket to a pinned `InetAddress`;
3. for HTTPS, preserve the original canonical hostname for SNI and certificate
   hostname verification while the TCP socket connects to the pinned address;
4. re-resolve on every new pooled connection;
5. enforce per-origin/global pool limits and idle eviction;
6. enforce connect/header/body-idle/overall deadlines;
7. run redirect targets back through this policy before opening another
   connection.

Only after those invariants are proven should `http.request` be widened beyond
the current literal-loopback target.

## Test gates

`OutboundEgressPolicyTest` covers:

- canonical authority and raw-target preservation;
- ambiguous authority rejection;
- public-only forward-proxy defaults;
- explicit reverse-proxy private/loopback opt-ins;
- mixed public/private DNS rejection;
- fresh resolution generations on successive connections;
- DNS answer deduplication and count limits;
- redirect reauthorization and HTTPS downgrade denial;
- literal-IP DNS bypass with an explicit connection pin;
- IPv4/IPv6 special-use classification;
- invalid deadline and pool-limit configurations.

Exact-head JDK25 execution is still required before the policy is merge-ready.
