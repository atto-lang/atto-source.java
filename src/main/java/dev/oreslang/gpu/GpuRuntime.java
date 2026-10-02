package dev.oreslang.gpu;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public process-wide GPU backend SPI for Oreslang embeddings.
 *
 * The interpreter never evaluates a gpu callable on the CPU as a fallback.
 * A backend must explicitly accept and execute each dispatched invocation.
 */
public final class GpuRuntime {
    private static final AtomicReference<Backend> PROCESS_BACKEND = new AtomicReference<>();

    public enum CallableKind { FNC, ROUTINE }

    public interface Backend {
        String name();

        default boolean supports(Invocation invocation) {
            return true;
        }

        Object invoke(Invocation invocation);
    }

    public record Invocation(
            String callable,
            CallableKind callableKind,
            List<?> arguments) {
        public Invocation {
            Objects.requireNonNull(callable, "callable");
            Objects.requireNonNull(callableKind, "callableKind");
            arguments = List.copyOf(arguments);
        }
    }

    public static void installBackend(Backend backend) {
        PROCESS_BACKEND.set(Objects.requireNonNull(backend, "backend"));
    }

    public static void clearBackend() {
        PROCESS_BACKEND.set(null);
    }

    public boolean available() {
        return PROCESS_BACKEND.get() != null;
    }

    public String backendName() {
        Backend backend = PROCESS_BACKEND.get();
        return backend == null ? "unavailable" : backend.name();
    }

    public Object dispatch(String callable, CallableKind callableKind, List<?> arguments) {
        Invocation invocation = new Invocation(callable, callableKind, arguments);
        Backend backend = PROCESS_BACKEND.get();
        if (backend == null) {
            throw new GpuUnavailableException("gpu callable '" + callable
                    + "' requires a GPU backend; none is installed and CPU fallback is forbidden");
        }
        if (!backend.supports(invocation)) {
            throw new GpuCapabilityUnavailableException("GPU backend '" + backend.name()
                    + "' cannot execute gpu callable '" + callable + "'");
        }
        try {
            return backend.invoke(invocation);
        } catch (GpuException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new GpuExecutionException("GPU backend '" + backend.name()
                    + "' failed while executing '" + callable + "'", failure);
        }
    }

    public static class GpuException extends RuntimeException {
        public GpuException(String message) { super(message); }
        public GpuException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class GpuUnavailableException extends GpuException {
        public GpuUnavailableException(String message) { super(message); }
    }

    public static final class GpuCapabilityUnavailableException extends GpuException {
        public GpuCapabilityUnavailableException(String message) { super(message); }
    }

    public static final class GpuExecutionException extends GpuException {
        public GpuExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
