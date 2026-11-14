package dev.oreslang.types;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Backend-independent semantic rules for ownership/concurrency auto traits.
 *
 * <p>This is deliberately independent of parser syntax. Frontends elaborate
 * source types into {@link TypeModel}; every backend must then reach the same
 * Send/Sync/Share answer. Share is Oreslang-specific: it proves that backing
 * storage may be aliased across actor/allocation domains. A value may be Send
 * without being Share (for example, unique transferable ownership).
 */
public final class AutoTraitRules {
    private AutoTraitRules() { }

    public enum AutoTrait { SEND, SYNC, SHARE }

    public enum Kind {
        SCALAR,
        NOMINAL,
        BOX,
        ARC,
        WEAK,
        RC,
        REFCELL,
        MUTEX,
        RWLOCK,
        DYN
    }

    public record Capabilities(boolean send, boolean sync, boolean share) {
        public Capabilities {
            // Sharing an allocation across actor domains must never outlive
            // the Send + Sync proofs required for cross-lane safe access.
            // Normalize even independently constructed structural/dyn caps,
            // not only built-in wrapper inference.
            share = share && send && sync;
        }

        public boolean has(AutoTrait trait) {
            return switch (trait) {
                case SEND -> send;
                case SYNC -> sync;
                case SHARE -> share;
            };
        }

        private Capabilities without(Set<AutoTrait> negative) {
            return new Capabilities(
                    send && !negative.contains(AutoTrait.SEND),
                    sync && !negative.contains(AutoTrait.SYNC),
                    share && !negative.contains(AutoTrait.SHARE));
        }
    }

    /**
     * Canonical type shape after frontend elaboration.
     *
     * @param kind semantic wrapper/category
     * @param parameters fields or wrapped type arguments
     * @param dynBounds explicit auto-trait bounds on a dyn object
     * @param shareableDomain whether this value's backing storage has explicit
     *                        cross-actor sharing authority
     * @param negative explicit compiler-known negative auto traits
     */
    public record TypeModel(
            Kind kind,
            List<TypeModel> parameters,
            Set<AutoTrait> dynBounds,
            boolean shareableDomain,
            Set<AutoTrait> negative) {
        public TypeModel {
            Objects.requireNonNull(kind, "kind");
            parameters = List.copyOf(parameters);
            dynBounds = Set.copyOf(dynBounds);
            negative = Set.copyOf(negative);
        }

        public static TypeModel scalar() {
            return new TypeModel(Kind.SCALAR, List.of(), Set.of(), true, Set.of());
        }

        public static TypeModel nominal(boolean shareableDomain, TypeModel... fields) {
            return new TypeModel(
                    Kind.NOMINAL, List.of(fields), Set.of(), shareableDomain, Set.of());
        }

        public static TypeModel wrapped(
                Kind kind, TypeModel value, boolean shareableDomain) {
            return new TypeModel(
                    kind, List.of(value), Set.of(), shareableDomain, Set.of());
        }

        public static TypeModel dyn(Set<AutoTrait> bounds, boolean shareableDomain) {
            return new TypeModel(
                    Kind.DYN, List.of(), bounds, shareableDomain, Set.of());
        }

        public TypeModel denying(AutoTrait... traits) {
            return new TypeModel(
                    kind, parameters, dynBounds, shareableDomain, Set.of(traits));
        }
    }

    private static final int MAX_TYPE_DEPTH = 128;
    private static final int MAX_TRAIT_VISITS = 4096;
    private static final Capabilities NO_PROOF =
            new Capabilities(false, false, false);

    private static final class ProofBudget {
        private int visitsLeft = MAX_TRAIT_VISITS;
    }

    public static Capabilities infer(TypeModel type) {
        Objects.requireNonNull(type, "type");
        return inferBounded(type, 0, new ProofBudget());
    }

    private static Capabilities inferBounded(
            TypeModel type, int depth, ProofBudget budget) {
        // A deep or adversarial nested type must not overflow the Java stack
        // or reset the budget at each wrapper/nominal field. Deny traits.
        if (depth >= MAX_TYPE_DEPTH || budget.visitsLeft-- <= 0) {
            return NO_PROOF;
        }
        Capabilities base = switch (type.kind()) {
            case SCALAR -> {
                // A scalar has no nested storage. Otherwise a fabricated
                // scalar carrying Rc/borrowed metadata could bypass the
                // transitive Send/Sync/Share check at a Task/actor boundary.
                yield type.parameters().isEmpty() && type.dynBounds().isEmpty()
                        ? new Capabilities(true, true, true)
                        : new Capabilities(false, false, false);
            }
            case NOMINAL -> {
                boolean send = all(type.parameters(), AutoTrait.SEND, depth + 1, budget);
                boolean sync = all(type.parameters(), AutoTrait.SYNC, depth + 1, budget);
                boolean share = type.shareableDomain()
                        && all(type.parameters(), AutoTrait.SHARE, depth + 1, budget);
                yield new Capabilities(send, sync, share);
            }
            case BOX -> {
                TypeModel value = exactlyOne(type);
                Capabilities nested = inferBounded(value, depth + 1, budget);
                // Box is unique ownership. It may move between execution lanes
                // when T is Send, but it is not aliasable actor-shared storage.
                yield new Capabilities(nested.send(), nested.sync(), false);
            }
            case ARC, WEAK -> {
                TypeModel value = exactlyOne(type);
                Capabilities nested = inferBounded(value, depth + 1, budget);
                boolean concurrent = type.shareableDomain()
                        && nested.send() && nested.sync();
                yield new Capabilities(
                        concurrent,
                        concurrent,
                        concurrent && nested.share());
            }
            case RC -> {
                exactlyOne(type);
                yield new Capabilities(false, false, false);
            }
            case REFCELL -> {
                TypeModel value = exactlyOne(type);
                Capabilities nested = inferBounded(value, depth + 1, budget);
                yield new Capabilities(nested.send(), false, false);
            }
            case MUTEX -> {
                TypeModel value = exactlyOne(type);
                Capabilities nested = inferBounded(value, depth + 1, budget);
                // Like Rust's synchronization containers, cross-lane shared
                // access only requires moving T into/out of the protected slot.
                yield new Capabilities(nested.send(), nested.send(), false);
            }
            case RWLOCK -> {
                Capabilities nested = inferBounded(exactlyOne(type), depth + 1, budget);
                // Concurrent readers expose shared T, so T must also be Sync.
                yield new Capabilities(nested.send(), nested.send() && nested.sync(), false);
            }
            case DYN -> type.parameters().isEmpty()
                    ? new Capabilities(
                            type.dynBounds().contains(AutoTrait.SEND),
                            type.dynBounds().contains(AutoTrait.SYNC),
                            type.shareableDomain()
                                    && type.dynBounds().contains(AutoTrait.SHARE))
                    : new Capabilities(false, false, false);
        };
        return base.without(type.negative());
    }

    private static TypeModel exactlyOne(TypeModel type) {
        if (type.parameters().size() != 1) {
            throw new IllegalArgumentException(
                    type.kind() + " requires exactly one type parameter");
        }
        return type.parameters().get(0);
    }

    private static boolean all(
            List<TypeModel> values, AutoTrait trait,
            int depth, ProofBudget budget) {
        for (TypeModel value : values) {
            if (!inferBounded(value, depth, budget).has(trait)) return false;
        }
        return true;
    }
}
