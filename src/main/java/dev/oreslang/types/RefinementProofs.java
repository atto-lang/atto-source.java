package dev.oreslang.types;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Sound finite signed-i64 interval proof for typed call/return contracts.
 * All arithmetic uses unbounded integers before proving i64 representability.
 *
 * <p>This is a static checker primitive, not a compiler-recognized source
 * refinement syntax or a Z3 completeness claim. A failed implication does
 * not authorize a runtime assertion or an unchecked cast.
 */
public final class RefinementProofs {
    private RefinementProofs() { }

    public record Interval(long minimum, long maximum) {
        public Interval {
            if (minimum > maximum) {
                throw new IllegalArgumentException("empty refinement interval");
            }
        }
        public static Interval exactly(long n) { return new Interval(n, n); }
        public static Interval anyI64() { return new Interval(Long.MIN_VALUE, Long.MAX_VALUE); }
        public boolean entails(Interval expected) {
            Objects.requireNonNull(expected);
            return minimum >= expected.minimum && maximum <= expected.maximum;
        }
        public Interval plus(Interval b) {
            Objects.requireNonNull(b);
            return checked(BigInteger.valueOf(minimum).add(BigInteger.valueOf(b.minimum)),
                    BigInteger.valueOf(maximum).add(BigInteger.valueOf(b.maximum)));
        }
        public Interval minus(Interval b) {
            Objects.requireNonNull(b);
            return checked(BigInteger.valueOf(minimum).subtract(BigInteger.valueOf(b.maximum)),
                    BigInteger.valueOf(maximum).subtract(BigInteger.valueOf(b.minimum)));
        }
        public Interval times(Interval b) {
            Objects.requireNonNull(b);
            BigInteger[] ends = new BigInteger[] {
                    BigInteger.valueOf(minimum).multiply(BigInteger.valueOf(b.minimum)),
                    BigInteger.valueOf(minimum).multiply(BigInteger.valueOf(b.maximum)),
                    BigInteger.valueOf(maximum).multiply(BigInteger.valueOf(b.minimum)),
                    BigInteger.valueOf(maximum).multiply(BigInteger.valueOf(b.maximum))
            };
            BigInteger lo = ends[0], hi = ends[0];
            for (BigInteger n : ends) {
                lo = lo.min(n);
                hi = hi.max(n);
            }
            return checked(lo, hi);
        }
    }

    public record FunctionContract(String symbol, List<Interval> preconditions,
                                   Interval postcondition, Set<EffectContracts.Effect> allowedEffects) {
        public FunctionContract {
            Objects.requireNonNull(symbol);
            if (symbol.isBlank()) throw new IllegalArgumentException("missing function identity");
            preconditions = List.copyOf(preconditions);
            Objects.requireNonNull(postcondition);
            allowedEffects = Set.copyOf(allowedEffects);
            if (allowedEffects.contains(EffectContracts.Effect.FFI_UNKNOWN)
                    || allowedEffects.contains(EffectContracts.Effect.UNKNOWN_CALL)) {
                throw new IllegalArgumentException("unknown effects cannot be certified in a function contract");
            }
        }
    }

    public static void requireCall(FunctionContract contract, List<Interval> arguments,
                                   Set<EffectContracts.Effect> actualEffects) {
        Objects.requireNonNull(contract);
        Objects.requireNonNull(arguments);
        Objects.requireNonNull(actualEffects);
        if (arguments.size() != contract.preconditions().size()) {
            throw new IllegalArgumentException("refined function call arity mismatch");
        }
        for (int i = 0; i < arguments.size(); i++) {
            Interval actual = Objects.requireNonNull(arguments.get(i));
            if (!actual.entails(contract.preconditions().get(i))) {
                throw new IllegalArgumentException("unproved precondition argument " + i
                        + " in " + contract.symbol());
            }
        }
        if (!contract.allowedEffects().containsAll(actualEffects)) {
            throw new IllegalArgumentException("unproved effect in " + contract.symbol());
        }
    }

    /**
     * The compiler must supply every reachable normal return arm. Requiring
     * the list nonempty prevents vacuous proofs for a missing return.
     * This helper cannot itself certify CFG completeness.
     */
    public static void requireReturns(FunctionContract contract, List<Interval> allNormalReturns) {
        Objects.requireNonNull(contract);
        Objects.requireNonNull(allNormalReturns);
        if (allNormalReturns.isEmpty()) {
            throw new IllegalArgumentException("missing proven normal return paths");
        }
        for (Interval result : allNormalReturns) {
            if (!Objects.requireNonNull(result).entails(contract.postcondition())) {
                throw new IllegalArgumentException("unproved postcondition in " + contract.symbol());
            }
        }
    }

    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private static Interval checked(BigInteger lower, BigInteger upper) {
        if (lower.compareTo(MIN) < 0 || upper.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("static i64 overflow requires checked arithmetic handling");
        }
        return new Interval(lower.longValueExact(), upper.longValueExact());
    }
}
