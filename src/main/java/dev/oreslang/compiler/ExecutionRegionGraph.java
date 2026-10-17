package dev.oreslang.compiler;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Experimental typed CPU/GPU execution-region AST and ordered graph IR.
 * This is a reference compiler foundation, NOT enabled .ores parsing or GPU runtime.
 * Only pure host i64 arithmetic can execute. Device work is never evaluated on CPU.
 */
public final class ExecutionRegionGraph {
    private ExecutionRegionGraph() {}

    public enum TypeKind { VOID, I64, HOST_BUFFER_F32, GPU_BUFFER_F32, FUTURE }
    public record Type(TypeKind kind, Type inner) {
        public Type {
            Objects.requireNonNull(kind);
            if ((kind == TypeKind.FUTURE) != (inner != null))
                throw new IllegalArgumentException("Only Future has inner type");
        }
        public static Type future(Type t) { return new Type(TypeKind.FUTURE, t); }
    }
    public static final Type VOID = new Type(TypeKind.VOID, null);
    public static final Type I64 = new Type(TypeKind.I64, null);
    public static final Type HOST_F32 = new Type(TypeKind.HOST_BUFFER_F32, null);
    public static final Type GPU_F32 = new Type(TypeKind.GPU_BUFFER_F32, null);

    public sealed interface Expr permits CpuRegion, GpuRegion, I64Literal, Add,
            Parameter, Upload, Download, DeviceKernel, Await, Sequence {}
    public record CpuRegion(Expr body, boolean nb) implements Expr {
        public CpuRegion { Objects.requireNonNull(body); }
    }
    public record GpuRegion(Expr body, boolean nb) implements Expr {
        public GpuRegion { Objects.requireNonNull(body); }
    }
    public record I64Literal(long value) implements Expr {}
    public record Add(Expr left, Expr right) implements Expr {
        public Add { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    }
    public record Parameter(String name, Type type) implements Expr {
        public Parameter { validName(name); Objects.requireNonNull(type); }
    }
    public record Upload(Expr value) implements Expr {
        public Upload { Objects.requireNonNull(value); }
    }
    public record Download(Expr value) implements Expr {
        public Download { Objects.requireNonNull(value); }
    }
    public record DeviceKernel(String name, Expr value) implements Expr {
        public DeviceKernel { validName(name); Objects.requireNonNull(value); }
    }
    public record Await(Expr value) implements Expr {
        public Await { Objects.requireNonNull(value); }
    }
    public record Sequence(List<Expr> values) implements Expr {
        public Sequence { values = List.copyOf(values); }
    }

    public enum Effect { HOST, DEVICE, TRANSFER, SUSPEND }
    public enum Domain { CPU, GPU, HOST, TRANSFER }
    public enum Opcode { CONST_I64, ADD_I64, PARAM, UPLOAD, DOWNLOAD,
        DEVICE_KERNEL, DEVICE_FENCE, DEVICE_ADMISSION, SUBMIT, AWAIT }
    public record Step(int id, Opcode opcode, Domain domain, Type type,
                       List<Integer> dependencies, int left, int right, String detail, Long literal) {
        public Step { dependencies = List.copyOf(dependencies); }
    }
    public record Plan(List<Step> steps, Type resultType, int returnId,
                       List<Effect> effects, int deviceKernels) {
        public Plan { steps = List.copyOf(steps); effects = List.copyOf(effects); }
    }
    private record Value(Type type, int id) {}

