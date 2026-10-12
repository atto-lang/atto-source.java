package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.MessagePackCollectionCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiler-owned nominal enum and type-only message-pattern registry.
 *
 * A pattern expands to a closed ordered list of enum variants. No enum or
 * pattern object needs to be shared across isolated actors. Sender/receiver
 * compile compatible names, numeric tags and codec shapes independently.
 * Imported enum namespaces/generic variants are deliberately not enabled.
 */
public final class TaggedMessageContracts {
    private TaggedMessageContracts() {}

    public record Case(String owner, String name, int tag,
                       Ast.TypeRef sourcePayload,
                       MessagePackCollectionCodec.Shape payload) {
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
            if (owner == null) throw new IllegalArgumentException("unknown message enum " + qualified);
            for (Case candidate : owner.cases()) {
                if (candidate.name().equals(qualified.substring(dot + 1))) return candidate;
            }
            throw new IllegalArgumentException("unknown enum variant " + qualified);
        }

        public Domain requireDomain(String name) {
            Domain named = patterns.get(name);
            if (named != null) return named;
            EnumType all = enums.get(name);
            if (all != null) return new Domain(name, all.cases());
            throw new IllegalArgumentException("unknown message pattern/enum " + name);
        }
    }

    private static boolean hasExplicitBox(Ast.TypeRef ref) {
        if (ref == null) return false;
        if (ref.name().equals("Box")) return true;
        return ref.arguments().stream().anyMatch(TaggedMessageContracts::hasExplicitBox);
    }

    private static void appendPatternCase(List<Case> items, Set<String> seen, Case entry) {
        if (!seen.add(entry.qualifiedName())) {
            throw new IllegalArgumentException(
                    "duplicate pattern alternative " + entry.qualifiedName());
        }
        if (items.size() >= 4096) {
            throw new IllegalArgumentException(
                    "expanded match pattern exceeds 4096 alternatives");
        }
        items.add(entry);
    }

    /**
     * Compiler symbol/type registry. A nested enum payload can be a local
     * nominal value even though it has NO compiled wire shape yet.
     * A null payload shape with non-null sourcePayload means "not wire-safe",
     * never "payloadless". Wire admission must check both fields.
     */
    public static Registry forTypes(Ast.Program program) {
        return build(program, true);
    }

    /** Requires every enum payload to have a finite portable wire shape. */
    public static Registry verify(Ast.Program program) {
        return build(program, false);
    }

    private static Registry build(Ast.Program program, boolean localOnly) {
        Objects.requireNonNull(program);
        List<EnumPayloadDependencies.Reference> nested =
                EnumPayloadDependencies.check(program);
        // Direct/mutual unboxed cycles were already rejected by the graph
        // pass. Acyclic nested enum *values* now have a finite wire codec.
        // Indirect recursive schemas and Box<T> remain local-only.
        Map<String, Ast.EnumDecl> sourceEnums = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.EnumDecl enumeration) {
                    sourceEnums.put(enumeration.name(), enumeration);
                }
            }
        }
        Set<String> localOnlyCases = new HashSet<>();
        for (var dependency : nested) {
            localOnlyCases.add(dependency.from() + "." + dependency.caseName());
        }
        Map<String, EnumType> enums = new LinkedHashMap<>();
        Map<String, Ast.PatternDecl> pending = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            // The current tagged wire identity is file-root local by design;
            // reject nested module declaration until import/export ABI exists.
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.EnumDecl)
                        && !(declaration instanceof Ast.PatternDecl)) continue;
                if (!module.name().equals(Parser.ROOT_MODULE)) {
                    throw new IllegalArgumentException(
                            "typed message enum/pattern declarations must currently be top-level");
                }
                String name = declaration instanceof Ast.EnumDecl e ? e.name()
                        : ((Ast.PatternDecl) declaration).name();
                if (!taken.add(name)) throw new IllegalArgumentException(
                        "duplicate enum/pattern name " + name);
                if (declaration instanceof Ast.PatternDecl p) {
                    pending.put(p.name(), p);
                    continue;
                }
                Ast.EnumDecl enumDecl = (Ast.EnumDecl) declaration;
                // Revalidate ASTs at the semantic boundary. Parsing source
                // text is not the only way internal passes can produce an AST.
                if (enumDecl.cases().isEmpty() || enumDecl.cases().size() > 4096) {
                    throw new IllegalArgumentException("enum must declare 1..4096 cases");
                }
                ArrayList<Case> entries = new ArrayList<>();
                Set<String> caseNames = new HashSet<>();
                int previousTag = 0;
                for (Ast.EnumCase entry : enumDecl.cases()) {
                    if (entry.name() == null || entry.name().isBlank()
                            || !caseNames.add(entry.name())) {
                        throw new IllegalArgumentException(
                                "duplicate or invalid enum case " + entry.name());
                    }
                    if (entry.tag() < 1 || entry.tag() <= previousTag) {
                        throw new IllegalArgumentException(
                                "enum wire tags must be positive and strictly increasing");
                    }
                    previousTag = entry.tag();
                    MessagePackCollectionCodec.Shape shape = null;
                    if (entry.payload() != null) {
                        try {
                            shape = NestedEnumWireShapes.forPayload(entry.payload(), sourceEnums);
                        } catch (IllegalArgumentException unsupported) {
                            // Only known nominal dependency cycles may stay
                            // local-only. Other malformed types must fail even
                            // in local registration rather than being erased.
                            boolean indirect = localOnlyCases.contains(
                                    enumDecl.name() + "." + entry.name());
                            boolean recursiveWire = unsupported.getMessage().contains(
                                    "recursive enum wire schema");
                            boolean boxedWire = unsupported.getMessage().contains(
                                    "no portable Serializable wire codec: Box")
                                    && hasExplicitBox(entry.payload());
                            if (!localOnly
                                    || (!boxedWire && !(indirect && recursiveWire))) {
                                throw unsupported;
                            }
                        }
                    }
                    entries.add(new Case(enumDecl.name(), entry.name(), entry.tag(),
                            entry.payload(), shape));
                }
                enums.put(enumDecl.name(), new EnumType(enumDecl.name(), entries));
            }
        }
        Registry stage = new Registry(enums, Map.of());
        Map<String, Domain> patterns = new LinkedHashMap<>();
        for (Ast.PatternDecl p : pending.values()) {
            if (p.alternatives().isEmpty() || p.alternatives().size() > 4096) {
                throw new IllegalArgumentException(
                        "pattern must declare 1..4096 alternatives");
            }
            List<Case> items = new ArrayList<>();
            Set<String> alternatives = new HashSet<>();
            for (String source : p.alternatives()) {
                if (source == null || source.isBlank()) {
                    throw new IllegalArgumentException("invalid pattern alternative");
                }
                if (source.startsWith("...")) {
                    String enumName = source.substring(3);
                    EnumType expanded = enums.get(enumName);
                    if (expanded == null) {
                        throw new IllegalArgumentException(
                                "unknown enum in match pattern spread " + source);
                    }
                    // Expands the enum at every compilation, not at runtime;
                    // full-case coverage automatically follows new variants.
                    for (Case variant : expanded.cases()) {
                        appendPatternCase(items, alternatives, variant);
                    }
                } else {
                    // Enum-to-pattern dependencies cannot exist: only enums
                    // are eligible, avoiding recursion and cyclic spreads.
                    appendPatternCase(items, alternatives, stage.requireCase(source));
                }
            }
            patterns.put(p.name(), new Domain(p.name(), items));
        }
        return new Registry(enums, patterns);
    }
}
