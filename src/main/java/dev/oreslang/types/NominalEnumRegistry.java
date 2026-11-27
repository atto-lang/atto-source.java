package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Minimal compiler-owned nominal enum registry.
 *
 * This deliberately excludes actor wire codecs and named match-pattern domains.
 * It exists only to provide closed local nominal identity, construction and
 * exhaustive matching for the ML TrainingError integration slice.
 */
public final class NominalEnumRegistry {
    private NominalEnumRegistry() { }

    public record Case(String owner, String name, Ast.TypeRef payload, int tag) {
        public String qualifiedName() {
            return owner + "." + name;
        }
    }

    public record EnumType(String name, List<Case> cases) {
        public EnumType {
            cases = List.copyOf(cases);
        }
    }

    public record Registry(Map<String, EnumType> enums) {
        public Registry {
            enums = Map.copyOf(enums);
        }

        public EnumType requireEnum(String name) {
            EnumType enumeration = enums.get(name);
            if (enumeration == null) {
                throw new IllegalArgumentException("unknown nominal enum " + name);
            }
            return enumeration;
        }

        public Case caseOrNull(String qualified) {
            int dot = qualified.indexOf('.');
            if (dot <= 0 || dot != qualified.lastIndexOf('.')
                    || dot == qualified.length() - 1) {
                return null;
            }
            EnumType owner = enums.get(qualified.substring(0, dot));
            if (owner == null) return null;
            String caseName = qualified.substring(dot + 1);
            for (Case candidate : owner.cases()) {
                if (candidate.name().equals(caseName)) return candidate;
            }
            return null;
        }

        public Case requireCase(String qualified) {
            Case candidate = caseOrNull(qualified);
            if (candidate == null) {
                throw new IllegalArgumentException("unknown nominal enum case " + qualified);
            }
            return candidate;
        }
    }

    public static Registry forProgram(Ast.Program program) {
        Objects.requireNonNull(program, "program");

        Map<String, Ast.EnumDecl> declarations = new LinkedHashMap<>();
        Set<String> ordinaryNames = new HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.EnumDecl enumeration) {
                    if (!module.name().equals(Parser.ROOT_MODULE)) {
                        throw new IllegalArgumentException(
                                "nominal enum declarations must currently be top-level");
                    }
                    Ast.EnumDecl prior = declarations.putIfAbsent(
                            enumeration.name(), enumeration);
                    if (prior != null) {
                        throw new IllegalArgumentException(
                                "duplicate nominal enum " + enumeration.name());
                    }
                    continue;
                }
                String ordinary = declarationName(declaration);
                if (ordinary != null) ordinaryNames.add(ordinary);
            }
        }

        Set<String> reserved = Set.of(
                "bool", "Bool", "int", "uint", "bigint",
                "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
                "float", "f32", "f64", "decimal", "complex",
                "string", "String", "void", "Option", "Result",
                "Tuple", "FixedArray", "FixedList", "Array", "List",
                "ActorMail", "ActorRef", "Channel", "Future",
                "Mutex", "SharedMutex", "MutexGuard");

        rejectPayloadCycles(declarations);

        Map<String, EnumType> enums = new LinkedHashMap<>();
        for (Ast.EnumDecl enumeration : declarations.values()) {
            String name = enumeration.name();
            if (name == null || name.isBlank()
                    || reserved.contains(name)
                    || ordinaryNames.contains(name)) {
                throw new IllegalArgumentException(
                        "ambiguous or reserved nominal enum name " + name);
            }
            if (enumeration.cases().isEmpty()
                    || enumeration.cases().size() > 4096) {
                throw new IllegalArgumentException(
                        "nominal enum must declare 1..4096 cases");
            }

            List<Case> cases = new ArrayList<>(enumeration.cases().size());
            Set<String> names = new HashSet<>();
            Set<Integer> tags = new HashSet<>();
            int previousTag = 0;
            for (Ast.EnumCase source : enumeration.cases()) {
                if (source.name() == null || source.name().isBlank()
                        || !names.add(source.name())) {
                    throw new IllegalArgumentException(
                            "duplicate or invalid enum case " + source.name());
                }
                if (source.tag() < 1
                        || source.tag() <= previousTag
                        || !tags.add(source.tag())) {
                    throw new IllegalArgumentException(
                            "enum tags must be positive and strictly increasing");
                }
                previousTag = source.tag();
                cases.add(new Case(
                        name,
                        source.name(),
                        source.payload(),
                        source.tag()));
            }
            enums.put(name, new EnumType(name, cases));
        }
        return new Registry(enums);
    }

    private static void rejectPayloadCycles(
            Map<String, Ast.EnumDecl> declarations) {
        Set<String> complete = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (String name : declarations.keySet()) {
            visitPayloadDependencies(
                    name,
                    declarations,
                    complete,
                    active);
        }
    }

    private static void visitPayloadDependencies(
            String name,
            Map<String, Ast.EnumDecl> declarations,
            Set<String> complete,
            Set<String> active) {
        if (complete.contains(name)) return;
        if (!active.add(name)) {
            throw new IllegalArgumentException(
                    "nominal enum payload cycle involving " + name);
        }
        Ast.EnumDecl declaration = declarations.get(name);
        if (declaration != null) {
            for (Ast.EnumCase variant : declaration.cases()) {
                if (variant.payload() == null) continue;
                Set<String> dependencies = new HashSet<>();
                collectEnumDependencies(
                        variant.payload(),
                        declarations.keySet(),
                        dependencies);
                for (String dependency : dependencies) {
                    visitPayloadDependencies(
                            dependency,
                            declarations,
                            complete,
                            active);
                }
            }
        }
        active.remove(name);
        complete.add(name);
    }

    private static void collectEnumDependencies(
            Ast.TypeRef type,
            Set<String> enumNames,
            Set<String> dependencies) {
        if (enumNames.contains(type.name())) {
            dependencies.add(type.name());
        }
        for (Ast.TypeRef argument : type.arguments()) {
            collectEnumDependencies(
                    argument,
                    enumNames,
                    dependencies);
        }
        if (type.isRecordType()) {
            for (Ast.TypeRef member : type.recordMembers().values()) {
                collectEnumDependencies(
                        member,
                        enumNames,
                        dependencies);
            }
        }
    }

    private static String declarationName(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl function) return function.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        return null;
    }
}
