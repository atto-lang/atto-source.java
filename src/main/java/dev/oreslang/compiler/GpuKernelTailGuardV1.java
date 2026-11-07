package dev.oreslang.compiler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * G1c source-only 1D tail-guard verifier for a restricted AttoLang GPU kernel.
 *
 * A successful result proves only a syntactic domination relation between the
 * lane-zero index and one integer source bound. It does NOT prove the bound is
 * <= any actual device allocation, that lanes execute with the claimed geometry,
 * provider/kernel identity, loan lifetime, or device completion. No returned
 * object is authority to dispatch. Non-1D or non-canonical indexed access
 * intentionally fails closed until dedicated proofs are implemented.
 */
public final class GpuKernelTailGuardV1 {
    private GpuKernelTailGuardV1() { }

    public enum Evidence { SOURCE_TAIL_GUARD_ONLY_NOT_EXECUTABLE }

    public record Report(long inspectedMemoryOps, Evidence evidence) {
        public Report {
            if (inspectedMemoryOps < 0 || evidence != Evidence.SOURCE_TAIL_GUARD_ONLY_NOT_EXECUTABLE)
                throw error("invalid source-only audit result");
        }
        public boolean executable() { return false; }
    }

    private static IllegalArgumentException error(String why) {
        return new IllegalArgumentException("GPU-KERNEL-010: " + why);
    }

    private static final class Context {
        private final Map<String, GpuKernelSourceHirV1.Type> parameters;
        private final Map<String, GpuKernelSourceHirV1.Value> definitions;

        private Context(Map<String, GpuKernelSourceHirV1.Type> parameters,
                        Map<String, GpuKernelSourceHirV1.Value> definitions) {
            this.parameters = parameters;
            this.definitions = definitions;
        }
        private Context child() { return new Context(parameters, new HashMap<>(definitions)); }
    }

    private static boolean laneZero(GpuKernelSourceHirV1.Value v, Context ctx, int depth) {
        if (v == null || depth > 16 || v.type() != GpuKernelSourceHirV1.Type.I64)
            return false;
        if (v.opcode().equals("gpu.global_id"))
            return v.operands().isEmpty() && "0".equals(v.symbol());
        if (v.opcode().equals("local") && v.operands().isEmpty()) {
            GpuKernelSourceHirV1.Value definition = ctx.definitions.get(v.symbol());
            return definition != null && laneZero(definition, ctx, depth + 1);
        }
        return false;
    }

