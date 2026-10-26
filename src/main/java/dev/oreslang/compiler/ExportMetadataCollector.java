package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

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
 * Deterministic source-export metadata derived from resolved ExportBindings.
 *
 * This is source-language interface metadata only. It does not create or
 * authorize JVM interop entry points, Native Image exports, ELF/Mach-O/COFF
 * dynamic symbols, Wasm exports, CLR exports, or ES-module exports.
 */
public final class ExportMetadataCollector {
    private ExportMetadataCollector() { }

    public static final int VERSION = 1;

    public record Entry(
            int version,
            String ownerNamespaceId,
            String localSymbolId,
            Ast.ExportKind kind,
            String localName,
            String externalName,
            Ast.ExportScope scope,
            Ast.ExportOrigin origin,
            String signatureSha256) {
        public Entry {
            if (version != VERSION) throw new IllegalArgumentException("unsupported export metadata version");
            Objects.requireNonNull(ownerNamespaceId);
            Objects.requireNonNull(localSymbolId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(localName);
            Objects.requireNonNull(externalName);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(origin);
            if (signatureSha256 == null || !signatureSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid export signature digest");
            }
        }
    }

    public static List<Entry> collect(Ast.Program checkedProgram) {
        Objects.requireNonNull(checkedProgram, "checked program");
        ExportResolver.Resolution resolved = ExportResolver.resolve(checkedProgram);
        Map<String, Ast.ModuleDecl> modulesByOwner = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : checkedProgram.modules()) {
            modulesByOwner.put(ExportResolver.namespaceId(checkedProgram, module), module);
        }

        ArrayList<Entry> result = new ArrayList<>();
        for (Map.Entry<String, Ast.ModuleDecl> namespace : modulesByOwner.entrySet()) {
            String owner = namespace.getKey();
            Ast.ModuleDecl module = namespace.getValue();
            for (Ast.ExportBinding binding : resolved.bindingsFor(owner)) {
                String canonical = canonicalSignature(checkedProgram, module, binding);
                result.add(new Entry(
                        VERSION,
                        binding.ownerNamespaceId(),
                        binding.localSymbolId(),
                        binding.localKind(),
                        binding.localName(),
                        binding.externalName(),
                        binding.scope(),
                        binding.origin(),
                        sha256(canonical)));
            }
        }

        result.sort(Comparator
                .comparing(Entry::ownerNamespaceId, ExportMetadataCollector::compareUtf8)
                .thenComparing(Entry::externalName, ExportMetadataCollector::compareUtf8)
                .thenComparing(entry -> entry.kind().name())
                .thenComparing(Entry::localSymbolId, ExportMetadataCollector::compareUtf8)
                .thenComparing(entry -> entry.scope().name()));
        return List.copyOf(result);
    }

    private static String canonicalSignature(
            Ast.Program program,
            Ast.ModuleDecl ownerModule,
            Ast.ExportBinding binding) {
        StringBuilder out = new StringBuilder();
        field(out, "oreslang.export.signature.v1");
        field(out, binding.localKind().name());

        if (binding.localKind() == Ast.ExportKind.MODULE) {
            Ast.ModuleDecl target = program.modules().stream()
                    .filter(module -> module.name().equals(binding.localName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "resolved module export lost target '" + binding.localName() + "'"));
            types(out, target.contracts());
            annotations(out, target.annotations());
            return out.toString();
        }

        List<Ast.Decl> declarations = ownerModule.declarations().stream()
                .filter(declaration -> binding.localName().equals(declarationName(declaration)))
                .filter(declaration -> binding.localKind() == declarationKind(declaration))
                .toList();
        if (declarations.isEmpty()) {
            throw new IllegalArgumentException(
                    "resolved export lost declaration '" + binding.localName() + "'");
        }

        ArrayList<String> family = new ArrayList<>();
        for (Ast.Decl declaration : declarations) {
            family.add(canonicalDeclaration(declaration));
        }
        family.sort(ExportMetadataCollector::compareUtf8);
        out.append(family.size()).append('[');
        for (String signature : family) field(out, signature);
        out.append(']');
        return out.toString();
    }

