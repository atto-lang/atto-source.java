package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal compiler-owned nominal enum registry for local source typing.
 *
 * This deliberately does not grant actor-wire serialization, nested enum wire
 * shapes, MessagePack transport, pattern spreading, or package export ABI.
 */
public final class NominalEnumRegistry {
    private NominalEnumRegistry() { }

    public record Variant(String owner, String name, Ast.TypeRef payload, int tag) {
        public String qualifiedName() { return owner + "." + name; }
    }

    public record EnumType(String name, List<Variant> variants) {
        public EnumType { variants = List.copyOf(variants); }

        public Variant requireVariant(String name) {
            for (Variant variant : variants) {
                if (variant.name().equals(name)) return variant;
            }
            throw new IllegalArgumentException(
                    "unknown enum variant " + this.name + "." + name);
        }
    }

    public record Registry(Map<String, EnumType> enums) {
        public Registry { enums = Map.copyOf(enums); }

        public EnumType enumType(String name) { return enums.get(name); }

        public Variant variant(String qualified) {
            int dot = qualified.lastIndexOf('.');
            if (dot <= 0 || dot == qualified.length() - 1) return null;
            EnumType owner = enums.get(qualified.substring(0, dot));
            if (owner == null) return null;
            String caseName = qualified.substring(dot + 1);
            for (Variant variant : owner.variants()) {
                if (variant.name().equals(caseName)) return variant;
            }
            return null;
        }
    }

    public static Registry build(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        Map<String, EnumType> enums = new LinkedHashMap<>();

        java.util.Set<String> reserved = new java.util.HashSet<>(java.util.Set.of(
                "bool", "int", "uint", "float", "decimal", "complex",
                "string", "String", "void", "Option", "Result", "Tuple",
                "Array", "List", "Future", "Channel", "ActorMail", "ActorRef",
                "Mutex", "SharedMutex", "Object"));
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.EnumDecl) continue;
                String name = null;
                if (declaration instanceof Ast.FunctionDecl fn) name = fn.name();
                else if (declaration instanceof Ast.ClassDecl klass) name = klass.name();
                else if (declaration instanceof Ast.InterfaceDecl iface) name = iface.name();
                else if (declaration instanceof Ast.TypeAliasDecl alias) name = alias.name();
                else if (declaration instanceof Ast.FieldDecl field) name = field.name();
                if (name != null) reserved.add(name);
            }
        }

        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.EnumDecl enumeration)) continue;
                if (!module.name().equals(Parser.ROOT_MODULE)) {
                    throw new IllegalArgumentException(
                            "nominal enums are currently top-level only");
                }
                if (enumeration.name() == null || enumeration.name().isBlank()) {
                    throw new IllegalArgumentException("enum name cannot be blank");
                }
                if (reserved.contains(enumeration.name())) {
                    throw new IllegalArgumentException(
                            "enum name conflicts with an existing declaration or builtin: "
                                    + enumeration.name());
                }
                if (enumeration.cases().isEmpty() || enumeration.cases().size() > 4096) {
                    throw new IllegalArgumentException("enum must declare 1..4096 cases");
                }
                if (enums.containsKey(enumeration.name())) {
                    throw new IllegalArgumentException(
                            "duplicate enum declaration '" + enumeration.name() + "'");
                }

                Map<String, Boolean> names = new LinkedHashMap<>();
                int previousTag = 0;
                java.util.ArrayList<Variant> variants = new java.util.ArrayList<>();
                for (Ast.EnumCase entry : enumeration.cases()) {
                    if (entry.name() == null || entry.name().isBlank()
                            || names.putIfAbsent(entry.name(), Boolean.TRUE) != null) {
                        throw new IllegalArgumentException(
                                "duplicate or invalid enum case '" + entry.name() + "'");
                    }
                    if (entry.tag() < 1 || entry.tag() <= previousTag) {
                        throw new IllegalArgumentException(
                                "enum tags must be positive and strictly increasing");
                    }
                    previousTag = entry.tag();
                    variants.add(new Variant(
                            enumeration.name(),
                            entry.name(),
                            entry.payload(),
                            entry.tag()));
                }
                enums.put(enumeration.name(),
                        new EnumType(enumeration.name(), variants));
            }
        }
        return new Registry(enums);
    }
}
