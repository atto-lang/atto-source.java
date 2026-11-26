package dev.oreslang.types;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Bounded successful-plan memo, owned by one immutable code image.
 * Keys compare by identity. Callers supply immutable keys and plans, and must
 * never share a cache across resolver generations or copy modes.
 */
public final class CopyPlanCache<P> {
    private final IdentityHashMap<Object, P> entries = new IdentityHashMap<>();
    private final int maxEntries;
    private final int maxWeight;
    private int weight;

    public CopyPlanCache(int maxEntries, int maxWeight) {
        if (maxEntries < 0 || maxWeight < 0) throw new IllegalArgumentException("negative cache limit");
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
    }

    public P getOrCompute(Object key, Supplier<P> derive, ToIntFunction<P> weigh) {
        Objects.requireNonNull(key);
        synchronized (entries) {
            P prior = entries.get(key);
            if (prior != null) return prior;
        }
        // Do not hold a shared lock across expensive compiler derivation.
        // Concurrent misses can derive twice; only one immutable plan is kept.
        P plan = Objects.requireNonNull(derive.get());
        int cost = weigh.applyAsInt(plan);
        if (cost <= 0) throw new IllegalArgumentException("invalid plan weight");
        synchronized (entries) {
            P prior = entries.get(key);
            if (prior != null) return prior;
            if (entries.size() < maxEntries && cost <= maxWeight - weight) {
                entries.put(key, plan);
                weight += cost;
            }
            return plan;
        }
    }

    public int size() {
        synchronized (entries) { return entries.size(); }
    }

    public int weight() {
        synchronized (entries) { return weight; }
    }
}
