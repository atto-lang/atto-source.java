package dev.oreslang.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Compile-time admission of externally produced .oresi metadata bound to a
 * trusted, immutable dependency-lock entry. Never loads or executes binaries.
 *
 * A .oresi file is an exported INTERFACE, not a verified implementation or
 * authentication certificate. The caller must independently authenticate the
 * lockfile, compiler provenance and platform-native binary payload.
 *
 * This staged API is deliberately not wired to import grammar or guest linking:
 * the v1 codec has no full resolved ABI, imported type identities or private
 * native storage layout proof. No linkable program is emitted by this class.
 */
public final class PinnedPackageInterface {
    private PinnedPackageInterface() { }

    private static final Pattern ALIAS = Pattern.compile("[a-z][a-z0-9_]{0,127}");
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern INSTANCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/@#:+-]{0,255}");

    /** One digest-pinned public-interface envelope per declared namespace. */
    public record NamespacePin(String namespace, String interfaceSha256) {
        public NamespacePin {
            if (namespace == null || !NAMESPACE.matcher(namespace).matches()
                    || interfaceSha256 == null || !DIGEST.matcher(interfaceSha256).matches()) {
                throw new IllegalArgumentException("invalid namespace interface pin");
            }
        }
    }

    /**
     * Exact package instance and compiler profile expected from an
     * independently verified dependency manifest + lock.
     *
     * This value is not itself a proof of provenance, authorization, trust,
     * binary payload compatibility or runtime capabilities.
     */
    public record Lock(
            String alias, String packageInstanceId, String compilerVersion,
            String targetAbi, String featurePolicy, List<NamespacePin> namespaces) {
        public Lock {
            if (alias == null || !ALIAS.matcher(alias).matches()
                    || packageInstanceId == null
                    || !INSTANCE.matcher(packageInstanceId).matches()
                    || packageInstanceId.contains("//")
                    || packageInstanceId.contains("/../")
                    || packageInstanceId.endsWith("/..")
                    || packageInstanceId.contains("/./")
                    || packageInstanceId.endsWith("/.")
                    || compilerVersion == null || compilerVersion.isBlank()
                    || targetAbi == null || targetAbi.isBlank()
                    || featurePolicy == null || featurePolicy.isBlank()) {
                throw new IllegalArgumentException("invalid locked dependency identity/profile");
            }
            Objects.requireNonNull(namespaces, "namespace pins");
            namespaces = List.copyOf(namespaces);
            if (namespaces.isEmpty() || namespaces.size() > 4096) {
                throw new IllegalArgumentException("bounded namespace pin set required");
            }
            Set<String> seen = new HashSet<>();
            for (NamespacePin pin : namespaces) {
                if (!seen.add(pin.namespace())) {
                    throw new IllegalArgumentException("duplicate locked namespace");
                }
            }
        }
    }

    /** Qualified symbol identity only. No executable body or actor reference. */
    public record PublicSymbol(
            String packageInstanceId, String namespace, String module,
            ExportInterfaceCodec.Kind kind, String name, String signature) { }

    public static final class Verified {
        private final Lock lock;
        private final Map<String, List<PublicSymbol>> namespaces;
        private final List<PublicSymbol> exported;

        private Verified(Lock lock, Map<String, List<PublicSymbol>> namespaces) {
            this.lock = lock;
            TreeMap<String, List<PublicSymbol>> copy = new TreeMap<>();
            List<PublicSymbol> all = new ArrayList<>();
            namespaces.forEach((name, values) -> {
                List<PublicSymbol> symbols = List.copyOf(values);
                copy.put(name, symbols);
                all.addAll(symbols);
            });
            this.namespaces = java.util.Collections.unmodifiableMap(copy);
            this.exported = List.copyOf(all);
        }

        public String alias() { return lock.alias(); }
        public String packageInstanceId() { return lock.packageInstanceId(); }

        /** The entire public surface stays qualified, never ambient. */
        public List<PublicSymbol> packageExports() { return exported; }

        public List<PublicSymbol> namespaceExports(String name) {
            List<PublicSymbol> values = namespaces.get(name);
            if (values == null) {
                throw new IllegalArgumentException("namespace not exported by locked package");
            }
            return values;
        }

        /**
         * Only explicitly exported modules can be selected. A public function
         * in a private/unrepresented module is not a module import grant.
         */
        public List<PublicSymbol> moduleExports(String namespace, String module) {
            List<PublicSymbol> values = namespaceExports(namespace);
            boolean present = values.stream().anyMatch(s ->
                    s.kind() == ExportInterfaceCodec.Kind.MODULE
                    && s.module().equals(module) && s.name().equals(module));
            if (!present) {
                throw new IllegalArgumentException("module not exported by locked package");
            }
            return values.stream().filter(s -> s.module().equals(module)).toList();
        }
    }

    public static Verified admit(Lock lock, Map<String, byte[]> envelopes) {
        Objects.requireNonNull(lock, "locked dependency");
        Objects.requireNonNull(envelopes, "namespace .oresi envelopes");
        if (envelopes.size() != lock.namespaces().size()) {
            throw new IllegalArgumentException("interface envelope inventory differs from lock");
        }
        Map<String, byte[]> snapshot = new HashMap<>();
        envelopes.forEach((name, bytes) -> {
            if (name == null || bytes == null || snapshot.putIfAbsent(name, bytes.clone()) != null) {
                throw new IllegalArgumentException("invalid or duplicate interface envelope");
            }
        });
        Map<String, List<PublicSymbol>> index = new TreeMap<>();
        for (NamespacePin pin : lock.namespaces()) {
            byte[] data = snapshot.get(pin.namespace());
            if (data == null) {
                throw new IllegalArgumentException("missing locked namespace interface");
            }
            ExportInterfaceCodec.InterfaceFile decoded = ExportInterfaceCodec.decode(data);
            if (!decoded.sha256().equals(pin.interfaceSha256())) {
                throw new IllegalArgumentException("package interface digest mismatch");
            }
            ExportInterfaceCodec.Header header = decoded.header();
            if (!header.namespace().equals(pin.namespace())
                    || !header.compilerVersion().equals(lock.compilerVersion())
                    || !header.targetAbi().equals(lock.targetAbi())
                    || !header.featurePolicy().equals(lock.featurePolicy())) {
                throw new IllegalArgumentException("package interface profile/namespace mismatch");
            }

            Set<String> publicModules = new HashSet<>();
            for (ExportInterfaceCodec.Entry entry : decoded.entries()) {
                if (entry.kind() == ExportInterfaceCodec.Kind.MODULE) {
                    if (!entry.module().equals(entry.name())) {
                        throw new IllegalArgumentException("invalid public module export identity");
                    }
                    publicModules.add(entry.module());
                }
            }
            List<PublicSymbol> visible = new ArrayList<>();
            for (ExportInterfaceCodec.Entry entry : decoded.entries()) {
                // V1 metadata is an exported-only surface, but it cannot
                // authenticate the compiler or prove containing-module scope.
                // Orphan entries are never admitted even from a matching hash.
                if (!publicModules.contains(entry.module())) {
                    throw new IllegalArgumentException("unverified containing module visibility");
                }
                visible.add(new PublicSymbol(lock.packageInstanceId(), pin.namespace(),
                        entry.module(), entry.kind(), entry.name(), entry.signature()));
            }
            // Preserve ExportInterfaceCodec's unsigned UTF-8 canonical entry order.
            index.put(pin.namespace(), visible);
        }
        if (index.size() != snapshot.size()) {
            throw new IllegalArgumentException("unlocked extra namespace interface");
        }
        return new Verified(lock, index);
    }
}
