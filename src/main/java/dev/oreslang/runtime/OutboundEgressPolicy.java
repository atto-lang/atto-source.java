package dev.oreslang.runtime;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fail-closed outbound authority and address policy.
 *
 * <p>This class is intentionally transport-neutral. A successful
 * {@link #resolveForConnection(String, Purpose, Policy, Resolver)} call is only
 * an authorization + DNS pinning decision. The physical transport MUST connect
 * to one of {@link ResolvedTarget#addresses()} and must not re-resolve
 * {@link ResolvedTarget#host()} behind the policy layer.</p>
 *
 * <p>Call resolution once for every new physical connection. Reusing a
 * ResolvedTarget as a connection-pool key is valid only for the lifetime of
 * that physical connection; a later connection must resolve and authorize
 * again. This is the DNS-rebinding boundary.</p>
 */
final class OutboundEgressPolicy {
    private static final AtomicLong NEXT_RESOLUTION_GENERATION =
            new AtomicLong();

    enum Purpose {
        GENERAL_HTTP,
        FORWARD_PROXY,
        REVERSE_PROXY
    }

    enum AddressClass {
        PUBLIC,
        PRIVATE,
        LOOPBACK,
        LINK_LOCAL,
        CARRIER_GRADE_NAT,
        DOCUMENTATION,
        BENCHMARK,
        MULTICAST,
        UNSPECIFIED,
        TRANSITION,
        RESERVED
    }

    record Limits(
            int maxDnsAddresses,
            int maxRedirects,
            Duration connectTimeout,
            Duration headerTimeout,
            Duration bodyIdleTimeout,
            Duration overallTimeout,
            int maxConnectionsPerOrigin,
            int maxConnectionsGlobal) {
        Limits {
            if (maxDnsAddresses < 1 || maxDnsAddresses > 64) {
                throw new IllegalArgumentException(
                        "maxDnsAddresses must be 1..64");
            }
            if (maxRedirects < 0 || maxRedirects > 20) {
                throw new IllegalArgumentException(
                        "maxRedirects must be 0..20");
            }
            requirePositive(connectTimeout, "connectTimeout");
            requirePositive(headerTimeout, "headerTimeout");
            requirePositive(bodyIdleTimeout, "bodyIdleTimeout");
            requirePositive(overallTimeout, "overallTimeout");
            if (overallTimeout.compareTo(connectTimeout) < 0) {
                throw new IllegalArgumentException(
                        "overallTimeout cannot be shorter than connectTimeout");
            }
            if (maxConnectionsPerOrigin < 1
                    || maxConnectionsPerOrigin > 4096) {
                throw new IllegalArgumentException(
                        "maxConnectionsPerOrigin must be 1..4096");
            }
            if (maxConnectionsGlobal < maxConnectionsPerOrigin
                    || maxConnectionsGlobal > 65_536) {
                throw new IllegalArgumentException(
                        "maxConnectionsGlobal must be >= per-origin and <= 65536");
            }
        }

        static Limits defaults() {
            return new Limits(
                    16,
                    5,
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(60),
                    32,
                    256);
        }

        private static void requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    record Policy(
            Set<AddressClass> allowedAddressClasses,
            boolean allowHttpsDowngrade,
            Limits limits) {
        Policy {
            allowedAddressClasses = Set.copyOf(
                    Objects.requireNonNull(
                            allowedAddressClasses,
                            "allowedAddressClasses"));
            if (allowedAddressClasses.isEmpty()) {
                throw new IllegalArgumentException(
                        "outbound policy must allow at least one address class");
            }
            limits = Objects.requireNonNull(limits, "limits");
        }

        static Policy generalHttpDefault() {
            return new Policy(
                    Set.of(AddressClass.PUBLIC),
                    false,
                    Limits.defaults());
        }

        static Policy forwardProxyDefault() {
            return new Policy(
                    Set.of(AddressClass.PUBLIC),
                    false,
                    Limits.defaults());
        }

        static Policy reverseProxy(
                boolean allowPrivate,
                boolean allowLoopback) {
            var allowed = new java.util.LinkedHashSet<AddressClass>();
            allowed.add(AddressClass.PUBLIC);
            if (allowPrivate) allowed.add(AddressClass.PRIVATE);
            if (allowLoopback) allowed.add(AddressClass.LOOPBACK);
            return new Policy(allowed, false, Limits.defaults());
        }

        static Policy loopbackOnly() {
            return new Policy(
                    Set.of(AddressClass.LOOPBACK),
                    false,
                    Limits.defaults());
        }

        boolean permits(AddressClass addressClass) {
            return allowedAddressClasses.contains(addressClass);
        }
    }

    @FunctionalInterface
    interface Resolver {
        List<InetAddress> resolve(String asciiHost)
                throws UnknownHostException;

        static Resolver system() {
            return host -> List.of(InetAddress.getAllByName(host));
        }
    }

    record ResolvedTarget(
            URI uri,
            String scheme,
            String host,
            int port,
            String authority,
            List<InetAddress> addresses,
            Purpose purpose,
            long resolutionGeneration) {
        ResolvedTarget {
            uri = Objects.requireNonNull(uri, "uri");
            scheme = Objects.requireNonNull(scheme, "scheme");
            host = Objects.requireNonNull(host, "host");
            authority = Objects.requireNonNull(authority, "authority");
            addresses = List.copyOf(
                    Objects.requireNonNull(addresses, "addresses"));
            purpose = Objects.requireNonNull(purpose, "purpose");
            if (addresses.isEmpty()) {
                throw new IllegalArgumentException(
                        "resolved target requires at least one pinned address");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                        "resolved target port must be 1..65535");
            }
            if (resolutionGeneration <= 0) {
                throw new IllegalArgumentException(
                        "resolution generation must be positive");
            }
        }

        InetSocketAddress connectAddress(int attempt) {
            InetAddress address =
                    addresses.get(Math.floorMod(attempt, addresses.size()));
            return new InetSocketAddress(address, port);
        }
    }

    private record CanonicalTarget(
            URI uri,
            String scheme,
            String host,
            int port,
            String authority,
            InetAddress literalAddress) { }

    private OutboundEgressPolicy() { }

    static ResolvedTarget resolveForConnection(
            String rawUrl,
            Purpose purpose,
            Policy policy,
            Resolver resolver) {
        Objects.requireNonNull(rawUrl, "rawUrl");
        URI parsed;
        try {
            parsed = new URI(rawUrl);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException(
                    "outbound URL is not a valid absolute URI",
                    invalid);
        }
        return resolveForConnection(
                parsed,
                purpose,
                policy,
                resolver);
    }

    static ResolvedTarget resolveForConnection(
            URI raw,
            Purpose purpose,
            Policy policy,
            Resolver resolver) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(resolver, "resolver");

        CanonicalTarget canonical = canonicalize(raw);
        List<InetAddress> addresses;
        if (canonical.literalAddress() != null) {
            addresses = List.of(canonical.literalAddress());
        } else {
            List<InetAddress> resolved;
            try {
                resolved = resolver.resolve(canonical.host());
            } catch (UnknownHostException failure) {
                throw new IllegalArgumentException(
                        "outbound DNS resolution failed for "
                                + canonical.host(),
                        failure);
            }
            addresses = canonicalizeAddresses(
                    resolved,
                    policy.limits().maxDnsAddresses());
        }

        return authorizeCanonicalResolved(
                canonical,
                purpose,
                policy,
                addresses);
    }

    /**
     * Authorize an address set produced by an external, physically cancellable
     * resolver. This path never performs DNS itself and therefore cannot
     * silently fall back to InetAddress.getAllByName().
     */
    static ResolvedTarget authorizeResolved(
            URI raw,
            Purpose purpose,
            Policy policy,
            List<InetAddress> resolved) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(policy, "policy");
        CanonicalTarget canonical = canonicalize(raw);

        List<InetAddress> addresses;
        if (canonical.literalAddress() != null) {
            addresses = List.of(canonical.literalAddress());
            if (resolved != null
                    && !resolved.isEmpty()
                    && !canonicalizeAddresses(
                            resolved,
                            policy.limits().maxDnsAddresses())
                            .equals(addresses)) {
                throw new IllegalArgumentException(
                        "literal outbound target cannot be rebound by resolver results");
            }
        } else {
            addresses = canonicalizeAddresses(
                    Objects.requireNonNull(
                            resolved,
                            "resolved addresses"),
                    policy.limits().maxDnsAddresses());
        }
        return authorizeCanonicalResolved(
                canonical,
                purpose,
                policy,
                addresses);
    }

    private static ResolvedTarget authorizeCanonicalResolved(
            CanonicalTarget canonical,
            Purpose purpose,
            Policy policy,
            List<InetAddress> addresses) {
        for (InetAddress address : addresses) {
            AddressClass addressClass = classify(address);
            if (!policy.permits(addressClass)) {
                throw new SecurityException(
                        "outbound target "
                                + canonical.authority()
                                + " resolved to denied "
                                + addressClass
                                + " address "
                                + address.getHostAddress());
            }
        }

        return new ResolvedTarget(
                canonical.uri(),
                canonical.scheme(),
                canonical.host(),
                canonical.port(),
                canonical.authority(),
                addresses,
                purpose,
                NEXT_RESOLUTION_GENERATION.incrementAndGet());
    }

    static ResolvedTarget authorizeRedirect(
            ResolvedTarget previous,
            String location,
            int redirectCount,
            Policy policy,
            Resolver resolver) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(resolver, "resolver");

        if (redirectCount < 0
                || redirectCount >= policy.limits().maxRedirects()) {
            throw new SecurityException(
                    "outbound redirect limit exceeded");
        }

        URI next;
        try {
            next = previous.uri().resolve(new URI(location));
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException(
                    "invalid outbound redirect location",
                    invalid);
        }
        CanonicalTarget canonical = canonicalize(next);
        if (previous.scheme().equals("https")
                && canonical.scheme().equals("http")
                && !policy.allowHttpsDowngrade()) {
            throw new SecurityException(
                    "HTTPS to HTTP outbound redirect downgrade is denied");
        }

        return resolveForConnection(
                canonical.uri(),
                previous.purpose(),
                policy,
                resolver);
    }

    static URI canonicalUri(String rawUrl) {
        Objects.requireNonNull(rawUrl, "rawUrl");
        try {
            return canonicalize(new URI(rawUrl)).uri();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException(
                    "outbound URL is not a valid absolute URI",
                    invalid);
        }
    }

    static AddressClass classify(InetAddress address) {
        Objects.requireNonNull(address, "address");
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address && raw.length == 4) {
            return classifyV4(raw);
        }
        if (address instanceof Inet6Address && raw.length == 16) {
            return classifyV6(raw);
        }
        return AddressClass.RESERVED;
    }

    private static CanonicalTarget canonicalize(URI raw) {
        Objects.requireNonNull(raw, "raw");
        if (!raw.isAbsolute() || raw.isOpaque()) {
            throw new IllegalArgumentException(
                    "outbound URL must be an absolute hierarchical URI");
        }
        if (raw.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "outbound URL userinfo is forbidden");
        }
        if (raw.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "outbound URL fragments are forbidden");
        }

        String rawScheme = raw.getScheme();
        if (rawScheme == null) {
            throw new IllegalArgumentException(
                    "outbound URL scheme is required");
        }
        String scheme = rawScheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(
                    "outbound URL scheme must be http or https");
        }

        String parsedHost = raw.getHost();
        if (parsedHost == null || parsedHost.isBlank()) {
            throw new IllegalArgumentException(
                    "outbound URL requires a canonical server host");
        }

        String host = normalizeHost(parsedHost);
        InetAddress literal = parseLiteral(host);
        if (literal != null) {
            host = canonicalLiteral(literal);
        }

        int port = raw.getPort();
        if (port == -1) {
            port = scheme.equals("https") ? 443 : 80;
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(
                    "outbound URL port must be 1..65535");
        }

        String rawPath = raw.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) rawPath = "/";
        rejectUnsafeUriText(rawPath, "path");
        String rawQuery = raw.getRawQuery();
        if (rawQuery != null) rejectUnsafeUriText(rawQuery, "query");

        String authority = authority(host, port);
        URI canonical;
        try {
            // Preserve already-valid raw path/query octets. The component
            // constructors would quote '%' again and could silently change the
            // request target (for example %2F -> %252F).
            canonical = new URI(
                    scheme + "://" + authority + rawPath
                            + (rawQuery == null ? "" : "?" + rawQuery));
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException(
                    "outbound URL cannot be canonicalized",
                    impossible);
        }

        return new CanonicalTarget(
                canonical,
                scheme,
                host,
                port,
                authority,
                literal);
    }

    private static String normalizeHost(String value) {
        String host = value;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.indexOf('%') >= 0) {
            throw new IllegalArgumentException(
                    "IPv6 zone identifiers are forbidden in outbound URLs");
        }
        if (host.indexOf(':') >= 0) {
            InetAddress literal = parseIpv6(host);
            return canonicalLiteral(literal);
        }
        if (looksLikeIpv4(host)) {
            return canonicalIpv4(host);
        }
        if (host.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "ambiguous integer-form IPv4 hosts are forbidden");
        }

        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
            if (host.isEmpty() || host.endsWith(".")) {
                throw new IllegalArgumentException(
                        "outbound DNS host has invalid trailing dots");
            }
        }
        String ascii;
        try {
            ascii = IDN.toASCII(
                    host,
                    IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "outbound DNS host is invalid",
                    invalid);
        }
        ascii = ascii.toLowerCase(Locale.ROOT);
        if (ascii.isEmpty() || ascii.length() > 253) {
            throw new IllegalArgumentException(
                    "outbound DNS host length is invalid");
        }
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > 63
                    || label.startsWith("-")
                    || label.endsWith("-")) {
                throw new IllegalArgumentException(
                        "outbound DNS host label is invalid");
            }
        }
        return ascii;
    }

    private static InetAddress parseLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return parseIpv6(host);
        }
        if (looksLikeIpv4(host)) {
            byte[] bytes = parseIpv4Bytes(host);
            try {
                return InetAddress.getByAddress(bytes);
            } catch (UnknownHostException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return null;
    }

    private static InetAddress parseIpv6(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet6Address)) {
                throw new IllegalArgumentException(
                        "outbound IPv6 literal is invalid");
            }
            return address;
        } catch (UnknownHostException invalid) {
            throw new IllegalArgumentException(
                    "outbound IPv6 literal is invalid",
                    invalid);
        }
    }

    private static boolean looksLikeIpv4(String host) {
        if (host.isEmpty()) return false;
        boolean dot = false;
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (ch == '.') {
                dot = true;
            } else if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return dot;
    }

    private static String canonicalIpv4(String host) {
        byte[] bytes = parseIpv4Bytes(host);
        return (bytes[0] & 0xff)
                + "." + (bytes[1] & 0xff)
                + "." + (bytes[2] & 0xff)
                + "." + (bytes[3] & 0xff);
    }

    private static byte[] parseIpv4Bytes(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "outbound IPv4 literal must contain four octets");
        }
        byte[] result = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()
                    || (part.length() > 1 && part.charAt(0) == '0')) {
                throw new IllegalArgumentException(
                        "outbound IPv4 octets must use canonical decimal form");
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(
                        "outbound IPv4 octet is invalid",
                        invalid);
            }
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException(
                        "outbound IPv4 octet is outside 0..255");
            }
            result[i] = (byte) value;
        }
        return result;
    }

    private static String canonicalLiteral(InetAddress address) {
        return address.getHostAddress().toLowerCase(Locale.ROOT);
    }

    private static String authority(String host, int port) {
        return host.indexOf(':') >= 0
                ? "[" + host + "]:" + port
                : host + ":" + port;
    }

    private static void rejectUnsafeUriText(String value, String where) {
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "outbound URL " + where + " contains a backslash");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch <= 0x1f || ch == 0x7f) {
                throw new IllegalArgumentException(
                        "outbound URL " + where + " contains a control character");
            }
        }
    }

    private static List<InetAddress> canonicalizeAddresses(
            List<InetAddress> resolved,
            int maxAddresses) {
        Objects.requireNonNull(resolved, "resolved addresses");
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException(
                    "outbound DNS returned no addresses");
        }

        Map<String, InetAddress> unique = new LinkedHashMap<>();
        for (InetAddress address : resolved) {
            if (address == null) {
                throw new IllegalArgumentException(
                        "outbound DNS returned a null address");
            }
            String key = Arrays.toString(address.getAddress());
            unique.putIfAbsent(key, address);
            if (unique.size() > maxAddresses) {
                throw new IllegalArgumentException(
                        "outbound DNS address count exceeds "
                                + maxAddresses);
            }
        }
        return List.copyOf(unique.values());
    }

    private static AddressClass classifyV4(byte[] raw) {
        int a = raw[0] & 0xff;
        int b = raw[1] & 0xff;
        int c = raw[2] & 0xff;

        if (a == 0) return AddressClass.UNSPECIFIED;
        if (a == 10) return AddressClass.PRIVATE;
        if (a == 100 && b >= 64 && b <= 127) {
            return AddressClass.CARRIER_GRADE_NAT;
        }
        if (a == 127) return AddressClass.LOOPBACK;
        if (a == 169 && b == 254) return AddressClass.LINK_LOCAL;
        if (a == 172 && b >= 16 && b <= 31) {
            return AddressClass.PRIVATE;
        }
        if (a == 192 && b == 0 && c == 0) {
            return AddressClass.RESERVED;
        }
        if (a == 192 && b == 0 && c == 2) {
            return AddressClass.DOCUMENTATION;
        }
        if (a == 192 && b == 88 && c == 99) {
            return AddressClass.TRANSITION;
        }
        if (a == 192 && b == 168) return AddressClass.PRIVATE;
        if (a == 198 && (b == 18 || b == 19)) {
            return AddressClass.BENCHMARK;
        }
        if (a == 198 && b == 51 && c == 100) {
            return AddressClass.DOCUMENTATION;
        }
        if (a == 203 && b == 0 && c == 113) {
            return AddressClass.DOCUMENTATION;
        }
        if (a >= 224 && a <= 239) {
            return AddressClass.MULTICAST;
        }
        if (a >= 240) return AddressClass.RESERVED;
        return AddressClass.PUBLIC;
    }

    private static AddressClass classifyV6(byte[] raw) {
        boolean allZero = true;
        for (byte value : raw) {
            if (value != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) return AddressClass.UNSPECIFIED;

        boolean loopback = true;
        for (int i = 0; i < 15; i++) {
            if (raw[i] != 0) {
                loopback = false;
                break;
            }
        }
        if (loopback && raw[15] == 1) {
            return AddressClass.LOOPBACK;
        }

        int first = raw[0] & 0xff;
        int second = raw[1] & 0xff;

        if ((first & 0xfe) == 0xfc) {
            return AddressClass.PRIVATE;
        }
        if (first == 0xfe && (second & 0xc0) == 0x80) {
            return AddressClass.LINK_LOCAL;
        }
        if (first == 0xff) return AddressClass.MULTICAST;

        if (prefix(raw, new int[] {
                0x20, 0x01, 0x0d, 0xb8
        }, 32)) {
            return AddressClass.DOCUMENTATION;
        }
        if (prefix(raw, new int[] {
                0x20, 0x01, 0x00, 0x02, 0x00, 0x00
        }, 48)) {
            return AddressClass.BENCHMARK;
        }
        // 2001::/23 is IETF protocol-assignment space. Some
        // individual subranges are globally reachable, but ordinary outbound
        // HTTP has no reason to treat protocol/anycast/transition space as a
        // public origin. Keep the whole block fail-closed.
        if (prefix(raw, new int[] {
                0x20, 0x01, 0x00
        }, 23)) {
            return AddressClass.TRANSITION;
        }
        if (prefix(raw, new int[] {
                0x20, 0x02
        }, 16)) {
            return AddressClass.TRANSITION;
        }
        // RFC 9637 documentation prefix.
        if (prefix(raw, new int[] {
                0x3f, 0xff, 0x00
        }, 20)) {
            return AddressClass.DOCUMENTATION;
        }
        if (prefix(raw, new int[] {
                0x00, 0x64, 0xff, 0x9b
        }, 32)) {
            return AddressClass.TRANSITION;
        }
        if (prefix(raw, new int[] {
                0x01, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        }, 64)) {
            return AddressClass.RESERVED;
        }

        // Global unicast is currently allocated from 2000::/3. Treat all
        // other unicast space as reserved until an explicit policy update
        // audits a new allocation.
        if ((first & 0xe0) == 0x20) {
            return AddressClass.PUBLIC;
        }
        return AddressClass.RESERVED;
    }

    private static boolean prefix(
            byte[] address,
            int[] prefixBytes,
            int prefixBits) {
        if (prefixBits < 0 || prefixBits > address.length * 8) {
            throw new IllegalArgumentException("invalid prefix length");
        }
        int fullBytes = prefixBits / 8;
        int remainingBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if ((address[i] & 0xff) != prefixBytes[i]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        return ((address[fullBytes] & 0xff) & mask)
                == (prefixBytes[fullBytes] & mask);
    }
}
