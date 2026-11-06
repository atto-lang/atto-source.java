package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.List;
import java.util.Objects;

/**
 * G1b: immutable, source-derived GPU launch shape IR.
 *
 * This pass is called by the actual TypeChecker from parsed .atto AST, but
 * deliberately does NOT resolve types, device capabilities, ownership,
 * GPU effects or completion fences. A successful Candidate is never
 * executable and must not be used to issue commands or bypass GPU-LAUNCH-008.
 */
public final class GpuLaunchSourceHirV1 {
    private GpuLaunchSourceHirV1() { }

    public enum Mode { PHYSICAL_GRID_BLOCK, LOGICAL_OVER_GROUP }
    public enum Evidence { SOURCE_SHAPE_ONLY_NOT_EXECUTABLE }

    /** Checked symbolic 64-bit integer expression; names still need a type proof. */
    public sealed interface I64Expr permits Constant, Symbol, CeilDiv, Arithmetic { }
    public record Constant(long value) implements I64Expr {
        public Constant {
            if (value < 0) throw geometry("geometry values cannot be negative");
        }
    }
    public record Symbol(String name) implements I64Expr {
        public Symbol { requireName(name, "symbol"); }
    }
    public record CeilDiv(I64Expr extent, I64Expr group) implements I64Expr {
        public CeilDiv {
            Objects.requireNonNull(extent, "ceil_div extent");
            Objects.requireNonNull(group, "ceil_div group");
            if (group instanceof Constant c && c.value() == 0)
                throw geometry("ceil_div divisor must be positive");
        }
    }
    public record Arithmetic(String operator, I64Expr left, I64Expr right)
            implements I64Expr {
        public Arithmetic {
            if (!operator.equals("+") && !operator.equals("-") && !operator.equals("*"))
                throw geometry("unsupported integer geometry operator");
            Objects.requireNonNull(left, "arithmetic left");
            Objects.requireNonNull(right, "arithmetic right");
        }
    }

    public record Geometry(Mode mode, List<I64Expr> extent, List<I64Expr> group) {
        public Geometry {
            Objects.requireNonNull(mode, "GPU geometry mode");
            extent = List.copyOf(extent);
            group = List.copyOf(group);
            if (extent.size() != 3 || group.size() != 3)
                throw geometry("normalized launch geometry must have three axes");
            for (int axis = 0; axis < 3; axis++) {
                if (group.get(axis) instanceof Constant c && c.value() == 0)
                    throw geometry("block/group dimensions must be positive");
                if (mode == Mode.PHYSICAL_GRID_BLOCK
                        && extent.get(axis) instanceof Constant c && c.value() == 0)
                    throw geometry("physical grid dimensions must be positive");
            }
        }
    }

    public record Candidate(String kernelSymbol, List<Ast.Expr> orderedArguments,
                            String deviceSymbol, Geometry geometry,
                            String queueSymbol, I64Expr sharedBytes, Evidence evidence) {
        public Candidate {
            requireName(kernelSymbol, "kernel");
            orderedArguments = List.copyOf(orderedArguments);
            requireName(deviceSymbol, "device");
            Objects.requireNonNull(geometry, "GPU launch geometry");
            requireName(queueSymbol, "queue");
            Objects.requireNonNull(sharedBytes, "GPU shared bytes");
            if (evidence != Evidence.SOURCE_SHAPE_ONLY_NOT_EXECUTABLE)
                throw new IllegalArgumentException("GPU-LAUNCH-008: missing source-only evidence state");
        }
        /** No source IR candidate can confer compiler/device execution authority. */
        public boolean executable() { return false; }
    }

    private static IllegalArgumentException geometry(String detail) {
        return new IllegalArgumentException("GPU-LAUNCH-003: " + detail);
    }

    private static void requireName(String name, String field) {
        if (name == null || name.isBlank() || name.length() > 256)
            throw new IllegalArgumentException("GPU-LAUNCH-001: invalid " + field + " symbol");
    }

    private static String staticPath(Ast.Expr expr, int depth, String label) {
        if (depth > 8)
            throw new IllegalArgumentException("GPU-LAUNCH-001: qualified " + label + " too deep");
        if (expr instanceof Ast.NameExpr name) {
            requireName(name.name(), label);
            return name.name();
        }
        if (expr instanceof Ast.MemberExpr member) {
            requireName(member.member(), label);
            return staticPath(member.receiver(), depth + 1, label) + "." + member.member();
        }
        throw new IllegalArgumentException(
                "GPU-LAUNCH-001: " + label + " must be a statically named symbol");
    }

