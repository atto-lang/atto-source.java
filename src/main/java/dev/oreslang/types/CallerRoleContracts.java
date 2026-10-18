package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time, non-transitive caller-role metadata. Roles are NOMINAL enum
 * case identities, never strings supplied by a caller or runtime parameters.
 *
 * This is an admission/proof component, not an executable permission system.
 * The staged enum frontend is still rejected at the public typecheck/runtime
 * ingress until nominal-enum lowering is integrated across backends.
 */
public final class CallerRoleContracts {
    private CallerRoleContracts() { }

    public static final class Index {
        private final IdentityHashMap<Object, Set<String>> granted = new IdentityHashMap<>();
        private final IdentityHashMap<Object, Set<String>> permitted = new IdentityHashMap<>();

        private Index() { }

        /** The roles of THIS lexical callable, not its ancestors or callbacks. */
        public Set<String> callerRoles(Object declaration) {
            return granted.getOrDefault(declaration, Set.of());
        }

        public boolean isRestricted(Object declaration) {
            return permitted.containsKey(declaration);
        }

        /**
         * Enforce only AFTER a call has been bound to an exact declaration.
         * A name-only or arbitrary runtime lookup must never be used as proof.
         */
        public void requireDirectCall(Object callee, Set<String> currentCallerRoles, String displayName) {
            Set<String> allowed = permitted.get(callee);
            if (allowed == null) return;
            for (String role : currentCallerRoles) {
                if (allowed.contains(role)) return;
            }
            throw new IllegalArgumentException("@CallableBy denies direct call to '" + displayName
                    + "': caller lacks an authorized @CallerRole enum case");
        }

        /** A guarded callable must not be erased into a function value. */
        public void rejectReification(Object declaration, String displayName) {
            if (isRestricted(declaration)) {
                throw new IllegalArgumentException("@CallableBy function '" + displayName
                        + "' is direct-call-only; indirect calls and method references "
                        + "need compiler-bound caller identity");
            }
        }
    }

    public static Index validate(Ast.Program program) {
        if (program == null) throw new IllegalArgumentException("missing AST program");
        Map<String, Ast.EnumDecl> enums = new HashMap<>();
        Set<String> namespaces = new HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            if (!namespaces.add(module.name())) {
                throw new IllegalArgumentException("duplicate module '" + module.name() + "'");
            }
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.EnumDecl e) {
                    String key = module.name() + "." + e.name();
                    if (enums.putIfAbsent(key, e) != null) {
                        throw new IllegalArgumentException("duplicate caller-role enum '" + key + "'");
                    }
                }
            }
        }
        Index index = new Index();
        for (Ast.ModuleDecl module : program.modules()) {
            rejectOtherTargets(module.annotations(), "module '" + module.name() + "'");
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    put(index, fn, fn.annotations(), module.name(), enums,
                            "function/routine '" + module.name() + "." + fn.name() + "'");
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    if (klass.constructor() != null) {
                        rejectOtherTargets(klass.constructor().annotations(),
                                "constructor of '" + klass.name() + "'");
                    }
                    for (Ast.FieldDecl field : klass.fields()) {
                        rejectOtherTargets(field.annotations(), "field '" + klass.name() + "." + field.name() + "'");
                    }
                    for (Ast.MethodDecl method : klass.methods()) {
                        put(index, method, method.annotations(), module.name(), enums,
                                "method '" + klass.name() + "." + method.name() + "'");
                    }
                } else if (declaration instanceof Ast.FieldDecl field) {
                    rejectOtherTargets(field.annotations(), "module binding '" + field.name() + "'");
                }
            }
        }
        return index;
    }

    private static void rejectOtherTargets(List<Ast.Annotation> annotations, String target) {
        for (Ast.Annotation a : annotations) {
            if (a.name().equals("CallerRole") || a.name().equals("CallableBy")) {
                throw new IllegalArgumentException("@" + a.name()
                        + " requires a function, routine, or method; invalid on " + target);
            }
        }
    }

    private static void put(Index index, Object declaration, List<Ast.Annotation> annotations,
                            String module, Map<String, Ast.EnumDecl> enums, String label) {
        boolean sawCallerRole = false;
        boolean sawCallableBy = false;
        for (Ast.Annotation annotation : annotations) {
            boolean caller = annotation.name().equals("CallerRole");
            boolean allowed = annotation.name().equals("CallableBy");
            if (!caller && !allowed) continue;
            if (caller ? sawCallerRole : sawCallableBy) {
                throw new IllegalArgumentException("duplicate @" + annotation.name() + " on " + label);
            }
            if (caller) sawCallerRole = true;
            else sawCallableBy = true;
            if (annotation.arguments().isEmpty() || annotation.arguments().size() > 64) {
                throw new IllegalArgumentException("@" + annotation.name()
                        + " requires 1..64 qualified enum case arguments on " + label);
            }
            Set<String> resolved = new LinkedHashSet<>();
            for (Ast.TypeRef argument : annotation.arguments()) {
                String identity = resolveRole(argument, module, enums, annotation.name());
                if (!resolved.add(identity)) {
                    throw new IllegalArgumentException("duplicate role '" + identity
                            + "' in @" + annotation.name() + " on " + label);
                }
            }
            Set<String> frozen = Collections.unmodifiableSet(resolved);
            if (caller) index.granted.put(declaration, frozen);
            else index.permitted.put(declaration, frozen);
        }
    }

    private static String resolveRole(Ast.TypeRef value, String module,
                                      Map<String, Ast.EnumDecl> enums, String annotation) {
        if (value == null || value.inferArguments() || !value.arguments().isEmpty()
                || value.isStringLiteral() || value.isIntLiteral() || value.isBoolLiteral()
                || value.isNamedParameter() || value.name().startsWith("$")) {
            throw new IllegalArgumentException("@" + annotation
                    + " requires Enum.Case or Module.Enum.Case, not a literal or type expression");
        }
        String spelling = value.name();
        int lastDot = spelling.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == spelling.length() - 1) {
            throw new IllegalArgumentException("@" + annotation
                    + " requires Enum.Case or Module.Enum.Case, got '" + spelling + "'");
        }
        String typeName = spelling.substring(0, lastDot);
        String caseName = spelling.substring(lastDot + 1);
        String identity = typeName.contains(".") ? typeName : module + "." + typeName;
        Ast.EnumDecl e = enums.get(identity);
        if (e == null) {
            throw new IllegalArgumentException("@" + annotation + " refers to unknown or unlinked enum '" + identity + "'");
        }
        for (Ast.EnumCase variant : e.cases()) {
            if (variant.name().equals(caseName)) {
                if (variant.payload() != null) {
                    throw new IllegalArgumentException("@" + annotation
                            + " role '" + spelling + "' must be a payload-free enum case");
                }
                return identity + "." + caseName;
            }
        }
        throw new IllegalArgumentException("@" + annotation + " refers to unknown case '" + spelling + "'");
    }
}
