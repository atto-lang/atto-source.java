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
 * Minimal compiler-owned nominal enum registry extracted from the #623 ADT work.
 * This slice is deliberately local/type-level only: it does not enable enum actor-wire
 * codecs, schema export, nested enum serialization, or cross-code-unit ABI identity.
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

    public record Registry(Map<String, EnumType> enums, Map<String, Domain> patterns) {
        public Registry {
            enums = Map.copyOf(enums);
            patterns = Map.copyOf(patterns);
        }

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
            Domain named = patterns.get(name);
            if (named != null) return named;
            EnumType all = enums.get(name);
            if (all != null) return new Domain(name, all.cases());
            throw new IllegalArgumentException("unknown enum/pattern " + name);
        }
    }

    public static Registry verify(Ast.Program program) {
        Objects.requireNonNull(program);
        Map<String, EnumType> enums = new LinkedHashMap<>();
        Map<String, Ast.PatternDecl> pending = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();

        // Root-level nominal names are currently simple (not package-qualified)
        // runtime/type symbols. Fail closed on any ordinary declaration that
        // could shadow or be shadowed by that simple name.
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

        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.EnumDecl) && !(declaration instanceof Ast.PatternDecl)) continue;
                if (!module.name().equals(Parser.ROOT_MODULE)) {
                    throw new IllegalArgumentException("enum/pattern declarations must currently be top-level");
                }
                String name = declaration instanceof Ast.EnumDecl e ? e.name() : ((Ast.PatternDecl) declaration).name();
                if (!taken.add(name) || ordinaryNames.contains(name)) {
                    throw new IllegalArgumentException(
                            "ambiguous enum/pattern name conflicts with another declaration: " + name);
                }
                if (declaration instanceof Ast.PatternDecl p) {
                    pending.put(p.name(), p);
                    continue;
                }
                Ast.EnumDecl e = (Ast.EnumDecl) declaration;
                if (e.cases().isEmpty() || e.cases().size() > 4096) {
                    throw new IllegalArgumentException("enum must declare 1..4096 cases");
                }
                ArrayList<Case> cases = new ArrayList<>();
                Set<String> caseNames = new HashSet<>();
                Set<Integer> tags = new HashSet<>();
                int previous = 0;
                for (Ast.EnumCase item : e.cases()) {
                    if (!caseNames.add(item.name())) throw new IllegalArgumentException("duplicate enum case " + e.name() + "." + item.name());
                    if (item.tag() < 1 || !tags.add(item.tag()) || item.tag() <= previous) {
                        throw new IllegalArgumentException("enum tags must be unique positive monotonically increasing integers");
                    }
                    previous = item.tag();
                    // This extraction exists only to unblock the closed
                    // TrainingError<Result> fixture. Payload-bearing enums stay
                    // disabled until the fuller #623 local-value guards and
                    // ownership/runtime boundary are reconciled.
                    if (item.payload() != null) {
                        throw new IllegalArgumentException(
                                "payload-bearing enums are not enabled by the minimal local nominal slice");
                    }
                    cases.add(new Case(e.name(), item.name(), item.tag(), null));
                }
                enums.put(e.name(), new EnumType(e.name(), cases));
            }
        }
        Registry stage = new Registry(enums, Map.of());
        Map<String, Domain> patterns = new LinkedHashMap<>();
        for (Ast.PatternDecl p : pending.values()) {
            if (p.alternatives().isEmpty() || p.alternatives().size() > 4096) {
                throw new IllegalArgumentException("pattern must declare 1..4096 alternatives");
            }
            ArrayList<Case> cases = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String q : p.alternatives()) {
                Case c = stage.requireCase(q);
                if (!seen.add(c.qualifiedName())) throw new IllegalArgumentException("duplicate pattern alternative " + q);
                cases.add(c);
            }
            patterns.put(p.name(), new Domain(p.name(), cases));
        }
        return new Registry(enums, patterns);
    }
}
