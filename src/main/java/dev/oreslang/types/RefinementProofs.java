package dev.oreslang.types;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Sound finite signed-i64 interval proof for typed call/return contracts.
 * All arithmetic uses unbounded integers before proving i64 representability.
 *
 * <p>The compiler may ask for a proof of representability or for an interval
 * covering normal results of an operation that retains its runtime checks.
 * A failed implication does not authorize a cast or establish a property.
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
            return multiply(b, false);
        }
        public boolean nonnegative() { return minimum >= 0; }
        public boolean positive() { return minimum > 0; }
        public boolean nonpositive() { return maximum <= 0; }
        public boolean atMost(Interval b) { return maximum <= b.minimum; }

        /** Bound the normal results of an operation with an enforced carrier check. */
        public Interval onNormalResultWithin(Interval carrier) {
            long low = Math.max(minimum, carrier.minimum);
            long high = Math.min(maximum, carrier.maximum);
            return low <= high ? new Interval(low, high) : carrier;
        }

        public Interval constrain(String operator, long bound) {
            long lower = minimum;
            long upper = maximum;
            switch (operator) {
                case ">" -> {
                    if (bound == Long.MAX_VALUE) throw emptyConstraint();
                    lower = Math.max(lower, bound + 1);
                }
                case ">=" -> lower = Math.max(lower, bound);
                case "<" -> {
                    if (bound == Long.MIN_VALUE) throw emptyConstraint();
                    upper = Math.min(upper, bound - 1);
                }
                case "<=" -> upper = Math.min(upper, bound);
                case "eq" -> {
                    lower = Math.max(lower, bound);
                    upper = Math.min(upper, bound);
                }
                default -> throw new IllegalArgumentException("E_REFINEMENT_PREDICATE: expected an interval comparison");
            }
            if (lower > upper) throw emptyConstraint();
            return new Interval(lower, upper);
        }

        public Interval plusOnNormalResult(Interval b) {
            return clip(BigInteger.valueOf(minimum).add(BigInteger.valueOf(b.minimum)),
                    BigInteger.valueOf(maximum).add(BigInteger.valueOf(b.maximum)));
        }

        public Interval minusOnNormalResult(Interval b) {
            return clip(BigInteger.valueOf(minimum).subtract(BigInteger.valueOf(b.maximum)),
                    BigInteger.valueOf(maximum).subtract(BigInteger.valueOf(b.minimum)));
        }

        public Interval timesOnNormalResult(Interval b) {
            return multiply(b, true);
        }

        public Interval divideOnNormalResult(Interval b) {
            if ((b.minimum <= 0 && b.maximum >= 0)
                    || (minimum == Long.MIN_VALUE && b.minimum <= -1 && b.maximum >= -1)) return anyI64();
            long a = minimum / b.minimum;
            long c = minimum / b.maximum;
            long d = maximum / b.minimum;
            long e = maximum / b.maximum;
            return new Interval(Math.min(Math.min(a, c), Math.min(d, e)),
                    Math.max(Math.max(a, c), Math.max(d, e)));
        }

        private Interval multiply(Interval b, boolean normalResultsOnly) {
            Objects.requireNonNull(b);
            BigInteger[] ends = {
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
            return normalResultsOnly ? clip(lo, hi) : checked(lo, hi);
        }

        private static Interval clip(BigInteger lower, BigInteger upper) {
            BigInteger first = lower.max(MIN);
            BigInteger last = upper.min(MAX);
            return first.compareTo(last) <= 0 ? new Interval(first.longValueExact(), last.longValueExact()) : anyI64();
        }

        private static IllegalArgumentException emptyConstraint() {
            return new IllegalArgumentException("E_REFINEMENT_EMPTY: contradictory interval");
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