    private static String canonicalDeclaration(Ast.Decl declaration) {
        StringBuilder out = new StringBuilder();

        if (declaration instanceof Ast.FunctionDecl fn) {
            field(out, fn.kind().name());
            field(out, fn.actorKind().name());
            flags(out, fn.async(), fn.suspends(), fn.generator(), fn.structural(),
                    fn.nonLexical(), fn.trapped());
            genericSpecs(out, fn.genericParameters(), fn.genericSpecs());
            params(out, fn.parameters());
            type(out, fn.returnType());
            effects(out, fn.effects());
            annotations(out, fn.annotations());
            return out.toString();
        }

        if (declaration instanceof Ast.TypeAliasDecl alias) {
            strings(out, alias.genericParameters());
            type(out, alias.target());
            return out.toString();
        }

        if (declaration instanceof Ast.InterfaceDecl iface) {
            field(out, iface.declarationKind().name());
            flags(out, iface.moduleContract());
            strings(out, iface.genericParameters());
            types(out, iface.parents());
            ArrayList<String> members = new ArrayList<>();
            for (Ast.InterfaceMember member : iface.members()) {
                StringBuilder part = new StringBuilder();
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    field(part, "fnc");
                    field(part, fn.name());
                    flags(part, fn.structural());
                    strings(part, fn.genericParameters());
                    params(part, fn.parameters());
                    type(part, fn.returnType());
                    effects(part, fn.effects());
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    field(part, "field");
                    field(part, field.name());
                    field(part, field.bindingKind().name());
                    flags(part, field.mutableReferent());
                    type(part, field.type());
                }
                members.add(part.toString());
            }
            stringsSorted(out, members);
            return out.toString();
        }

