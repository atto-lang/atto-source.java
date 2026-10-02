package dev.oreslang.gpu;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

    /** Marker used by private guest-runtime values that have a safe GPU wire form. */
    public interface Transferable {
        Object freezeForGpu();
    }

    /** Stable public representation for Oreslang complex scalar values. */
    public record ComplexValue(double real, double imaginary) { }

    /** Stable public representation for Oreslang Option values. */
    public record OptionValue(boolean present, Object value) {
        public OptionValue {
            if (present && value == null) throw new IllegalArgumentException("Some cannot carry standalone null");
            if (!present && value != null) throw new IllegalArgumentException("None cannot carry a value");
        }
    }

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
            arguments = freezeArguments(arguments);
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
            return freezeValue(backend.invoke(invocation), true);
        } catch (GpuException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new GpuExecutionException("GPU backend '" + backend.name()
                    + "' failed while executing '" + callable + "'", failure);
        }
    }

    private static List<?> freezeArguments(List<?> arguments) {
        ArrayList<Object> frozen = new ArrayList<>(arguments.size());
        for (Object argument : arguments) frozen.add(freezeValue(argument, false));
        return List.copyOf(frozen);
    }

    private static Object freezeValue(Object value, boolean allowRootVoidNull) {
        if (value == null) {
            if (allowRootVoidNull) return null;
            throw new GpuTransferException("standalone null cannot cross the Oreslang GPU boundary; use Option");
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ComplexValue) {
            return value;
        }
        if (value instanceof OptionValue option) {
            return option.present()
                    ? new OptionValue(true, freezeValue(option.value(), false))
                    : new OptionValue(false, null);
        }
        if (value instanceof Transferable transferable) {
            Object transferred = transferable.freezeForGpu();
            if (transferred == value) {
                throw new GpuTransferException("GPU transferable values must produce a distinct wire representation");
            }
            return freezeValue(transferred, false);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> frozen = new ArrayList<>(list.size());
            for (Object item : list) frozen.add(freezeValue(item, false));
            return List.copyOf(frozen);
        }
        if (value instanceof Set<?> set) {
            return set.stream().map(item -> freezeValue(item, false))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> frozen = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                frozen.put(freezeValue(entry.getKey(), false), freezeValue(entry.getValue(), false));
            }
            return Map.copyOf(frozen);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            ArrayList<Object> frozen = new ArrayList<>(length);
            for (int i = 0; i < length; i++) frozen.add(freezeValue(Array.get(value, i), false));
            return List.copyOf(frozen);
        }
        throw new GpuTransferException("value of type " + value.getClass().getName()
                + " cannot cross the Oreslang GPU boundary");
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

    public static final class GpuTransferException extends GpuException {
        public GpuTransferException(String message) { super(message); }
    }
}
