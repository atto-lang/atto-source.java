package dev.oreslang.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closed-world source-level proxy expansion, before type and ownership checking.
 *
 * A source expression new Proxy(new Foo(...)) is parsed as
 * new FooProxy(new Foo(...)); this pass emits the ordinary FooProxy class.
 * Neither runtime reflection nor runtime class construction is involved.
 *
 * v1 mirrors public, synchronous, non-generic instance methods only. A
 * feature we cannot faithfully mirror must fail closed, not silently be
 * omitted from the generated API.
 */
public final class StaticProxySynthesis {
    private static final String TARGET_FIELD = "__proxy_target";

    private StaticProxySynthesis() { }

    public static Ast.Program expand(Ast.Program program, Set<String> requestedNames) {
        if (requestedNames.isEmpty()) return program;
        Set<String> requested = Set.copyOf(requestedNames);
        Map<String, Target> found = new LinkedHashMap<>();
        Set<String> unresolvedTypeAliases = new HashSet<>();
        // Resolution of generated nominal types is not yet namespace-qualified.
        // A declaration in another module must not capture a synthetic name.
        Set<String> occupiedNames = new HashSet<>();
        Set<String> importedNames = new HashSet<>();
        for (Ast.ImportDecl imported : program.imports()) {
            // Without a completed qualified symbol table an open import might
            // resolve to FooProxy (or even Proxy) after synthesis. Fail closed.
            if (imported.wildcard()) {
                throw fail("wildcard imports require qualified proxy name resolution");
            }
            occupiedNames.addAll(imported.names());
            importedNames.addAll(imported.names());
            if (imported.namespace() != null) {
                occupiedNames.add(imported.namespace());
                importedNames.add(imported.namespace());
            }
        }
        if (occupiedNames.contains("Proxy")) {
            throw fail("an imported Proxy name conflicts with the compiler intrinsic");
        }
        for (String target : requested) {
            if (importedNames.contains(target)) {
                throw fail("proxy target '" + target
                        + "' collides with an imported name or namespace alias");
            }
        }
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) occupiedNames.add(klass.name());
                if (declaration instanceof Ast.FunctionDecl fn) occupiedNames.add(fn.name());
                if (declaration instanceof Ast.FieldDecl field) occupiedNames.add(field.name());
                if (declaration instanceof Ast.InterfaceDecl iface) occupiedNames.add(iface.name());
                if (declaration instanceof Ast.TypeAliasDecl alias) {
                    occupiedNames.add(alias.name());
                    unresolvedTypeAliases.add(alias.name());
                }
                if (declaration instanceof Ast.ClassDecl klass && requested.contains(klass.name())) {
                    if (found.putIfAbsent(klass.name(), new Target(module.name(), klass)) != null) {
                        throw fail("class '" + klass.name()
                                + "' is ambiguous across modules; qualify the target in a later proxy version");
                    }
                }
                if (declaration instanceof Ast.ClassDecl klass && klass.name().equals("Proxy")) {
                    throw fail("the class name 'Proxy' conflicts with the compiler intrinsic");
                }
            }
        }
        for (String name : requested) {
            if (!found.containsKey(name)) {
                throw fail("target '" + name
                        + "' must be an ordinary class declared in this compilation unit");
            }
        }
        List<Ast.ModuleDecl> modules = new ArrayList<>(program.modules().size());
        for (Ast.ModuleDecl module : program.modules()) {
            List<Ast.Decl> output = new ArrayList<>(module.declarations());
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.ClassDecl klass)
                        || !requested.contains(klass.name())) continue;
                String generatedName = klass.name() + "Proxy";
                if (!occupiedNames.add(generatedName)) {
                    throw fail("generated class '" + generatedName
                            + "' collides with an existing or generated declaration");
                }
                output.add(proxyClass(klass, generatedName, unresolvedTypeAliases));
            }
            modules.add(new Ast.ModuleDecl(
                    module.name(), module.annotations(), module.contracts(), output));
        }
        // Never erase file-level export contracts during synthetic class expansion.
        return new Ast.Program(program.namespace(), program.imports(), modules, program.exports());
    }

    private record Target(String module, Ast.ClassDecl klass) { }

    private static Ast.ClassDecl proxyClass(Ast.ClassDecl target, String generatedName,
                                           Set<String> unresolvedTypeAliases) {
        if (target.actorKind() != Ast.ActorKind.NONE)
            throw fail("actors cannot be wrapped as ordinary class proxies: " + target.name());
        if (target.isAbstract() || !target.parents().isEmpty() || !target.interfaces().isEmpty())
            throw fail("abstract/inherited/implemented class surfaces need complete method resolution: " + target.name());
        if (!target.genericParameters().isEmpty())
            throw fail("generic class specialization is not implemented for proxy " + target.name());
        if (target.constructor() != null && !target.constructor().annotations().isEmpty()) {
            throw fail("annotated target constructor needs checked proxy metadata: "
                    + target.name());
        }
        for (Ast.FieldDecl field : target.fields()) {
            if (!field.annotations().isEmpty())
                throw fail("annotated fields need expanded method-surface proofs before proxying " + target.name());
        }

        Ast.TypeRef targetType = Ast.TypeRef.simple(target.name());
        Ast.FieldDecl delegate = new Ast.FieldDecl(TARGET_FIELD, Ast.Visibility.PRIVATE,
                Ast.BindingKind.VAL, targetType, List.of(), null, false);
        Ast.ConstructorDecl constructor = new Ast.ConstructorDecl(target.visibility(),
                List.of(new Ast.Param(targetType, "target")), List.of(),
                List.of(new Ast.ExprStmt(new Ast.AssignExpr(
                        new Ast.MemberExpr(new Ast.NameExpr("self"), TARGET_FIELD),
                        new Ast.NameExpr("target")))));

        List<Ast.MethodDecl> methods = new ArrayList<>();
        Set<Ast.MethodArity> emittedSlots = new HashSet<>();
        for (Ast.MethodDecl method : target.methods()) {
            // Class functions are not instance members. A later, explicit
            // class-facade feature may mirror static functions separately.
            if (method.isStatic() || method.visibility() != Ast.Visibility.PUBLIC) continue;
            if (method.isAbstract() || method.async() || method.suspends()
                    || method.structural() || method.explicitReceiverType() != null
                    || !method.genericParameters().isEmpty() || !method.effects().isEmpty()) {
                throw fail("method '" + target.name() + "." + method.name() + "/"
                        + method.arity() + "' requires unsupported proxy effects/receiver/generics");
            }
            if (!method.annotations().isEmpty()) {
                // A typed annotation may change callable policy, argument defaults,
                // return handling or codegen. Dropping it is not a safe delegation.
                throw fail("annotated method requires checked proxy metadata: "
                        + target.name() + "." + method.name());
            }
            if (!emittedSlots.add(method.overloadIdentity())) {
                throw fail("ambiguous proxy method slot: " + target.name() + "."
                        + method.name() + "/" + method.arity());
            }
            if (unsafeSignatureType(method.returnType(), unresolvedTypeAliases)) {
                throw fail("proxy return type needs ownership/borrow lowering: "
                        + target.name() + "." + method.name());
            }
            if (exposesTargetType(method.returnType(), target.name())) {
                // Until trusted self-return rewriting exists, a returned Foo or
                // Option<Foo> can expose the same target behind FooProxy and
                // silently bypass any proxy-surface policy.
                throw fail("proxy return type can expose the wrapped target: "
                        + target.name() + "." + method.name());
            }
            if (erasesTargetIdentity(method.returnType())) {
                // Foo may be returned through Any/Object/dynamic (including a
                // nested container). Until compiler alias and return-value
                // provenance checks exist, do not expose an opaque result.
                throw fail("proxy return type may erase wrapped-target identity: "
                        + target.name() + "." + method.name());
            }
            if (method.name().equals(TARGET_FIELD))
                throw fail("proxy storage name collides with callable " + target.name() + "." + TARGET_FIELD);
            List<Ast.Expr> arguments = new ArrayList<>();
            for (Ast.Param parameter : method.parameters()) {
                if ("self".equals(parameter.name()) || TARGET_FIELD.equals(parameter.name()))
                    throw fail("proxy parameter shadows generated receiver/storage: "
                            + target.name() + "." + method.name());
                if (parameter.structural() || parameter.mutable()
                        || unsafeSignatureType(parameter.type(), unresolvedTypeAliases))
                    throw fail("proxy parameter borrows/structural contracts need proven reborrowing: "
                            + target.name() + "." + method.name());
                arguments.add(new Ast.NameExpr(parameter.name()));
            }
            Ast.CallExpr call = new Ast.CallExpr(
                    new Ast.MemberExpr(
                            new Ast.MemberExpr(new Ast.NameExpr("self"), TARGET_FIELD),
                            method.name()), arguments);
            List<Ast.Stmt> body = "void".equals(method.returnType().name())
                    ? List.of(new Ast.ExprStmt(call), new Ast.ReturnStmt(null))
                    : List.of(new Ast.ReturnStmt(call));
            methods.add(new Ast.MethodDecl(method.name(), Ast.Visibility.PUBLIC,
                    false, false, false, false, false, null, List.of(),
                    method.parameters(), method.returnType(), List.of(), body));
        }
        return new Ast.ClassDecl(generatedName, target.visibility(), false,
                Ast.ActorKind.NONE, List.of(), List.of(), List.of(),
                List.of(delegate), constructor, methods);
    }

    /**
     * Avoid forwarding borrowed/callable/inferred values until the compiler
     * proves the target lifetime, alias rights and closure transport. Recurse
     * through nested generic arguments (e.g. Option<borrow Foo>) as well.
     */
    private static boolean unsafeSignatureType(Ast.TypeRef type, Set<String> unresolvedTypeAliases) {
        // An alias can hide the wrapped target, a mutable aggregate or a
        // borrowed/callable signature. Do not forward until nominal resolution
        // proves its definition and transitive ownership/effect requirements.
        if (unresolvedTypeAliases.contains(type.name())
                || type.isBorrow() || type.isStructType() || type.inferArguments()
                || type.name().equals("Fnc") || type.name().equals("Function")
                || type.name().equals("Routine")
                || type.name().equals("$infer$")) return true;
        for (Ast.TypeRef arg : type.arguments()) {
            if (unsafeSignatureType(arg, unresolvedTypeAliases)) return true;
        }
        return false;
    }

    private static boolean exposesTargetType(Ast.TypeRef type, String targetName) {
        if (type.name().equals(targetName) || type.name().equals("self")) return true;
        for (Ast.TypeRef arg : type.arguments()) {
            if (exposesTargetType(arg, targetName)) return true;
        }
        return false;
    }

    /**
     * A statically declared Foo return is already rejected above, but an opaque
     * supertype or erased nested payload may hide Foo at runtime. Until the
     * compiler proves an identity-preserving adapter, fail closed. Future
     * qualified type resolution can replace this conservative name check.
     */
    private static boolean erasesTargetIdentity(Ast.TypeRef type) {
        String name = type.name();
        if (name.equals("Any") || name.equals("any")
                || name.equals("Object") || name.equals("object")
                || name.equals("Unknown") || name.equals("unknown")
                || name.equals("Dynamic") || name.equals("dynamic")
                || name.equals("?")) return true;
        for (Ast.TypeRef arg : type.arguments()) {
            if (erasesTargetIdentity(arg)) return true;
        }
        return false;
    }

    private static IllegalArgumentException fail(String message) {
        return new IllegalArgumentException("AOT_PROXY_UNSUPPORTED: " + message);
    }
}
