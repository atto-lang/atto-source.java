package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One singleton instance per semantic memory slot.
 *
 * A slot is the main/shared memory domain or one isolated actor memory domain.
 * The registry never exposes the mutable state object. Callers receive an opaque
 * slot-bound Handle and execute operations under the cell lock. Arguments and
 * results cross the boundary as frozen data, preventing mutable alias escape.
 */
public final class MemorySlotSingletonRegistry implements AutoCloseable {
    private static final int MAX_SINGLETONS_PER_SLOT = 4_096;
    private static final int MAX_KEY_CHARS = 2_048;

    private final ActorRuntime runtime;
    private final ConcurrentHashMap<Object, ConcurrentHashMap<String, Cell>> slots =
            new ConcurrentHashMap<>();

    MemorySlotSingletonRegistry(ActorRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @FunctionalInterface
    public interface Operation<S> {
        Object apply(S state, List<Object> arguments) throws Exception;
    }

    public <S> Handle<S> getOrCreate(
            String key,
            Supplier<? extends S> stateFactory) {
        Objects.requireNonNull(stateFactory, "stateFactory");
        String normalized = normalizeKey(key);
        Object slot = runtime.currentMemorySlotKey();

        ConcurrentHashMap<String, Cell> table =
                slots.computeIfAbsent(slot, ignored -> new ConcurrentHashMap<>());
        Cell cell = table.computeIfAbsent(normalized, ignored -> {
            if (table.size() >= MAX_SINGLETONS_PER_SLOT) {
                throw new IllegalStateException(
                        "memory-slot singleton limit exceeded: " + MAX_SINGLETONS_PER_SLOT);
            }
            Object state = Objects.requireNonNull(
                    stateFactory.get(),
                    "singleton state factory returned null");
            return new Cell(slot, normalized, state);
        });
        return new Handle<>(this, cell);
    }

    public int singletonCountForCurrentSlot() {
        Map<String, Cell> table = slots.get(runtime.currentMemorySlotKey());
        return table == null ? 0 : table.size();
    }

    /**
     * Called by ActorRuntime when one isolated/private memory slot is retired.
     * Shared/main slot state lives until runtime close.
     */
    void releaseSlot(Object slot) {
        ConcurrentHashMap<String, Cell> table = slots.remove(slot);
        if (table == null) return;
        for (Cell cell : table.values()) cell.close();
        table.clear();
    }

    @Override
    public void close() {
        for (Object slot : List.copyOf(slots.keySet())) releaseSlot(slot);
    }

    private static String normalizeKey(String key) {
        String normalized = Objects.requireNonNull(key, "key").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("singleton key cannot be blank");
        if (normalized.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException(
                    "singleton key exceeds " + MAX_KEY_CHARS + " characters");
        }
        return normalized;
    }

    public static final class Handle<S> implements SandboxForbiddenCapability {
        private final MemorySlotSingletonRegistry registry;
        private final Cell cell;

        private Handle(MemorySlotSingletonRegistry registry, Cell cell) {
            this.registry = registry;
            this.cell = cell;
        }

        public UUID instanceId() {
            return cell.instanceId;
        }

        public String singletonKey() {
            return cell.key;
        }

        public Object call(List<?> arguments, Operation<S> operation) {
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(operation, "operation");
            if (registry.runtime.currentMemorySlotKey() != cell.slot) {
                throw new SecurityException(
                        "singleton handle belongs to a different memory slot");
            }

            @SuppressWarnings("unchecked")
            List<Object> frozenArguments =
                    (List<Object>) ActorRuntime.freeze(new ArrayList<>(arguments));

            synchronized (cell.lock) {
                if (cell.closed) {
                    throw new IllegalStateException(
                            "memory-slot singleton is closed: " + cell.key);
                }
                try {
                    @SuppressWarnings("unchecked")
                    S state = (S) cell.state;
                    return ActorRuntime.freeze(operation.apply(state, frozenArguments));
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new RuntimeException(
                            "memory-slot singleton operation failed: " + cell.key,
                            failure);
                }
            }
        }
    }

    private static final class Cell {
        private final Object slot;
        private final String key;
        private final UUID instanceId = UUID.randomUUID();
        private final Object lock = new Object();
        private Object state;
        private boolean closed;

        private Cell(Object slot, String key, Object state) {
            this.slot = Objects.requireNonNull(slot);
            this.key = Objects.requireNonNull(key);
            this.state = Objects.requireNonNull(state);
        }

        private void close() {
            Object toClose;
            synchronized (lock) {
                if (closed) return;
                closed = true;
                toClose = state;
                state = null;
            }
            if (toClose instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception failure) {
                    throw new IllegalStateException(
                            "failed to close memory-slot singleton " + key,
                            failure);
                }
            }
        }
    }
}
