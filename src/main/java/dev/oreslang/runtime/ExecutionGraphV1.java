package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trusted, host-side typed execution-graph IR v1.
 *
 * <p>NOT the guest source AST and NOT a GPU backend. This defines immutable
 * graph admission and runs the already-implemented CPU scalar slice only.
 * GPU nodes are representable for compiler planning but fail closed before
 * executing ANY step. There is no implicit GPU-on-CPU fallback or transfer.</p>
 *
 * <p>Use only from trusted compiler/runtime code. Public helpers are not a
 * capability to run arbitrary host callables or execute GPU workloads.</p>
 */
public final class ExecutionGraphV1 {
    private ExecutionGraphV1() { }

    public enum Domain { CPU, GPU }
    public enum ValueKind { UNIT, HOST_I64, DEVICE_I64 }
    public enum Operation { UNIT, CONSTANT_I64, ADD_I64, UPLOAD_I64, GPU_ADD_I64, DOWNLOAD_I64 }
    public enum Unit { INSTANCE }

    /** Opaque marker: only an actual device backend may create device values. */
    public static final class DeviceI64 {
        private DeviceI64() { }
    }

    public record Node(int id, Domain domain, Operation operation, ValueKind result,
                       List<Integer> inputs, long constant) {
        public Node {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(result, "result");
            inputs = List.copyOf(inputs);
            if (id < 0) throw new IllegalArgumentException("negative node ID");
            for (int input : inputs) {
                if (input < 0 || input >= id) {
                    throw new IllegalArgumentException("graph dependency must precede consumer");
                }
            }
        }
    }

    /** Unforgeable, builder-local typed reference to a prior node. */
    public static final class Ref<T> {
        private final Builder owner;
        private final int index;
        private final ValueKind kind;
        private Ref(Builder owner, int index, ValueKind kind) {
            this.owner = owner;
            this.index = index;
            this.kind = kind;
        }
        public ValueKind kind() { return kind; }
        public int index() { return index; }
    }

    public static final class Builder {
        private final List<Node> steps = new ArrayList<>();
        private boolean sealed;

        private <T> Ref<T> append(Domain domain, Operation op, ValueKind kind,
                                   long constant, Ref<?>... dependencies) {
            if (sealed) throw new IllegalStateException("builder already sealed");
            if (steps.size() == 4096) throw new IllegalArgumentException("execution graph exceeds node budget");
            List<Integer> ids = new ArrayList<>(dependencies.length);
            for (Ref<?> dep : dependencies) {
                Objects.requireNonNull(dep, "dependency");
                if (dep.owner != this || dep.index >= steps.size()) {
                    throw new IllegalArgumentException("foreign or forward execution-graph reference");
                }
                ids.add(dep.index);
            }
            int id = steps.size();
            steps.add(new Node(id, domain, op, kind, ids, constant));
            return new Ref<>(this, id, kind);
        }

        private void require(Ref<?> value, ValueKind expected) {
            Objects.requireNonNull(value, "value");
            if (value.owner != this || value.index >= steps.size() || value.kind != expected) {
                throw new IllegalArgumentException("invalid domain, type, or graph ownership");
            }
        }

        public Ref<Unit> cpuUnit() {
            return append(Domain.CPU, Operation.UNIT, ValueKind.UNIT, 0);
        }

        public Ref<Long> cpuI64(long value) {
            return append(Domain.CPU, Operation.CONSTANT_I64, ValueKind.HOST_I64, value);
        }

        public Ref<Long> cpuAdd(Ref<Long> left, Ref<Long> right) {
            require(left, ValueKind.HOST_I64);
            require(right, ValueKind.HOST_I64);
            return append(Domain.CPU, Operation.ADD_I64, ValueKind.HOST_I64, 0, left, right);
        }

        /** Explicit transfer boundary. No transfer provider is installed yet. */
        public Ref<DeviceI64> uploadI64(Ref<Long> host) {
            require(host, ValueKind.HOST_I64);
            return append(Domain.GPU, Operation.UPLOAD_I64, ValueKind.DEVICE_I64, 0, host);
        }

        /** Explicit device compute node; no CPU emulation is permitted. */
        public Ref<DeviceI64> gpuAddI64(Ref<DeviceI64> left, Ref<DeviceI64> right) {
            require(left, ValueKind.DEVICE_I64);
            require(right, ValueKind.DEVICE_I64);
            return append(Domain.GPU, Operation.GPU_ADD_I64, ValueKind.DEVICE_I64, 0, left, right);
        }

        public Ref<Long> downloadI64(Ref<DeviceI64> device) {
            require(device, ValueKind.DEVICE_I64);
            return append(Domain.CPU, Operation.DOWNLOAD_I64, ValueKind.HOST_I64, 0, device);
        }

        public <T> Plan<T> build(Ref<T> output) {
            if (sealed) throw new IllegalStateException("builder already sealed");
            Objects.requireNonNull(output, "output");
            if (output.owner != this || output.index >= steps.size()) {
                throw new IllegalArgumentException("graph output belongs to another builder");
            }
            sealed = true;
            return new Plan<>(List.copyOf(steps), output.index, output.kind);
        }
    }

    public static final class Plan<T> {
        private final List<Node> nodes;
        private final int output;
        private final ValueKind resultType;

