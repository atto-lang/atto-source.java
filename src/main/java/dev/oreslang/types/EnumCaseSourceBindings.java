package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.EnumCaseOverloadPolicy.Domain;
import dev.oreslang.types.EnumCaseOverloadPolicy.EnumInfo;
import dev.oreslang.types.EnumCaseOverloadPolicy.Parameter;
import dev.oreslang.types.EnumCaseOverloadPolicy.Signature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, compiler-only bridge from the REAL Oreslang EnumDecl/FunctionDecl/
 * MethodDecl AST to EnumCaseOverloadPolicy. It is NOT the executable checker:
 * TypeChecker still rejects enum declarations before runtime admission.
 *
 * Only local, nongeneric declarations are in scope. Imported types, inherited
 * method slots, actor entries and generic instantiations MUST wait for a
 * provenance-preserving cross-unit lowering implementation.
 */
public final class EnumCaseSourceBindings {
    private EnumCaseSourceBindings() { }

    public record Key(Domain domain, String owner, String name, int arity) { }
    public record Candidate(Signature signature, Ast.FunctionDecl function, Ast.MethodDecl method) {
        public Candidate {
            Objects.requireNonNull(signature, "signature");
            if ((function == null) == (method == null)) {
                throw new IllegalArgumentException("candidate requires exactly one callable");
            }
        }
    }

    /**
     * Offline AST proof that a syntactic nullary enum literal could select one
     * exact source-declaration target at compile time. The raw call AST can be
     * forged and is NOT an expression-typed result of production TypeChecker.
     * In particular, real lexical shadowing/import resolution is outstanding.
     */
    public record StagedLiteralBinding(
            Ast.CallExpr call, Candidate target, String staticSlotId) {
        public StagedLiteralBinding {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(target, "target");
            if (!target.signature().symbolId().equals(staticSlotId)) {
                throw new IllegalArgumentException("binding symbol does not match selected declaration");
            }
        }
    }

    public static final class Proof {
        private final Map<Key, List<Candidate>> families;
        private final Map<String, EnumInfo> enums;
        private final Map<String, EnumTag32Plan> nullaryTagLayouts;

        // Keep the compiler-verified proof unforgeable by ordinary callers.
        // A public record constructor would let a host bypass lexical binding.
        private Proof(Map<Key, List<Candidate>> families,
                      Map<String, EnumInfo> enums,
                      Map<String, EnumTag32Plan> nullaryTagLayouts) {
            Map<Key, List<Candidate>> immutable = new LinkedHashMap<>();
            families.forEach((key, candidates) -> immutable.put(key, List.copyOf(candidates)));
            this.families = Map.copyOf(immutable);
            this.enums = Map.copyOf(enums);
            this.nullaryTagLayouts = Map.copyOf(nullaryTagLayouts);
        }

        public Map<Key, List<Candidate>> families() { return families; }
        public Map<String, EnumInfo> enums() { return enums; }
        /**
         * Compiler-only 32-bit tagged constant plans for fully nullary enums.
         * A proven tag is a value representation, never a runtime overload key.
         */
        public Map<String, EnumTag32Plan> nullaryTagLayouts() { return nullaryTagLayouts; }

        /**
         * Source-AST nullary-literal selector, **offline only**. This is
         * intentionally narrower than actual expression resolution: module
         * free-function calls; no imports, method dispatch, parameters,
         * variable aliases, lexical shadowing, captured values or transforms.
         *
         * A runtime must never execute this result without the independent
         * production TypeChecker/ownership linking and trusted call binding.
         */
        public StagedLiteralBinding bindStagedNullaryLiteralCall(
                Key key, Ast.CallExpr call) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(call, "call");
            if (key.domain() != Domain.MODULE
                    || !(call.callee() instanceof Ast.NameExpr callee)
                    || !key.name().equals(callee.name())
                    || call.typeArgumentsPresent()
                    || !call.typeArguments().isEmpty()
                    || call.arguments().size() != key.arity()) {
                throw new IllegalArgumentException(
                        "offline literal binding requires direct nongeneric module call");
            }
            List<Candidate> candidates = families.get(key);
            if (candidates == null) {
                throw new IllegalArgumentException("unproven enum overload family");
            }
            List<Parameter> staticArgs = new ArrayList<>();
            for (Ast.Expr argument : call.arguments()) {
                if (!(argument instanceof Ast.MemberExpr member)
                        || !(member.receiver() instanceof Ast.NameExpr sourceOwner)) {
                    throw new IllegalArgumentException(
                            "offline selector requires direct syntactic nullary enum cases");
                }
                String enumId = key.owner() + "." + sourceOwner.name();
                EnumTag32Plan layout = nullaryTagLayouts.get(enumId);
                if (layout == null) {
                    throw new IllegalArgumentException(
                            "missing certified local nullary AOT case layout");
                }
                layout.constantTag(member.member()); // case presence; no dynamic dispatch
                staticArgs.add(Parameter.enumCase(enumId, member.member()));
            }
            Signature chosen = EnumCaseOverloadPolicy.selectStatic(
                    candidates.stream().map(Candidate::signature).toList(),
                    enums, staticArgs);
            for (Candidate candidate : candidates) {
                if (candidate.signature() == chosen) {
                    return new StagedLiteralBinding(
                            call, candidate, candidate.signature().symbolId());
                }
            }
            throw new IllegalStateException(
                    "selected static case target was not a source declaration");
        }

