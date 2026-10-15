package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scoped, deny-by-default permissions for host I/O.
 *
 * <p>The {@link IsolatePolicy} capability set remains the coarse actor/isolate
 * authority boundary. This class narrows those capabilities to concrete paths,
 * network endpoints, environment keys, commands, system information, FFI
 * libraries, and runtime import roots. Explicit deny rules always win.</p>
 */
public final class RuntimePermissions {
    public enum Permission {
        READ("read", IsolatePolicy.Capability.FILESYSTEM_READ),
        WRITE("write", IsolatePolicy.Capability.FILESYSTEM_WRITE),
        NET("net", IsolatePolicy.Capability.NETWORK),
        ENV("env", IsolatePolicy.Capability.ENVIRONMENT),
        RUN("run", IsolatePolicy.Capability.CHILD_PROCESS),
        SYS("sys", IsolatePolicy.Capability.PROCESS_INFO),
        FFI("ffi", IsolatePolicy.Capability.FFI),
        IMPORT("import", IsolatePolicy.Capability.HOT_CODE_LOAD);

        private final String cliName;
        private final IsolatePolicy.Capability capability;

        Permission(String cliName, IsolatePolicy.Capability capability) {
            this.cliName = cliName;
            this.capability = capability;
        }

        public String cliName() { return cliName; }
        public IsolatePolicy.Capability capability() { return capability; }

        static Permission fromCliName(String name) {
            for (Permission permission : values()) {
                if (permission.cliName.equals(name)) return permission;
            }
            throw new IllegalArgumentException("unknown Oreslang permission '" + name + "'");
        }
    }

    private static final String ARG_PREFIX = "--ores-permission-";

    private record Rule(
            boolean allowAll,
            Set<String> allowed,
            boolean denyAll,
            Set<String> denied) {
        private Rule {
            allowed = Set.copyOf(allowed);
            denied = Set.copyOf(denied);
        }
    }

    private final EnumMap<Permission, Rule> rules;

    private RuntimePermissions(EnumMap<Permission, Rule> rules) {
        this.rules = new EnumMap<>(Permission.class);
        this.rules.putAll(rules);
    }

    /** Deny every privileged host-I/O operation. */
    public static RuntimePermissions denyAll() {
        return new Builder().build();
    }

    /**
     * Compatibility bridge for embedders that only supply coarse capabilities:
     * an explicitly granted capability means unrestricted access in that
     * category until a scoped permission policy is supplied.
     */
    public static RuntimePermissions fromCapabilities(Set<IsolatePolicy.Capability> capabilities) {
        Builder builder = new Builder();
        for (Permission permission : Permission.values()) {
            if (capabilities.contains(permission.capability())) builder.allowAll(permission);
        }
        return builder.build();
    }

    public static RuntimePermissions fromApplicationArguments(
            String[] args,
            IsolatePolicy isolatePolicy) {
        Builder builder = new Builder();
        boolean sawPermissionArgument = false;
        boolean sawExplicitMarker = false;
        Set<String> seenSerializedRules = new LinkedHashSet<>();

        for (String arg : args) {
            if (!arg.startsWith(ARG_PREFIX) || arg.startsWith("--ores-permission-check=")) continue;
            sawPermissionArgument = true;
            // Even deny-all must stay explicit. Duplicated markers and exact
            // rule arguments are ambiguous/corrupted serialized policies,
            // not an opportunity to silently reinterpret caller authority.
            if (arg.equals(ARG_PREFIX + "explicit=true")) {
                if (sawExplicitMarker)
                    throw new IllegalArgumentException("duplicate Oreslang permission marker");
                sawExplicitMarker = true;
                continue;
            }
            if (!seenSerializedRules.add(arg))
                throw new IllegalArgumentException("duplicate Oreslang permission argument");
            String remainder = arg.substring(ARG_PREFIX.length());
            int equals = remainder.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("invalid Oreslang permission argument: " + arg);
            }
            String key = remainder.substring(0, equals);
            String value = remainder.substring(equals + 1);
            boolean deny;
            String permissionName;
            if (key.endsWith("-allow")) {
                deny = false;
                permissionName = key.substring(0, key.length() - "-allow".length());
            } else if (key.endsWith("-deny")) {
                deny = true;
                permissionName = key.substring(0, key.length() - "-deny".length());
            } else {
                throw new IllegalArgumentException("invalid Oreslang permission argument: " + arg);
            }

            Permission permission = Permission.fromCliName(permissionName);
            if (deny) builder.deny(permission, value);
            else builder.allow(permission, value);
        }