    private static void validName(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("invalid region identifier");
    }
    private static IllegalArgumentException reject(String why) {
        return new IllegalArgumentException("execution region: " + why);
    }
    public static Plan lower(Expr root, Map<String, Type> parameters) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(parameters);
        if (parameters.size() > 64) throw reject("parameter budget exceeded");
        for (var e : parameters.entrySet()) {
            validName(e.getKey());
            if (e.getValue() == null || e.getValue().kind() == TypeKind.VOID ||
                    e.getValue().kind() == TypeKind.FUTURE)
                throw reject("invalid parameter type");
        }
        return new Builder(Map.copyOf(parameters)).build(root);
    }
    private static final class Builder {
        private final Map<String, Type> parameters;
        private final List<Step> steps = new ArrayList<>();
        private final EnumSet<Effect> effects = EnumSet.noneOf(Effect.class);
        private int deviceKernels;
        private Builder(Map<String, Type> parameters) { this.parameters = parameters; }
        private Value emit(Opcode op, Domain domain, Type type, int left, int right,
                           String detail, Long literal) {
            if (steps.size() >= 2048) throw reject("graph node budget exceeded");
            int id = steps.size();
            LinkedHashSet<Integer> deps = new LinkedHashSet<>();
            if (left >= 0) deps.add(left);
            if (right >= 0) deps.add(right);
            if (id > 0) deps.add(id - 1);
            for (int d : deps) if (d < 0 || d >= id) throw reject("bad dependency");
            steps.add(new Step(id, op, domain, type, List.copyOf(deps),
                               left, right, detail, literal));
            return new Value(type, id);
        }
        private Value emit(Opcode op, Domain domain, Type type, int l, int r) {
            return emit(op, domain, type, l, r, null, null);
        }
        private Plan build(Expr root) {
            Value result = walk(root, Domain.CPU, 0);
            return new Plan(steps, result.type(), result.id(), List.copyOf(effects), deviceKernels);
        }
        private Value walk(Expr expr, Domain domain, int depth) {
            if (depth > 64) throw reject("AST depth exceeded");
            if (expr instanceof I64Literal n) {
                if (domain != Domain.CPU) throw reject("host scalar inside GPU region");
                effects.add(Effect.HOST);
                return emit(Opcode.CONST_I64, Domain.CPU, I64, -1, -1, null, n.value());
            }
            if (expr instanceof Parameter p) {
                if (!Objects.equals(parameters.get(p.name()), p.type()))
                    throw reject("undeclared/mistyped parameter");
                return emit(Opcode.PARAM, Domain.HOST, p.type(), -1, -1, p.name(), null);
            }
            if (expr instanceof Add a) {
                if (domain != Domain.CPU) throw reject("host arithmetic inside GPU region");
                Value l = walk(a.left(), domain, depth + 1);
                Value r = walk(a.right(), domain, depth + 1);
                if (!I64.equals(l.type()) || !I64.equals(r.type()))
                    throw reject("Add requires i64 operands");
                effects.add(Effect.HOST);
                return emit(Opcode.ADD_I64, Domain.CPU, I64, l.id(), r.id());
            }
            if (expr instanceof Upload u) {
                if (domain != Domain.CPU) throw reject("upload must be a host continuation");
                Value v = walk(u.value(), domain, depth + 1);
                if (!HOST_F32.equals(v.type())) throw reject("upload residency mismatch");
                effects.add(Effect.TRANSFER); effects.add(Effect.SUSPEND);
                return emit(Opcode.UPLOAD, Domain.TRANSFER, GPU_F32, v.id(), -1);
            }
            if (expr instanceof Download d) {
                if (domain != Domain.CPU) throw reject("download must be a host continuation");
                Value v = walk(d.value(), domain, depth + 1);
                if (!GPU_F32.equals(v.type())) throw reject("download residency mismatch");
                effects.add(Effect.TRANSFER); effects.add(Effect.SUSPEND);
                return emit(Opcode.DOWNLOAD, Domain.TRANSFER, HOST_F32, v.id(), -1);
            }
            if (expr instanceof DeviceKernel k) {
                if (domain != Domain.GPU) throw reject("device kernel outside GPU region");
                Value v = walk(k.value(), domain, depth + 1);
                if (!GPU_F32.equals(v.type())) throw reject("device input residency mismatch");
                effects.add(Effect.DEVICE); effects.add(Effect.SUSPEND);
                deviceKernels++;
                return emit(Opcode.DEVICE_KERNEL, Domain.GPU, GPU_F32, v.id(), -1, k.name(), null);
            }
            if (expr instanceof Await a) {
                if (domain != Domain.CPU) throw reject("await requires host continuation");
                Value v = walk(a.value(), domain, depth + 1);
                if (v.type().kind() != TypeKind.FUTURE) throw reject("await requires Future<T>");
                effects.add(Effect.SUSPEND);
                return emit(Opcode.AWAIT, Domain.CPU, v.type().inner(), v.id(), -1);
            }
            if (expr instanceof Sequence s) {
                Value last = new Value(VOID, -1);
                for (Expr item : s.values()) last = walk(item, domain, depth + 1);
                return last;
            }
            if (expr instanceof CpuRegion c)
                return region(c.body(), c.nb(), Domain.CPU, domain, depth);
            if (expr instanceof GpuRegion g)
                return region(g.body(), g.nb(), Domain.GPU, domain, depth);
            throw reject("unknown AST node");
        }
        private Value region(Expr body, boolean nb, Domain next, Domain prior, int depth) {
            if (prior == Domain.GPU && next == Domain.CPU) {
                effects.add(Effect.SUSPEND);
                emit(Opcode.DEVICE_FENCE, Domain.TRANSFER, VOID, -1, -1);
            }
            if (prior == Domain.CPU && next == Domain.GPU) {
                effects.add(Effect.SUSPEND);
                emit(Opcode.DEVICE_ADMISSION, Domain.TRANSFER, VOID, -1, -1);
            }
            int before = deviceKernels;
            Value v = walk(body, next, depth + 1);
            if (next == Domain.GPU && before == deviceKernels)
                throw reject("empty GPU region, no kernel work");
            if (nb) {
                effects.add(Effect.SUSPEND);
                return emit(Opcode.SUBMIT, Domain.HOST, Type.future(v.type()), v.id(), -1);
            }
            return v;
        }
    }

    /** Pure, bounded host i64 oracle only: never GPU or async fallback. */
    public static long executeCpuI64(Plan p) {
        Objects.requireNonNull(p);
        if (!I64.equals(p.resultType())) throw reject("CPU oracle requires i64");
        var steps = p.steps();
        if (steps.size() > 2048 || p.returnId() < 0 || p.returnId() >= steps.size())
            throw reject("bad return node");
        long[] values = new long[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.id() != i || s.domain() != Domain.CPU)
                throw reject("no GPU/transfer/async execution provider");
            for (int d : s.dependencies()) if (d < 0 || d >= i)
                throw reject("malformed dependency");
            switch (s.opcode()) {
                case CONST_I64 -> {
                    if (!I64.equals(s.type()) || s.literal() == null)
                        throw reject("malformed i64 constant");
                    values[i] = s.literal();
                }
                case ADD_I64 -> {
                    if (s.left() < 0 || s.right() < 0 || s.left() >= i ||
                            s.right() >= i || !s.dependencies().contains(s.left()) ||
                            !s.dependencies().contains(s.right()))
                        throw reject("malformed i64 operands");
                    values[i] = Math.addExact(values[s.left()], values[s.right()]);
                }
                default -> throw reject("CPU oracle cannot execute " + s.opcode());
            }
        }
        return values[p.returnId()];
    }
}
