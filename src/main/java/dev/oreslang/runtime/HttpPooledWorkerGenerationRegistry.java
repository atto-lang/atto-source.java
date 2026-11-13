package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Host-only lifetime registry for all incarnations of a reusable HTTP
 * actor pool, including generations displaced during listener shutdown.
 *
 * A pool slot describes only the *current* incarnation. Physical shutdown
 * must cancel every generation, including one no longer reachable from
 * the slot array. Register and stop race under this registry monitor.
 * A generation is retired only when its physical carrier exit is proven.
 */
final class HttpPooledWorkerGenerationRegistry<T> {
    private final Set<T> generations = new HashSet<>();
    private boolean stopping;

    synchronized boolean register(T generation) {
        Objects.requireNonNull(generation, "generation");
        if (stopping) return false;
        if (!generations.add(generation)) {
            throw new IllegalStateException("duplicate HTTP worker generation");
        }
        return true;
    }

    synchronized void retire(T generation) {
        Objects.requireNonNull(generation, "generation");
        generations.remove(generation);
    }

    synchronized List<T> stopAndSnapshot() {
        stopping = true;
        return List.copyOf(generations);
    }

    synchronized boolean stopping() {
        return stopping;
    }

    synchronized int outstanding() {
        return generations.size();
    }
}