        if (!sawPermissionArgument)
            return fromCapabilities(isolatePolicy.capabilities());

        RuntimePermissions explicit = builder.build();
        // Scoped rules ATTENUATE isolate authority; they must never add a
        // coarse capability omitted by the host's isolation boundary. The
        // OresContext.requirePermission path also checks the coarse policy,
        // but standalone embedders must not receive an over-granting object.
        for (Permission permission : Permission.values()) {
            if (explicit.grantsAnything(permission)
                    && !isolatePolicy.capabilities().contains(permission.capability())) {
                throw new SecurityException("permission " + permission.cliName()
                        + " exceeds isolate capability " + permission.capability());
            }
        }
        return explicit;
    }

    public String[] applicationArguments() {
        ArrayList<String> arguments = new ArrayList<>();
        // Distinguish an explicitly empty (deny-all) permission policy from
        // the legacy embedding API that omitted a permission policy entirely.
        arguments.add(ARG_PREFIX + "explicit=true");
        for (Permission permission : Permission.values()) {
            Rule rule = rules.get(permission);
            if (rule == null) continue;
            if (rule.allowAll()) {
                arguments.add(ARG_PREFIX + permission.cliName() + "-allow=*");
            } else {
                rule.allowed().stream().sorted().forEach(scope ->
                        arguments.add(ARG_PREFIX + permission.cliName() + "-allow=" + scope));
            }
            if (rule.denyAll()) {
                arguments.add(ARG_PREFIX + permission.cliName() + "-deny=*");
            } else {
                rule.denied().stream().sorted().forEach(scope ->
                        arguments.add(ARG_PREFIX + permission.cliName() + "-deny=" + scope));
            }
        }
        return arguments.toArray(String[]::new);
    }

    public RuntimePermissions withAllowed(Permission permission, String... scopes) {
        Builder builder = new Builder(this);
        for (String scope : scopes) builder.allow(permission, scope);
        return builder.build();
    }

    public RuntimePermissions withDenied(Permission permission, String... scopes) {
        Builder builder = new Builder(this);
        for (String scope : scopes) builder.deny(permission, scope);
        return builder.build();
    }

    public RuntimePermissions allowAll(Permission... permissions) {
        Builder builder = new Builder(this);
        for (Permission permission : permissions) builder.allowAll(permission);
        return builder.build();
    }

    public void require(Permission permission, String resource, String api) {
        if (!allows(permission, resource)) {
            String rendered = resource == null || resource.isBlank() ? "<all>" : resource;
            throw new SecurityException(
                    "Oreslang permission denied: " + permission.cliName()
                            + " access to '" + rendered + "' required by " + api);
        }
    }

    public boolean allows(Permission permission, String resource) {
        Rule rule = rules.get(permission);
        if (rule == null || !safeScope(resource)) return false;

        // Invalid endpoint spellings must not match a broad network grant.
        if (permission == Permission.NET) {
            NetworkScope endpoint = parseNetworkScope(resource, false);
            if (endpoint == null) return false;
            if (endpoint.port() == null) {
                // A portless authorization request cannot prove it excludes
                // a denied port on the same host. Do not let a broad allow
                // turn this missing detail into an authorization bypass.
                for (String denied : rule.denied()) {
                    NetworkScope blocked = parseNetworkScope(denied, true);
                    if (blocked != null && blocked.port() != null
                            && networkHostMatches(endpoint, blocked)) {
                        return false;
                    }
                }
            }
        }
        String normalized = normalize(permission, resource);
        if (rule.denyAll() || matchesAny(permission, normalized, rule.denied())) return false;
        return rule.allowAll() || matchesAny(permission, normalized, rule.allowed());
    }

    public boolean grantsAnything(Permission permission) {
        Rule rule = rules.get(permission);
        return rule != null && !rule.denyAll() && (rule.allowAll() || !rule.allowed().isEmpty());
    }

    public Set<IsolatePolicy.Capability> grantedCapabilities() {
        EnumSet<IsolatePolicy.Capability> capabilities =
                EnumSet.noneOf(IsolatePolicy.Capability.class);
        for (Permission permission : Permission.values()) {
            if (grantsAnything(permission)) capabilities.add(permission.capability());
        }
        return Set.copyOf(capabilities);
    }

    private static boolean matchesAny(
            Permission permission,
            String resource,
            Set<String> scopes) {
        for (String scope : scopes) {
            if (matches(permission, resource, scope)) return true;
        }
        return false;
    }

    private static boolean matches(Permission permission, String resource, String rawScope) {
        if ("*".equals(rawScope)) return true;
        String scope = normalize(permission, rawScope);

        return switch (permission) {
            case READ, WRITE, FFI, IMPORT -> {
                if (resource.equals(scope)) yield true;
                String separator = java.io.File.separator;
                yield resource.startsWith(scope.endsWith(separator) ? scope : scope + separator);
            }
            case NET -> networkMatches(resource, scope);
            case ENV, RUN, SYS -> tokenMatches(resource, scope);
        };
    }

    /**
     * Compare normalized host and port independently. Comparing a wildcard
     * hostname against the whole "host:port" string previously made
     * --deny-net=*.example.com:443 a no-op against --allow-net=*.
     */
    private static boolean networkMatches(String resource, String scope) {
        NetworkScope target = parseNetworkScope(resource, false);
        NetworkScope rule = parseNetworkScope(scope, true);
        if (target == null || rule == null) return false;
        if (rule.port() != null && !rule.port().equals(target.port())) return false;
        return networkHostMatches(target, rule);
    }

    private static boolean networkHostMatches(NetworkScope target, NetworkScope rule) {
        if (rule.host().startsWith("*.")) {
            String suffix = rule.host().substring(1);
            return target.host().endsWith(suffix)
                    && target.host().length() > suffix.length();
        }
        return rule.host().equals(target.host());
    }

    private record NetworkScope(String host, Integer port) {
        String canonical() {
            return port == null ? host : host + ":" + port;
        }
    }

    /**
     * Host[:port] or [IPv6][:port]; no DNS lookup, unbracketed IPv6,
     * credentials, URL path, partial port, or additional colon is accepted.
     * Canonicalize the final DNS dot so a deny cannot be bypassed with an
     * equivalent fully-qualified DNS hostname.
     */
    private static NetworkScope parseNetworkScope(String input, boolean allowWildcard) {
        if (input == null || input.isBlank()) return null;
        String text = input.trim().toLowerCase(Locale.ROOT);
        String host;
        String portText = null;
        if (text.startsWith("[")) {
            int closing = text.indexOf(']');
            if (closing < 3 || text.indexOf('[', 1) >= 0) return null;
            String literal = text.substring(1, closing);
            // Compare IPv6 *addresses*, not textual spellings: [::1] and
            // [0:0:0:0:0:0:0:1] must share the same allow/deny identity.
            // Only numeric literals are admitted here (never hostnames or
            // zone identifiers), and IPv4-mapped forms are rejected instead
            // of silently crossing the IPv4/IPv6 permission boundary.
            if (!literal.contains(":") || !literal.matches("[0-9a-f:.]+")) return null;
            try {
                java.net.InetAddress address = java.net.InetAddress.getByName(literal);
                if (!(address instanceof java.net.Inet6Address)) return null;
                byte[] bytes = address.getAddress();
                StringBuilder canonical = new StringBuilder("[");
                for (int i = 0; i < bytes.length; i += 2) {
                    if (i != 0) canonical.append(':');
                    canonical.append(Integer.toHexString(
                            ((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff)));
                }
                host = canonical.append(']').toString();
            } catch (java.net.UnknownHostException invalid) {
                return null;
            }
            if (closing + 1 < text.length()) {
                if (text.charAt(closing + 1) != ':') return null;
                portText = text.substring(closing + 2);
            }
        } else {
            int colon = text.indexOf(':');
            if (colon < 0) {
                host = text;
            } else {
                if (text.indexOf(':', colon + 1) >= 0) return null;
                host = text.substring(0, colon);
                portText = text.substring(colon + 1);
            }
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.isEmpty() || host.startsWith(".") || host.contains("..")
                    || !host.matches("[a-z0-9*._-]+")) return null;
            if (host.contains("*") && !(allowWildcard && host.startsWith("*.")
                    && host.indexOf('*', 1) < 0)) return null;
            // Numeric IPv4 aliases (127.1, 2130706433, leading-zero octets)
            // may resolve to a denied dotted-quad on some hosts. Reject them
            // before an unrestricted network grant can mask the denied IP.
            // The actual socket layer must still check the resolved address.
            if (host.matches("[0-9.]+")) {
                String[] octets = host.split("\\.", -1);
                if (octets.length != 4) return null;
                for (String octet : octets) {
                    if (octet.isEmpty() || octet.length() > 3
                            || (octet.length() > 1 && octet.charAt(0) == '0')
                            || !octet.matches("[0-9]+")
                            || Integer.parseInt(octet) > 255) return null;
                }
            }
        }
        Integer port = null;
        if (portText != null) {
            if (portText.isEmpty() || !portText.matches("[0-9]{1,5}")) return null;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException invalid) {
                return null;
            }
            if (port < 1 || port > 65535) return null;
        }
        return new NetworkScope(host, port);
    }

    private static boolean tokenMatches(String resource, String scope) {
        if (resource.equals(scope)) return true;
        return scope.endsWith("*")
                && resource.startsWith(scope.substring(0, scope.length() - 1));
    }

    /** Avoid treating malformed host resources as all-access requests.
     * POSIX filenames may contain control bytes, but they are deliberately
     * unsupported permission scope/resource spellings across VM backends.
     */
    private static boolean safeScope(String value) {
        if (value == null || value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) return false;
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    return false;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(Permission permission, String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        if ("*".equals(trimmed)) return "*";

        return switch (permission) {
            case READ, WRITE, FFI, IMPORT -> normalizePath(trimmed);
            case NET -> {
                NetworkScope scope = parseNetworkScope(trimmed, true);
                if (scope == null) {
                    throw new IllegalArgumentException("invalid network permission scope '" + trimmed + "'");
                }
                yield scope.canonical();
            }
            case ENV, RUN, SYS -> trimmed;
        };
    }

    private static String normalizePath(String value) {
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            Path existing = path;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing != null) {
                try {
                    path = existing.toRealPath().resolve(existing.relativize(path)).normalize();
                } catch (java.io.IOException failure) {
                    throw new IllegalArgumentException(
                            "cannot resolve permission path '" + value + "'", failure);
                }
            }
            return path.toString();
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("invalid permission path '" + value + "'", invalid);
        }
    }

    private static final class MutableRule {
        private boolean allowAll;
        private boolean denyAll;
        private final LinkedHashSet<String> allowed = new LinkedHashSet<>();
        private final LinkedHashSet<String> denied = new LinkedHashSet<>();

        private MutableRule() { }

        private MutableRule(Rule rule) {
            this.allowAll = rule.allowAll();
            this.denyAll = rule.denyAll();
            this.allowed.addAll(rule.allowed());
            this.denied.addAll(rule.denied());
        }

        private Rule freeze() {
            return new Rule(allowAll, allowed, denyAll, denied);
        }
    }

    public static final class Builder {
        private final EnumMap<Permission, MutableRule> rules =
                new EnumMap<>(Permission.class);

        public Builder() { }

        public Builder(RuntimePermissions source) {
            source.rules.forEach((permission, rule) ->
                    rules.put(permission, new MutableRule(rule)));
        }

        public Builder allowAll(Permission permission) {
            MutableRule rule = rules.computeIfAbsent(permission, ignored -> new MutableRule());
            rule.allowAll = true;
            return this;
        }

        public Builder allow(Permission permission, String scope) {
            // Empty filesystem scopes previously normalized to "" and matched
            // EVERY absolute Unix path via resource.startsWith("/").
            // A missing scope must never become a root-wide capability.
            if (!safeScope(scope))
                throw new IllegalArgumentException("invalid allow scope for " + permission);
            MutableRule rule = rules.computeIfAbsent(permission, ignored -> new MutableRule());
            if ("*".equals(scope.trim())) rule.allowAll = true;
            else rule.allowed.add(normalize(permission, scope));
            return this;
        }

        public Builder allowCsv(Permission permission, String scopes) {
            // Stage all normalized scopes before touching live authority:
            // invalid path syntax or malformed CSV must not partially grant.
            List<String> values = normalizedCsvScopes(permission, scopes);
            MutableRule current = rules.get(permission);
            MutableRule staged = current == null
                    ? new MutableRule() : new MutableRule(current.freeze());
            for (String value : values) {
                if ("*".equals(value)) staged.allowAll = true;
                else staged.allowed.add(value);
            }
            rules.put(permission, staged);
            return this;
        }

        public Builder denyAll(Permission permission) {
            MutableRule rule = rules.computeIfAbsent(permission, ignored -> new MutableRule());
            rule.denyAll = true;
            return this;
        }

        public Builder deny(Permission permission, String scope) {
            // Denials must not accept an ambiguous empty resource either;
            // malformed serialized arguments should fail, not be reinterpreted.
            if (scope == null || scope.isBlank())
                throw new IllegalArgumentException("empty deny scope for " + permission);
            MutableRule rule = rules.computeIfAbsent(permission, ignored -> new MutableRule());
            if ("*".equals(scope.trim())) rule.denyAll = true;
            else rule.denied.add(normalize(permission, scope));
            return this;
        }

        public Builder denyCsv(Permission permission, String scopes) {
            List<String> values = normalizedCsvScopes(permission, scopes);
            MutableRule current = rules.get(permission);
            MutableRule staged = current == null
                    ? new MutableRule() : new MutableRule(current.freeze());
            for (String value : values) {
                if ("*".equals(value)) staged.denyAll = true;
                else staged.denied.add(value);
            }
            rules.put(permission, staged);
            return this;
        }

        private static List<String> normalizedCsvScopes(Permission permission, String scopes) {
            if (scopes == null || scopes.isBlank()) {
                throw new IllegalArgumentException("empty scope list for " + permission);
            }
            String[] parts = scopes.split(",", -1);
            List<String> values = new ArrayList<>(parts.length);
            for (String part : parts) {
                String value = part.trim();
                if (value.isEmpty()) {
                    throw new IllegalArgumentException(
                            "empty comma-separated scope for " + permission);
                }
                if (!safeScope(value))
                    throw new IllegalArgumentException("invalid permission scope for " + permission);
                values.add("*".equals(value) ? "*" : normalize(permission, value));
            }
            return values;
        }

        public RuntimePermissions build() {
            EnumMap<Permission, Rule> frozen = new EnumMap<>(Permission.class);
            rules.forEach((permission, rule) -> frozen.put(permission, rule.freeze()));
            return new RuntimePermissions(frozen);
        }
    }
}
