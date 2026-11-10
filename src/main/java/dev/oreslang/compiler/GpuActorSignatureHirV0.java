package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Typed, immutable SOURCE SIGNATURE descriptor for a GPU actor.
 *
 * This is deliberately not executable MIR. A method body remains opaque
 * provenance-bearing source tokens; no borrow, effect, device residency,
 * supervisor, physical GPU, or fence proof is inferred from this descriptor.
 * Calling describe() does not authorize kernel dispatch or actor spawning.
 */
public final class GpuActorSignatureHirV0 {
    private GpuActorSignatureHirV0() { }

    public enum DeviceKind {
        VOID, BOOL, I32, I64, U32, U64, F32, F64, F32_DEVICE_BUFFER
    }

    public record Field(String name, DeviceKind kind) {
        public Field {
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
            if (kind == DeviceKind.VOID) {
                throw new IllegalArgumentException("GPU_ACTOR_VOID_STATE");
            }
        }
    }

    public record Argument(String name, DeviceKind kind) {
        public Argument {
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
            if (kind == DeviceKind.VOID) {
                throw new IllegalArgumentException("GPU_ACTOR_VOID_ARGUMENT");
            }
        }
    }

    public record Method(String name, List<Argument> arguments, DeviceKind result,
                         int sourceTokenCount) {
        public Method {
            Objects.requireNonNull(name);
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(result);
            if (sourceTokenCount < 1 || sourceTokenCount > 1024) {
                throw new IllegalArgumentException("GPU_ACTOR_BODY_TOKEN_BUDGET");
            }
        }
    }

    public record Descriptor(String name, Ast.Visibility visibility,
                             List<Field> state, List<Method> methods,
                             String admissionStage,
                             boolean bodyEffectsVerified,
                             boolean ownedDeviceBuffersVerified,
                             boolean executable,
                             boolean physicalGpuVerified) {
        public Descriptor {
            Objects.requireNonNull(name);
            Objects.requireNonNull(visibility);
            state = List.copyOf(state);
            methods = List.copyOf(methods);
            if (!"TYPED_SIGNATURE_ONLY".equals(admissionStage)
                    || bodyEffectsVerified || ownedDeviceBuffersVerified
                    || executable || physicalGpuVerified) {
                throw new IllegalArgumentException("GPU_ACTOR_UNPROVEN_PROMOTION");
            }
        }
    }

    private static DeviceKind kind(Ast.TypeRef type, boolean allowVoid) {
        Objects.requireNonNull(type);
        if (type.inferArguments()) {
            throw new IllegalArgumentException("GPU_ACTOR_INFERRED_TYPE_UNSUPPORTED");
        }
        if (type.arguments().isEmpty()) {
            DeviceKind result = switch (type.name()) {
                case "void" -> DeviceKind.VOID;
                case "bool" -> DeviceKind.BOOL;
                case "int", "i64" -> DeviceKind.I64;
                case "i32" -> DeviceKind.I32;
                case "u32" -> DeviceKind.U32;
                case "u64" -> DeviceKind.U64;
                case "f32" -> DeviceKind.F32;
                case "f64", "float" -> DeviceKind.F64;
                default -> throw new IllegalArgumentException(
                    "GPU_ACTOR_DEVICE_TYPE_UNSUPPORTED: " + type.name());
            };
            if (result == DeviceKind.VOID && !allowVoid) {
                throw new IllegalArgumentException("GPU_ACTOR_VOID_VALUE");
            }
            return result;
        }
        // Shape metadata only. Runtime ownership, view access and lifetime
        // require a future proof; a type name is never a device loan.
        if (type.name().equals("GpuBuffer")
                && type.arguments().size() == 1
                && !type.arguments().getFirst().inferArguments()
                && type.arguments().getFirst().arguments().isEmpty()
                && type.arguments().getFirst().name().equals("f32")) {
            return DeviceKind.F32_DEVICE_BUFFER;
        }
        throw new IllegalArgumentException("GPU_ACTOR_DEVICE_TYPE_UNSUPPORTED: " + type.name());
    }

    public static Descriptor describe(Ast.GpuActorDecl actor) {
        Objects.requireNonNull(actor);
        if (actor.name().isBlank() || actor.name().length() > 1024) {
            throw new IllegalArgumentException("GPU_ACTOR_NAME_INVALID");
        }
        if (actor.stateFields().size() > 128 || actor.methods().size() > 64) {
            throw new IllegalArgumentException("GPU_ACTOR_SCHEMA_BUDGET");
        }

        List<Field> state = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Ast.ProtectedStateField field : actor.stateFields()) {
            if (!names.add(field.name())) {
                throw new IllegalArgumentException("GPU_ACTOR_DUPLICATE_STATE_FIELD");
            }
            state.add(new Field(field.name(), kind(field.type(), false)));
            // The initializer AST is retained in the source declaration;
            // this descriptor does NOT evaluate/validate initializer effects.
        }

        List<Method> methods = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        for (Ast.ProtectedMethod method : actor.methods()) {
            if (!signatures.add(method.name() + "/" + method.parameters().size())) {
                throw new IllegalArgumentException("GPU_ACTOR_DUPLICATE_METHOD_ARITY");
            }
            if (method.parameters().size() > 32) {
                throw new IllegalArgumentException("GPU_ACTOR_ARGUMENT_BUDGET");
            }
            List<Argument> arguments = new ArrayList<>();
            Set<String> paramNames = new HashSet<>();
            for (Ast.Param param : method.parameters()) {
                if (!paramNames.add(param.name())) {
                    throw new IllegalArgumentException("GPU_ACTOR_DUPLICATE_PARAMETER");
                }
                arguments.add(new Argument(param.name(), kind(param.type(), false)));
            }
            if (method.bodyTokens().isEmpty()
                    || method.bodyTokens().size() != method.bodyTokenKinds().size()
                    || method.bodyTokens().size() > 1024
                    || method.bodyTokenKinds().stream().anyMatch(t -> t.equals("UNVERIFIED"))) {
                throw new IllegalArgumentException("GPU_ACTOR_UNVERIFIED_BODY_PROVENANCE");
            }
            methods.add(new Method(method.name(), arguments,
                kind(method.returnType(), true), method.bodyTokens().size()));
        }
        return new Descriptor(actor.name(), actor.visibility(), state, methods,
                "TYPED_SIGNATURE_ONLY", false, false, false, false);
    }
}