        private Plan(List<Node> nodes, int output, ValueKind resultType) {
            Objects.requireNonNull(resultType, "resultType");
            Objects.requireNonNull(nodes, "nodes");
            if (nodes.isEmpty() || nodes.size() > 4096 || output < 0 || output >= nodes.size()) {
                throw new IllegalArgumentException("invalid typed graph size or output");
            }
            for (int i = 0; i < nodes.size(); i++) {
                Node node = Objects.requireNonNull(nodes.get(i), "node");
                if (node.id() != i) throw new IllegalArgumentException("invalid graph node ID");
                for (int dep : node.inputs()) {
                    if (dep < 0 || dep >= i) {
                        throw new IllegalArgumentException("invalid graph dependency");
                    }
                }
                switch (node.operation()) {
                    case UNIT -> signature(nodes, node, Domain.CPU, ValueKind.UNIT);
                    case CONSTANT_I64 -> signature(nodes, node, Domain.CPU, ValueKind.HOST_I64);
                    case ADD_I64 -> signature(nodes, node, Domain.CPU, ValueKind.HOST_I64,
                            ValueKind.HOST_I64, ValueKind.HOST_I64);
                    case UPLOAD_I64 -> signature(nodes, node, Domain.GPU, ValueKind.DEVICE_I64,
                            ValueKind.HOST_I64);
                    case GPU_ADD_I64 -> signature(nodes, node, Domain.GPU, ValueKind.DEVICE_I64,
                            ValueKind.DEVICE_I64, ValueKind.DEVICE_I64);
                    case DOWNLOAD_I64 -> signature(nodes, node, Domain.CPU, ValueKind.HOST_I64,
                            ValueKind.DEVICE_I64);
                }
            }
            if (nodes.get(output).result() != resultType) {
                throw new IllegalArgumentException("invalid typed graph output kind");
            }
            this.nodes = List.copyOf(nodes);
            this.output = output;
            this.resultType = resultType;
        }

        private static void signature(List<Node> nodes, Node node, Domain domain,
                                      ValueKind result, ValueKind... expectedInputs) {
            if (node.domain() != domain || node.result() != result
                    || node.inputs().size() != expectedInputs.length) {
                throw new IllegalArgumentException("invalid typed graph operation signature");
            }
            for (int i = 0; i < expectedInputs.length; i++) {
                int dependency = node.inputs().get(i);
                if (dependency < 0 || dependency >= node.id()
                        || nodes.get(dependency).result() != expectedInputs[i]) {
                    throw new IllegalArgumentException("invalid typed graph operand kind");
                }
            }
        }

        public List<Node> nodes() { return nodes; }
        public ValueKind resultType() { return resultType; }

        public boolean cpuOnly() {
            return nodes.stream().allMatch(n -> n.domain() == Domain.CPU
                    && n.operation() != Operation.DOWNLOAD_I64);
        }

        private void admitCpu() {
            if (!cpuOnly()) {
                throw new UnsupportedOperationException(
                        "GPU execution/transfer provider unavailable; no implicit CPU fallback");
            }
        }

        @SuppressWarnings("unchecked")
        private T evaluate(AtomicBoolean cancelled) {
            Object[] results = new Object[nodes.size()];
            for (Node node : nodes) {
                if (cancelled.get()) throw new CancellationException("execution graph cancelled");
                int id = node.id();
                results[id] = switch (node.operation()) {
                    case UNIT -> Unit.INSTANCE;
                    case CONSTANT_I64 -> node.constant();
                    case ADD_I64 -> Math.addExact((Long) results[node.inputs().get(0)],
                                                  (Long) results[node.inputs().get(1)]);
                    case UPLOAD_I64, GPU_ADD_I64, DOWNLOAD_I64 -> throw new IllegalStateException(
                            "unadmitted execution domain step");
                };
            }
            if (cancelled.get()) throw new CancellationException("execution graph cancelled");
            return (T) results[output];
        }

        /** Synchronous CPU-only execution; never invoke from an actor carrier. */
        public T executeCpu() {
            admitCpu();
            return evaluate(new AtomicBoolean());
        }

        /**
         * One-shot nonblocking CPU submission to an externally budgeted, trusted
         * executor. Returns OresFuture, NOT a channel/stream.
         *
         * <p>A cancellation request suppresses publication and checks between
         * steps, but cannot forcibly stop an already running CPU operation.
         * Executor quotas/admission remain the responsibility of its owner.</p>
         */
        public OresFuture<T> submitCpu(Executor executor) {
            Objects.requireNonNull(executor, "executor");
            try {
                admitCpu(); // reject GPU plan BEFORE any task can run
            } catch (UnsupportedOperationException ex) {
                return OresFuture.failed(ex);
            }
            AtomicBoolean cancelled = new AtomicBoolean();
            OresFuture<T> result = new OresFuture<>(() -> cancelled.set(true));
            try {
                executor.execute(() -> {
                    if (result.isDone()) return;
                    try {
                        T value = evaluate(cancelled);
                        if (!result.isDone()) result.completeFromRuntime(value);
                    } catch (CancellationException ex) {
                        result.cancel(false);
                    } catch (Throwable ex) {
                        result.failFromRuntime(ex);
                    }
                });
            } catch (RuntimeException ex) {
                result.failFromRuntime(ex);
            }
            return result;
        }
    }
}
