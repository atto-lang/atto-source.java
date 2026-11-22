package dev.oreslang.types;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical compiler-owned type identity and safety metadata.
 *
 * <p>This registry is the semantic source of truth for prelude type names.
 * It is not runtime storage, an allocator, or a claim that every backend has
 * emitted lowering for every entry. A registry hit establishes nominal
 * identity only; TypeChecker and OwnershipChecker remain responsible for
 * checking arity, capabilities, lifetimes, moves, drops and actor transfer.</p>
 */
public final class CoreTypeRegistry {
    private CoreTypeRegistry() { }

    public enum Scope { CORE, ALLOC, EXPLICIT }
    public enum Kind { TYPE, CONSTRUCTOR, TRAIT }
    public enum Ownership {
        /** Copy/send/drop behavior is derived recursively from payloads. */
        PAYLOAD_DEPENDENT,
        /** Non-owning view with a source lifetime. */
        BORROWED_VIEW,
        /** Owns allocation/storage and requires deterministic destruction. */
        OWNED,
        /** Contract-only type; no value storage is implied. */
        TRAIT
    }
    public enum Allocation { NONE, BORROWED_VIEW, ALLOCATOR_REQUIRED }
    public enum DropPolicy {
        NONE,
        /** Drop exactly the active variant's live payload, once. */
        ACTIVE_PAYLOAD_ONCE,
        /** Drop owned backing storage and its initialized elements. */
        OWNED_STORAGE,
        /** No runtime drop contract; must not be instantiated as a value. */
        NOT_A_VALUE
    }
    public enum SendPolicy {
        /** Sendable iff all live payload/member types are Sendable. */
        PAYLOAD_DEPENDENT,
        /** Borrow never becomes Sendable merely from its referent's type. */
        NEVER_BORROWED,
        /** Trait assertion must be compiler-proven, never name-implied. */
        PROOF_REQUIRED,
        /** No runtime value. */
        NOT_APPLICABLE
    }
    public enum LoweringStatus {
        /** Existing Java source semantics/type lowering; guest/AOT still gated by tests. */
        JAVA_INTRINSIC,
        /** Existing legacy scalar name; not an owned alloc::String. */
        JAVA_LEGACY_SCALAR,
        /** Catalog/spec contract only; backend lowering is not implied. */
        CONTRACT_ONLY
    }

    public record TypeInfo(
            String name,
            String canonicalIdentity,
            Scope scope,
            Kind kind,
            int minGenericArity,
            int maxGenericArity,
            Ownership ownership,
            Allocation allocation,
            DropPolicy dropPolicy,
            SendPolicy sendPolicy,
            LoweringStatus javaStatus) {
        public TypeInfo {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(canonicalIdentity, "canonicalIdentity");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(ownership, "ownership");
            Objects.requireNonNull(allocation, "allocation");
            Objects.requireNonNull(dropPolicy, "dropPolicy");
            Objects.requireNonNull(sendPolicy, "sendPolicy");
            Objects.requireNonNull(javaStatus, "javaStatus");
            if (name.isBlank() || !canonicalIdentity.endsWith("/" + name)
                    || canonicalIdentity.startsWith("/") || canonicalIdentity.contains("..")
                    || minGenericArity < 0 || maxGenericArity < minGenericArity) {
                throw new IllegalArgumentException("invalid canonical core type metadata: " + name);
            }
            if (kind == Kind.TRAIT && (ownership != Ownership.TRAIT
                    || dropPolicy != DropPolicy.NOT_A_VALUE
                    || sendPolicy != SendPolicy.NOT_APPLICABLE)) {
                throw new IllegalArgumentException("trait has value-storage metadata: " + name);
            }
            if (allocation == Allocation.ALLOCATOR_REQUIRED
                    && scope == Scope.CORE) {
                throw new IllegalArgumentException("core-only type cannot require alloc profile: " + name);
            }
            if (ownership == Ownership.BORROWED_VIEW
                    && allocation != Allocation.BORROWED_VIEW) {
                throw new IllegalArgumentException("borrowed view allocation metadata mismatch: " + name);
            }
        }

        public boolean acceptsArity(int arity) {
            return arity >= minGenericArity && arity <= maxGenericArity;
        }

        public boolean compilerOwnedIdentity() {
            return kind == Kind.CONSTRUCTOR || scope != Scope.EXPLICIT;
        }
    }

    private static final Map<String, TypeInfo> TYPES = create();

