package dev.oreslang.types;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Compiler-owned implicit Atto prelude: static symbol bindings, not VM globals.
 *
 * <p>This catalog is a source-name contract and deliberately does not install,
 * link, allocate or execute an `.atto` module. Each backend owns its own
 * implementation; the type checker and OwnershipChecker must still prove
 * constructor identity, loans, drops and available target lowering.</p>
 *
 * <p>Java's historical `string`/`String` scalar can denote a borrowed or
 * immutable host-backed view even in a core-only compilation. Such legacy
 * scalar recognition is NOT an allocator-owned `alloc/string/String` proof.</p>
 */
public final class AutomaticPrelude {
    private AutomaticPrelude() { }

    public enum Profile { CORE, ALLOC, HOSTED }
    public enum Scope { CORE, ALLOC, EXPLICIT }
    public enum Kind { TYPE, CONSTRUCTOR, TRAIT }
    /** Static safety classification; NOT a borrow-checking result. */
    public enum Ownership { PAYLOAD_DEPENDENT, BORROWED_VIEW, OWNED, TRAIT }

    public record Binding(String name, String canonical, Scope scope, Kind kind,
                          Ownership ownership) {
        public Binding {
            Objects.requireNonNull(name);
            Objects.requireNonNull(canonical);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(ownership);
            if (name.isBlank() || !canonical.endsWith("/" + name)
                    || canonical.contains("..") || canonical.startsWith("/")) {
                throw new IllegalArgumentException("invalid prelude canonical identity");
            }
        }
    }

    private static final Map<String, Binding> CATALOG = createCatalog();

    private static Map<String, Binding> createCatalog() {
        Map<String, Binding> names = new LinkedHashMap<>();
        // Core ADTs are compiler-owned; a syntactic variant is never a user
        // function with the same display name. Ownership follows its payload.
        add(names, "Option", "core/option", Scope.CORE, Kind.TYPE, Ownership.PAYLOAD_DEPENDENT);
        add(names, "Some", "core/option", Scope.CORE, Kind.CONSTRUCTOR, Ownership.PAYLOAD_DEPENDENT);
        add(names, "None", "core/option", Scope.CORE, Kind.CONSTRUCTOR, Ownership.PAYLOAD_DEPENDENT);
        add(names, "Result", "core/result", Scope.CORE, Kind.TYPE, Ownership.PAYLOAD_DEPENDENT);
        add(names, "Ok", "core/result", Scope.CORE, Kind.CONSTRUCTOR, Ownership.PAYLOAD_DEPENDENT);
        add(names, "Err", "core/result", Scope.CORE, Kind.CONSTRUCTOR, Ownership.PAYLOAD_DEPENDENT);
        add(names, "Error", "core/error", Scope.CORE, Kind.TYPE, Ownership.OWNED);
        add(names, "Array", "core/array", Scope.CORE, Kind.TYPE, Ownership.PAYLOAD_DEPENDENT);
        add(names, "FixedArray", "core/array", Scope.CORE, Kind.TYPE, Ownership.PAYLOAD_DEPENDENT);
        add(names, "Slice", "core/slice", Scope.CORE, Kind.TYPE, Ownership.BORROWED_VIEW);
        add(names, "Tuple", "core/tuple", Scope.CORE, Kind.TYPE, Ownership.PAYLOAD_DEPENDENT);
        add(names, "Pair", "core/tuple", Scope.CORE, Kind.TYPE, Ownership.PAYLOAD_DEPENDENT);
        add(names, "Iterator", "core/iter", Scope.CORE, Kind.TRAIT, Ownership.TRAIT);
        add(names, "Range", "core/range", Scope.CORE, Kind.TYPE, Ownership.PAYLOAD_DEPENDENT);
        add(names, "Eq", "core/cmp", Scope.CORE, Kind.TRAIT, Ownership.TRAIT);
        add(names, "Ord", "core/cmp", Scope.CORE, Kind.TRAIT, Ownership.TRAIT);
        add(names, "Ordering", "core/cmp", Scope.CORE, Kind.TYPE, Ownership.PAYLOAD_DEPENDENT);
        add(names, "From", "core/convert", Scope.CORE, Kind.TRAIT, Ownership.TRAIT);
        add(names, "Into", "core/convert", Scope.CORE, Kind.TRAIT, Ownership.TRAIT);
        add(names, "TryFrom", "core/convert", Scope.CORE, Kind.TRAIT, Ownership.TRAIT);
        add(names, "TryInto", "core/convert", Scope.CORE, Kind.TRAIT, Ownership.TRAIT);
        add(names, "Display", "core/fmt", Scope.CORE, Kind.TRAIT, Ownership.TRAIT);
        add(names, "Debug", "core/fmt", Scope.CORE, Kind.TRAIT, Ownership.TRAIT);
        add(names, "Sendable", "core/marker", Scope.CORE, Kind.TRAIT, Ownership.TRAIT);
        add(names, "String", "alloc/string", Scope.ALLOC, Kind.TYPE, Ownership.OWNED);
        add(names, "List", "alloc/list", Scope.ALLOC, Kind.TYPE, Ownership.OWNED);
        add(names, "Vec", "alloc/vec", Scope.ALLOC, Kind.TYPE, Ownership.OWNED);
        add(names, "Map", "alloc/map", Scope.ALLOC, Kind.TYPE, Ownership.OWNED);
        add(names, "Set", "alloc/set", Scope.ALLOC, Kind.TYPE, Ownership.OWNED);
        add(names, "Box", "alloc/box", Scope.ALLOC, Kind.TYPE, Ownership.OWNED);
        add(names, "HashMap", "alloc/hash_map", Scope.EXPLICIT, Kind.TYPE, Ownership.OWNED);
        add(names, "HashSet", "alloc/hash_set", Scope.EXPLICIT, Kind.TYPE, Ownership.OWNED);
        add(names, "Entry", "alloc/map", Scope.EXPLICIT, Kind.TYPE, Ownership.PAYLOAD_DEPENDENT);
        return Collections.unmodifiableMap(names);
    }

