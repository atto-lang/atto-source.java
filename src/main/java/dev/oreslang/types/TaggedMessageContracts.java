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
 * Minimal compiler-owned local nominal enum registry extracted from #623.
 *
 * <p>This integration intentionally supports only nullary local enum values
 * needed by TrainingError. It does not enable named match-pattern domains,
 * payload-bearing enum values, actor-wire codecs, schema export, nested enum
 * serialization, or cross-code-unit ABI identity.
 */
public final class TaggedMessageContracts {
    private TaggedMessageContracts() { }

    public record Case(String owner, String name, int tag, Ast.TypeRef sourcePayload) {
        public String qualifiedName() { return owner + "." + name; }
    }

    public record EnumType(String name, List<Case> cases) {
        public EnumType { cases = List.copyOf(cases); }
    }

    public record Domain(String name, List<Case> cases) {
        public Domain { cases = List.copyOf(cases); }
    }

    public record Registry(Map<String, EnumType> enums) {
        public Registry { enums = Map.copyOf(enums); }

        public Case requireCase(String qualified) {
            int dot = qualified.lastIndexOf('.');
            if (dot <= 0 || dot == qualified.length() - 1) {
                throw new IllegalArgumentException("expected Enum.Case: " + qualified);
            }
            EnumType owner = enums.get(qualified.substring(0, dot));
            if (owner == null) throw new IllegalArgumentException("unknown enum " + qualified);
            for (Case candidate : owner.cases()) {
                if (candidate.name().equals(qualified.substring(dot + 1))) return candidate;
            }
            throw new IllegalArgumentException("unknown enum case " + qualified);
        }

        public Domain requireDomain(String name) {
            EnumType all = enums.get(name);
            if (all == null) throw new IllegalArgumentException("unknown enum " + name);
            return new Domain(name, all.cases());
        }
    }

    public static Registry verify(Ast.Program program) {
        Objects.requireNonNull(program);
        Map<String, EnumType> enums = new LinkedHashMap<>();

        Set<String> ordinaryNames = new HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            if (!module.name().equals(Parser.ROOT_MODULE)) ordinaryNames.add(module.name());
            for (Ast.Decl other : module.declarations()) {
                String ordinary = null;
                if (other instanceof Ast.ClassDecl value) ordinary = value.name();
                else if (other instanceof Ast.InterfaceDecl value) ordinary = value.name();
                else if (other instanceof Ast.TypeAliasDecl value) ordinary = value.name();
                else if (other instanceof Ast.FunctionDecl value) ordinary = value.name();
                else if (other instanceof Ast.FieldDecl value) ordinary = value.name();
                if (ordinary != null) ordinaryNames.add(ordinary);
            }
        }

        Set<String> taken = new HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.EnumDecl enumDecl)) continue;
                if (!module.name().equals(Parser.ROOT_MODULE)) {
                    throw new IllegalArgumentException(
                            "enum declarations must currently be top-level");
                }
                String name = enumDecl.name();
                if (!taken.add(name) || ordinaryNames.contains(name)) {
                    throw new IllegalArgumentException(
                            "ambiguous enum name conflicts with another declaration: " + name);
                }
                if (enumDecl.cases().isEmpty() || enumDecl.cases().size() > 4096) {
                    throw new IllegalArgumentException("enum must declare 1..4096 cases");
                }

                ArrayList<Case> cases = new ArrayList<>();
                Set<String> caseNames = new HashSet<>();
                Set<Integer> tags = new HashSet<>();
                int previous = 0;
                for (Ast.EnumCase item : enumDecl.cases()) {
                    if (!caseNames.add(item.name())) {
                        throw new IllegalArgumentException(
                                "duplicate enum case " + enumDecl.name() + "." + item.name());
                    }
                    if (item.tag() < 1 || !tags.add(item.tag()) || item.tag() <= previous) {
                        throw new IllegalArgumentException(
                                "enum tags must be unique positive monotonically increasing integers");
                    }
                    previous = item.tag();
                    if (item.payload() != null) {
                        throw new IllegalArgumentException(
                                "payload-bearing enums are not enabled by the minimal local nominal slice");
                    }
                    cases.add(new Case(enumDecl.name(), item.name(), item.tag(), null));
                }
                enums.put(enumDecl.name(), new EnumType(enumDecl.name(), cases));
            }
        }
        return new Registry(enums);
    }
}
