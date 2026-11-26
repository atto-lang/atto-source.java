package dev.oreslang.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler-side lexical retained-field ledger. Root and region keys are exact
 * provenance identities supplied by the ownership pass, NOT source names or
 * equality-comparable values. A region denotes a proved alias equivalence
 * class, conservatively including transitive overlapping reachable storage.
 *
 * This component never infers disjointness or authorizes runtime copying.
 * Callers must prove fresh roots, region provenance, all alias/escape paths
 * and control-flow integration before admitting a loan-backed copy.
 * Sequence-root indexed families are deliberately outside this first slice.
 */
public final class ShallowCopyLoanLedger {
    private record Root(Map<String, Object> fields, boolean dependent) { }
    private record Lease(Object copy, Set<Object> regions) { }
    private final IdentityHashMap<Object, Root> roots = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Integer> readers = new IdentityHashMap<>();
    private final ArrayDeque<Scope> scopes = new ArrayDeque<>();

    public final class Scope implements AutoCloseable {
        private final List<Lease> leases = new ArrayList<>();
        private boolean closed;
        private Scope() { }
        @Override public void close() {
            if (closed) return;
            if (scopes.peek() != this) throw fail("E_COPY_LOAN", "lexical scope closed out of order");
            for (int i = leases.size() - 1; i >= 0; --i) {
                Lease lease = leases.get(i);
                roots.remove(lease.copy());
                for (Object region : lease.regions()) {
                    int count = readers.getOrDefault(region, 0);
                    if (count <= 0) throw new IllegalStateException("missing shallow-copy read loan");
                    if (count == 1) readers.remove(region);
                    else readers.put(region, count - 1);
                }
            }
            scopes.pop();
            closed = true;
        }
    }

    public Scope openScope() {
        Scope scope = new Scope();
        scopes.push(scope);
        return scope;
    }

    /** Register precisely the retained fields, with independently proved region keys. */
    public void registerRoot(Object root, DerivedCopyPlanV1.ShallowLoanLayout layout,
                             Map<String, Object> provedRegions) {
        if (root == null || roots.containsKey(root) || provedRegions == null
                || !(layout.root() instanceof DerivedCopyPlanV1.Instance)) {
            throw fail("E_COPY_LOAN", "expected fresh named class root and concrete retained fields");
        }
        Set<String> expected = new java.util.LinkedHashSet<>();
        for (var edge : layout.retained()) {
            if (!(edge instanceof DerivedCopyPlanV1.RetainedField field)
                    || !expected.add(field.field())) {
                throw fail("E_COPY_LOAN", "unsupported or duplicate retained edge");
            }
        }
        if (!expected.equals(provedRegions.keySet())
                || provedRegions.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw fail("E_COPY_LOAN", "incomplete retained-field provenance");
        }
        roots.put(root, new Root(new LinkedHashMap<>(provedRegions), false));
    }

    /** Mint a dependent root and a scope-owned lease in one checked operation. */
    public void copyInto(Scope scope, Object source, Object copy) {
        if (scope == null || scope.closed || scopes.peek() != scope || copy == null
                || roots.containsKey(copy)) {
            throw fail("E_COPY_LOAN", "copy requires a fresh root and current lexical scope");
        }
        Root original = root(source);
        Set<Object> regions = Collections.newSetFromMap(new IdentityHashMap<>());
        regions.addAll(original.fields().values());
        for (Object region : regions) {
            if (readers.getOrDefault(region, 0) == Integer.MAX_VALUE) {
                throw fail("E_COPY_LOAN", "read-loan count exhausted");
            }
        }
        Root dependent = new Root(new LinkedHashMap<>(original.fields()), true);
        Lease lease = new Lease(copy, regions);
        for (Object region : regions) readers.merge(region, 1, Integer::sum);
        roots.put(copy, dependent);
        scope.leases.add(lease);
    }

    /** Descendant writes use the exact retained-field region, not the root slot. */
    public void requireDescendantWritable(Object root, String field) {
        Object region = region(root, field);
        if (readers.containsKey(region)) {
            throw fail("E_COPY_READONLY", "retained field has a live read loan: " + field);
        }
    }

    /**
     * Replacing an entire slot is distinct from writing its former referent.
     * Only a fresh, disjoint region is accepted; existing leases are retained
     * conservatively until their lexical scope ends.
     */
    public void replaceWithFresh(Object root, String field, Object freshRegion) {
        region(root, field);
        if (freshRegion == null || readers.containsKey(freshRegion)) {
            throw fail("E_COPY_LOAN", "replacement must have fresh region provenance");
        }
        for (Root known : roots.values()) {
            if (known.fields().values().stream().anyMatch(region -> region == freshRegion)) {
                throw fail("E_COPY_LOAN", "replacement aliases a known region");
            }
        }
        root(root).fields().put(field, freshRegion);
    }

    /** Copy-dependent values and owners with retained read loans cannot escape. */
    public void requireTransferable(Object identity) {
        Root root = root(identity);
        if (root.dependent() || root.fields().values().stream().anyMatch(readers::containsKey)) {
            throw fail("E_COPY_LOAN", "owner or dependent copy has a live lexical loan");
        }
    }

    private Root root(Object identity) {
        Root root = roots.get(identity);
        if (root == null) throw fail("E_COPY_LOAN", "unknown or expired root provenance");
        return root;
    }

    private Object region(Object root, String field) {
        Object region = root(root).fields().get(field);
        if (region == null) throw fail("E_COPY_LOAN", "unknown retained-field provenance: " + field);
        return region;
    }

    private static IllegalArgumentException fail(String code, String detail) {
        return new IllegalArgumentException(code + ": " + detail);
    }
}
