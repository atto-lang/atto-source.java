package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceHirV1.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Conservative source-declaration argument typing for a G1e candidate.
 *
 * This is NOT a compiler-issued typed binding, a borrow checker, or a
 * capability. The source-only preflight reads explicitly declared ordinary
 * host function parameters; lexical locals, captures and inferred aliases
 * must await real TypeChecker symbol-resolution/ownership integration.
 *
 * In particular, TypeRef and the Link/Plan records are user-constructible
 * source descriptions. Nothing returned here permits GPU code generation,
 * trusted device allocation, execution or ownership transfer.
 */
public final class GpuKernelTypedSourceArgsV1 {
    private GpuKernelTypedSourceArgsV1() { }

    public enum Evidence { DECLARED_HOST_ABI_SHAPE_ONLY_NOT_EXECUTABLE }

    public record Binding(int index, String sourceName, Type actualType,
                          Type requiredType, boolean deviceBuffer) {
        public Binding {
            if (index < 0 || actualType == null || requiredType == null)
                throw reject("invalid typed source binding");
            if (sourceName == null || sourceName.isBlank())
                throw reject("unnamed typed source binding");
        }
    }

    public record Report(String caller, String kernel,
                         List<Binding> bindings, Evidence evidence) {
        public Report {
            if (caller == null || caller.isBlank()
                    || kernel == null || kernel.isBlank()
                    || evidence != Evidence.DECLARED_HOST_ABI_SHAPE_ONLY_NOT_EXECUTABLE)
                throw reject("typed source report cannot grant authority");
            bindings = List.copyOf(bindings);
        }
        public boolean executable() { return false; }
    }

    private static IllegalArgumentException reject(String why) {
        return new IllegalArgumentException("GPU-LAUNCH-002: " + why);
    }

    private static Type sourceType(Ast.TypeRef ref) {
        if (ref == null || ref.isBorrow() || ref.inferArguments())
            throw reject("borrowed, inferred or missing host ABI type");
        if (ref.arguments().isEmpty()) {
            return switch (ref.name()) {
                case "int", "i64" -> Type.I64;
                case "f32" -> Type.F32;
                case "bool" -> Type.BOOL;
                default -> throw reject("unsupported host GPU scalar argument type: " + ref.name());
            };
        }
        if (ref.arguments().size() != 1)
            throw reject("nested host GPU argument type not admitted");
        Ast.TypeRef element = ref.arguments().getFirst();
        if (!"f32".equals(element.name()) || !element.arguments().isEmpty()
                || element.isBorrow() || element.inferArguments())
            throw reject("host GPU buffer must have exact element type f32");
        return switch (ref.name()) {
            case "GpuReadBuffer" -> Type.BUFFER_READ_F32;
            case "GpuWriteBuffer" -> Type.BUFFER_WRITE_F32;
            case "GpuReadWriteBuffer" -> Type.BUFFER_READ_WRITE_F32;
            default -> throw reject("unsupported device buffer view: " + ref.name());
        };
    }

    private static boolean device(Type type) {
        return type == Type.BUFFER_READ_F32
                || type == Type.BUFFER_WRITE_F32
                || type == Type.BUFFER_READ_WRITE_F32;
    }

    private static boolean writes(Type type) {
        return type == Type.BUFFER_WRITE_F32 || type == Type.BUFFER_READ_WRITE_F32;
    }

    /**
     * G1e cannot prove host function SSA, local shadowing, early exit or
     * effects yet. Admit ONLY one direct launch (assignment to a fresh
     * binding or expression statement) and optionally a trailing bare return.
     * Reject every other statement instead of returning a misleading report.
     *
     * This remains declarative source shape, never an execution capability.
     */
    private static void requireMatchingCallSite(Ast.FunctionDecl caller,
                                                GpuKernelSourceLinkV1.Link link) {
        int matched = 0;
        boolean returned = false;
        java.util.HashSet<String> formals = new java.util.HashSet<>();
        for (Ast.Param param : caller.parameters())
            formals.add(param.name());
        for (Ast.Stmt statement : caller.body()) {
            if (returned)
                throw reject("unreachable statement after source return");
            if (statement instanceof Ast.ReturnStmt ret) {
                if (matched != 1 || ret.value() != null)
                    throw reject("GPU source launch needs only an optional trailing bare return");
                returned = true;
                continue;
            }

            Ast.Expr expression;
            if (statement instanceof Ast.BindingStmt binding) {
                if (binding.kind() == Ast.BindingKind.LET
                        || binding.inferredTypeName() != null
                        || binding.name() == null
                        || binding.name().isBlank()
                        || formals.contains(binding.name()))
                    throw reject("mutable, inferred or shadowing launch-result binding");
                expression = binding.initializer();
            } else if (statement instanceof Ast.ExprStmt evaluated) {
                expression = evaluated.expression();
            } else {
                throw reject("unproven host control flow or effect around GPU launch");
            }
            if (!(expression instanceof Ast.GpuLaunchExpr launch))
                throw reject("unproven host statement or local alias around GPU launch");
            if (++matched != 1)
                throw reject("multiple source GPU launches require ownership effect proof");

            var candidate = GpuLaunchSourceHirV1.lower(launch);
            String target = candidate.kernelSymbol();
            if (!target.equals(link.kernel())
                    && !target.equals(link.module() + "." + link.kernel()))
                throw reject("direct GPU launch target differs from source link");
            if (candidate.orderedArguments().size() != link.arguments().size())
                throw reject("direct GPU launch argument count differs from source link");
            for (int i = 0; i < link.arguments().size(); i++) {
                Ast.Expr raw = candidate.orderedArguments().get(i);
                var bound = link.arguments().get(i);
                GpuKernelSourceLinkV1.ArgumentKind kind;
                String text;
                if (raw instanceof Ast.NameExpr name) {
                    kind = GpuKernelSourceLinkV1.ArgumentKind.LEXICAL_NAME_UNTYPED;
                    text = name.name();
                } else if (raw instanceof Ast.LiteralExpr literal
                        && literal.value() instanceof Long n) {
                    kind = GpuKernelSourceLinkV1.ArgumentKind.I64_LITERAL;
                    text = Long.toString(n);
                } else if (raw instanceof Ast.LiteralExpr literal
                        && literal.value() instanceof Boolean value) {
                    kind = GpuKernelSourceLinkV1.ArgumentKind.BOOL_LITERAL;
                    text = value.toString();
                } else {
                    throw reject("unsupported source expression in GPU call-site");
                }
                if (kind != bound.kind() || !text.equals(bound.name()))
                    throw reject("GPU link arguments are not attached to caller source");
            }
        }
        if (matched != 1)
            throw reject("GPU link has no unique direct launch in caller source");
    }