        /**
         * SOURCE-FREE / OFFLINE ORACLE ONLY. These TypeRef values are
         * caller-supplied and NOT a validated expression type proof. Never
         * wire this helper into runtime or optimizer dispatch: production
         * lowering must supply typechecker-authenticated symbol IDs instead.
         */
        public Candidate selectStagedTypeRefs(Key key, List<Ast.TypeRef> staticArgumentTypes) {
            List<Candidate> candidates = families.get(key);
            if (candidates == null) throw new IllegalArgumentException("unknown static callable family");
            if (staticArgumentTypes.size() != key.arity()) {
                throw new IllegalArgumentException("argument arity mismatch");
            }
            List<Parameter> args = new ArrayList<>();
            for (Ast.TypeRef ref : staticArgumentTypes) {
                args.add(resolvedArg(ref, key.owner(), enums));
            }
            Signature chosen = EnumCaseOverloadPolicy.selectStatic(
                    candidates.stream().map(Candidate::signature).toList(), enums, args);
            // Strict reference identity: equal-looking signatures are NEVER
            // a justification to substitute another source declaration.
            for (Candidate candidate : candidates) {
                if (candidate.signature() == chosen) return candidate;
            }
            throw new IllegalStateException("selected slot missing its declaration identity");
        }
    }

    public static Proof compileLocalProof(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        if (!program.imports().isEmpty()) {
            throw new IllegalArgumentException("case overload proof requires explicit linked import IDs");
        }
        String scope = program.namespace() == null ? "local_unit" : program.namespace();
        if (scope.isBlank()) throw new IllegalArgumentException("blank source namespace");
        // This stage never invents module identities from short case spellings.
        Map<String, EnumInfo> enums = new LinkedHashMap<>();
        Map<String, EnumTag32Plan> nullaryTagLayouts = new LinkedHashMap<>();
        Map<String, Map<String, String>> localEnums = new HashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            String owner = scope + "." + module.name();
            Map<String, String> moduleEnums = new LinkedHashMap<>();
            // The offline oracle must not assign two independent declarations
            // the same supposed nominal owner, even if the production checker
            // would later reject that source for a separate reason.
            Set<String> nominalNames = new LinkedHashSet<>();
            for (Ast.Decl decl : module.declarations()) {
                String nominal = null;
                if (decl instanceof Ast.EnumDecl d) nominal = d.name();
                else if (decl instanceof Ast.ClassDecl d) nominal = d.name();
                else if (decl instanceof Ast.InterfaceDecl d) nominal = d.name();
                else if (decl instanceof Ast.TypeAliasDecl d) nominal = d.name();
                if (nominal != null && !nominalNames.add(nominal)) {
                    throw new IllegalArgumentException(
                            "duplicate/ambiguous nominal name in module: " + nominal);
                }
            }
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.EnumDecl en)) continue;
                String id = owner + "." + en.name();
                if (moduleEnums.putIfAbsent(en.name(), id) != null
                        || enums.containsKey(id)) {
                    throw new IllegalArgumentException("duplicate nominal enum declaration");
                }
                Set<String> cases = new LinkedHashSet<>();
                int prior = 0;
                boolean eligible = true;
                boolean nullary = true;
                for (Ast.EnumCase entry : en.cases()) {
                    if (entry.tag() <= prior || !cases.add(entry.name())) {
                        throw new IllegalArgumentException("duplicate/unordered enum case identity or tag");
                    }
                    prior = entry.tag();
                    if (entry.payload() != null) nullary = false;
                    if (!eligiblePayload(entry.payload())) eligible = false;
                }
                // Record constructor enforces case-name limits and nonempty.
                enums.put(id, new EnumInfo(id, List.copyOf(cases), eligible));
                // Nullary AOT layout is a deliberately smaller portable
                // subset of the source/type-level enum model. Never reject
                // an otherwise valid payload-bearing or Unicode source enum
                // merely because the native AOT layout has narrower support.
                // Runtime/TypeChecker still reject ALL enum guest execution.
                if (nullary && en.cases().size() <= EnumTag32Plan.MAX_CASES
                        && portableNullaryAotId(id)
                        && en.cases().stream().allMatch(
                                entry -> portableNullaryAotId(entry.name()))) {
                    List<EnumTag32Plan.CaseTag> caseTags = new ArrayList<>();
                    for (Ast.EnumCase entry : en.cases()) {
                        caseTags.add(new EnumTag32Plan.CaseTag(entry.name(), entry.tag()));
                    }
                    nullaryTagLayouts.put(id, new EnumTag32Plan(
                            id, caseTags, en.visibility() == Ast.Visibility.PUBLIC));
                }
            }
            if (localEnums.putIfAbsent(owner, Map.copyOf(moduleEnums)) != null) {
                throw new IllegalArgumentException("duplicate module declaration");
            }
        }

        Map<Key, List<Pending>> pending = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            String owner = scope + "." + module.name();
            Map<String, String> visible = localEnums.get(owner);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    if (fn.actorKind() != Ast.ActorKind.NONE
                            || !fn.genericParameters().isEmpty()) {
                        if (fn.parameters().stream().anyMatch(p ->
                                isLocalEnumRef(p.type(), visible))) {
                            throw new IllegalArgumentException(
                                    "actor/generic source enum overload requires dedicated binding proof");
                        }
                        continue;
                    }
                    Key key = new Key(Domain.MODULE, owner, fn.name(), fn.parameters().size());
                    pending.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new Pending(fn.parameters(), fn, null, visible));
                } else if (decl instanceof Ast.ClassDecl cls) {
                    if (cls.actorKind() != Ast.ActorKind.NONE
                            || !cls.genericParameters().isEmpty() || !cls.parents().isEmpty()) {
                        if (cls.methods().stream().anyMatch(m -> m.parameters().stream()
                                .anyMatch(p -> isLocalEnumRef(p.type(), visible)))) {
                            throw new IllegalArgumentException(
                                    "inherited/generic/actor enum methods require explicit slot proof");
                        }
                        continue;
                    }
                    String methodOwner = owner + "." + cls.name();
                    for (Ast.MethodDecl method : cls.methods()) {
                        if (!method.genericParameters().isEmpty()) {
                            if (method.parameters().stream().anyMatch(p ->
                                    isLocalEnumRef(p.type(), visible))) {
                                throw new IllegalArgumentException(
                                        "generic method enum specialization lacks type substitution proof");
                            }
                            continue;
                        }
                        Domain domain = method.isStatic() ? Domain.STATIC : Domain.INSTANCE;
                        Key key = new Key(domain, methodOwner,
                                method.name(), method.parameters().size());
                        pending.computeIfAbsent(key, ignored -> new ArrayList<>())
                                .add(new Pending(method.parameters(), null, method, visible));
                    }
                }
            }
        }

        Map<Key, List<Candidate>> families = new LinkedHashMap<>();
        for (Map.Entry<Key, List<Pending>> group : pending.entrySet()) {
            Key key = group.getKey();
            List<Pending> entries = group.getValue();
            boolean usesEnum = entries.stream().anyMatch(p -> p.parameters().stream()
                    .anyMatch(arg -> isLocalEnumRef(arg.type(), p.localEnums())));
            if (!usesEnum) continue; // unaffected legacy arity-only declarations
            List<Candidate> candidates = new ArrayList<>();
            for (Pending p : entries) {
                List<Parameter> params = new ArrayList<>();
                for (Ast.Param parameter : p.parameters()) {
                    if (parameter.structural() || parameter.mutable()) {
                        throw new IllegalArgumentException("structural/mutable enum overload parameter");
                    }
                    params.add(parameter(parameter.type(), p.localEnums(), enums));
                }
                Signature signature = new Signature(key.domain(), key.owner(), key.name(), params);
                candidates.add(new Candidate(signature, p.function(), p.method()));
            }
            EnumCaseOverloadPolicy.validateFamily(
                    candidates.stream().map(Candidate::signature).toList(), enums);
            families.put(key, List.copyOf(candidates));
        }
        return new Proof(families, enums, nullaryTagLayouts);
    }

    private record Pending(List<Ast.Param> parameters, Ast.FunctionDecl function,
                           Ast.MethodDecl method, Map<String, String> localEnums) { }

    private static boolean portableNullaryAotId(String identity) {
        // Mirrors the current staged native AOT identifier subset, not the
        // broader language grammar. Qualified IDs permit dot separators.
        if (identity == null || identity.isEmpty()) return false;
        for (String component : identity.split("\\.", -1)) {
            if (component.isEmpty()) return false;
            for (int i = 0; i < component.length(); i++) {
                char ch = component.charAt(i);
                boolean head = (ch >= 'A' && ch <= 'Z')
                        || (ch >= 'a' && ch <= 'z') || ch == '_';
                if (!head && !(i > 0 && ch >= '0' && ch <= '9')) return false;
            }
        }
        return true;
    }

    private static boolean eligiblePayload(Ast.TypeRef payload) {
        if (payload == null) return true;
        if (payload.inferArguments() || !payload.arguments().isEmpty()) return false;
        return switch (payload.name()) {
            case "bool", "int", "uint", "i8", "u8", "i16", "u16",
                    "i32", "u32", "i64", "u64", "f64", "float", "string", "String" -> true;
            default -> false;
        };
    }

    private static boolean isLocalEnumRef(Ast.TypeRef ref, Map<String, String> local) {
        if (ref == null || ref.inferArguments() || !ref.arguments().isEmpty()) return false;
        String n = ref.name();
        if (local.containsKey(n)) return true;
        int dot = n.indexOf('.');
        return dot > 0 && local.containsKey(n.substring(0, dot));
    }

    private static Parameter parameter(Ast.TypeRef ref, Map<String, String> local,
                                       Map<String, EnumInfo> enums) {
        if (ref == null || ref.inferArguments() || !ref.arguments().isEmpty()) {
            throw new IllegalArgumentException("no generic/structural parameter in case overload family");
        }
        String name = ref.name();
        if (local.containsKey(name)) return Parameter.enumType(local.get(name));
        int dot = name.indexOf('.');
        if (dot > 0) {
            String enumId = local.get(name.substring(0, dot));
            if (enumId == null || name.indexOf('.', dot + 1) != -1) {
                throw new IllegalArgumentException("unlinked or malformed enum-case type " + name);
            }
            String caseName = name.substring(dot + 1);
            if (!enums.get(enumId).cases().contains(caseName)) {
                throw new IllegalArgumentException("unresolved enum case " + name);
            }
            return Parameter.enumCase(enumId, caseName);
        }
        return switch (name) {
            case "int", "bool", "string", "String", "float", "f64" ->
                    Parameter.fixed(canonicalScalar(name));
            default -> throw new IllegalArgumentException(
                    "unproven ordinary parameter type in case overload family: " + name);
        };
    }

    private static Parameter resolvedArg(
            Ast.TypeRef arg, String callableOwner, Map<String, EnumInfo> enums) {
        if (arg == null || arg.inferArguments() || !arg.arguments().isEmpty()) {
            throw new IllegalArgumentException("unresolved static argument type");
        }
        // Method owners include a final class segment; module owners do not.
        String name = arg.name();
        for (String identity : enums.keySet()) {
            // Statically typed values can be parent enums or refined cases.
            int dot = identity.lastIndexOf('.');
            String localShort = identity.substring(dot + 1);
            String moduleOwner = identity.substring(0, dot);
            if (!callableOwner.equals(moduleOwner)
                    && !callableOwner.startsWith(moduleOwner + ".")) continue;
            if (name.equals(localShort)) return Parameter.enumType(identity);
            if (name.startsWith(localShort + ".")
                    && name.indexOf('.', localShort.length() + 1) == -1) {
                String caseName = name.substring(localShort.length() + 1);
                EnumInfo info = enums.get(identity);
                if (info.cases().contains(caseName)) return Parameter.enumCase(identity, caseName);
            }
        }
        return Parameter.fixed(canonicalScalar(name));
    }

    private static String canonicalScalar(String name) {
        // This is intentionally narrower than all Oreslang numeric types.
        // Expand only after a shared native/JVM width-aware ABI decision.
        return switch (name) {
            case "String" -> "string";
            case "f64" -> "float";
            case "int", "bool", "string", "float" -> name;
            default -> throw new IllegalArgumentException(
                    "unproven or noncanonical fixed scalar: " + name);
        };
    }
}
