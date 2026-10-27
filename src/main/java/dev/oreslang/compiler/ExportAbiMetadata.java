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

/**
 * Versioned source-interface metadata for Oreslang exports.
 *
 * This is deliberately NOT a JVM/native/Wasm/CLR dynamic-export table. It is
 * compiler metadata that downstream linkers can authenticate and lower under
 * their own target-specific rules.
 */
public final class ExportAbiMetadata {
    private ExportAbiMetadata() { }

    public static final int VERSION = 1;

    public record Entry(
            String ownerNamespaceId,
            String localSymbolId,
            Ast.ExportKind localKind,
            String externalName,
            Ast.ExportScope scope,
            String signatureDigest,
            ExportResolver.Origin origin) { }

    public record Document(int version, List<Entry> entries) {
        public Document {
            if (version != VERSION) throw new IllegalArgumentException("unsupported export metadata version");
            entries = List.copyOf(entries);
        }
    }

    public static Document collect(Ast.Program checkedProgram) {
        ExportResolver.Result resolved = ExportResolver.resolve(checkedProgram);
        Map<String, String> signatures = signatureDigests(checkedProgram);
        List<Entry> entries = new ArrayList<>();

        for (ExportResolver.ExportBinding binding : resolved.bindings()) {
            String digest = signatures.get(binding.localSymbolId());
            if (digest == null) {
                throw new IllegalArgumentException(
                        "[EXPORT_METADATA_MISSING_SIGNATURE] no source signature for " + binding.localSymbolId());
            }
            entries.add(new Entry(
                    binding.ownerNamespaceId(),
                    binding.localSymbolId(),
                    binding.localKind(),
                    binding.externalName(),
                    binding.scope(),
                    digest,
                    binding.origin()));
        }

        entries.sort(Comparator
                .comparing(Entry::ownerNamespaceId)
                .thenComparing(Entry::externalName)
                .thenComparing(e -> e.localKind().name())
                .thenComparing(Entry::localSymbolId)
                .thenComparing(e -> e.scope().name())
                .thenComparing(Entry::signatureDigest));
        return new Document(VERSION, entries);
    }

