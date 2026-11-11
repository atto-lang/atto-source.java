package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, per-domain dependency graph for synchronous singleton initialization.
 * A dependency records a pending nested or cross-thread initializer lookup;
 * cycles fail the requesting receipt rather than deadlocking its caller.
 * Package-private: never expose keys, thread identities or capabilities to guests.
 */
final class SingletonDependencyGraph<K> {
    private static final int MAX_DEPENDENCIES_PER_INITIALIZER = 64;
    private final Map<K, Set<K>> dependencies = new HashMap<>();
    private final ThreadLocal<ArrayDeque<K>> initializing =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** Called only after the caller has passed declaration schema/quota checks. */
    synchronized void dependOn(K target) {
        Objects.requireNonNull(target, "singleton dependency key");
        K current = initializing.get().peek();
        if (current == null) return;
        if (current.equals(target) || reaches(target, current)) {
            throw new IllegalStateException("singleton initializer cycle");
        }
        Set<K> children = dependencies.computeIfAbsent(current, ignored -> new HashSet<>());
        if (!children.contains(target)
                && children.size() >= MAX_DEPENDENCIES_PER_INITIALIZER) {
            throw new IllegalStateException("singleton initializer dependency quota exceeded");
        }
        children.add(target);
    }

    /** Only the thread that actually runs initialize.get() enters this stack. */
    void begin(K key) {
        Objects.requireNonNull(key, "singleton initializer key");
        initializing.get().push(key);
    }

    /** Drop both outgoing and incoming edges as soon as this future is terminal. */
    synchronized void finish(K key) {
        ArrayDeque<K> stack = initializing.get();
        if (stack.isEmpty() || !Objects.equals(stack.peek(), key)) {
            throw new IllegalStateException("singleton initializer stack corrupted");
        }
        stack.pop();
        if (stack.isEmpty()) initializing.remove();
        dependencies.remove(key);
        dependencies.values().forEach(children -> children.remove(key));
        dependencies.values().removeIf(Set::isEmpty);
    }

    private boolean reaches(K start, K goal) {
        ArrayDeque<K> visit = new ArrayDeque<>();
        Set<K> seen = new HashSet<>();
        visit.add(start);
        while (!visit.isEmpty()) {
            K next = visit.removeLast();
            if (next.equals(goal)) return true;
            if (!seen.add(next)) continue;
            for (K child : dependencies.getOrDefault(next, Set.of())) {
                if (!seen.contains(child)) visit.addLast(child);
            }
        }
        return false;
    }
}
