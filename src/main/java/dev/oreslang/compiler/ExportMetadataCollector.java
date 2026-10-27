package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic source-interface metadata derived from checked export bindings.
 *
 * <p>The digest is a conservative source ABI/signature fingerprint. It never
 * authorizes or emits a JVM host member, Native Image symbol, ELF/Mach-O/COFF
 * dynamic symbol, Wasm export, CLR export, or ESM export.</p>
 */
public final class ExportMetadataCollector {
    private ExportMetadataCollector() { }

    public record Entry(
            String ownerNamespaceId,
            String localSymbolId,
            Ast.ExportKind localKind,
            String externalName,
            Ast.ExportScope scope,
            String signatureAbiSha256,
            ExportResolver.BindingOrigin origin) {
        public Entry {
            Objects.requireNonNull(ownerNamespaceId);
            Objects.requireNonNull(localSymbolId);
            Objects.requireNonNull(localKind);
            Objects.requireNonNull(externalName);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(signatureAbiSha256);
            Objects.requireNonNull(origin);
        }
    }

    public static List<Entry> collect(Ast.Program program) {
        return collect(program, null);
    }

    /**
     * Incremental dependency invalidation only: exported constants currently
     * have no portable, canonical compile-time value encoding. Rather than
     * disabling incremental compilation for existing public constants, make
     * any change in their complete source unit conservatively change its ABI.
     * This MUST NOT be used for emitted source-interface metadata: the strict
     * collect(program) method continues to fail closed for constants.
     */
    public static List<Entry> collectForIncrementalAbi(
            Ast.Program program, String completeSource) {
        return collect(program, Objects.requireNonNull(completeSource, "complete source"));
    }

    private static List<Entry> collect(Ast.Program program, String completeSource) {
        ExportResolver.Result resolved = ExportResolver.resolve(program);
        Map<String, Ast.ModuleDecl> modules = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) modules.put(module.name(), module);

        List<Entry> result = new ArrayList<>();
        for (Map.Entry<String, List<ExportResolver.ExportBinding>> namespace :
                resolved.namespaces().entrySet()) {
            for (ExportResolver.ExportBinding binding : namespace.getValue()) {
                result.add(new Entry(
                        binding.ownerNamespaceId(),
                        binding.localSymbolId(),
                        binding.localKind(),
                        binding.externalName(),
                        binding.scope(),
                        completeSource == null
                                ? digestFamily(program, modules, binding)
                                : digestFamilyForIncrementalAbi(
                                        program, modules, binding, completeSource),
                        binding.origin()));
            }
        }

