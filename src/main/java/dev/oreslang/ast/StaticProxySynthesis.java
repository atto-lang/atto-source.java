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
    private static final String HOOK_FIELD = "__proxy_hooks";

    /** A statically known interceptor construction request. */
    public record InterceptorRequest(String targetName, String interceptorName) {
        public InterceptorRequest {
            if (targetName == null || interceptorName == null
                    || !targetName.matches("[A-Za-z_][A-Za-z_0-9]*")
                    || !interceptorName.matches("[A-Za-z_][A-Za-z_0-9]*")) {
                throw fail("interceptor target and type names must be plain identifiers");
            }
        }

        public String generatedName() {
            return targetName + "With" + interceptorName + "Proxy";
        }
    }

    private StaticProxySynthesis() { }

    public static Ast.Program expand(Ast.Program program, Set<String> requestedNames) {
        return expand(program, requestedNames, List.of());
    }

    public static Ast.Program expand(Ast.Program program, Set<String> requestedNames,
                                     List<InterceptorRequest> interceptorRequests) {
        if (requestedNames.isEmpty() && interceptorRequests.isEmpty()) return program;
        Set<String> requested = new HashSet<>(requestedNames);
        // A single compilation unit can construct many proxy instances of
        // the same target/hook type. One synthetic nominal class is shared
        // across those call sites. Preserve source appearance order to give
        // deterministic generated declaration order for exact-tree builds.
        List<InterceptorRequest> uniqueRequests =
                List.copyOf(new java.util.LinkedHashSet<>(interceptorRequests));
        for (InterceptorRequest request : uniqueRequests) requested.add(request.targetName());
        Map<String, Target> interceptors = new HashMap<>();
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
        for (InterceptorRequest request : uniqueRequests) {
            if (importedNames.contains(request.interceptorName())) {
                throw fail("proxy interceptor '" + request.interceptorName()
                        + "' collides with an imported name or namespace alias");
            }
        }
        for (String target : requested) {
            if (importedNames.contains(target)) {
                throw fail("proxy target '" + target
                        + "' collides with an imported name or namespace alias");
            }
        }
        // Newer main AST preserves explicit export metadata and protected
        // declaration types. A generated nominal name must never shadow an
        // exported or protected declaration.
        for (Ast.ExportDecl export : program.exports()) {
            occupiedNames.add(export.name());
        }
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) occupiedNames.add(klass.name());
                if (declaration instanceof Ast.StateContainerDecl state) occupiedNames.add(state.name());
                if (declaration instanceof Ast.VirtualActorDecl actor) occupiedNames.add(actor.name());
                if (declaration instanceof Ast.GpuActorDecl gpu) occupiedNames.add(gpu.name());
                if (declaration instanceof Ast.StandaloneActorDecl actor) occupiedNames.add(actor.name());
                if (declaration instanceof Ast.FunctionDecl fn) occupiedNames.add(fn.name());
                if (declaration instanceof Ast.FieldDecl field) occupiedNames.add(field.name());
                if (declaration instanceof Ast.InterfaceDecl iface) occupiedNames.add(iface.name());
                if (declaration instanceof Ast.TypeAliasDecl alias) {
                    occupiedNames.add(alias.name());
                    unresolvedTypeAliases.add(alias.name());
                }
                if (declaration instanceof Ast.ClassDecl klass
                        && uniqueRequests.stream().anyMatch(r -> r.interceptorName().equals(klass.name()))) {
                    if (interceptors.putIfAbsent(klass.name(), new Target(module.name(), klass)) != null) {
                        throw fail("proxy interceptor class '" + klass.name()
                                + "' is ambiguous across modules");
                    }
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
        for (InterceptorRequest request : uniqueRequests) {
            Target target = found.get(request.targetName());
            Target hook = interceptors.get(request.interceptorName());
            if (hook == null) {
                throw fail("interceptor '" + request.interceptorName()
                        + "' must be an ordinary class declared in this compilation unit");
            }
            if (!target.module().equals(hook.module())) {
                throw fail("interceptor and target must be declared in the same module: "
                        + request.targetName() + ", " + request.interceptorName());
            }
        }
        List<Ast.ModuleDecl> modules = new ArrayList<>(program.modules().size());
        for (Ast.ModuleDecl module : program.modules()) {
            List<Ast.Decl> output = new ArrayList<>(module.declarations());
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.ClassDecl klass)
                        || !requested.contains(klass.name())) continue;
                if (requestedNames.contains(klass.name())) {
                    String generatedName = klass.name() + "Proxy";
                    if (!occupiedNames.add(generatedName)) {
                        throw fail("generated class '" + generatedName
                                + "' collides with an existing or generated declaration");
                    }
                    output.add(proxyClass(klass, generatedName, unresolvedTypeAliases));
                }
                for (InterceptorRequest request : uniqueRequests) {
                    if (!request.targetName().equals(klass.name())) continue;
                    String generatedName = request.generatedName();
                    if (!occupiedNames.add(generatedName)) {
                        throw fail("generated class '" + generatedName
                                + "' collides with an existing or generated declaration");
                    }
                    output.add(interceptorProxyClass(
                            program, klass, interceptors.get(request.interceptorName()).klass(),
                            generatedName, unresolvedTypeAliases));
                }
            }
            modules.add(new Ast.ModuleDecl(
                    module.name(), module.annotations(), module.contracts(), output));
        }
        // Preserve current-main export declarations. The legacy three-arg
        // constructor silently discards exports and is not safe for rewrites.
        return new Ast.Program(program.namespace(), program.imports(), modules, program.exports());
    }

    private record Target(String module, Ast.ClassDecl klass) { }

    private static Ast.ClassDecl proxyClass(Ast.ClassDecl target, String generatedName,
                                           Set<String> unresolvedTypeAliases) {
        if (target.actorKind() != Ast.ActorKind.NONE)
            throw fail("actors cannot be wrapped as ordinary class proxies: " + target.name());
        if (!target.annotations().isEmpty())
            throw fail("annotated target class requires checked proxy policy metadata: " + target.name());
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
            if (method.isAbstract() || method.async() || method.suspends() || method.critical()
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
     * Typed, synchronous "before" interceptor slice. The ordinary method
     * vtable dispatches the hook, without reflective method enumeration or
     * a dynamically manufactured callable. A checked one-shot around/proceed
     * continuation is deliberately NOT implemented here.
     */
    private static Ast.ClassDecl interceptorProxyClass(
            Ast.Program program, Ast.ClassDecl target, Ast.ClassDecl interceptor, String generatedName,
            Set<String> unresolvedTypeAliases) {
        if (!interceptor.annotations().isEmpty()) {
            throw fail("annotated interceptor class requires checked proxy policy metadata: "
                    + interceptor.name());
        }
        if (interceptor.actorKind() != Ast.ActorKind.NONE || interceptor.isAbstract()
                || !interceptor.parents().isEmpty() || !interceptor.interfaces().isEmpty()
                || !interceptor.genericParameters().isEmpty()) {
            throw fail("interceptor must be a concrete, nonactor, nongeneric local class: "
                    + interceptor.name());
        }
        Ast.ClassDecl delegate = proxyClass(target, generatedName, unresolvedTypeAliases);
        if (delegate.methods().isEmpty()) {
            throw fail("interceptor target exposes no eligible public instance methods: "
                    + target.name());
        }
        for (Ast.MethodDecl forwarded : delegate.methods()) {
            if (forwarded.name().equals(HOOK_FIELD)) {
                throw fail("proxy storage name collides with callable "
                        + target.name() + "." + HOOK_FIELD);
            }
            for (Ast.Param parameter : forwarded.parameters()) {
                if (HOOK_FIELD.equals(parameter.name())) {
                    throw fail("proxy parameter shadows generated receiver/storage: "
                            + target.name() + "." + forwarded.name());
                }
                // A before hook sees the argument and then the target sees it.
                // Without affine continuation/loan lowering this is sound only
                // for obviously copyable, immutable scalar values.
                if (!parameter.type().arguments().isEmpty()
                        || !Set.of("int", "float", "bool", "String", "string")
                                .contains(parameter.type().name())) {
                    throw fail("before interception requires copyable scalar arguments: "
                            + target.name() + "." + forwarded.name());
                }
            }
        }
        Map<Ast.MethodArity, Ast.MethodDecl> hooks = new HashMap<>();
        for (Ast.MethodDecl hook : interceptor.methods()) {
            if (!hook.name().startsWith("before_")) continue;
            if (hooks.putIfAbsent(hook.overloadIdentity(), hook) != null) {
                throw fail("ambiguous interceptor slot: " + interceptor.name() + "."
                        + hook.name() + "/" + hook.arity());
            }
        }

        List<Ast.MethodDecl> methods = new ArrayList<>();
        List<Ast.MethodDecl> verifiedHookRoots = new ArrayList<>();
        Set<Ast.MethodArity> consumed = new HashSet<>();
        for (Ast.MethodDecl method : delegate.methods()) {
            Ast.MethodArity slot = new Ast.MethodArity("before_" + method.name(), method.arity());
            Ast.MethodDecl hook = hooks.get(slot);
            if (hook == null) {
                throw fail("missing typed interceptor method " + interceptor.name() + "."
                        + slot.name() + "/" + slot.arity());
            }
            consumed.add(slot);
            if (hook.visibility() != Ast.Visibility.PUBLIC || hook.isStatic()
                    || hook.isAbstract() || hook.async() || hook.suspends() || hook.critical()
                    || hook.structural() || hook.explicitReceiverType() != null
                    || !hook.genericParameters().isEmpty() || !hook.effects().isEmpty()
                    || !hook.annotations().isEmpty()
                    || !hook.returnType().equals(Ast.TypeRef.simple("void"))) {
                throw fail("interceptor method must be public, synchronous, void and have no declared checked exceptional effects: "
                        + interceptor.name() + "." + hook.name());
            }
            // The shared closed-world verifier below has an explicit AST
            // node/depth budget. Do not recursively scan hook syntax here:
            // that old prepass was unbounded and could overflow the Java stack
            // before the bounded admission gate ran.
            // Collect validated entrypoints before checking the shared
            // call graph once. This avoids a separate full method-table
            // allocation and repeated helper traversal for every public hook.
            verifiedHookRoots.add(hook);
            for (int i = 0; i < hook.parameters().size(); i++) {
                Ast.Param provided = hook.parameters().get(i);
                Ast.Param expected = method.parameters().get(i);
                if (provided.structural() || provided.mutable()
                        || !provided.type().equals(expected.type())
                        || "self".equals(provided.name()) || TARGET_FIELD.equals(provided.name())
                        || HOOK_FIELD.equals(provided.name())) {
                    throw fail("interceptor parameter contract mismatch: "
                            + interceptor.name() + "." + hook.name());
                }
            }
            List<Ast.Expr> args = new ArrayList<>();
            for (Ast.Param parameter : method.parameters()) {
                args.add(new Ast.NameExpr(parameter.name()));
            }
            Ast.CallExpr hookCall = new Ast.CallExpr(
                    new Ast.MemberExpr(
                            new Ast.MemberExpr(new Ast.NameExpr("self"), HOOK_FIELD),
                            hook.name()), args);
            List<Ast.Stmt> body = new ArrayList<>();
            body.add(new Ast.ExprStmt(hookCall));
            body.addAll(method.body());
            methods.add(new Ast.MethodDecl(
                    method.name(), method.visibility(), method.isStatic(), method.isAbstract(),
                    method.async(), method.suspends(), method.structural(),
                    method.explicitReceiverType(), method.genericParameters(),
                    method.parameters(), method.returnType(), method.annotations(), body,
                    method.effects()));
        }
        for (Ast.MethodArity slot : hooks.keySet()) {
            if (!consumed.contains(slot)) {
                throw fail("interceptor method does not match any forwarded public method: "
                        + interceptor.name() + "." + slot.name() + "/" + slot.arity());
            }
        }

        // Check transitive effects after all hooks have been matched to
        // target slots. Duplicate helper edges share one bounded proof.
        StaticProxyHookEffectClosure.verifyAll(program, interceptor, verifiedHookRoots);

        List<Ast.FieldDecl> fields = new ArrayList<>(delegate.fields());
        fields.add(new Ast.FieldDecl(HOOK_FIELD, Ast.Visibility.PRIVATE,
                Ast.BindingKind.VAL, Ast.TypeRef.simple(interceptor.name()),
                List.of(), null, false));
        List<Ast.Param> parameters = List.of(
                new Ast.Param(Ast.TypeRef.simple(target.name()), "target"),
                new Ast.Param(Ast.TypeRef.simple(interceptor.name()), "hooks"));
        List<Ast.Stmt> constructorBody = new ArrayList<>(delegate.constructor().body());
        constructorBody.add(new Ast.ExprStmt(new Ast.AssignExpr(
                new Ast.MemberExpr(new Ast.NameExpr("self"), HOOK_FIELD),
                new Ast.NameExpr("hooks"))));
        Ast.ConstructorDecl constructor = new Ast.ConstructorDecl(
                delegate.constructor().visibility(), parameters, List.of(), constructorBody);
        return new Ast.ClassDecl(generatedName, target.visibility(), false,
                Ast.ActorKind.NONE, List.of(), List.of(), List.of(), fields, constructor, methods);
    }

    /**
     * Conservative syntax gate for the before-only slice. This is NOT a
     * transitive call-graph proof: helper-method effects remain unverified and
     * the draft must not be promoted until call targets are effect-checked.
     * Unknown AST forms fail closed instead of accidentally discarding a
     * suspension signal when the target method is invoked next.
     */
    /**
     * Avoid forwarding borrowed/callable/inferred values until the compiler
     * proves the target lifetime, alias rights and closure transport. Recurse
     * through nested generic arguments (e.g. Option<borrow Foo>) as well.
     */
    // Bound metadata traversal before type/ownership resolution. Direct
    // AST producers can build nesting far deeper than the normal parser's
    // accepted surface grammar; the previous three recursive walks risked
    // StackOverflowError before issuing a compiler diagnostic.
    private static final int MAX_SIGNATURE_NESTING = 64;
    private static final int MAX_SIGNATURE_NODES = 4096;

    private record SignatureNode(Ast.TypeRef type, int depth) { }

    private static boolean anyNestedSignatureType(
            Ast.TypeRef root, java.util.function.Predicate<Ast.TypeRef> unsafe) {
        if (root == null) throw fail("proxy signature type is missing");
        java.util.ArrayDeque<SignatureNode> pending = new java.util.ArrayDeque<>();
        pending.push(new SignatureNode(root, 0));
        int count = 0;
        while (!pending.isEmpty()) {
            SignatureNode node = pending.pop();
            if (node.depth() > MAX_SIGNATURE_NESTING || ++count > MAX_SIGNATURE_NODES) {
                throw fail("proxy signature type exceeds nesting/node budget");
            }
            Ast.TypeRef type = node.type();
            if (type == null || type.name() == null) {
                throw fail("proxy signature contains a missing type name");
            }
            if (unsafe.test(type)) return true;
            List<Ast.TypeRef> args = type.arguments();
            if (args.size() > MAX_SIGNATURE_NODES - count) {
                throw fail("proxy signature type exceeds nesting/node budget");
            }
            // Reverse-push so the original argument order is deterministic.
            for (int i = args.size() - 1; i >= 0; i--) {
                Ast.TypeRef child = args.get(i);
                if (child == null) throw fail("proxy signature contains a missing nested type");
                pending.push(new SignatureNode(child, node.depth() + 1));
            }
        }
        return false;
    }

    private static boolean unsafeSignatureType(Ast.TypeRef type, Set<String> unresolvedTypeAliases) {
        // An alias can hide the wrapped target, mutable aggregates, or
        // borrowed/callable types; reject until qualified resolution proves
        // the complete transitive ownership/effect contract.
        return anyNestedSignatureType(type, current ->
                unresolvedTypeAliases.contains(current.name())
                || current.isBorrow() || current.isStructType() || current.inferArguments()
                || current.name().equals("Fnc") || current.name().equals("Function")
                || current.name().equals("Routine")
                || current.name().equals("$infer$"));
    }

    private static boolean exposesTargetType(Ast.TypeRef type, String targetName) {
        return anyNestedSignatureType(type, current ->
                current.name().equals(targetName) || current.name().equals("self"));
    }

    /**
     * An opaque supertype or erased nested payload can conceal the wrapped
     * target identity. Without an identity-preserving adapter, reject.
     */
    private static boolean erasesTargetIdentity(Ast.TypeRef type) {
        return anyNestedSignatureType(type, current ->
                Set.of("Any", "any", "Object", "object", "Unknown", "unknown",
                        "Dynamic", "dynamic", "?").contains(current.name()));
    }

    private static IllegalArgumentException fail(String message) {
        return new IllegalArgumentException("AOT_PROXY_UNSUPPORTED: " + message);
    }
}
