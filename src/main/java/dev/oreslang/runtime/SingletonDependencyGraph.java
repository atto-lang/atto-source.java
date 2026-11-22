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
    private static final int MAX_DOMAIN_DEPENDENCIES = 16_384;
    private static final int MAX_INITIALIZER_DEPTH = 64;
    private final Map<K, Set<K>> dependencies = new HashMap<>();
    private final Map<K, Set<K>> dependents = new HashMap<>();
    private int edgeCount;
    // Completed lookups must not allocate/retain a ThreadLocal stack.
    private final ThreadLocal<ArrayDeque<K>> initializing = new ThreadLocal<>();

    /** Called only after the caller has passed declaration schema/quota checks. */
    synchronized void dependOn(K target) {
        Objects.requireNonNull(target, "singleton dependency key");
        ArrayDeque<K> stack = initializing.get();
        if (stack == null || stack.isEmpty()) return;
        K current = stack.peek();
        if (current.equals(target) || reaches(target, current)) {
            throw new IllegalStateException("singleton initializer cycle");
        }
        Set<K> children = dependencies.computeIfAbsent(current, ignored -> new HashSet<>());
        if (!children.contains(target)
                && children.size() >= MAX_DEPENDENCIES_PER_INITIALIZER) {
            throw new IllegalStateException("singleton initializer dependency quota exceeded");
        }
        if (!children.contains(target)) {
            if (edgeCount >= MAX_DOMAIN_DEPENDENCIES) {
                throw new IllegalStateException("singleton domain dependency quota exceeded");
            }
            children.add(target);
            dependents.computeIfAbsent(target, ignored -> new HashSet<>()).add(current);
            edgeCount++;
        }
    }

    /** Only the thread that actually runs initialize.get() enters this stack. */
    void begin(K key) {
        Objects.requireNonNull(key, "singleton initializer key");
        ArrayDeque<K> stack = initializing.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            initializing.set(stack);
        }
        if (stack.size() >= MAX_INITIALIZER_DEPTH) {
            throw new IllegalStateException("singleton initializer depth exceeded");
        }
        stack.push(key);
    }

    /** Drop both outgoing and incoming edges as soon as this future is terminal. */
    synchronized void finish(K key) {
        ArrayDeque<K> stack = initializing.get();
        if (stack == null || stack.isEmpty() || !Objects.equals(stack.peek(), key)) {
            throw new IllegalStateException("singleton initializer stack corrupted");
        }
        stack.pop();
        if (stack.isEmpty()) initializing.remove();
        Set<K> children = dependencies.remove(key);
        if (children != null) {
            for (K child : children) {
                Set<K> reverse = dependents.get(child);
                if (reverse != null) {
                    reverse.remove(key);
                    if (reverse.isEmpty()) dependents.remove(child);
                }
                edgeCount--;
            }
        }
        Set<K> parents = dependents.remove(key);
        if (parents != null) {
            for (K parent : parents) {
                Set<K> outgoing = dependencies.get(parent);
                if (outgoing != null && outgoing.remove(key)) {
                    edgeCount--;
                    if (outgoing.isEmpty()) dependencies.remove(parent);
                }
            }
        }
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