    /**
     * A source-only function signature may prove declaration shape, never
     * runtime value provenance or equality of the value named n to launch over.
     * Resolve no unspecified local/captured symbols: fail closed instead.
     */
    public static Report audit(Ast.FunctionDecl caller,
                               GpuKernelSourceHirV1.Plan kernel,
                               GpuKernelSourceLinkV1.Link link) {
        Objects.requireNonNull(caller, "GPU launch caller source declaration");
        Objects.requireNonNull(kernel, "GPU source kernel plan");
        Objects.requireNonNull(link, "GPU source kernel link");
        if (link.evidence() != GpuKernelSourceLinkV1.Evidence.SOURCE_SIGNATURE_ONLY_NOT_EXECUTABLE
                || kernel.evidence() != GpuKernelSourceHirV1.Evidence.TYPED_SOURCE_ONLY_NOT_EXECUTABLE
                || !kernel.symbol().equals(link.kernel())
                || link.arguments().size() != kernel.parameters().size()
                || kernel.returnType() != Type.VOID)
            throw reject("kernel link and source signature mismatch");
        if (caller.parameters().size() > 64 || link.arguments().size() > 64)
            throw reject("source argument ABI quota exceeded");
        requireMatchingCallSite(caller, link);

        Map<String, Type> hostParams = new HashMap<>();
        for (Ast.Param param : caller.parameters()) {
            if (param.name() == null || param.name().isBlank()
                    || param.name().length() > 256
                    || param.structural() || param.mutable())
                throw reject("mutable, structural or invalid host GPU parameter");
            Type type = sourceType(param.type());
            if (hostParams.putIfAbsent(param.name(), type) != null)
                throw reject("duplicate host parameter symbol: " + param.name());
        }

        Map<String, Type> seenBuffers = new HashMap<>();
        List<Binding> result = new ArrayList<>();
        for (int i = 0; i < link.arguments().size(); i++) {
            var arg = link.arguments().get(i);
            var parameter = kernel.parameters().get(i);
            if (arg.expected() != parameter.type())
                throw reject("source link expected type differs from kernel ABI");
            Type actual;
            String sourceName = arg.name();
            switch (arg.kind()) {
                case LEXICAL_NAME_UNTYPED -> {
                    actual = hostParams.get(sourceName);
                    if (actual == null)
                        throw reject("unresolved lexical GPU argument: " + sourceName);
                }
                case I64_LITERAL -> {
                    try {
                        Long.parseLong(sourceName);
                    } catch (NumberFormatException | NullPointerException badLiteral) {
                        throw reject("invalid i64 literal source argument");
                    }
                    actual = Type.I64;
                }
                case BOOL_LITERAL -> {
                    if (!"true".equals(sourceName) && !"false".equals(sourceName))
                        throw reject("invalid bool literal source argument");
                    actual = Type.BOOL;
                }
                default -> throw reject("unrecognized GPU argument kind");
            }
            if (actual != parameter.type())
                throw reject("host argument type cannot match device kernel parameter "
                        + parameter.name() + " without implicit conversion");
            if (device(actual)) {
                if (arg.kind() != GpuKernelSourceLinkV1.ArgumentKind.LEXICAL_NAME_UNTYPED)
                    throw reject("device buffer must be declared lexical argument");
                Type previous = seenBuffers.putIfAbsent(sourceName, actual);
                if (previous != null && (writes(previous) || writes(actual)))
                    throw reject("writable device buffer alias reuses lexical source name");
            }
            result.add(new Binding(i, sourceName, actual,
                    parameter.type(), device(actual)));
        }
        return new Report(caller.name(), link.kernel(), result,
                Evidence.DECLARED_HOST_ABI_SHAPE_ONLY_NOT_EXECUTABLE);
    }
}
