package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Host-owned actor domain -> subprocess registry lifecycle.
 *
 * <p>Registration and retirement are atomic relative to context shutdown.
 * The table lock is never held while closing a registry (which may notify
 * futures and dispatch off-carrier operating system cleanup).</p>
 */
final class ActorChildProcessRegistry implements AutoCloseable {
    private final Map<Object, ChildProcOwnedRuntime> byDomain = new HashMap<>();
    private boolean closed;

    synchronized void register(Object domain, ChildProcOwnedRuntime registry) {
        Objects.requireNonNull(domain, "actor execution domain");
        Objects.requireNonNull(registry, "child process registry");
        if (closed) throw new IllegalStateException("actor context is closing");
        if (byDomain.containsKey(domain)) {
            throw new IllegalStateException("actor already owns a child process manager");
        }
        byDomain.put(domain, registry);
    }

    void retire(Object domain) {
        final ChildProcOwnedRuntime registry;
        synchronized (this) {
            registry = byDomain.remove(domain);
        }
        if (registry != null) registry.close();
    }

    @Override public void close() {
        final List<ChildProcOwnedRuntime> registries;
        synchronized (this) {
            if (closed) return;
            closed = true;
            registries = new ArrayList<>(byDomain.values());
            byDomain.clear();
        }
        for (ChildProcOwnedRuntime registry : registries) registry.close();
    }
}