    private static Map<String, String> signatureDigests(Ast.Program program) {
        Map<String, List<String>> signatures = new LinkedHashMap<>();

        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                Ast.ExportKind kind = kindOf(declaration);
                String name = nameOf(declaration);
                String id = symbolId(module.name(), kind, name);
                signatures.computeIfAbsent(id, ignored -> new ArrayList<>())
                        .add(signatureOf(declaration));
            }
        }

        Ast.ModuleDecl root = program.modules().stream()
                .filter(m -> m.name().equals(Parser.ROOT_MODULE))
                .findFirst()
                .orElse(null);
        if (root != null) {
            for (Ast.ModuleDecl module : program.modules()) {
                if (module.name().equals(Parser.ROOT_MODULE)) continue;
                String id = symbolId(Parser.ROOT_MODULE, Ast.ExportKind.MODULE, module.name());
                signatures.computeIfAbsent(id, ignored -> new ArrayList<>())
                        .add(signatureOf(module));
            }
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : signatures.entrySet()) {
            List<String> family = new ArrayList<>(entry.getValue());
            family.sort(ExportAbiMetadata::compareUtf8);
            StringBuilder canonical = new StringBuilder();
            field(canonical, "oreslang.export.signature.v1");
            for (String item : family) field(canonical, item);
            result.put(entry.getKey(), sha256(canonical.toString()));
        }
        return Map.copyOf(result);
    }

    private static String symbolId(String owner, Ast.ExportKind kind, String name) {
        return owner + "::" + kind.name().toLowerCase(java.util.Locale.ROOT) + "::" + name;
    }

    private static String signatureOf(Ast.ModuleDecl module) {
        StringBuilder out = new StringBuilder();
        field(out, "module");
        field(out, module.name());
        for (Ast.TypeRef contract : module.contracts()) type(out, contract);
        annotations(out, module.annotations());
        return out.toString();
    }

    private static String signatureOf(Ast.Decl declaration) {
        StringBuilder out = new StringBuilder();
        Ast.ExportKind kind = kindOf(declaration);
        field(out, kind.name());
        field(out, nameOf(declaration));

        if (declaration instanceof Ast.FunctionDecl fn) {
            field(out, fn.kind().name());
            field(out, fn.actorKind().name());
            flags(out, fn.async(), fn.suspends(), fn.generator(), fn.structural(), fn.nonLexical(), fn.trapped());
            genericSpecs(out, fn.genericSpecs());
            params(out, fn.parameters());
            type(out, fn.returnType());
            effects(out, fn.effects());
            annotations(out, fn.annotations());
        } else if (declaration instanceof Ast.ClassDecl cls) {
            field(out, cls.actorKind().name());
            flags(out, cls.isAbstract());
            strings(out, cls.genericParameters());
            types(out, cls.parents());
            types(out, cls.interfaces());
            if (cls.constructor() == null) {
                field(out, "synthesized-constructor");
                field(out, Integer.toString(cls.fields().size()));
                for (Ast.FieldDecl constructorField : cls.fields()) {
                    type(out, constructorField.type());
                }
            } else if (cls.constructor().visibility() == Ast.Visibility.PUBLIC) {
                field(out, "public-constructor");
                params(out, cls.constructor().parameters());
                annotations(out, cls.constructor().annotations());
            }
            for (Ast.FieldDecl field : cls.fields()) {
                if (field.visibility() != Ast.Visibility.PUBLIC) continue;
                field(out, "field");
                field(out, field.name());
                field(out, field.bindingKind().name());
                flags(out, field.mutableReferent());
                type(out, field.type());
                annotations(out, field.annotations());
            }
            for (Ast.MethodDecl method : cls.methods()) {
                if (method.visibility() != Ast.Visibility.PUBLIC) continue;
                field(out, "method");
                field(out, method.name());
                flags(out, method.isStatic(), method.isAbstract(), method.async(), method.suspends(), method.structural());
                field(out, "receiver");
                if (method.explicitReceiverType() == null) field(out, "implicit");
                else type(out, method.explicitReceiverType());
                strings(out, method.genericParameters());
                params(out, method.parameters());
                type(out, method.returnType());
                effects(out, method.effects());
                annotations(out, method.annotations());
            }
        } else if (declaration instanceof Ast.InterfaceDecl iface) {
            field(out, iface.flavor().name());
            strings(out, iface.genericParameters());
            types(out, iface.parents());
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    field(out, "fnc");
                    field(out, fn.name());
                    strings(out, fn.genericParameters());
                    params(out, fn.parameters());
                    type(out, fn.returnType());
                    effects(out, fn.effects());
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    field(out, "field");
                    field(out, field.name());
                    type(out, field.type());
                }
            }
        } else if (declaration instanceof Ast.TypeAliasDecl alias) {
            strings(out, alias.genericParameters());
            type(out, alias.target());
        } else if (declaration instanceof Ast.FieldDecl field) {
            if (field.bindingKind() == Ast.BindingKind.CONST) {
                throw new IllegalArgumentException(
                        "[EXPORT_CONST_ABI_VALUE_UNENCODED] exported const metadata requires canonical compile-time value encoding");
            }
            field(out, field.bindingKind().name());
            flags(out, field.mutableReferent());
            type(out, field.type());
            annotations(out, field.annotations());
        }

        return out.toString();
    }

    private static Ast.ExportKind kindOf(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            return fn.kind() == Ast.CallableKind.ROUTINE
                    ? Ast.ExportKind.ROUTINE
                    : Ast.ExportKind.FUNCTION;
        }
        if (declaration instanceof Ast.ClassDecl cls)
            return cls.actorKind() == Ast.ActorKind.NONE ? Ast.ExportKind.CLASS : Ast.ExportKind.ACTOR;
        if (declaration instanceof Ast.InterfaceDecl iface) {
            return switch (iface.flavor()) {
                case INTERFACE -> Ast.ExportKind.INTERFACE;
                case TRAIT -> Ast.ExportKind.TRAIT;
                case CONTRACT -> Ast.ExportKind.CONTRACT;
            };
        }
        if (declaration instanceof Ast.TypeAliasDecl) return Ast.ExportKind.TYPE;
        if (declaration instanceof Ast.SymbolDecl) return Ast.ExportKind.SYMBOL;
        if (declaration instanceof Ast.FieldDecl field) {
            return switch (field.bindingKind()) {
                case CONST -> Ast.ExportKind.CONST;
                case VAL -> Ast.ExportKind.VAL;
                case LET -> Ast.ExportKind.LET;
            };
        }
        throw new IllegalArgumentException("unsupported export declaration " + declaration.getClass().getName());
    }

    private static String nameOf(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) return fn.name();
        if (declaration instanceof Ast.ClassDecl cls) return cls.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        if (declaration instanceof Ast.SymbolDecl symbol) return symbol.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        throw new IllegalArgumentException("unsupported export declaration " + declaration.getClass().getName());
    }

    private static void genericSpecs(StringBuilder out, List<Ast.GenericParameter> specs) {
        field(out, Integer.toString(specs.size()));
        for (Ast.GenericParameter spec : specs) {
            field(out, spec.name());
            if (spec.defaultType() == null) field(out, "(no-default)");
            else type(out, spec.defaultType());
        }
    }

    private static void effects(StringBuilder out, Ast.EffectSpec effects) {
        field(out, "throws");
        types(out, effects.throwsTypes());
        field(out, "raises");
        types(out, effects.raisesTypes());
        field(out, "panics");
        types(out, effects.panicsTypes());
    }

    private static void annotations(StringBuilder out, List<Ast.Annotation> annotations) {
        field(out, Integer.toString(annotations.size()));
        for (Ast.Annotation annotation : annotations) {
            field(out, annotation.name());
            types(out, annotation.arguments());
        }
    }

    private static void params(StringBuilder out, List<Ast.Param> params) {
        field(out, Integer.toString(params.size()));
        for (Ast.Param param : params) {
            field(out, param.name());
            flags(out, param.mutable(), param.structural());
            type(out, param.type());
        }
    }

    private static void types(StringBuilder out, List<Ast.TypeRef> types) {
        field(out, Integer.toString(types.size()));
        for (Ast.TypeRef type : types) type(out, type);
    }

    private static void strings(StringBuilder out, List<String> strings) {
        field(out, Integer.toString(strings.size()));
        for (String value : strings) field(out, value);
    }

    private static void type(StringBuilder out, Ast.TypeRef type) {
        if (type == null || type.name() == null || type.name().isBlank()
                || type.name().equals("$infer$") || type.name().equals("Unknown")
                || type.inferArguments()) {
            throw new IllegalArgumentException(
                    "[EXPORT_METADATA_UNRESOLVED_TYPE] exported source signature contains unresolved type metadata");
        }
        field(out, type.name());
        field(out, "resolved");
        field(out, Integer.toString(type.arguments().size()));
        for (Ast.TypeRef child : type.arguments()) type(out, child);
    }

    private static void flags(StringBuilder out, boolean... flags) {
        StringBuilder bits = new StringBuilder(flags.length);
        for (boolean flag : flags) bits.append(flag ? '1' : '0');
        field(out, bits.toString());
    }

    private static void field(StringBuilder out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.append(bytes.length).append(':').append(value).append(';');
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