    private static Map<String, TypeInfo> create() {
        Map<String, TypeInfo> out = new LinkedHashMap<>();
        // Core ADTs. Payload-dependent does not mean Copy: affine payloads
        // make their carrier affine, and only the active tag is live.
        add(out, "Option", "core/option", Scope.CORE, Kind.TYPE, 1, 1,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.ACTIVE_PAYLOAD_ONCE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.JAVA_INTRINSIC);
        add(out, "Some", "core/option", Scope.CORE, Kind.CONSTRUCTOR, 1, 1,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.ACTIVE_PAYLOAD_ONCE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.JAVA_INTRINSIC);
        add(out, "None", "core/option", Scope.CORE, Kind.CONSTRUCTOR, 0, 0,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.NONE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.JAVA_INTRINSIC);
        add(out, "Result", "core/result", Scope.CORE, Kind.TYPE, 2, 2,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.ACTIVE_PAYLOAD_ONCE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.JAVA_INTRINSIC);
        add(out, "Ok", "core/result", Scope.CORE, Kind.CONSTRUCTOR, 1, 1,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.ACTIVE_PAYLOAD_ONCE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.JAVA_INTRINSIC);
        add(out, "Err", "core/result", Scope.CORE, Kind.CONSTRUCTOR, 1, 1,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.ACTIVE_PAYLOAD_ONCE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.JAVA_INTRINSIC);

        add(out, "Error", "core/error", Scope.CORE, Kind.TYPE, 0, 0,
                Ownership.OWNED, Allocation.NONE, DropPolicy.OWNED_STORAGE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "Array", "core/array", Scope.CORE, Kind.TYPE, 1, 2,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.ACTIVE_PAYLOAD_ONCE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.JAVA_INTRINSIC);
        add(out, "FixedArray", "core/array", Scope.CORE, Kind.TYPE, 2, 2,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.ACTIVE_PAYLOAD_ONCE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "Slice", "core/slice", Scope.CORE, Kind.TYPE, 1, 1,
                Ownership.BORROWED_VIEW, Allocation.BORROWED_VIEW, DropPolicy.NONE,
                SendPolicy.NEVER_BORROWED, LoweringStatus.CONTRACT_ONLY);
        add(out, "Tuple", "core/tuple", Scope.CORE, Kind.TYPE, 0, 32,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.ACTIVE_PAYLOAD_ONCE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "Pair", "core/tuple", Scope.CORE, Kind.TYPE, 2, 2,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.ACTIVE_PAYLOAD_ONCE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "Iterator", "core/iter", Scope.CORE, Kind.TRAIT, 1, 1,
                Ownership.TRAIT, Allocation.NONE, DropPolicy.NOT_A_VALUE,
                SendPolicy.NOT_APPLICABLE, LoweringStatus.CONTRACT_ONLY);
        add(out, "Range", "core/range", Scope.CORE, Kind.TYPE, 1, 1,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.NONE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "Eq", "core/cmp", Scope.CORE, Kind.TRAIT, 0, 0,
                Ownership.TRAIT, Allocation.NONE, DropPolicy.NOT_A_VALUE,
                SendPolicy.NOT_APPLICABLE, LoweringStatus.CONTRACT_ONLY);
        add(out, "Ord", "core/cmp", Scope.CORE, Kind.TRAIT, 0, 0,
                Ownership.TRAIT, Allocation.NONE, DropPolicy.NOT_A_VALUE,
                SendPolicy.NOT_APPLICABLE, LoweringStatus.CONTRACT_ONLY);
        add(out, "Ordering", "core/cmp", Scope.CORE, Kind.TYPE, 0, 0,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.NONE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "From", "core/convert", Scope.CORE, Kind.TRAIT, 1, 1,
                Ownership.TRAIT, Allocation.NONE, DropPolicy.NOT_A_VALUE,
                SendPolicy.NOT_APPLICABLE, LoweringStatus.CONTRACT_ONLY);
        add(out, "Into", "core/convert", Scope.CORE, Kind.TRAIT, 1, 1,
                Ownership.TRAIT, Allocation.NONE, DropPolicy.NOT_A_VALUE,
                SendPolicy.NOT_APPLICABLE, LoweringStatus.CONTRACT_ONLY);
        add(out, "TryFrom", "core/convert", Scope.CORE, Kind.TRAIT, 1, 1,
                Ownership.TRAIT, Allocation.NONE, DropPolicy.NOT_A_VALUE,
                SendPolicy.NOT_APPLICABLE, LoweringStatus.CONTRACT_ONLY);
        add(out, "TryInto", "core/convert", Scope.CORE, Kind.TRAIT, 1, 1,
                Ownership.TRAIT, Allocation.NONE, DropPolicy.NOT_A_VALUE,
                SendPolicy.NOT_APPLICABLE, LoweringStatus.CONTRACT_ONLY);
        add(out, "Display", "core/fmt", Scope.CORE, Kind.TRAIT, 0, 0,
                Ownership.TRAIT, Allocation.NONE, DropPolicy.NOT_A_VALUE,
                SendPolicy.NOT_APPLICABLE, LoweringStatus.CONTRACT_ONLY);
        add(out, "Debug", "core/fmt", Scope.CORE, Kind.TRAIT, 0, 0,
                Ownership.TRAIT, Allocation.NONE, DropPolicy.NOT_A_VALUE,
                SendPolicy.NOT_APPLICABLE, LoweringStatus.CONTRACT_ONLY);
        add(out, "Sendable", "core/marker", Scope.CORE, Kind.TRAIT, 0, 0,
                Ownership.TRAIT, Allocation.NONE, DropPolicy.NOT_A_VALUE,
                SendPolicy.NOT_APPLICABLE, LoweringStatus.CONTRACT_ONLY);

        // Allocation-enabled convenience prelude. Visibility does not install
        // an allocator; compilation must separately admit the alloc profile.
        add(out, "String", "alloc/string", Scope.ALLOC, Kind.TYPE, 0, 0,
                Ownership.OWNED, Allocation.ALLOCATOR_REQUIRED, DropPolicy.OWNED_STORAGE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.JAVA_LEGACY_SCALAR);
        add(out, "List", "alloc/list", Scope.ALLOC, Kind.TYPE, 1, 1,
                Ownership.OWNED, Allocation.ALLOCATOR_REQUIRED, DropPolicy.OWNED_STORAGE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "Vec", "alloc/vec", Scope.ALLOC, Kind.TYPE, 1, 1,
                Ownership.OWNED, Allocation.ALLOCATOR_REQUIRED, DropPolicy.OWNED_STORAGE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "Map", "alloc/map", Scope.ALLOC, Kind.TYPE, 2, 2,
                Ownership.OWNED, Allocation.ALLOCATOR_REQUIRED, DropPolicy.OWNED_STORAGE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "Set", "alloc/set", Scope.ALLOC, Kind.TYPE, 1, 1,
                Ownership.OWNED, Allocation.ALLOCATOR_REQUIRED, DropPolicy.OWNED_STORAGE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "Box", "alloc/box", Scope.ALLOC, Kind.TYPE, 1, 1,
                Ownership.OWNED, Allocation.ALLOCATOR_REQUIRED, DropPolicy.OWNED_STORAGE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "HashMap", "alloc/hash_map", Scope.EXPLICIT, Kind.TYPE, 2, 2,
                Ownership.OWNED, Allocation.ALLOCATOR_REQUIRED, DropPolicy.OWNED_STORAGE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "HashSet", "alloc/hash_set", Scope.EXPLICIT, Kind.TYPE, 1, 1,
                Ownership.OWNED, Allocation.ALLOCATOR_REQUIRED, DropPolicy.OWNED_STORAGE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        add(out, "Entry", "alloc/map", Scope.EXPLICIT, Kind.TYPE, 2, 2,
                Ownership.PAYLOAD_DEPENDENT, Allocation.NONE, DropPolicy.ACTIVE_PAYLOAD_ONCE,
                SendPolicy.PAYLOAD_DEPENDENT, LoweringStatus.CONTRACT_ONLY);
        return Collections.unmodifiableMap(out);
    }

    private static void add(Map<String, TypeInfo> out, String name, String module,
                            Scope scope, Kind kind, int minArity, int maxArity,
                            Ownership ownership, Allocation allocation, DropPolicy drop,
                            SendPolicy send, LoweringStatus javaStatus) {
        TypeInfo info = new TypeInfo(name, module + "/" + name, scope, kind,
                minArity, maxArity, ownership, allocation, drop, send, javaStatus);
        if (out.putIfAbsent(name, info) != null) {
            throw new IllegalStateException("duplicate core registry identity: " + name);
        }
    }

    public static Map<String, TypeInfo> all() { return TYPES; }
    public static Optional<TypeInfo> lookup(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(TYPES.get(name));
    }
    public static boolean protectedIdentity(String name) {
        TypeInfo info = TYPES.get(name);
        return info != null && info.compilerOwnedIdentity()
                || "FixedList".equals(name) || "Vector".equals(name)
                || "ReadLoan".equals(name) || "WriteLoan".equals(name);
    }
    public static boolean coreConstructor(String name) {
        TypeInfo info = TYPES.get(name);
        return info != null && info.scope() == Scope.CORE && info.kind() == Kind.CONSTRUCTOR;
    }
    public static boolean coreSumCarrier(String name, int arity) {
        TypeInfo info = TYPES.get(name);
        return info != null && info.scope() == Scope.CORE
                && info.kind() == Kind.TYPE && info.ownership() == Ownership.PAYLOAD_DEPENDENT
                && info.acceptsArity(arity) && ("Option".equals(name) || "Result".equals(name));
    }
}
