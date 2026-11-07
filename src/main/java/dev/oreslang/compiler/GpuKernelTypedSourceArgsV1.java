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
