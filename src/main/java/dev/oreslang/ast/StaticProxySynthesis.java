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
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
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
            Set<String> occupied = new HashSet<>();
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) occupied.add(klass.name());
                if (declaration instanceof Ast.FunctionDecl fn) occupied.add(fn.name());
                if (declaration instanceof Ast.FieldDecl field) occupied.add(field.name());
                if (declaration instanceof Ast.InterfaceDecl iface) occupied.add(iface.name());
                if (declaration instanceof Ast.TypeAliasDecl alias) occupied.add(alias.name());
            }
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.ClassDecl klass)
                        || !requested.contains(klass.name())) continue;
                String generatedName = klass.name() + "Proxy";
                if (occupied.contains(generatedName)) {
                    throw fail("generated class '" + generatedName + "' collides with an existing declaration");
                }
                occupied.add(generatedName);
                output.add(proxyClass(klass, generatedName));
            }
            modules.add(new Ast.ModuleDecl(
                    module.name(), module.annotations(), module.contracts(), output));
        }
        return new Ast.Program(program.namespace(), program.imports(), modules);
    }

    private record Target(String module, Ast.ClassDecl klass) { }

    private static Ast.ClassDecl proxyClass(Ast.ClassDecl target, String generatedName) {
        if (target.actorKind() != Ast.ActorKind.NONE)
            throw fail("actors cannot be wrapped as ordinary class proxies: " + target.name());
        if (target.isAbstract() || !target.parents().isEmpty() || !target.interfaces().isEmpty())
            throw fail("abstract/inherited/implemented class surfaces need complete method resolution: " + target.name());
        if (!target.genericParameters().isEmpty())
            throw fail("generic class specialization is not implemented for proxy " + target.name());
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
            if (method.name().equals(TARGET_FIELD))
                throw fail("proxy storage name collides with callable " + target.name() + "." + TARGET_FIELD);
            List<Ast.Expr> arguments = new ArrayList<>();
            for (Ast.Param parameter : method.parameters()) {
                if (parameter.structural() || parameter.mutable())
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

    private static IllegalArgumentException fail(String message) {
        return new IllegalArgumentException("AOT_PROXY_UNSUPPORTED: " + message);
    }
}
