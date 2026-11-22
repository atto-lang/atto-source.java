package dev.oreslang.types;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Automatic lexical prelude view over {@link CoreTypeRegistry}.
 *
 * <p>This class determines which canonical names are automatically visible
 * under a source profile. It is not a process-global object, runtime module,
 * allocator grant or a substitute for borrow/type checking.</p>
 */
public final class AutomaticPrelude {
    private AutomaticPrelude() { }

    public enum Profile { CORE, ALLOC, HOSTED }
    public enum Scope { CORE, ALLOC, EXPLICIT }
    public enum Kind { TYPE, CONSTRUCTOR, TRAIT }
    public enum Ownership { COPY, PAYLOAD_DEPENDENT, BORROWED_VIEW, OWNED, TRAIT }

    public record Binding(String name, String canonical, Scope scope, Kind kind,
                          Ownership ownership) {
        public Binding {
            Objects.requireNonNull(name);
            Objects.requireNonNull(canonical);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(ownership);
            if (name.isBlank() || canonical.isBlank()
                    || canonical.contains("..") || canonical.startsWith("/")) {
                throw new IllegalArgumentException("invalid prelude canonical identity");
            }
        }
    }

    private static final Map<String, Binding> CATALOG = createCatalog();

    private static Map<String, Binding> createCatalog() {
        Map<String, Binding> names = new LinkedHashMap<>();
        for (CoreTypeRegistry.TypeInfo type : CoreTypeRegistry.all().values()) {
            Scope scope = switch (type.scope()) {
                case CORE -> Scope.CORE;
                case ALLOC -> Scope.ALLOC;
                case EXPLICIT -> Scope.EXPLICIT;
            };
            Kind kind = switch (type.kind()) {
                case TYPE -> Kind.TYPE;
                case CONSTRUCTOR -> Kind.CONSTRUCTOR;
                case TRAIT -> Kind.TRAIT;
            };
            Ownership ownership = switch (type.ownership()) {
                case COPY -> Ownership.COPY;
                case PAYLOAD_DEPENDENT -> Ownership.PAYLOAD_DEPENDENT;
                case BORROWED_VIEW -> Ownership.BORROWED_VIEW;
                case OWNED -> Ownership.OWNED;
                case TRAIT -> Ownership.TRAIT;
            };
            Binding binding = new Binding(type.name(), type.canonicalIdentity(),
                    scope, kind, ownership);
            if (names.putIfAbsent(binding.name(), binding) != null) {
                throw new IllegalStateException("duplicate prelude binding " + binding.name());
            }
        }
        return Collections.unmodifiableMap(names);
    }

    public static Map<String, Binding> catalog() { return CATALOG; }

    /** Name visibility only: a registry entry does not assert backend lowering. */
    public static Optional<Binding> lookup(String name, Profile profile) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(profile);
        Binding candidate = CATALOG.get(name);
        if (candidate == null || candidate.scope() == Scope.EXPLICIT) return Optional.empty();
        if (candidate.scope() == Scope.ALLOC && profile == Profile.CORE) return Optional.empty();
        return Optional.of(candidate);
    }

    public static Optional<CoreTypeRegistry.TypeInfo> typeInfo(String name) {
        return CoreTypeRegistry.lookup(name);
    }

    public static boolean coreConstructor(String name) {
        return CoreTypeRegistry.coreConstructor(name);
    }

    public static boolean protectedIdentity(String name) {
        return CoreTypeRegistry.protectedIdentity(name);
    }

    public static boolean coreSumCarrier(String name, int arity) {
        return CoreTypeRegistry.coreSumCarrier(name, arity);
    }
}