    private static I64Expr integer(Ast.Expr expr, int depth) {
        if (depth > 16) throw geometry("geometry expression too deep");
        if (expr instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long n) return new Constant(n);
        if (expr instanceof Ast.NameExpr name)
            return new Symbol(name.name());
        if (expr instanceof Ast.UnaryExpr unary) {
            if (unary.operator().equals("+"))
                return integer(unary.operand(), depth + 1);
            if (unary.operator().equals("-")
                    && unary.operand() instanceof Ast.LiteralExpr lit
                    && lit.value() instanceof Long n) {
                if (n == Long.MIN_VALUE) throw geometry("signed integer overflow");
                return new Constant(-n);
            }
            throw geometry("unsupported unary integer geometry");
        }
        if (expr instanceof Ast.CallExpr call) {
            if (!(call.callee() instanceof Ast.NameExpr name)
                    || !name.name().equals("ceil_div")
                    || call.typeArgumentsPresent()
                    || call.origin() != Ast.CallOrigin.DIRECT
                    || call.arguments().size() != 2) {
                throw geometry("only checked ceil_div calls are admitted in geometry");
            }
            I64Expr a = integer(call.arguments().get(0), depth + 1);
            I64Expr b = integer(call.arguments().get(1), depth + 1);
            if (b instanceof Constant c && c.value() == 0)
                throw geometry("ceil_div divisor must be positive");
            if (a instanceof Constant x && b instanceof Constant y) {
                long result = x.value() / y.value()
                        + (x.value() % y.value() == 0 ? 0 : 1);
                return new Constant(result);
            }
            return new CeilDiv(a, b);
        }
        if (expr instanceof Ast.BinaryExpr bin) {
            String op = bin.operator();
            if (!op.equals("+") && !op.equals("-") && !op.equals("*"))
                throw geometry("unsupported integer geometry operator");
            I64Expr left = integer(bin.left(), depth + 1);
            I64Expr right = integer(bin.right(), depth + 1);
            if (left instanceof Constant a && right instanceof Constant b) {
                try {
                    long result = switch (op) {
                        case "+" -> Math.addExact(a.value(), b.value());
                        case "-" -> Math.subtractExact(a.value(), b.value());
                        default -> Math.multiplyExact(a.value(), b.value());
                    };
                    return new Constant(result);
                } catch (ArithmeticException overflow) {
                    throw geometry("constant integer geometry overflows signed 64-bit");
                }
            }
            return new Arithmetic(op, left, right);
        }
        if (expr instanceof Ast.AwaitExpr || expr instanceof Ast.TaskExpr
                || expr instanceof Ast.RuntimeCallExpr || expr instanceof Ast.AssignExpr
                || expr instanceof Ast.GpuLaunchExpr || expr instanceof Ast.ChannelOpExpr)
            throw new IllegalArgumentException(
                    "GPU-LAUNCH-006: effectful or suspending GPU launch geometry");
        throw geometry("unsupported non-integer or effectful geometry expression");
    }

    public static Candidate lower(Ast.GpuLaunchExpr launch) {
        Objects.requireNonNull(launch, "GPU launch AST");
        if (launch.arguments().size() > 64)
            throw new IllegalArgumentException("GPU-LAUNCH-002: too many kernel arguments");
        // These are only lexical references. Type, ABI, lifetime and queue
        // authority must be resolved in distinct compiler/provider proofs.
        String kernel = staticPath(launch.kernel(), 0, "kernel");
        String device = staticPath(launch.device(), 0, "device");
        String queue = staticPath(launch.queue(), 0, "queue");

        Mode mode;
        List<Ast.Expr> extent;
        List<Ast.Expr> block;
        if (launch.geometry() instanceof Ast.GpuGridGeometry physical) {
            mode = Mode.PHYSICAL_GRID_BLOCK;
            extent = physical.grid();
            block = physical.block();
        } else if (launch.geometry() instanceof Ast.GpuOverGeometry logical) {
            mode = Mode.LOGICAL_OVER_GROUP;
            extent = logical.over();
            block = logical.group();
        } else {
            throw geometry("unrecognized GPU launch geometry");
        }
        List<I64Expr> parsedExtent = extent.stream().map(x -> integer(x, 0)).toList();
        List<I64Expr> parsedBlock = block.stream().map(x -> integer(x, 0)).toList();
        Geometry geometry = new Geometry(mode, parsedExtent, parsedBlock);
        I64Expr shared = integer(launch.sharedBytes(), 0);
        return new Candidate(kernel, launch.arguments(), device, geometry, queue,
                shared, Evidence.SOURCE_SHAPE_ONLY_NOT_EXECUTABLE);
    }
}
