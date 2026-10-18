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

    public record Proof(Map<Key, List<Candidate>> families, Map<String, EnumInfo> enums) {
        public Proof {
            Map<Key, List<Candidate>> immutable = new LinkedHashMap<>();
            families.forEach((key, candidates) -> immutable.put(key, List.copyOf(candidates)));
            families = Map.copyOf(immutable);
            enums = Map.copyOf(enums);
        }

        /**
         * Resolves types ONLY from compiler-proven local qualified parameter
         * type refs. It is forbidden to derive them from runtime enum objects.
         */
        public Candidate select(Key key, List<Ast.TypeRef> staticArgumentTypes) {
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
        Map<String, Map<String, String>> localEnums = new HashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            String owner = scope + "." + module.name();
            Map<String, String> moduleEnums = new LinkedHashMap<>();
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
                for (Ast.EnumCase entry : en.cases()) {
                    if (entry.tag() <= prior || !cases.add(entry.name())) {
                        throw new IllegalArgumentException("duplicate/unordered enum case identity or tag");
                    }
                    prior = entry.tag();
                    if (!eligiblePayload(entry.payload())) eligible = false;
                }
                // Record constructor enforces case-name limits and nonempty.
                enums.put(id, new EnumInfo(id, List.copyOf(cases), eligible));
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
                            || !fn.genericParameters().isEmpty()) continue;
                    Key key = new Key(Domain.MODULE, owner, fn.name(), fn.parameters().size());
                    pending.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new Pending(fn.parameters(), fn, null, visible));
                } else if (decl instanceof Ast.ClassDecl cls) {
                    if (cls.actorKind() != Ast.ActorKind.NONE
                            || !cls.genericParameters().isEmpty() || !cls.parents().isEmpty()) continue;
                    String methodOwner = owner + "." + cls.name();
                    for (Ast.MethodDecl method : cls.methods()) {
                        if (!method.genericParameters().isEmpty()) continue;
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
        return new Proof(families, enums);
    }

    private record Pending(List<Ast.Param> parameters, Ast.FunctionDecl function,
                           Ast.MethodDecl method, Map<String, String> localEnums) { }

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
                    Parameter.fixed(name.equals("String") ? "string" : name);
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
        if (name.equals("String")) name = "string";
        return Parameter.fixed(name);
    }
}
