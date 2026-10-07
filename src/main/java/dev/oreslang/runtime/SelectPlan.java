package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.oreslang.runtime.ChannelRuntime.SelectCase;
import dev.oreslang.runtime.ChannelRuntime.SelectPolicy;
import dev.oreslang.runtime.ChannelRuntime.SelectResult;
import dev.oreslang.runtime.ChannelRuntime.SelectSet;

/**
 * Opt-in reusable selection descriptor for stable and dynamically edited case sets.
 *
 * <p>The original static select and {@link SelectSet} paths are unchanged.
 * A plan retains one immutable SelectSet while its cases are stable, avoiding
 * rebuilding the case collection on each selection. Each invocation still
 * creates its own atomic registration and Future in ChannelRuntime; there are
 * deliberately no persistent channel waiters or speculative reservations.</p>
 *
 * <p>Dynamic plans publish copy-on-write case snapshots. A selection already
 * admitted on the old snapshot continues against exactly those old cases, even
 * if the plan is edited or its last external reference is dropped. Editing a
 * plan does not cancel, steal from, or re-arm an in-flight selection. Callers
 * own their returned Futures and may cancel those Futures normally.</p>
 *
 * <p>A plan is a local coordination capability: do not share a mutable plan
 * across actor isolation boundaries. Cross-thread host edits are serialized,
 * and readers see one complete snapshot (never a partially edited case set).</p>
 */
public final class SelectPlan {
    private record State(long version, SelectSet set) { }

    private final boolean mutable;
    private volatile State state;

    private SelectPlan(boolean mutable, Collection<? extends SelectCase> cases) {
        this.mutable = mutable;
        this.state = new State(0, new SelectSet(cases));
    }

    /** Fixed cases: fastest reuse when channel identities and write values stay stable. */
    public static SelectPlan fixed(Collection<? extends SelectCase> cases) {
        return new SelectPlan(false, cases);
    }

    /** Mutable case set: edits copy and atomically publish a new descriptor. */
    public static SelectPlan dynamic(Collection<? extends SelectCase> cases) {
        return new SelectPlan(true, cases);
    }

    public static SelectPlan fixed(SelectCase... cases) {
        return fixed(List.of(cases));
    }

    public static SelectPlan dynamic(SelectCase... cases) {
        return dynamic(List.of(cases));
    }

    /** Map iteration order determines case indices; values are snapshotted. */
    public static SelectPlan dynamicMap(Map<?, ? extends SelectCase> cases) {
        Objects.requireNonNull(cases, "cases");
        return dynamic(new ArrayList<>(cases.values()));
    }

    public boolean isMutable() {
        return mutable;
    }

    /** Increments on successful edits only. The first revision is zero. */
    public long version() {
        return state.version();
    }

    public int size() {
        return state.set().cases().size();
    }

    /** Stable descriptor reference for a single selection invocation. */
    public SelectSet snapshot() {
        return state.set();
    }

    public OresFuture<SelectResult> selectAsync() {
        return selectAsync(SelectPolicy.FAIR);
    }

    public OresFuture<SelectResult> selectAsync(SelectPolicy policy) {
        return snapshot().selectAsync(policy);
    }

    public Optional<SelectResult> trySelect() {
        return trySelect(SelectPolicy.FAIR);
    }

    public Optional<SelectResult> trySelect(SelectPolicy policy) {
        return snapshot().trySelect(policy);
    }

    public synchronized void add(SelectCase selectCase) {
        requireMutable();
        ArrayList<SelectCase> next = currentCases();
        next.add(Objects.requireNonNull(selectCase, "selectCase"));
        publish(next);
    }

    public synchronized void replace(int index, SelectCase selectCase) {
        requireMutable();
        ArrayList<SelectCase> next = currentCases();
        next.set(index, Objects.requireNonNull(selectCase, "selectCase"));
        publish(next);
    }

    public synchronized void remove(int index) {
        requireMutable();
        ArrayList<SelectCase> next = currentCases();
        next.remove(index);
        publish(next);
    }

    public synchronized void reset(Collection<? extends SelectCase> cases) {
        requireMutable();
        publish(Objects.requireNonNull(cases, "cases"));
    }

    private ArrayList<SelectCase> currentCases() {
        return new ArrayList<>(state.set().cases());
    }

    private void publish(Collection<? extends SelectCase> cases) {
        // Validate before publishing. Failed edits never corrupt an earlier snapshot.
        SelectSet next = new SelectSet(cases);
        state = new State(Math.addExact(state.version(), 1), next);
        // Fair rotation is intentionally scoped to each immutable revision.
        // PRIORITY and RANDOM keep their existing SelectSet semantics.
    }

    private void requireMutable() {
        if (!mutable) {
            throw new IllegalStateException("fixed SelectPlan cannot be edited");
        }
    }
}
