package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

/**
 * Process-wide actor registry for Oreslang singleton modules.
 *
 * A singleton module has exactly one state cell and one serial mailbox in this
 * runtime process. Importers never receive the state object; generated/runtime
 * proxies submit typed operations to the handle. Arguments and results cross
 * the boundary through {@link ActorRuntime#freeze(Object)} so writable aliases
 * cannot escape the owning actor.
 *
 * This class intentionally has process lifetime. Cells are not owned by an
 * individual Graal Context or isolate and therefore are not closed when one
 * isolate is disposed.
 */
public final class ProcessSingletonRegistry {
    private static final int MAILBOX_CAPACITY = 65_536;
    private static final Map<String, Cell> CELLS = new ConcurrentHashMap<>();

    private ProcessSingletonRegistry() { }

    @FunctionalInterface
    public interface Operation<S> {
        Object apply(S state, List<Object> arguments) throws Exception;
    }

    public static <S> Handle<S> getOrCreate(String key, Supplier<? extends S> stateFactory) {
        String normalized = Objects.requireNonNull(key, "key").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("singleton key cannot be blank");
        Objects.requireNonNull(stateFactory, "stateFactory");

        Cell cell = CELLS.computeIfAbsent(normalized, ignored -> new Cell(normalized, stateFactory));
        return new Handle<>(cell);
    }

    public static int processSingletonCount() {
        return CELLS.size();
    }

    public static final class Handle<S> {
        private final Cell cell;

        private Handle(Cell cell) {
            this.cell = cell;
        }

        public UUID instanceId() {
            return cell.instanceId;
        }

        /**
         * Request/reply actor call. The returned stage completes after the
         * singleton actor has serialized and executed the operation.
         */
        public CompletionStage<Object> call(List<?> arguments, Operation<S> operation) {
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(operation, "operation");

            List<Object> frozen = new ArrayList<>(arguments.size());
            for (Object argument : arguments) frozen.add(ActorRuntime.freeze(argument));

            CompletableFuture<Object> reply = new CompletableFuture<>();
            Request request = new Request(
                    List.copyOf(frozen),
                    (state, args) -> operation.apply(cast(state), args),
                    reply);
            if (!cell.mailbox.offer(request)) {
                reply.completeExceptionally(new IllegalStateException(
                        "singleton mailbox limit exceeded for " + cell.key));
            }
            return reply;
        }

        @SuppressWarnings("unchecked")
        private S cast(Object state) {
            return (S) state;
        }
    }

    @FunctionalInterface
    private interface ErasedOperation {
        Object apply(Object state, List<Object> arguments) throws Exception;
    }

    private record Request(
            List<Object> arguments,
            ErasedOperation operation,
            CompletableFuture<Object> reply) { }

    private static final class Cell {
        private final String key;
        private final UUID instanceId = UUID.randomUUID();
        private final BlockingQueue<Request> mailbox = new LinkedBlockingQueue<>(MAILBOX_CAPACITY);
        private final Supplier<?> stateFactory;

        private Cell(String key, Supplier<?> stateFactory) {
            this.key = key;
            this.stateFactory = stateFactory;
            Thread.ofVirtual()
                    .name("ores-process-singleton-" + key.replaceAll("[^A-Za-z0-9_.-]", "_"))
                    .start(this::run);
        }

        private void run() {
            Object state;
            Throwable initializationFailure = null;
            try {
                state = Objects.requireNonNull(stateFactory.get(),
                        "singleton state factory returned null for " + key);
            } catch (Throwable failure) {
                state = null;
                initializationFailure = failure;
            }

            while (true) {
                Request request;
                try {
                    request = mailbox.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (initializationFailure != null) {
                    request.reply.completeExceptionally(initializationFailure);
                    continue;
                }

                try {
                    Object result = request.operation.apply(state, request.arguments);
                    request.reply.complete(ActorRuntime.freeze(result));
                } catch (Throwable failure) {
                    request.reply.completeExceptionally(failure);
                }
            }
        }
    }
}
