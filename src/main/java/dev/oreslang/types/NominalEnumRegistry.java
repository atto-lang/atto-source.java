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