    private static boolean bound(GpuKernelSourceHirV1.Value v, Context ctx) {
        if (v == null || v.type() != GpuKernelSourceHirV1.Type.I64
                || !v.operands().isEmpty()) return false;
        if (v.opcode().equals("local")) {
            return ctx.parameters.get(v.symbol()) == GpuKernelSourceHirV1.Type.I64;
        }
        if (v.opcode().equals("i64.literal")) {
            try {
                return Long.parseLong(v.symbol()) >= 0;
            } catch (NumberFormatException | NullPointerException ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean comparison(GpuKernelSourceHirV1.Value v,
                                      Context ctx, String opcode) {
        return v != null && v.type() == GpuKernelSourceHirV1.Type.BOOL
                && v.opcode().equals(opcode)
                && v.operands().size() == 2
                && laneZero(v.operands().get(0), ctx, 0)
                && bound(v.operands().get(1), ctx);
    }

    /** Returns the number of checked buffer loads nested in one value. */
    private static long inspectValue(GpuKernelSourceHirV1.Value v, Context ctx,
                                     boolean guarded, int depth) {
        if (v == null) throw error("null typed GPU expression");
        if (depth > 32) throw error("typed GPU expression exceeds resource budget");
        long operations = 0;
        if (v.opcode().equals("buffer.load")) {
            if (!guarded || v.type() != GpuKernelSourceHirV1.Type.F32
                    || v.operands().size() != 2
                    || !laneZero(v.operands().get(1), ctx, 0)) {
                throw error("device read is not protected by a matching 1D lane guard");
            }
            operations = 1;
        }
        for (GpuKernelSourceHirV1.Value child : v.operands()) {
            operations = Math.addExact(operations,
                    inspectValue(child, ctx, guarded, depth + 1));
        }
        return operations;
    }

    private static long inspectStatements(List<GpuKernelSourceHirV1.Instruction> instructions,
                                          Context context, boolean laneGuard,
                                          int depth) {
        if (depth > 16 || instructions.size() > 256)
            throw error("GPU tail-guard control-flow quota exceeded");
        boolean guarded = laneGuard;
        long memoryOps = 0;
        for (GpuKernelSourceHirV1.Instruction instruction : instructions) {
            Objects.requireNonNull(instruction, "GPU-KERNEL-010: null instruction");
            switch (instruction.opcode()) {
                case "LET" -> {
                    if (instruction.target() == null || instruction.value() == null)
                        throw error("malformed scalar binding in tail-guard audit");
                    memoryOps = Math.addExact(memoryOps,
                            inspectValue(instruction.value(), context, guarded, depth + 1));
                    if (context.parameters.containsKey(instruction.target())
                            || context.definitions.putIfAbsent(instruction.target(),
                                    instruction.value()) != null)
                        throw error("local binding shadows parameter or another scalar");
                }
                case "STORE_F32" -> {
                    GpuKernelSourceHirV1.Value value = instruction.value();
                    if (!guarded || value == null || !value.opcode().equals("store.arguments")
                            || value.operands().size() != 2
                            || !laneZero(value.operands().get(0), context, 0)) {
                        throw error("device write is not protected by a matching 1D lane guard");
                    }
                    memoryOps = Math.addExact(memoryOps, 1);
                    memoryOps = Math.addExact(memoryOps,
                            inspectValue(value.operands().get(1), context, guarded, depth + 1));
                }
                case "IF" -> {
                    GpuKernelSourceHirV1.Value condition = instruction.value();
                    if (condition == null || condition.type() != GpuKernelSourceHirV1.Type.BOOL)
                        throw error("GPU branch predicate missing typed boolean");
                    memoryOps = Math.addExact(memoryOps,
                            inspectValue(condition, context, guarded, depth + 1));
                    boolean guardedIfTrue = guarded
                            || comparison(condition, context, "compare.<");
                    memoryOps = Math.addExact(memoryOps,
                            inspectStatements(instruction.body(), context.child(),
                                    guardedIfTrue, depth + 1));
                    // If a lane >= n branch must RETURN_VOID unconditionally
                    // at its end, only the fallthrough path can reach later
                    // instructions, and it has lane < n. No else is encoded.
                    if (comparison(condition, context, "compare.>=")
                            && !instruction.body().isEmpty()
                            && instruction.body().getLast().opcode().equals("RETURN_VOID")) {
                        guarded = true;
                    }
                }
                case "RETURN_VOID" -> {
                    if (instruction.value() != null || !instruction.body().isEmpty())
                        throw error("unexpected return operands");
                    return memoryOps; // unreachable following instructions
                }
                default -> throw error("unexpected typed kernel instruction: " + instruction.opcode());
            }
        }
        return memoryOps;
    }

    /**
     * Fail closed before source-HIR exposure: this audit does not permit
     * execution or establish actual allocation capacity for the bound.
     */
    public static Report audit(GpuKernelSourceHirV1.Plan plan) {
        Objects.requireNonNull(plan, "GPU source plan");
        if (plan.evidence() != GpuKernelSourceHirV1.Evidence.TYPED_SOURCE_ONLY_NOT_EXECUTABLE)
            throw error("missing source-only kernel evidence");
        Map<String, GpuKernelSourceHirV1.Type> params = new HashMap<>();
        for (GpuKernelSourceHirV1.Parameter p : plan.parameters()) {
            if (params.putIfAbsent(p.name(), p.type()) != null)
                throw error("duplicate GPU kernel parameter name");
        }
        long ops = inspectStatements(plan.body(),
                new Context(Map.copyOf(params), new HashMap<>()), false, 0);
        return new Report(ops, Evidence.SOURCE_TAIL_GUARD_ONLY_NOT_EXECUTABLE);
    }
}
