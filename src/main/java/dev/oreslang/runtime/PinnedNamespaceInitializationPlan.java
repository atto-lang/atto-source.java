package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed entry-reachable initialization plan for an already verified,
 * closed namespace source inventory. Linking and compile-time validation may
 * inspect every supplied unit, but only units transitively required by the
 * entry may run initialization code.
 *
 * This class does not authenticate package ownership, source bytes or pins.
 */
public final class PinnedNamespaceInitializationPlan {
    private PinnedNamespaceInitializationPlan() { }

    public static List<List<String>> reachableGroups(
            Map<String, Set<String>> dependencies,
            List<List<String>> dependencyFirstGroups,
            String entry) {
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(dependencyFirstGroups, "initialization groups");
        Objects.requireNonNull(entry, "entry");
        if (!dependencies.containsKey(entry))
            throw new IllegalArgumentException("entry absent from verified initialization inventory");

        Map<String, Integer> groupOf = new HashMap<>();
        for (int i = 0; i < dependencyFirstGroups.size(); i++) {
            List<String> group = Objects.requireNonNull(
                    dependencyFirstGroups.get(i), "initialization group");
            if (group.isEmpty())
                throw new IllegalArgumentException("empty verified initialization group");
            for (String id : group) {
                if (id == null || !dependencies.containsKey(id))
                    throw new IllegalArgumentException("initialization group outside verified inventory");
                if (groupOf.putIfAbsent(id, i) != null)
                    throw new IllegalArgumentException("duplicate initialization identity");
            }
        }
        if (groupOf.size() != dependencies.size())
            throw new IllegalArgumentException("initialization plan omits verified source units");

        // Verify the planner's complete inventory and dependency-first
        // ordering before beginning guest initialization or executing hooks.
        for (Map.Entry<String, Set<String>> unit : dependencies.entrySet()) {
            Set<String> required = Objects.requireNonNull(
                    unit.getValue(), "source dependencies");
            for (String dependency : required) {
                Integer order = groupOf.get(dependency);
                if (order == null)
                    throw new IllegalArgumentException("dependency outside closed pinned inventory");
                if (order > groupOf.get(unit.getKey()))
                    throw new IllegalArgumentException("initialization dependency order violated");
            }
        }

        Set<String> reachable = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.push(entry);
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (!reachable.add(current)) continue;
            for (String dependency : dependencies.get(current))
                if (!reachable.contains(dependency)) pending.push(dependency);
        }

        List<List<String>> selected = new ArrayList<>();
        for (List<String> group : dependencyFirstGroups) {
            List<String> kept = new ArrayList<>();
            for (String id : group)
                if (reachable.contains(id)) kept.add(id);
            if (!kept.isEmpty()) selected.add(List.copyOf(kept));
        }
        return List.copyOf(selected);
    }
}
