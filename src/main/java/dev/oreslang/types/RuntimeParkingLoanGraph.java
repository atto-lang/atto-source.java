package dev.oreslang.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;

/** Compiler-local deferred loan checks on already resolved declaration identities.
 * Only known parking paths are reported by this staging analysis. No result is
 * an executable nonparking proof, particularly for callbacks or virtual calls.
 */
final class RuntimeParkingLoanGraph {
    private final IdentityHashMap<Object, Set<Object>> callers = new IdentityHashMap<>();
    private final Set<Object> parking = Collections.newSetFromMap(new IdentityHashMap<>());
    private record Site(Object target, String diagnostic) {}
    private final List<Site> blocked = new ArrayList<>();

    void markParking(Object owner) {
        if (owner != null) parking.add(owner);
    }

    void edge(Object caller, Object target, String loanDiagnostic) {
        Objects.requireNonNull(target, "resolved target");
        if (caller != null) callers.computeIfAbsent(target,
                ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(caller);
        if (loanDiagnostic != null) blocked.add(new Site(target, loanDiagnostic));
    }

    String firstViolation() {
        Set<Object> closure = Collections.newSetFromMap(new IdentityHashMap<>());
        closure.addAll(parking);
        ArrayDeque<Object> pending = new ArrayDeque<>(parking);
        while (!pending.isEmpty()) {
            Object next = pending.removeFirst();
            Set<Object> parents = callers.get(next);
            if (parents == null) continue;
            for (Object parent : parents) if (closure.add(parent)) pending.addLast(parent);
        }
        for (Site site : blocked) if (closure.contains(site.target())) return site.diagnostic();
        return null;
    }
}