        if (declaration instanceof Ast.ClassDecl klass) {
            field(out, klass.actorKind().name());
            flags(out, klass.isAbstract());
            strings(out, klass.genericParameters());
            types(out, klass.parents());
            types(out, klass.interfaces());

            Ast.ConstructorDecl ctor = klass.constructor();
            if (ctor == null) {
                field(out, "no-explicit-constructor");
            } else {
                field(out, ctor.visibility().name());
                if (ctor.visibility() == Ast.Visibility.PUBLIC) {
                    params(out, ctor.parameters());
                    annotations(out, ctor.annotations());
                }
            }

            ArrayList<String> members = new ArrayList<>();
            for (Ast.FieldDecl member : klass.fields()) {
                if (member.visibility() != Ast.Visibility.PUBLIC) continue;
                StringBuilder part = new StringBuilder();
                field(part, "field");
                field(part, member.name());
                field(part, member.bindingKind().name());
                flags(part, member.mutableReferent());
                type(part, member.type());
                annotations(part, member.annotations());
                members.add(part.toString());
            }
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.visibility() != Ast.Visibility.PUBLIC) continue;
                StringBuilder part = new StringBuilder();
                field(part, "method");
                field(part, method.name());
                flags(part, method.isStatic(), method.isAbstract(), method.async(),
                        method.suspends(), method.structural());
                optionalType(part, method.explicitReceiverType());
                strings(part, method.genericParameters());
                params(part, method.parameters());
                type(part, method.returnType());
                effects(part, method.effects());
                annotations(part, method.annotations());
                members.add(part.toString());
            }
            stringsSorted(out, members);
            return out.toString();
        }

        if (declaration instanceof Ast.FieldDecl field) {
            if (field.bindingKind() == Ast.BindingKind.CONST) {
                throw new IllegalArgumentException(
                        "EXPORT_CONST_ABI_VALUE_UNENCODED: exported const metadata requires canonical compile-time value encoding");
            }
            field(out, field.bindingKind().name());
            flags(out, field.mutableReferent());
            type(out, field.type());
            annotations(out, field.annotations());
            return out.toString();
        }

        throw new IllegalArgumentException(
                "unsupported exported declaration " + declaration.getClass().getSimpleName());
    }

    private static String declarationName(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) return fn.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        return null;
    }

    private static Ast.ExportKind declarationKind(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            return fn.kind() == Ast.CallableKind.ROUTINE
                    ? Ast.ExportKind.ROUTINE : Ast.ExportKind.FNC;
        }
        if (declaration instanceof Ast.ClassDecl klass) {
            return klass.actorKind() == Ast.ActorKind.NONE
                    ? Ast.ExportKind.CLASS : Ast.ExportKind.ACTOR;
        }
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.declarationKind();
        if (declaration instanceof Ast.TypeAliasDecl) return Ast.ExportKind.TYPE;
        if (declaration instanceof Ast.FieldDecl field) {
            return switch (field.bindingKind()) {
                case CONST -> Ast.ExportKind.CONST;
                case VAL -> Ast.ExportKind.VAL;
                case LET -> Ast.ExportKind.LET;
            };
        }
        return null;
    }

    private static void params(StringBuilder out, List<Ast.Param> params) {
        out.append(params.size()).append('[');
        for (Ast.Param param : params) {
            field(out, param.name());
            flags(out, param.mutable(), param.structural());
            type(out, param.type());
        }
        out.append(']');
    }

    private static void effects(StringBuilder out, Ast.EffectSpec effects) {
        types(out, effects.throwsTypes());
        types(out, effects.raisesTypes());
        types(out, effects.panicsTypes());
    }

    private static void annotations(StringBuilder out, List<Ast.Annotation> annotations) {
        out.append(annotations.size()).append('[');
        for (Ast.Annotation annotation : annotations) {
            field(out, annotation.name());
            types(out, annotation.arguments());
        }
        out.append(']');
    }

    private static void types(StringBuilder out, List<Ast.TypeRef> types) {
        out.append(types.size()).append('[');
        for (Ast.TypeRef value : types) type(out, value);
        out.append(']');
    }

    private static void optionalType(StringBuilder out, Ast.TypeRef type) {
        if (type == null) {
            field(out, "<none>");
        } else {
            type(out, type);
        }
    }

    private static void type(StringBuilder out, Ast.TypeRef type) {
        if (type == null || type.name() == null || type.name().isBlank()
                || type.name().equals("$infer$") || type.name().equals("Unknown")
                || type.inferArguments()) {
            throw new IllegalArgumentException(
                    "exported declaration has unresolved or inferred type");
        }
        field(out, type.name());
        out.append(type.arguments().size()).append('(');
        for (Ast.TypeRef child : type.arguments()) type(out, child);
        out.append(')');
    }

    private static void genericSpecs(
            StringBuilder out,
            List<String> names,
            List<Ast.GenericParameter> specs) {
        if (names.size() != specs.size()) {
            throw new IllegalArgumentException("generic export signature lost parameter specifications");
        }
        out.append(names.size()).append('[');
        for (int i = 0; i < names.size(); i++) {
            field(out, names.get(i));
            optionalType(out, specs.get(i).defaultType());
        }
        out.append(']');
    }

    private static void strings(StringBuilder out, List<String> values) {
        out.append(values.size()).append('[');
        for (String value : values) field(out, value);
        out.append(']');
    }

    private static void stringsSorted(StringBuilder out, List<String> values) {
        ArrayList<String> copy = new ArrayList<>(values);
        copy.sort(ExportMetadataCollector::compareUtf8);
        strings(out, copy);
    }

    private static void flags(StringBuilder out, boolean... values) {
        out.append(values.length).append('[');
        for (boolean value : values) out.append(value ? '1' : '0');
        out.append(']');
    }

    private static void field(StringBuilder out, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.append(utf8.length).append(':').append(value);
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