        result.sort(Comparator
                .comparing(Entry::ownerNamespaceId, ExportMetadataCollector::compareUtf8)
                .thenComparing(Entry::externalName, ExportMetadataCollector::compareUtf8)
                .thenComparing(e -> e.localKind().name())
                .thenComparing(Entry::localSymbolId, ExportMetadataCollector::compareUtf8));
        return List.copyOf(result);
    }

    private static String digestFamilyForIncrementalAbi(
            Ast.Program program,
            Map<String, Ast.ModuleDecl> modules,
            ExportResolver.ExportBinding binding,
            String completeSource) {
        try {
            return digestFamily(program, modules, binding);
        } catch (IllegalArgumentException unencoded) {
            String message = unencoded.getMessage();
            if (message == null
                    || !message.contains("[EXPORT_CONST_ABI_VALUE_UNENCODED]")) {
                throw unencoded;
            }
            // A const can appear as the exported binding itself or nested
            // inside another exported signature (for example, a public const
            // class field). Until canonical compile-time values exist, use
            // the complete source unit as a conservative incremental ABI
            // fingerprint. Strict emitted export metadata still fails closed.
            return sha256("conservative-incremental-const-source-v1\\n" + completeSource);
        }
    }

    private static String digestFamily(
            Ast.Program program,
            Map<String, Ast.ModuleDecl> modules,
            ExportResolver.ExportBinding binding) {
        StringBuilder signature = new StringBuilder();
        field(signature, "oreslang-source-export-signature-v1");
        field(signature, binding.localSymbolId());
        field(signature, binding.localKind().name());

        if (binding.localKind() == Ast.ExportKind.MODULE) {
            Ast.ModuleDecl module = modules.get(binding.localName());
            if (module == null) {
                throw new IllegalArgumentException(
                        "[EXPORT_METADATA_MISSING_LOCAL] exported module '"
                                + binding.localName() + "' was not found");
            }
            appendTypes(signature, module.contracts());
            appendAnnotations(signature, module.annotations());
            return sha256(signature.toString());
        }

        Ast.ModuleDecl owner = modules.get(binding.ownerNamespaceId());
        if (owner == null && binding.ownerNamespaceId().equals(Parser.ROOT_MODULE)) {
            owner = modules.get(Parser.ROOT_MODULE);
        }
        if (owner == null) {
            throw new IllegalArgumentException(
                    "[EXPORT_METADATA_MISSING_NAMESPACE] export namespace '"
                            + binding.ownerNamespaceId() + "' was not found");
        }

        if (binding.localKind() == Ast.ExportKind.CONST) {
            throw new IllegalArgumentException(
                    "[EXPORT_CONST_ABI_VALUE_UNENCODED] exported const metadata requires canonical compile-time value encoding");
        }

        List<String> declarationSignatures = new ArrayList<>();
        for (int i = 0; i < owner.declarations().size(); i++) {
            Ast.Decl declaration = owner.declarations().get(i);
            Ast.DeclarationMetadata meta =
                    metadataAt(program, owner, i, declaration);
            if (!meta.name().equals(binding.localName())
                    || meta.kind() != binding.localKind()) {
                continue;
            }
            declarationSignatures.add(signatureOf(declaration));
        }
        if (declarationSignatures.isEmpty()) {
            throw new IllegalArgumentException(
                    "[EXPORT_METADATA_MISSING_LOCAL] exported local symbol '"
                            + binding.localName() + "' was not found");
        }

        declarationSignatures.sort(ExportMetadataCollector::compareUtf8);
        signature.append(declarationSignatures.size()).append('[');
        for (String item : declarationSignatures) field(signature, item);
        signature.append(']');
        return sha256(signature.toString());
    }

    private static Ast.DeclarationMetadata metadataAt(
            Ast.Program program,
            Ast.ModuleDecl module,
            int index,
            Ast.Decl declaration) {
        if (module.declarationMetadata().size() == module.declarations().size()) {
            return module.declarationMetadata().get(index);
        }
        if (module.name().equals(Parser.ROOT_MODULE)
                && program.declarationMetadata().size() == module.declarations().size()) {
            return program.declarationMetadata().get(index);
        }
        return new Ast.DeclarationMetadata(
                declarationName(declaration),
                inferredKind(declaration),
                declarationVisibility(declaration),
                false);
    }

    private static String signatureOf(Ast.Decl declaration) {
        StringBuilder out = new StringBuilder();
        if (declaration instanceof Ast.FunctionDecl fn) {
            field(out, "function");
            field(out, fn.name());
            field(out, fn.kind().name());
            flags(out, fn.async(), fn.suspends(), fn.generator(), fn.structural(),
                    fn.nonLexical(), fn.trapped());
            field(out, fn.actorKind().name());
            appendGenericSpecs(out, fn.genericSpecs());
            appendParams(out, fn.parameters());
            appendType(out, fn.returnType());
            appendEffects(out, fn.effects());
            appendAnnotations(out, fn.annotations());
            return out.toString();
        }

        if (declaration instanceof Ast.ClassDecl klass) {
            field(out, "class");
            field(out, klass.name());
            flags(out, klass.isAbstract());
            field(out, klass.actorKind().name());
            field(out, klass.actorConcurrency().transport().name());
            out.append(klass.actorConcurrency().requestConcurrency()).append(';');
            out.append(klass.actorConcurrency().messageConcurrency()).append(';');
            appendStrings(out, klass.genericParameters());
            appendTypes(out, klass.parents());
            appendTypes(out, klass.interfaces());

            out.append(klass.fields().size()).append('[');
            for (Ast.FieldDecl field : klass.fields()) field(out, signatureOf(field));
            out.append(']');

            if (klass.constructor() == null) {
                field(out, "no-constructor");
            } else {
                field(out, klass.constructor().visibility().name());
                appendParams(out, klass.constructor().parameters());
                appendAnnotations(out, klass.constructor().annotations());
            }

            List<String> methods = new ArrayList<>();
            for (Ast.MethodDecl method : klass.methods()) {
                StringBuilder part = new StringBuilder();
                field(part, method.name());
                field(part, method.visibility().name());
                flags(part, method.isStatic(), method.isAbstract(), method.async(),
                        method.suspends(), method.structural());
                appendOptionalType(part, method.explicitReceiverType());
                appendStrings(part, method.genericParameters());
                appendParams(part, method.parameters());
                appendType(part, method.returnType());
                appendEffects(part, method.effects());
                appendAnnotations(part, method.annotations());
                methods.add(part.toString());
            }
            methods.sort(ExportMetadataCollector::compareUtf8);
            appendStrings(out, methods);
            return out.toString();
        }

        if (declaration instanceof Ast.InterfaceDecl iface) {
            field(out, "interface");
            field(out, iface.name());
            flags(out, iface.moduleContract());
            appendStrings(out, iface.genericParameters());
            appendTypes(out, iface.parents());

            List<String> members = new ArrayList<>();
            for (Ast.InterfaceMember member : iface.members()) {
                StringBuilder part = new StringBuilder();
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    field(part, "function");
                    field(part, fn.name());
                    flags(part, fn.structural());
                    appendStrings(part, fn.genericParameters());
                    appendParams(part, fn.parameters());
                    appendType(part, fn.returnType());
                    appendEffects(part, fn.effects());
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    field(part, "field");
                    field(part, field.name());
                    field(part, field.bindingKind().name());
                    flags(part, field.mutableReferent());
                    appendType(part, field.type());
                }
                members.add(part.toString());
            }
            members.sort(ExportMetadataCollector::compareUtf8);
            appendStrings(out, members);
            return out.toString();
        }

        if (declaration instanceof Ast.FieldDecl field) {
            if (field.bindingKind() == Ast.BindingKind.CONST
                    && field.visibility() == Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException(
                        "[EXPORT_CONST_ABI_VALUE_UNENCODED] public const member metadata requires canonical compile-time value encoding");
            }
            field(out, "binding");
            field(out, field.name());
            field(out, field.visibility().name());
            field(out, field.bindingKind().name());
            flags(out, field.mutableReferent());
            appendType(out, field.type());
            appendAnnotations(out, field.annotations());
            return out.toString();
        }

        if (declaration instanceof Ast.TypeAliasDecl alias) {
            field(out, "type");
            field(out, alias.name());
            appendStrings(out, alias.genericParameters());
            appendType(out, alias.target());
            return out.toString();
        }

        throw new IllegalArgumentException(
                "[EXPORT_METADATA_UNKNOWN_DECLARATION] unsupported declaration node");
    }

    private static String declarationName(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) return fn.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        throw new IllegalArgumentException("unknown declaration node");
    }

    private static Ast.ExportKind inferredKind(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            return fn.kind() == Ast.CallableKind.FNC
                    ? Ast.ExportKind.FNC : Ast.ExportKind.ROUTINE;
        }
        if (declaration instanceof Ast.ClassDecl klass) {
            return klass.actorKind() == Ast.ActorKind.NONE
                    ? Ast.ExportKind.CLASS : Ast.ExportKind.ACTOR;
        }
        if (declaration instanceof Ast.InterfaceDecl iface) {
            return iface.moduleContract()
                    ? Ast.ExportKind.CONTRACT : Ast.ExportKind.INTERFACE;
        }
        if (declaration instanceof Ast.TypeAliasDecl) return Ast.ExportKind.TYPE;
        if (declaration instanceof Ast.FieldDecl field) {
            return switch (field.bindingKind()) {
                case CONST -> Ast.ExportKind.CONST;
                case VAL -> Ast.ExportKind.VAL;
                case LET -> Ast.ExportKind.LET;
            };
        }
        throw new IllegalArgumentException("unknown declaration node");
    }

    private static Ast.Visibility declarationVisibility(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) return fn.visibility();
        if (declaration instanceof Ast.ClassDecl klass) return klass.visibility();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.visibility();
        if (declaration instanceof Ast.FieldDecl field) return field.visibility();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.visibility();
        throw new IllegalArgumentException("unknown declaration node");
    }

    private static void appendGenericSpecs(
            StringBuilder out, List<Ast.GenericParameter> generics) {
        out.append(generics.size()).append('[');
        for (Ast.GenericParameter generic : generics) {
            field(out, generic.name());
            appendOptionalType(out, generic.defaultType());
        }
        out.append(']');
    }

    private static void appendParams(StringBuilder out, List<Ast.Param> params) {
        out.append(params.size()).append('[');
        for (Ast.Param param : params) {
            field(out, param.name());
            flags(out, param.structural(), param.mutable());
            appendType(out, param.type());
        }
        out.append(']');
    }

    private static void appendEffects(StringBuilder out, Ast.EffectSpec effects) {
        appendTypes(out, effects.throwsTypes());
        appendTypes(out, effects.raisesTypes());
        appendTypes(out, effects.panicsTypes());
    }

    private static void appendAnnotations(
            StringBuilder out, List<Ast.Annotation> annotations) {
        List<String> parts = new ArrayList<>();
        for (Ast.Annotation annotation : annotations) {
            StringBuilder part = new StringBuilder();
            field(part, annotation.name());
            appendTypes(part, annotation.arguments());
            parts.add(part.toString());
        }
        parts.sort(ExportMetadataCollector::compareUtf8);
        appendStrings(out, parts);
    }

    private static void appendTypes(StringBuilder out, List<Ast.TypeRef> types) {
        out.append(types.size()).append('[');
        for (Ast.TypeRef type : types) appendType(out, type);
        out.append(']');
    }

    private static void appendOptionalType(StringBuilder out, Ast.TypeRef type) {
        if (type == null) {
            out.append('0');
        } else {
            out.append('1');
            appendType(out, type);
        }
    }

    private static void appendType(StringBuilder out, Ast.TypeRef type) {
        if (type == null) {
            field(out, "$missing$");
            return;
        }
        field(out, type.name());
        flags(out, type.inferArguments());
        appendTypes(out, type.arguments());
    }

    private static void appendStrings(StringBuilder out, List<String> values) {
        out.append(values.size()).append('[');
        for (String value : values) field(out, value);
        out.append(']');
    }

    private static void field(StringBuilder out, String value) {
        Objects.requireNonNull(value);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.append(bytes.length).append(':').append(value);
    }

    private static void flags(StringBuilder out, boolean... values) {
        for (boolean value : values) out.append(value ? '1' : '0');
        out.append(';');
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String source) {
        try {
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