    private static void add(Map<String, Binding> map, String name, String module,
                            Scope scope, Kind kind, Ownership ownership) {
        Binding next = new Binding(name, module + "/" + name, scope, kind, ownership);
        if (map.putIfAbsent(name, next) != null) {
            throw new IllegalStateException("duplicate prelude name " + name);
        }
    }

    public static Map<String, Binding> catalog() {
        return CATALOG;
    }

    /** Presence never implies that the target implements the named type. */
    public static Optional<Binding> lookup(String name, Profile profile) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(profile);
        Binding candidate = CATALOG.get(name);
        if (candidate == null || candidate.scope() == Scope.EXPLICIT) return Optional.empty();
        if (candidate.scope() == Scope.ALLOC && profile == Profile.CORE) return Optional.empty();
        return Optional.of(candidate);
    }

    public static boolean coreConstructor(String name) {
        Binding candidate = CATALOG.get(name);
        return candidate != null && candidate.scope() == Scope.CORE
                && candidate.kind() == Kind.CONSTRUCTOR;
    }

    /**
     * Privileged identity names cannot be rebound by a class, alias, routine,
     * foreign import or function. Collection identities are also protected
     * when their implementations are not yet available.
     */
    public static boolean protectedIdentity(String name) {
        return switch (name) {
            case "Option", "Result", "Some", "None", "Ok", "Err",
                    "Array", "FixedArray", "List", "FixedList",
                    "Vector", "Slice", "Tuple", "Map", "Set",
                    "ReadLoan", "WriteLoan" -> true;
            default -> false;
        };
    }

    /** A payload-sensitive sum is affine exactly when any active payload is. */
    public static boolean coreSumCarrier(String name, int arity) {
        return ("Option".equals(name) && arity == 1)
                || ("Result".equals(name) && arity == 2);
    }
}
