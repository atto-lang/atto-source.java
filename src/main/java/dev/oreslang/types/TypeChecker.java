package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.Union;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Unknown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static semantic pass run before Oreslang code is lowered/executed. */
public final class TypeChecker {
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.InterfaceDecl> interfaces = new HashMap<>();
    private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
    private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();

    private final IdentityHashMap<Ast.FunctionDecl, String> functionOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, String> classOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.InterfaceDecl, String> interfaceOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, Record> classShapeCache = new IdentityHashMap<>();

    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousInterfaces = new HashSet<>();
    private final Set<String> ambiguousTypeAliases = new HashSet<>();
    private final Set<String> importedValues = new HashSet<>();
    private final Set<Ast.TypeAliasDecl> resolvingAliases = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    public static Ast.Program check(Ast.Program program) {
        TypeChecker checker = new TypeChecker();
        checker.validateImports(program);
        checker.collect(program);
        checker.validateRoutineRecursion();
        checker.validate(program);
        OwnershipChecker.check(program);
        return program;
    }

    private void validateImports(Ast.Program program) {
        Set<String> exposed = new HashSet<>();
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.path() == null || imported.path().isBlank()) throw new IllegalArgumentException("import path cannot be empty");
            if (imported.wildcard()) {
                if (imported.namespace() == null || imported.namespace().isBlank()) throw new IllegalArgumentException("wildcard imports require a namespace alias");
                if (!exposed.add(imported.namespace())) throw new IllegalArgumentException("duplicate imported name '" + imported.namespace() + "'");
                importedValues.add(imported.namespace());
            } else {
                if (imported.names().isEmpty()) throw new IllegalArgumentException("named import must select at least one name");
                for (String name : imported.names()) {
                    if (!exposed.add(name)) throw new IllegalArgumentException("duplicate imported name '" + name + "'");
                    if (imported.kind() != Ast.ImportKind.CLASS) importedValues.add(name);
                }
            }
        }
    }

    private void collect(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            if (modules.putIfAbsent(module.name(), module) != null) throw new IllegalArgumentException("duplicate module '" + module.name() + "'");
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    putQualified(functions, ambiguousFunctions, module.name(), fn.name(), fn, fn.kind() == Ast.CallableKind.ROUTINE ? "routine" : "function");
                    functionOwners.put(fn, module.name());
                } else if (decl instanceof Ast.ClassDecl klass) {
                    putQualified(classes, ambiguousClasses, module.name(), klass.name(), klass, "class");
                    classOwners.put(klass, module.name());
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    putQualified(interfaces, ambiguousInterfaces, module.name(), iface.name(), iface, "interface");
                    interfaceOwners.put(iface, module.name());
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    putQualified(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias, "type alias");
                }
            }
        }
    }

    private static <T> void putQualified(Map<String, T> map, Set<String> ambiguous, String module, String name, T value, String kind) {
        String qualified = module + "." + name;
        if (map.putIfAbsent(qualified, value) != null) {
            throw new IllegalArgumentException("duplicate " + kind + " '" + qualified + "'; fnc/routine declarations cannot overload");
        }
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            ambiguous.add(name);
            map.remove(name);
        }
    }

    private void validate(Ast.Program program) {
        // Local overload identity must be validated before class-shape
        // construction. Otherwise same-name/same-arity declarations whose
        // types differ can fail later as a generic structural conflict before
        // the language's arity-only overload rule is diagnosed.
        for (Ast.ClassDecl klass : classOwners.keySet()) validateLocalClassOverloads(klass);
        for (Ast.ClassDecl klass : classOwners.keySet()) validateInheritedClassOverloads(klass);
        for (Ast.ClassDecl klass : classOwners.keySet()) validateAbstractMethodSlots(klass);
        for (Ast.ClassDecl klass : classOwners.keySet()) classShape(klass, new LinkedHashSet<>());
        for (Ast.InterfaceDecl iface : interfaceOwners.keySet()) interfaceShape(iface, nominalInterfaceType(iface), new LinkedHashSet<>());

        for (Ast.ModuleDecl module : program.modules()) {
            checkModuleAdherence(module);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(module.name(), klass);
                else if (decl instanceof Ast.InterfaceDecl iface) checkInterface(iface);
                else if (decl instanceof Ast.TypeAliasDecl alias) resolve(alias.target(), Set.copyOf(alias.genericParameters()), null);
                else if (decl instanceof Ast.FieldDecl field) checkModuleBinding(field);
            }
        }
    }

    private void checkModuleAdherence(Ast.ModuleDecl module) {
        for (Ast.Annotation annotation : module.annotations()) {
            if (!annotation.name().equals("AdheresTo")) continue;
            if (annotation.arguments().isEmpty()) throw new IllegalArgumentException("@AdheresTo requires at least one interface");
            Record actual = moduleShape(module);
            for (Ast.TypeRef ref : annotation.arguments()) {
                Ast.InterfaceDecl iface = findInterface(ref.name());
                if (iface == null) throw new IllegalArgumentException("unknown module interface '" + ref.name() + "'");
                Named interfaceType = resolveInterfaceView(iface, ref, Set.of(), null);
                Record expected = interfaceShape(iface, interfaceType, new LinkedHashSet<>());
                if (!assignable(actual, expected)) {
                    throw new IllegalArgumentException("module '" + module.name() + "' does not adhere to interface '" + ref.name() + "': expected " + expected + " but got " + actual);
                }
            }
        }
    }

    private Record moduleShape(Ast.ModuleDecl module) {
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                Type signature = functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
                mergeMember(members, fn.name(), signature, "module " + module.name());
                mergeMember(members, methodKey(fn.name(), fn.parameters().size()), signature, "module " + module.name());
            } else if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
                Type type = field.type() == null ? typeOf(field.initializer(), new Env(null), Set.of(), null) : resolve(field.type(), Set.of(), null);
                mergeMember(members, field.name(), type, "module " + module.name());
            }
        }
        return new Record(members);
    }

    private void checkInterface(Ast.InterfaceDecl iface) {
        Set<String> generics = uniqueGenerics(iface.genericParameters(), "interface " + iface.name());
        Set<String> memberKeys = new HashSet<>();
        for (Ast.TypeRef parentRef : iface.parents()) {
            if (findInterface(parentRef.name()) == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
        }

        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                String key = methodKey(fn.name(), fn.parameters().size());
                if (!memberKeys.add(key)) throw new IllegalArgumentException("duplicate interface method '" + iface.name() + "." + fn.name() + "' with arity " + fn.parameters().size());
                Set<String> all = new HashSet<>(generics);
                for (String generic : fn.genericParameters()) {
                    if (!all.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in interface " + iface.name() + "." + fn.name());
                }
                functionType(fn.parameters(), fn.returnType(), all, null);
            } else {
                Ast.InterfaceFieldDecl field = (Ast.InterfaceFieldDecl) member;
                if (!memberKeys.add(field.name())) throw new IllegalArgumentException("duplicate interface member '" + iface.name() + "." + field.name() + "'");
                resolve(field.type(), generics, null);
            }
        }
    }

    private void checkFunction(String module, Ast.FunctionDecl fn) {
        Set<String> generics = uniqueGenerics(fn.genericParameters(), (fn.kind() == Ast.CallableKind.ROUTINE ? "routine " : "function ") + fn.name());
        Env env = new Env(null);
        for (Ast.Param param : fn.parameters()) env.define(param.name(), resolveParam(param, generics, null), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        Type returns = resolve(fn.returnType(), generics, null);
        checkBlock(fn.body(), env, generics, returns, null);
        if (returns != Primitive.VOID && !definitelyReturns(fn.body())) {
            throw new IllegalArgumentException("non-void " + fn.kind().name().toLowerCase() + " '" + module + "." + fn.name() + "' must explicitly return on every path");
        }
        if (fn.visibility() == Ast.Visibility.PUBLIC && hasInferredArgs(fn.returnType())) {
            throw new IllegalArgumentException("public callable '" + fn.name() + "' cannot export unresolved <> type arguments");
        }
    }

    private void validateLocalClassOverloads(Ast.ClassDecl klass) {
        Set<Ast.MethodArity> instanceMethodOverloads = new HashSet<>();
        Set<Ast.MethodArity> staticFunctionOverloads = new HashSet<>();
        for (Ast.MethodDecl method : klass.methods()) {
            Set<Ast.MethodArity> overloads = method.isStatic() ? staticFunctionOverloads : instanceMethodOverloads;
            if (!overloads.add(method.overloadIdentity())) {
                String label = method.isStatic() ? "static function" : "method";
                throw new IllegalArgumentException(label + " '" + klass.name() + "." + method.name() + "' already has arity " + method.arity()
                        + "; overload identity is name + arity only; parameter types and return types do not participate");
            }
        }
    }

    private record MethodOrigin(Ast.ClassDecl owner, Named ownerType) { }

    private record ResolvedMethod(
            Ast.MethodDecl method,
            Ast.ClassDecl owner,
            Named ownerType,
            Named receiverType) { }

    private void validateInheritedClassOverloads(Ast.ClassDecl klass) {
        validateInheritedClassOverloads(klass, false);
        validateInheritedClassOverloads(klass, true);
    }

    private void validateInheritedClassOverloads(Ast.ClassDecl klass, boolean staticNamespace) {
        Set<Ast.MethodArity> locallyResolved = new HashSet<>();
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() == staticNamespace) locallyResolved.add(method.overloadIdentity());
        }

        Named classType = nominalClassType(klass);
        Map<Ast.MethodArity, List<MethodOrigin>> inherited =
                inheritedMethodOrigins(klass, classType, staticNamespace, new LinkedHashSet<>());

        for (Map.Entry<Ast.MethodArity, List<MethodOrigin>> entry : inherited.entrySet()) {
            if (locallyResolved.contains(entry.getKey())) continue;
            List<MethodOrigin> origins = entry.getValue();
            if (origins.size() <= 1) continue;

            String label = staticNamespace ? "static function" : "method";
            String owners = origins.stream()
                    .map(this::methodOriginLabel)
                    .sorted()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            throw new IllegalArgumentException("ambiguous inherited " + label + " '" + klass.name() + "."
                    + entry.getKey().name() + "' with arity " + entry.getKey().arity()
                    + " from " + owners
                    + "; add an explicit child declaration for this name + arity slot; argument and return types never select an overload");
        }
    }

    private Map<Ast.MethodArity, List<MethodOrigin>> inheritedMethodOrigins(
            Ast.ClassDecl klass,
            Named klassType,
            boolean staticNamespace,
            Set<Ast.ClassDecl> stack) {
        Map<Ast.MethodArity, List<MethodOrigin>> result = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Named parentType = resolveParentView(klass, klassType, parentRef, parent);
            mergeMethodOrigins(result, effectiveMethodOrigins(parent, parentType, staticNamespace, stack));
        }
        return result;
    }

    private Map<Ast.MethodArity, List<MethodOrigin>> effectiveMethodOrigins(
            Ast.ClassDecl klass,
            Named klassType,
            boolean staticNamespace,
            Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        try {
            Map<Ast.MethodArity, List<MethodOrigin>> result =
                    inheritedMethodOrigins(klass, klassType, staticNamespace, stack);
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.isStatic() != staticNamespace) continue;
                result.put(method.overloadIdentity(),
                        new ArrayList<>(List.of(new MethodOrigin(klass, canonicalClassView(klass, klassType)))));
            }
            return result;
        } finally {
            stack.remove(klass);
        }
    }

    private void mergeMethodOrigins(
            Map<Ast.MethodArity, List<MethodOrigin>> target,
            Map<Ast.MethodArity, List<MethodOrigin>> source) {
        for (Map.Entry<Ast.MethodArity, List<MethodOrigin>> entry : source.entrySet()) {
            List<MethodOrigin> origins = target.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>());
            for (MethodOrigin candidate : entry.getValue()) {
                boolean alreadyPresent = origins.stream().anyMatch(existing ->
                        existing.owner() == candidate.owner() && existing.ownerType().equals(candidate.ownerType()));
                if (!alreadyPresent) origins.add(candidate);
            }
        }
    }

    private Named resolveParentView(
            Ast.ClassDecl child,
            Named childType,
            Ast.TypeRef parentRef,
            Ast.ClassDecl parent) {
        Map<String, Type> bindings = classGenericBindings(child, childType);
        Type rawParent = resolve(parentRef, Set.copyOf(child.genericParameters()), childType);
        Type substituted = resolveSharedGeneric(rawParent, bindings);
        if (!(substituted instanceof Named named)) {
            throw new IllegalArgumentException("parent class view for '" + parentRef.name() + "' is not nominal");
        }
        if (named.arguments().size() != parent.genericParameters().size()) {
            throw new IllegalArgumentException("parent class '" + qualifiedClassName(parent) + "' expects "
                    + parent.genericParameters().size() + " type argument(s), got " + named.arguments().size());
        }
        return new Named(qualifiedClassName(parent), named.arguments());
    }

    private Named canonicalClassView(Ast.ClassDecl klass, Named view) {
        if (view.arguments().size() != klass.genericParameters().size()) {
            throw new IllegalArgumentException("class '" + qualifiedClassName(klass) + "' expects "
                    + klass.genericParameters().size() + " type argument(s), got " + view.arguments().size());
        }
        return new Named(qualifiedClassName(klass), view.arguments());
    }

    private Map<String, Type> classGenericBindings(Ast.ClassDecl klass, Named klassType) {
        Named canonical = canonicalClassView(klass, klassType);
        Map<String, Type> bindings = new HashMap<>();
        for (int i = 0; i < klass.genericParameters().size(); i++) {
            bindings.put(klass.genericParameters().get(i), canonical.arguments().get(i));
        }
        return bindings;
    }

    private String methodOriginLabel(MethodOrigin origin) {
        String owner = qualifiedClassName(origin.owner());
        if (origin.ownerType().arguments().isEmpty()) return owner;
        return owner + origin.ownerType().arguments();
    }

    private void validateAbstractMethodSlots(Ast.ClassDecl klass) {
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isAbstract()) continue;
            if (method.isStatic()) {
                throw new IllegalArgumentException(
                        "static class functions cannot be abstract: " + klass.name() + "." + method.name());
            }
            if (method.visibility() != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException(
                        "abstract method '" + klass.name() + "." + method.name()
                                + "' with arity " + method.arity()
                                + " must be public so subclasses can satisfy the slot");
            }
        }

        if (klass.isAbstract()) return;

        Map<Ast.MethodArity, Ast.MethodDecl> effective =
                effectiveInstanceMethods(klass, new LinkedHashSet<>());
        for (Map.Entry<Ast.MethodArity, Ast.MethodDecl> entry : effective.entrySet()) {
            if (!entry.getValue().isAbstract()) continue;
            throw new IllegalArgumentException(
                    "concrete class '" + klass.name() + "' has unresolved abstract method '"
                            + entry.getKey().name() + "' with arity " + entry.getKey().arity()
                            + "; implement that exact name + arity slot or declare the class abstract");
        }
    }

    private Map<Ast.MethodArity, Ast.MethodDecl> effectiveInstanceMethods(
            Ast.ClassDecl klass,
            Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) {
            throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        }
        try {
            Map<Ast.MethodArity, Ast.MethodDecl> result = new LinkedHashMap<>();
            for (Ast.TypeRef parentRef : klass.parents()) {
                Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
                if (parent == null) continue;
                for (Map.Entry<Ast.MethodArity, Ast.MethodDecl> inherited :
                        effectiveInstanceMethods(parent, stack).entrySet()) {
                    result.putIfAbsent(inherited.getKey(), inherited.getValue());
                }
            }
            for (Ast.MethodDecl method : klass.methods()) {
                if (!method.isStatic()) result.put(method.overloadIdentity(), method);
            }
            return result;
        } finally {
            stack.remove(klass);
        }
    }

    private void validateStaticSignatureDoesNotCaptureClassGenerics(
            Ast.ClassDecl klass,
            Ast.MethodDecl method,
            Set<String> classGenerics) {
        for (Ast.Param param : method.parameters()) {
            String captured = firstReferencedTypeName(param.type(), classGenerics);
            if (captured != null) {
                throw new IllegalArgumentException(
                        "static function '" + klass.name() + "." + method.name()
                                + "' cannot use enclosing class generic '" + captured
                                + "' in its callable contract; declare a static-function generic instead");
            }
        }
        String captured = firstReferencedTypeName(method.returnType(), classGenerics);
        if (captured != null) {
            throw new IllegalArgumentException(
                    "static function '" + klass.name() + "." + method.name()
                            + "' cannot use enclosing class generic '" + captured
                            + "' in its callable contract; declare a static-function generic instead");
        }
    }

    private String firstReferencedTypeName(Ast.TypeRef ref, Set<String> names) {
        if (ref == null) return null;
        if (names.contains(ref.name())) return ref.name();
        if (ref.isBorrow()) return firstReferencedTypeName(ref.borrowedTarget(), names);
        for (Ast.TypeRef argument : ref.arguments()) {
            String found = firstReferencedTypeName(argument, names);
            if (found != null) return found;
        }
        return null;
    }

    private void checkClass(String module, Ast.ClassDecl klass) {
        Set<String> classGenerics = uniqueGenerics(klass.genericParameters(), "class " + klass.name());
        Type self = nominalClassType(klass);

        Set<String> parentNames = new HashSet<>();
        for (Ast.TypeRef parent : klass.parents()) {
            if (!parentNames.add(parent.name())) throw new IllegalArgumentException("duplicate parent class '" + parent.name() + "' on " + klass.name());
            resolveClassParent(parent, klass);
        }

        for (Ast.FieldDecl field : klass.fields()) {
            Type fieldType = resolve(field.type(), classGenerics, self);
            if (field.initializer() != null) {
                Type actual = typeOf(field.initializer(), new Env(null), classGenerics, self);
                requireAssignable(actual, fieldType, "field initializer " + klass.name() + "." + field.name());
            }
            if (field.bindingKind() == Ast.BindingKind.CONST && field.initializer() != null && !constant(field.initializer())) {
                throw new IllegalArgumentException("const field '" + field.name() + "' needs a compile-time constant initializer");
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            Set<String> methodGenerics = uniqueGenerics(
                    method.genericParameters(),
                    (method.isStatic() ? "static function " : "method ") + klass.name() + "." + method.name());
            for (String generic : methodGenerics) {
                if (classGenerics.contains(generic)) {
                    throw new IllegalArgumentException(
                            "duplicate/shadowed generic '" + generic + "' in " + klass.name() + "." + method.name());
                }
            }
            if (method.isStatic()) {
                validateStaticSignatureDoesNotCaptureClassGenerics(klass, method, classGenerics);
            }

            Set<String> generics = new HashSet<>();
            if (!method.isStatic()) generics.addAll(classGenerics);
            generics.addAll(methodGenerics);

            if (method.explicitReceiverType() != null) {
                Ast.TypeRef receiverRef = method.explicitReceiverType();
                boolean namesEnclosingClass = receiverRef.name().equals(klass.name()) || receiverRef.name().equals(qualifiedClassName(klass)) || receiverRef.name().equals("self");
                if (!namesEnclosingClass) {
                    Type receiver = resolve(receiverRef, generics, self);
                    requireAssignable(self, receiver, "explicit self receiver in " + klass.name() + "." + method.name());
                }
            }

            Type callableSelf = method.isStatic() ? null : self;
            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", self, Ast.BindingKind.VAL);
            for (Ast.Param param : method.parameters()) env.define(param.name(), resolveParam(param, generics, callableSelf), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            Type returns = resolve(method.returnType(), generics, callableSelf);
            checkBlock(method.body(), env, generics, returns, callableSelf);
            if (!method.isAbstract() && returns != Primitive.VOID && !definitelyReturns(method.body())) {
                String label = method.isStatic() ? "static function" : "method";
                throw new IllegalArgumentException("non-void " + label + " '" + module + "." + klass.name() + "." + method.name() + "' must explicitly return on every path");
            }
        }

        Set<String> implemented = new HashSet<>();
        for (Ast.TypeRef interfaceRef : klass.interfaces()) {
            if (!implemented.add(interfaceRef.name())) throw new IllegalArgumentException("duplicate implemented interface '" + interfaceRef.name() + "' on " + klass.name());
            Ast.InterfaceDecl iface = findInterface(interfaceRef.name());
            if (iface == null) throw new IllegalArgumentException("unknown interface '" + interfaceRef.name() + "' implemented by " + klass.name());
            Named interfaceType = resolveInterfaceView(iface, interfaceRef, classGenerics, self);
            Record expected = interfaceShape(iface, interfaceType, new LinkedHashSet<>());
            Record actual = publicClassShape(klass, new LinkedHashSet<>());
            if (!assignable(actual, expected)) {
                throw new IllegalArgumentException("class '" + klass.name() + "' does not implement interface '" + interfaceRef.name() + "': expected " + expected + " but got " + actual);
            }
        }
    }

    private void checkModuleBinding(Ast.FieldDecl field) {
        if (field.initializer() == null) throw new IllegalArgumentException("module binding '" + field.name() + "' requires an initializer");
        Type actual = typeOf(field.initializer(), new Env(null), Set.of(), null);
        if (field.type() != null) requireAssignable(actual, resolve(field.type(), Set.of(), null), "initializer for " + field.name());
        if (field.bindingKind() == Ast.BindingKind.CONST && !constant(field.initializer())) {
            throw new IllegalArgumentException("const '" + field.name() + "' needs a compile-time constant initializer");
        }
    }

    private void checkBlock(List<Ast.Stmt> body, Env parent, Set<String> generics, Type expectedReturn, Type self) {
        Env env = new Env(parent);
        for (Ast.Stmt stmt : body) checkStatement(stmt, env, generics, expectedReturn, self);
    }

    private void checkStatement(Ast.Stmt stmt, Env env, Set<String> generics, Type expectedReturn, Type self) {
        if (stmt instanceof Ast.BindingStmt binding) {
            Type declaredAhead = binding.declaredType() == null ? null : resolve(binding.declaredType(), generics, self);
            boolean recursiveLambda = binding.initializer() instanceof Ast.LambdaExpr;
            if (recursiveLambda && declaredAhead instanceof Function) {
                env.define(binding.name(), declaredAhead, binding.kind());
            }
            if (binding.initializer() instanceof Ast.LambdaExpr lambda && declaredAhead instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = typeOfAgainstExpected(binding.initializer(), declaredAhead, env, generics, self);
            Type declared = declaredAhead == null ? actual : declaredAhead;
            requireAssignable(actual, declared, "initializer for " + binding.name());
            if (binding.kind() == Ast.BindingKind.CONST && !constant(binding.initializer())) {
                throw new IllegalArgumentException("const '" + binding.name() + "' needs a compile-time constant initializer");
            }
            if (recursiveLambda && declaredAhead instanceof Function) env.replace(binding.name(), declared, binding.kind());
            else env.define(binding.name(), declared, binding.kind());
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            Type source = deref(typeOf(destructure.initializer(), env, generics, self));
            if (destructure.kind() == Ast.DestructureKind.SEQUENCE) {
                if (source instanceof Tuple tuple) {
                    if (tuple.elements().size() != destructure.bindings().size()) {
                        throw new IllegalArgumentException("destructure arity mismatch: tuple has " + tuple.elements().size()
                                + " element(s), pattern has " + destructure.bindings().size());
                    }
                    for (int i = 0; i < destructure.bindings().size(); i++) {
                        defineDestructureBinding(env, destructure.bindings().get(i), tuple.elements().get(i));
                    }
                } else if (source instanceof ListType list) {
                    for (Ast.DestructureBinding binding : destructure.bindings()) {
                        defineDestructureBinding(env, binding, list.element());
                    }
                } else {
                    throw new IllegalArgumentException("sequence destructuring requires a tuple or array/list value");
                }
            } else {
                Record shape;
                if (source instanceof Record record) {
                    shape = record;
                } else if (source instanceof Named named) {
                    Ast.ClassDecl klass = findClass(named.name());
                    if (klass == null) throw new IllegalArgumentException("object destructuring requires a record/map-like value");
                    shape = publicClassShape(klass, named, new LinkedHashSet<>());
                } else if (source == Unknown.INSTANCE) {
                    shape = null;
                } else {
                    throw new IllegalArgumentException("object destructuring requires a record/map-like value");
                }

                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (binding.isDiscard()) continue;
                    Type member = shape == null ? Unknown.INSTANCE : shape.members().get(binding.name());
                    if (member == null) {
                        throw new IllegalArgumentException("object destructure requires member '" + binding.name() + "'");
                    }
                    env.define(binding.name(), member, binding.kind());
                }
            }
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() instanceof Ast.LambdaExpr lambda && expectedReturn instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = ret.value() == null
                    ? Primitive.VOID
                    : typeOfAgainstExpected(ret.value(), expectedReturn, env, generics, self);
            requireAssignable(actual, expectedReturn, "return value");
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) { typeOf(expression.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.DeferStmt defer) { typeOf(defer.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                requireAssignable(typeOf(branch.condition(), env, generics, self), Primitive.BOOL, "if condition");
                checkBlock(branch.body(), env, generics, expectedReturn, self);
            }
            checkBlock(conditional.elseBody(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.TryStmt attempted) {
            checkBlock(attempted.body(), env, generics, expectedReturn, self);
            Env caught = new Env(env);
            caught.define(attempted.errorName(), Unknown.INSTANCE, Ast.BindingKind.VAL);
            checkBlock(attempted.catchBody(), caught, generics, expectedReturn, self);
            checkBlock(attempted.finallyBody(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.ForOfStmt loop) {
            Type iterable = typeOf(loop.iterable(), env, generics, self);
            Type element = iterableElementType(iterable);
            Env loopEnv = new Env(env);
            loopEnv.define(loop.bindingName(), element, loop.bindingKind());
            checkBlock(loop.body(), loopEnv, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            Env loopEnv = new Env(env);
            if (loop.initializer() != null) checkStatement(loop.initializer(), loopEnv, generics, expectedReturn, self);
            if (loop.condition() != null) requireAssignable(typeOf(loop.condition(), loopEnv, generics, self), Primitive.BOOL, "for condition");
            if (loop.update() != null) typeOf(loop.update(), loopEnv, generics, self);
            checkBlock(loop.body(), loopEnv, generics, expectedReturn, self);
        }
    }

    private Type typeOf(Ast.Expr expr, Env env, Set<String> generics, Type self) {
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value == null) throw new IllegalArgumentException("standalone null values are forbidden; use Option<T>");
            if (value instanceof Long) return Primitive.INT;
            if (value instanceof Double) return Primitive.FLOAT;
            if (value instanceof Boolean) return Primitive.BOOL;
            if (value instanceof String s) return new StringLiteral(s);
            if (value instanceof Ast.Imaginary) return Primitive.COMPLEX;
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.NameExpr name) {
            Env.Binding local = env.lookup(name.name());
            if (local != null) return local.type();
            if (name.name().equals("stdio") || name.name().equals("process")) return new Named(name.name(), List.of());
            if (name.name().equals("Mutex") || name.name().equals("SharedMutex")) return new Named("$" + name.name() + "Factory", List.of());
            if (name.name().equals("print")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            if (name.name().equals("None")) return new Named("Option", List.of(Unknown.INSTANCE));
            Ast.ModuleDecl moduleNamespace = modules.get(name.name());
            if (moduleNamespace != null) return moduleShape(moduleNamespace);
            Ast.ClassDecl classNamespace = findClass(name.name());
            if (classNamespace != null) return new ClassNamespace(qualifiedClassName(classNamespace));
            if (importedValues.contains(name.name())) return Unknown.INSTANCE;
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) return functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
            throw new IllegalArgumentException("unknown name '" + name.name() + "'");
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            Type targetType;
            String where;
            if (assignment.target() instanceof Ast.NameExpr name) {
                Env.Binding binding = env.lookup(name.name());
                if (binding == null) throw new IllegalArgumentException("cannot assign unknown name '" + name.name() + "'");
                if (binding.kind() != Ast.BindingKind.LET) throw new IllegalArgumentException("cannot reassign " + binding.kind().name().toLowerCase() + " binding '" + name.name() + "'");
                targetType = binding.type();
                where = name.name();
            } else if (assignment.target() instanceof Ast.MemberExpr member) {
                targetType = memberType(member, env, generics, self);
                where = member.member();
            } else if (assignment.target() instanceof Ast.IndexExpr indexed) {
                Type receiver = deref(typeOf(indexed.receiver(), env, generics, self));
                Type index = typeOf(indexed.index(), env, generics, self);
                requireAssignable(index, Primitive.INT, "array/list index");
                if (receiver instanceof ListType list) targetType = list.element();
                else if (receiver instanceof Tuple tuple) targetType = tuple.elements().stream().reduce(Unknown.INSTANCE, this::commonType);
                else throw new IllegalArgumentException("indexed assignment requires an array/list or tuple");
                where = "index";
            } else throw new IllegalArgumentException("unsupported assignment target");
            Type value = typeOf(assignment.value(), env, generics, self);
            requireAssignable(value, targetType, "assignment to " + where);
            return targetType;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            requireAssignable(typeOf(conditional.condition(), env, generics, self), Primitive.BOOL, "ternary condition");
            Type left = typeOf(conditional.whenTrue(), env, generics, self);
            Type right = typeOf(conditional.whenFalse(), env, generics, self);
            return commonType(left, right);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            Type operand = typeOf(unary.operand(), env, generics, self);
            if (unary.operator().equals("&")) return new Borrow(operand, false);
            if (unary.operator().equals("&mut")) return new Borrow(operand, true);
            if (unary.operator().equals("!")) {
                requireAssignable(operand, Primitive.BOOL, "! operand");
                return Primitive.BOOL;
            }
            if (!Types.isNumeric(operand)) throw new IllegalArgumentException("unary " + unary.operator() + " needs a numeric operand");
            return operand;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            Type left = typeOf(binary.left(), env, generics, self);
            Type right = typeOf(binary.right(), env, generics, self);
            return switch (binary.operator()) {
                case ",", "|" -> {
                    requireAssignable(left, Primitive.BOOL, "boolean operand");
                    requireAssignable(right, Primitive.BOOL, "boolean operand");
                    yield Primitive.BOOL;
                }
                case "==", "!=" -> Primitive.BOOL;
                case "<", "<=", ">", ">=" -> {
                    if (!(Types.isNumeric(left) && Types.isNumeric(right)) && !(isStringLike(left) && isStringLike(right))) {
                        throw new IllegalArgumentException("comparison operands must both be numeric or both strings");
                    }
                    yield Primitive.BOOL;
                }
                case "+" -> isStringLike(left) && isStringLike(right) ? Primitive.STRING : numericJoin(left, right, "+");
                case "-", "*", "/", "%" -> numericJoin(left, right, binary.operator());
                default -> Unknown.INSTANCE;
            };
        }
        if (expr instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.MemberExpr factoryCall
                    && factoryCall.receiver() instanceof Ast.NameExpr factory
                    && (factory.name().equals("Mutex") || factory.name().equals("SharedMutex"))
                    && factoryCall.member().equals("new")) {
                if (call.arguments().size() != 1) throw new IllegalArgumentException(factory.name() + ".new expects exactly one value");
                Type element = typeOf(call.arguments().getFirst(), env, generics, self);
                if (element instanceof Borrow) {
                    throw new IllegalArgumentException(
                            factory.name() + "<T> requires owned data; borrowed values cannot become mutex state");
                }
                if (factory.name().equals("SharedMutex") && !isSharedSafe(element, new LinkedHashSet<>(), Map.of())) {
                    throw new IllegalArgumentException(
                            "SharedMutex<T> requires shared-safe owned data; borrows, Mutex, MutexGuard, Future, closures, and unresolved generic/dynamic values are not shareable");
                }
                return new Named(factory.name(), List.of(element));
            }
            if (call.callee() instanceof Ast.NameExpr name && name.name().equals("Some")) {
                if (call.arguments().size() != 1) throw new IllegalArgumentException("Some expects exactly one value");
                return new Named("Option", List.of(typeOf(call.arguments().getFirst(), env, generics, self)));
            }
            if (call.callee() instanceof Ast.MemberExpr member) {
                Type receiver = deref(typeOf(member.receiver(), env, generics, self));
                if (receiver instanceof ClassNamespace classNamespace) {
                    Ast.ClassDecl klass = findClass(classNamespace.className());
                    if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                    Ast.MethodDecl fn = findStaticFunction(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                    if (fn == null) throw new IllegalArgumentException("no static function '" + member.member() + "' with arity " + call.arguments().size() + " on " + klass.name());
                    Set<String> fnGenerics = new HashSet<>(fn.genericParameters());
                    List<Type> actualArguments = call.arguments().stream()
                            .map(argument -> typeOf(argument, env, generics, self))
                            .toList();
                    List<Type> expectedArguments = fn.parameters().stream()
                            .map(param -> resolveParam(param, fnGenerics, null))
                            .toList();
                    Map<String, Type> inferred = inferMethodGenericBindings(
                            expectedArguments,
                            actualArguments,
                            Set.copyOf(fn.genericParameters()),
                            "static function '" + klass.name() + "." + member.member()
                                    + "' with arity " + call.arguments().size());
                    for (int i = 0; i < call.arguments().size(); i++) {
                        Type expected = resolveSharedGeneric(expectedArguments.get(i), inferred);
                        validateLambdaArgument(call.arguments().get(i), expected, env, generics, self);
                        requireAssignable(
                                actualArguments.get(i),
                                expected,
                                "argument " + (i + 1) + " to static function '"
                                        + klass.name() + "." + member.member()
                                        + "' with arity " + call.arguments().size());
                    }
                    return resolveSharedGeneric(resolve(fn.returnType(), fnGenerics, null), inferred);
                }
                if (receiver instanceof Named named) {
                    if ((named.name().equals("Mutex") || named.name().equals("SharedMutex"))
                            && named.arguments().size() == 1
                            && (member.member().equals("with_lock") || member.member().equals("recover"))) {
                        if (member.member().equals("recover") && !named.name().equals("SharedMutex")) {
                            throw new IllegalArgumentException("recover is only available on SharedMutex<T>");
                        }
                        if (call.arguments().size() != 1) {
                            throw new IllegalArgumentException(member.member() + " expects exactly one callback");
                        }
                        Ast.Expr callback = call.arguments().getFirst();
                        if (!(callback instanceof Ast.LambdaExpr lambda)) {
                            throw new IllegalArgumentException(
                                    member.member() + " requires an inline one-argument lambda so the protected borrow cannot escape");
                        }
                        validateMutexCallback(lambda, named.arguments().getFirst(), env, generics, self);
                        return Primitive.VOID;
                    }
                    Ast.ClassDecl klass = findClass(named.name());
                    if (klass != null) {
                        ResolvedMethod resolved = findResolvedMethod(named, member.member(), call.arguments().size());
                        if (resolved == null) throw new IllegalArgumentException("no method '" + member.member() + "' with arity " + call.arguments().size() + " on " + named.name());
                        List<Type> actualArguments = call.arguments().stream()
                                .map(argument -> typeOf(argument, env, generics, self))
                                .toList();
                        List<Type> expectedArguments = resolved.method().parameters().stream()
                                .map(param -> resolvedMethodParameter(resolved, param))
                                .toList();
                        Map<String, Type> inferred = inferMethodGenericBindings(
                                expectedArguments,
                                actualArguments,
                                Set.copyOf(resolved.method().genericParameters()),
                                "method '" + named.name() + "." + member.member()
                                        + "' with arity " + call.arguments().size());
                        for (int i = 0; i < call.arguments().size(); i++) {
                            Type expected = resolveSharedGeneric(expectedArguments.get(i), inferred);
                            validateLambdaArgument(call.arguments().get(i), expected, env, generics, self);
                            requireAssignable(
                                    actualArguments.get(i),
                                    expected,
                                    "argument " + (i + 1) + " to method '"
                                            + named.name() + "." + member.member()
                                            + "' with arity " + call.arguments().size());
                        }
                        return resolveSharedGeneric(resolvedMethodReturn(resolved), inferred);
                    }
                }
            }
            Type callee = typeOf(call.callee(), env, generics, self);
            if (!(callee instanceof Function fn)) return Unknown.INSTANCE;
            if (fn.parameters().size() != call.arguments().size()) throw new IllegalArgumentException("call arity mismatch");
            for (int i = 0; i < fn.parameters().size(); i++) {
                validateLambdaArgument(call.arguments().get(i), fn.parameters().get(i), env, generics, self);
                requireAssignable(typeOf(call.arguments().get(i), env, generics, self), fn.parameters().get(i), "argument " + (i + 1));
            }
            return fn.result();
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr namespace && modules.containsKey(namespace.name())) {
                Ast.ClassDecl memberClass = classes.get(namespace.name() + "." + member.member());
                if (memberClass != null) return new ClassNamespace(qualifiedClassName(memberClass));
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("stdio")) {
                if (member.member().equals("print") || member.member().equals("println")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
                if (member.member().equals("stdout")) return new Named("stdio.stdout", List.of());
            }
            Type receiver = typeOf(member.receiver(), env, generics, self);
            Type mutexMember = builtinMutexMember(receiver, member.member());
            if (mutexMember != null) return mutexMember;
            receiver = unwrapMutexGuard(receiver);
            if (receiver instanceof Named named && named.name().equals("stdio.stdout") && member.member().equals("write")) {
                return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("process")) return Unknown.INSTANCE;
            if (member.receiver() instanceof Ast.NameExpr name && importedValues.contains(name.name())) return Unknown.INSTANCE;

            if (receiver instanceof ClassNamespace classNamespace) {
                Ast.ClassDecl klass = findClass(classNamespace.className());
                if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                List<Ast.MethodDecl> functions = findStaticFunctionsByName(klass, member.member(), new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    return functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function '" + member.member() + "' must be called so arity can select the overload");
                throw new IllegalArgumentException("unknown static member '" + member.member() + "' on " + klass.name());
            }

            if (receiver instanceof Record record) {
                Type result = record.members().get(member.member());
                if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
                return result;
            }
            if (receiver instanceof Named named) {
                Ast.ClassDecl klass = findClass(named.name());
                if (klass != null) {
                    Type field = findFieldType(klass, member.member(), new LinkedHashSet<>());
                    if (field != null) return field;
                    List<Ast.MethodDecl> methods = findMethodsByName(klass, member.member(), new LinkedHashSet<>());
                    if (methods.size() == 1) {
                        ResolvedMethod resolved = findResolvedMethod(named, member.member(), methods.getFirst().arity());
                        if (resolved == null) throw new IllegalArgumentException("unknown method '" + member.member() + "' on " + named.name());
                        return resolvedMethodFunction(resolved);
                    }
                    if (methods.size() > 1) throw new IllegalArgumentException("overloaded method '" + member.member() + "' must be called so arity can select the overload");
                }
            }
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            Type receiver = deref(typeOf(indexed.receiver(), env, generics, self));
            Type index = typeOf(indexed.index(), env, generics, self);
            requireAssignable(index, Primitive.INT, "array/list index");
            if (receiver instanceof ListType list) return list.element();
            if (receiver instanceof Tuple tuple) return tuple.elements().stream().reduce(Unknown.INSTANCE, this::commonType);
            throw new IllegalArgumentException("indexing requires an array/list or tuple");
        }
        if (expr instanceof Ast.NewExpr created) {
            Ast.ClassDecl klass = findClass(created.type().name());
            if (klass == null) return new Named(created.type().name(), created.type().arguments().stream().map(a -> resolve(a, generics, self)).toList());
            if (klass.isAbstract()) {
                throw new IllegalArgumentException("cannot instantiate abstract class '" + klass.name() + "'");
            }
            List<Ast.FieldDecl> fields = effectiveFields(klass, new LinkedHashSet<>());
            if (created.arguments().size() > fields.size()) throw new IllegalArgumentException("constructor for " + klass.name() + " received too many positional fields");
            Type nominal = nominalClassType(klass);
            for (int i = 0; i < fields.size(); i++) {
                Ast.FieldDecl field = fields.get(i);
                if (i < created.arguments().size()) {
                    requireAssignable(typeOf(created.arguments().get(i), env, generics, self),
                            resolve(field.type(), Set.copyOf(klass.genericParameters()), nominal), "constructor field " + field.name());
                } else if (field.initializer() == null) {
                    throw new IllegalArgumentException("constructor for " + klass.name() + " is missing field '" + field.name() + "'");
                }
            }
            return nominal;
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            Type awaitedType = typeOf(awaited.expression(), env, generics, self);
            if (awaitedType instanceof Named named && named.name().equals("Future") && named.arguments().size() == 1) return named.arguments().getFirst();
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.ListExpr list) {
            if (list.elements().isEmpty()) return new ListType(Unknown.INSTANCE);
            Type element = widenCollectionElement(typeOf(list.elements().getFirst(), env, generics, self));
            for (int i = 1; i < list.elements().size(); i++) {
                element = collectionElementJoin(element, widenCollectionElement(typeOf(list.elements().get(i), env, generics, self)));
            }
            return new ListType(element);
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return new Tuple(tuple.elements().stream().map(item -> typeOf(item, env, generics, self)).toList());
        }
        if (expr instanceof Ast.ObjectExpr object) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Ast.ObjectField field : object.fields()) {
                if (members.putIfAbsent(field.name(), typeOf(field.value(), env, generics, self)) != null) {
                    throw new IllegalArgumentException("duplicate obj field '" + field.name() + "'");
                }
            }
            return new Record(members);
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            Env lambdaEnv = new Env(env);
            List<Type> parameters = new ArrayList<>();
            for (Ast.Param param : lambda.parameters()) {
                Type type = resolveParam(param, generics, self);
                parameters.add(type);
                lambdaEnv.define(param.name(), type, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            if (lambda.expressionBody() != null) {
                throw new IllegalArgumentException("expression-body lambdas are not supported; lambdas require braces and explicit return");
            }
            checkBlock(lambda.blockBody(), lambdaEnv, generics, Unknown.INSTANCE, self);
            return new Function(parameters, Unknown.INSTANCE);
        }
        return Unknown.INSTANCE;
    }

    private Type typeOfAgainstExpected(Ast.Expr expr, Type expected, Env env, Set<String> generics, Type self) {
        if (expected instanceof Tuple && expr instanceof Ast.ListExpr list) {
            return new Tuple(list.elements().stream().map(item -> typeOf(item, env, generics, self)).toList());
        }
        return typeOf(expr, env, generics, self);
    }

    private void defineDestructureBinding(Env env, Ast.DestructureBinding binding, Type type) {
        if (!binding.isDiscard()) env.define(binding.name(), type, binding.kind());
    }

    private void validateMutexCallback(Ast.LambdaExpr lambda, Type expectedParameter, Env parent, Set<String> generics, Type self) {
        if (lambda.expressionBody() != null) {
            throw new IllegalArgumentException("mutex callbacks require a block body");
        }
        if (lambda.parameters().size() != 1) {
            throw new IllegalArgumentException("mutex callback must accept exactly one protected-value parameter");
        }
        Ast.Param param = lambda.parameters().getFirst();
        Type declared = param.type().name().equals("$infer$") ? expectedParameter : resolveParam(param, generics, self);
        requireAssignable(expectedParameter, declared, "mutex callback parameter " + param.name());
        requireAssignable(declared, expectedParameter, "mutex callback parameter " + param.name());
        Env lambdaEnv = new Env(parent);
        lambdaEnv.define(param.name(), declared, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        checkBlock(lambda.blockBody(), lambdaEnv, generics, Primitive.VOID, self);
    }


    private void validateLambdaArgument(Ast.Expr argument, Type expected, Env env, Set<String> generics, Type self) {
        if (argument instanceof Ast.LambdaExpr lambda && expected instanceof Function fn) {
            validateLambdaAgainstExpected(lambda, fn, env, generics, self);
        }
    }

    private void validateLambdaAgainstExpected(Ast.LambdaExpr lambda, Function expected, Env parent, Set<String> generics, Type self) {
        if (lambda.expressionBody() != null) {
            throw new IllegalArgumentException("lambdas always require a block body and explicit return for non-void results");
        }
        if (lambda.parameters().size() != expected.parameters().size()) {
            throw new IllegalArgumentException("lambda arity " + lambda.parameters().size() + " does not match expected function arity " + expected.parameters().size());
        }
        Env lambdaEnv = new Env(parent);
        for (int i = 0; i < lambda.parameters().size(); i++) {
            Ast.Param param = lambda.parameters().get(i);
            Type expectedParam = expected.parameters().get(i);
            Type declared = param.type().name().equals("$infer$") ? expectedParam : resolveParam(param, generics, self);
            requireAssignable(expectedParam, declared, "lambda parameter " + param.name());
            requireAssignable(declared, expectedParam, "lambda parameter " + param.name());
            lambdaEnv.define(param.name(), declared, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        }
        checkBlock(lambda.blockBody(), lambdaEnv, generics, expected.result(), self);
        if (expected.result() != Primitive.VOID && !definitelyReturns(lambda.blockBody())) {
            throw new IllegalArgumentException("non-void lambda must explicitly return on every path");
        }
    }

    private Type deref(Type type) {
        return type instanceof Borrow borrow ? borrow.target() : type;
    }

    private Type memberType(Ast.MemberExpr member, Env env, Set<String> generics, Type self) {
        Type receiver = unwrapMutexGuard(deref(typeOf(member.receiver(), env, generics, self)));
        if (receiver instanceof Record record) {
            Type result = record.members().get(member.member());
            if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
            return result;
        }
        if (receiver instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                Type field = findFieldType(klass, member.member(), new LinkedHashSet<>());
                if (field != null) return field;
            }
        }
        throw new IllegalArgumentException("assignment target '" + member.member() + "' is not a mutable data field");
    }

    private boolean isSharedSafe(Type type, Set<Ast.ClassDecl> seen, Map<String, Type> genericBindings) {
        if (type == Unknown.INSTANCE || type instanceof Borrow || type instanceof Function || type instanceof ClassNamespace) return false;
        if (type instanceof Primitive primitive) return primitive != Primitive.VOID;
        if (type instanceof StringLiteral) return true;
        if (type instanceof Generic generic) {
            Type bound = genericBindings.get(generic.name());
            return bound != null && bound != type && isSharedSafe(bound, seen, genericBindings);
        }
        if (type instanceof ListType list) return isSharedSafe(list.element(), seen, genericBindings);
        if (type instanceof Union union) {
            for (Type option : union.options()) if (!isSharedSafe(option, seen, genericBindings)) return false;
            return true;
        }
        if (type instanceof Tuple tuple) {
            for (Type element : tuple.elements()) if (!isSharedSafe(element, seen, genericBindings)) return false;
            return true;
        }
        if (type instanceof Union union) {
            for (Type option : union.options()) if (!isSharedSafe(option, seen, genericBindings)) return false;
            return true;
        }
        if (type instanceof Record record) {
            for (Type member : record.members().values()) if (!isSharedSafe(member, seen, genericBindings)) return false;
            return true;
        }
        if (!(type instanceof Named named)) return false;

        if (named.name().equals("Mutex") || named.name().equals("MutexGuard") || named.name().equals("Future")) return false;
        if (named.name().equals("Option") || named.name().equals("SharedMutex")) {
            return named.arguments().size() == 1 && isSharedSafe(named.arguments().getFirst(), seen, genericBindings);
        }

        for (Type argument : named.arguments()) {
            if (!isSharedSafe(argument, seen, genericBindings)) return false;
        }

        Ast.ClassDecl klass = findClass(named.name());
        if (klass == null) return false;
        if (!seen.add(klass)) return true;

        try {
            Map<String, Type> classBindings = new HashMap<>(genericBindings);
            if (named.arguments().size() != klass.genericParameters().size()) return false;
            for (int i = 0; i < klass.genericParameters().size(); i++) {
                classBindings.put(klass.genericParameters().get(i),
                        resolveSharedGeneric(named.arguments().get(i), genericBindings));
            }

            Type nominal = nominalClassType(klass);
            Set<String> classGenerics = Set.copyOf(klass.genericParameters());

            // Check only fields declared by this class under this class's own
            // generic environment. Flattening inherited fields here is unsafe:
            // a parent T must never be resolved as an unrelated child T.
            for (Ast.FieldDecl field : klass.fields()) {
                Type fieldType = resolveSharedGeneric(
                        resolve(field.type(), classGenerics, nominal),
                        classBindings);
                if (!isSharedSafe(fieldType, seen, classBindings)) return false;
            }

            // Recurse into each parent after substituting the child's generic
            // bindings into the parent's concrete type arguments.
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Type parentType = resolveSharedGeneric(resolve(parentRef, classGenerics, nominal), classBindings);
                if (!isSharedSafe(parentType, seen, classBindings)) return false;
            }

            return true;
        } finally {
            seen.remove(klass);
        }
    }

    /**
     * Performs simultaneous, capture-avoiding generic substitution.
     *
     * A replacement value belongs to the caller's generic scope, not to the
     * declaration whose type parameter is being replaced. Therefore a matched
     * Generic is replaced exactly once and the replacement is not recursively
     * re-substituted through the same binding map. This matters for cases such
     * as Child<T> extends Parent<Mutex<T>> where the inner T is Child.T, not
     * Parent.T.
     */
    private Type resolveSharedGeneric(Type type, Map<String, Type> bindings) {
        if (type instanceof Generic generic) {
            Type bound = bindings.get(generic.name());
            return bound == null ? type : bound;
        }
        if (type instanceof Named named) {
            return new Named(named.name(), named.arguments().stream()
                    .map(argument -> resolveSharedGeneric(argument, bindings))
                    .toList());
        }
        if (type instanceof Borrow borrow) {
            return new Borrow(resolveSharedGeneric(borrow.target(), bindings), borrow.mutable());
        }
        if (type instanceof Function function) {
            return new Function(
                    function.parameters().stream()
                            .map(parameter -> resolveSharedGeneric(parameter, bindings))
                            .toList(),
                    resolveSharedGeneric(function.result(), bindings));
        }
        if (type instanceof ListType list) {
            return new ListType(resolveSharedGeneric(list.element(), bindings));
        }
        if (type instanceof Tuple tuple) {
            return new Tuple(tuple.elements().stream()
                    .map(element -> resolveSharedGeneric(element, bindings))
                    .toList());
        }
        if (type instanceof Union union) {
            return Types.unionOf(union.options().stream()
                    .map(option -> resolveSharedGeneric(option, bindings))
                    .toList());
        }
        if (type instanceof Record record) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Map.Entry<String, Type> entry : record.members().entrySet()) {
                members.put(entry.getKey(), resolveSharedGeneric(entry.getValue(), bindings));
            }
            return new Record(members);
        }
        return type;
    }

    private Type builtinMutexMember(Type receiver, String member) {
        if (!(receiver instanceof Named named) || named.arguments().size() != 1) return null;
        Type element = named.arguments().getFirst();
        if (named.name().equals("Mutex") || named.name().equals("SharedMutex")) {
            Type guard = new Named("MutexGuard", List.of(element));
            return switch (member) {
                case "lock" -> new Function(List.of(), guard);
                case "try_lock" -> new Function(List.of(), new Named("Option", List.of(guard)));
                case "lock_async" -> new Function(List.of(), new Named("Future", List.of(guard)));
                case "with_lock" -> new Function(List.of(new Function(List.of(element), Primitive.VOID)), Primitive.VOID);
                case "is_poisoned" -> new Function(List.of(), Primitive.BOOL);
                case "recover" -> named.name().equals("SharedMutex")
                        ? new Function(List.of(new Function(List.of(element), Primitive.VOID)), Primitive.VOID)
                        : null;
                default -> null;
            };
        }
        if (named.name().equals("MutexGuard")) {
            return switch (member) {
                case "release" -> new Function(List.of(), Primitive.VOID);
                case "is_released" -> new Function(List.of(), Primitive.BOOL);
                default -> null;
            };
        }
        return null;
    }

    private Type unwrapMutexGuard(Type type) {
        if (type instanceof Named named && named.name().equals("MutexGuard") && named.arguments().size() == 1) {
            return named.arguments().getFirst();
        }
        return type;
    }

    private Type iterableElementType(Type iterable) {
        if (iterable instanceof ListType list) return list.element();
        if (iterable instanceof Tuple tuple) {
            Type result = Unknown.INSTANCE;
            for (Type element : tuple.elements()) result = result == Unknown.INSTANCE ? element : commonType(result, element);
            return result;
        }
        if (iterable instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                ResolvedMethod iterator = findResolvedMethod(named, "Symbol.iterator", 0);
                if (iterator != null) {
                    Type result = resolvedMethodReturn(iterator);
                    if (result instanceof ListType list) return list.element();
                    if (result instanceof Tuple tuple) return iterableElementType(tuple);
                    throw new IllegalArgumentException("[Symbol.iterator]() must return Array<T>, List<T>, or a tuple in v0");
                }
            }
        }
        throw new IllegalArgumentException("for-of requires an array/list, tuple, or a class with [Symbol.iterator]()");
    }

    private Type widenCollectionElement(Type type) {
        return type instanceof StringLiteral ? Primitive.STRING : type;
    }

    private Type collectionElementJoin(Type a, Type b) {
        if (assignable(b, a) && assignable(a, b)) return a;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        if (isStringLike(a) && isStringLike(b)) return Primitive.STRING;
        return Types.unionOf(a, b);
    }

    private Type commonType(Type a, Type b) {
        if (assignable(b, a) && assignable(a, b)) return a;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        if (isStringLike(a) && isStringLike(b)) return Primitive.STRING;
        return Unknown.INSTANCE;
    }

    private Type numericJoin(Type left, Type right, String op) {
        Type result = Types.numericJoin(left, right);
        if (result == Unknown.INSTANCE) throw new IllegalArgumentException("operator '" + op + "' needs numeric operands");
        return result;
    }

    private boolean isStringLike(Type type) {
        return type == Primitive.STRING || type instanceof StringLiteral;
    }

    private Record classShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        Record cached = classShapeCache.get(klass);
        if (cached != null) return cached;
        Record result = classShape(klass, nominalClassType(klass), stack);
        classShapeCache.put(klass, result);
        return result;
    }

    private Record classShape(Ast.ClassDecl klass, Named klassType, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        try {
            Named canonical = canonicalClassView(klass, klassType);
            Map<String, Type> bindings = classGenericBindings(klass, canonical);
            Map<String, Type> members = new LinkedHashMap<>();

            for (Ast.TypeRef parentRef : klass.parents()) {
                Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
                if (parent == null) continue;
                Named parentType = resolveParentView(klass, canonical, parentRef, parent);
                for (Map.Entry<String, Type> inherited : classShape(parent, parentType, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), inherited.getValue(), "multiple inheritance of " + klass.name());
                }
            }

            Set<String> classGenerics = Set.copyOf(klass.genericParameters());
            for (Ast.FieldDecl field : klass.fields()) {
                Type raw = resolve(field.type(), classGenerics, canonical);
                mergeMember(members, field.name(), resolveSharedGeneric(raw, bindings), "class " + klass.name());
            }
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.isStatic()) continue;
                Set<String> methodGenerics = new HashSet<>(classGenerics);
                methodGenerics.addAll(method.genericParameters());
                Type raw = functionType(method.parameters(), method.returnType(), methodGenerics, canonical);
                Type resolved = resolveSharedGeneric(raw, bindings);
                mergeMember(members, methodKey(method.name(), method.arity()),
                        canonicalizeMethodGenerics(resolved, method.genericParameters()),
                        "class " + klass.name());
            }
            return new Record(members);
        } finally {
            stack.remove(klass);
        }
    }

    private Record publicClassShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        return publicClassShape(klass, nominalClassType(klass), stack);
    }

    private Record publicClassShape(Ast.ClassDecl klass, Named klassType, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        try {
            Named canonical = canonicalClassView(klass, klassType);
            Map<String, Type> bindings = classGenericBindings(klass, canonical);
            Map<String, Type> members = new LinkedHashMap<>();

            for (Ast.TypeRef parentRef : klass.parents()) {
                Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
                if (parent == null) continue;
                Named parentType = resolveParentView(klass, canonical, parentRef, parent);
                for (Map.Entry<String, Type> inherited : publicClassShape(parent, parentType, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), inherited.getValue(), "public inheritance of " + klass.name());
                }
            }

            Set<String> classGenerics = Set.copyOf(klass.genericParameters());
            for (Ast.FieldDecl field : klass.fields()) {
                if (field.visibility() != Ast.Visibility.PUBLIC) continue;
                Type raw = resolve(field.type(), classGenerics, canonical);
                mergeMember(members, field.name(), resolveSharedGeneric(raw, bindings), "class " + klass.name());
            }
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.isStatic() || method.visibility() != Ast.Visibility.PUBLIC) continue;
                Set<String> methodGenerics = new HashSet<>(classGenerics);
                methodGenerics.addAll(method.genericParameters());
                Type raw = functionType(method.parameters(), method.returnType(), methodGenerics, canonical);
                Type resolved = resolveSharedGeneric(raw, bindings);
                mergeMember(members, methodKey(method.name(), method.arity()),
                        canonicalizeMethodGenerics(resolved, method.genericParameters()),
                        "class " + klass.name());
            }
            return new Record(members);
        } finally {
            stack.remove(klass);
        }
    }

    private Record interfaceShape(Ast.InterfaceDecl iface, Named ifaceType, Set<Ast.InterfaceDecl> stack) {
        if (!stack.add(iface)) throw new IllegalArgumentException("interface inheritance cycle involving '" + iface.name() + "'");
        try {
            Named canonical = canonicalInterfaceView(iface, ifaceType);
            Map<String, Type> bindings = interfaceGenericBindings(iface, canonical);
            Map<String, Type> members = new LinkedHashMap<>();

            for (Ast.TypeRef parentRef : iface.parents()) {
                Ast.InterfaceDecl parent = findInterface(parentRef.name());
                if (parent == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
                Named parentType = resolveParentInterfaceView(iface, canonical, parentRef, parent);
                for (Map.Entry<String, Type> inherited : interfaceShape(parent, parentType, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), inherited.getValue(), "interface inheritance of " + iface.name());
                }
            }

            Set<String> generics = Set.copyOf(iface.genericParameters());
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    Set<String> all = new HashSet<>(generics);
                    all.addAll(fn.genericParameters());
                    Type raw = functionType(fn.parameters(), fn.returnType(), all, null);
                    Type resolved = resolveSharedGeneric(raw, bindings);
                    mergeMember(members, methodKey(fn.name(), fn.parameters().size()),
                            canonicalizeMethodGenerics(resolved, fn.genericParameters()),
                            "interface " + iface.name());
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    Type raw = resolve(field.type(), generics, null);
                    mergeMember(members, field.name(), resolveSharedGeneric(raw, bindings), "interface " + iface.name());
                }
            }
            return new Record(members);
        } finally {
            stack.remove(iface);
        }
    }

    private Named nominalInterfaceType(Ast.InterfaceDecl iface) {
        return new Named(qualifiedInterfaceName(iface),
                iface.genericParameters().stream().map(Generic::new).map(Type.class::cast).toList());
    }

    private Named canonicalInterfaceView(Ast.InterfaceDecl iface, Named view) {
        if (view.arguments().size() != iface.genericParameters().size()) {
            throw new IllegalArgumentException("interface '" + qualifiedInterfaceName(iface) + "' expects "
                    + iface.genericParameters().size() + " type argument(s), got " + view.arguments().size());
        }
        return new Named(qualifiedInterfaceName(iface), view.arguments());
    }

    private Map<String, Type> interfaceGenericBindings(Ast.InterfaceDecl iface, Named ifaceType) {
        Named canonical = canonicalInterfaceView(iface, ifaceType);
        Map<String, Type> bindings = new HashMap<>();
        for (int i = 0; i < iface.genericParameters().size(); i++) {
            bindings.put(iface.genericParameters().get(i), canonical.arguments().get(i));
        }
        return bindings;
    }

    private Named resolveParentInterfaceView(
            Ast.InterfaceDecl child,
            Named childType,
            Ast.TypeRef parentRef,
            Ast.InterfaceDecl parent) {
        Map<String, Type> bindings = interfaceGenericBindings(child, childType);
        Type rawParent = resolve(parentRef, Set.copyOf(child.genericParameters()), null);
        Type substituted = resolveSharedGeneric(rawParent, bindings);
        if (!(substituted instanceof Named named)) {
            throw new IllegalArgumentException("parent interface view for '" + parentRef.name() + "' is not nominal");
        }
        return canonicalInterfaceView(parent, new Named(qualifiedInterfaceName(parent), named.arguments()));
    }

    private Named resolveInterfaceView(
            Ast.InterfaceDecl iface,
            Ast.TypeRef ref,
            Set<String> generics,
            Type self) {
        Type resolved = resolve(ref, generics, self);
        if (!(resolved instanceof Named named)) {
            throw new IllegalArgumentException("interface reference '" + ref.name() + "' is not nominal");
        }
        return canonicalInterfaceView(iface, new Named(qualifiedInterfaceName(iface), named.arguments()));
    }

    private List<Ast.FieldDecl> effectiveFields(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        LinkedHashMap<String, Ast.FieldDecl> fields = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) for (Ast.FieldDecl field : effectiveFields(parent, stack)) fields.putIfAbsent(field.name(), field);
        }
        for (Ast.FieldDecl field : klass.fields()) fields.put(field.name(), field);
        stack.remove(klass);
        return List.copyOf(fields.values());
    }

    private Type findFieldType(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        Type self = nominalClassType(klass);
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                seen.remove(klass);
                return resolve(field.type(), Set.copyOf(klass.genericParameters()), self);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Type result = findFieldType(parent, name, seen);
            if (result != null) {
                seen.remove(klass);
                return result;
            }
        }
        seen.remove(klass);
        return null;
    }

    private ResolvedMethod findResolvedMethod(Named receiverType, String name, int arity) {
        Ast.ClassDecl klass = findClass(receiverType.name());
        if (klass == null) return null;
        Named rootView = canonicalClassView(klass, receiverType);
        return findResolvedMethod(klass, rootView, rootView, name, arity, new LinkedHashSet<>());
    }

    private ResolvedMethod findResolvedMethod(
            Ast.ClassDecl klass,
            Named klassType,
            Named rootReceiverType,
            String name,
            int arity,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        try {
            for (Ast.MethodDecl method : klass.methods()) {
                if (!method.isStatic() && method.matchesArity(name, arity)) {
                    return new ResolvedMethod(method, klass, canonicalClassView(klass, klassType), rootReceiverType);
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
                if (parent == null) continue;
                Named parentType = resolveParentView(klass, klassType, parentRef, parent);
                ResolvedMethod candidate = findResolvedMethod(
                        parent, parentType, rootReceiverType, name, arity, seen);
                if (candidate != null) return candidate;
            }
            return null;
        } finally {
            seen.remove(klass);
        }
    }

    private Map<String, Type> inferMethodGenericBindings(
            List<Type> expectedArguments,
            List<Type> actualArguments,
            Set<String> methodGenerics,
            String callableLabel) {
        if (methodGenerics.isEmpty()) return Map.of();
        Map<String, Type> bindings = new HashMap<>();
        for (int i = 0; i < expectedArguments.size(); i++) {
            inferMethodGenericBindings(
                    expectedArguments.get(i),
                    actualArguments.get(i),
                    methodGenerics,
                    bindings,
                    callableLabel + " argument " + (i + 1));
        }
        return Map.copyOf(bindings);
    }

    private void inferMethodGenericBindings(
            Type expected,
            Type actual,
            Set<String> methodGenerics,
            Map<String, Type> bindings,
            String where) {
        if (expected instanceof Generic generic && methodGenerics.contains(generic.name())) {
            Type existing = bindings.get(generic.name());
            if (existing == null) {
                bindings.put(generic.name(), actual);
                return;
            }
            if (!assignable(actual, existing) || !assignable(existing, actual)) {
                throw new IllegalArgumentException(
                        where + " requires generic '" + generic.name()
                                + "' to have one consistent type; inferred "
                                + existing + " and " + actual);
            }
            return;
        }

        if (expected instanceof Named expectedNamed
                && actual instanceof Named actualNamed
                && expectedNamed.name().equals(actualNamed.name())
                && expectedNamed.arguments().size() == actualNamed.arguments().size()) {
            for (int i = 0; i < expectedNamed.arguments().size(); i++) {
                inferMethodGenericBindings(
                        expectedNamed.arguments().get(i),
                        actualNamed.arguments().get(i),
                        methodGenerics,
                        bindings,
                        where);
            }
            return;
        }

        if (expected instanceof ListType expectedList && actual instanceof ListType actualList) {
            inferMethodGenericBindings(
                    expectedList.element(),
                    actualList.element(),
                    methodGenerics,
                    bindings,
                    where);
            return;
        }

        if (expected instanceof Tuple expectedTuple
                && actual instanceof Tuple actualTuple
                && expectedTuple.elements().size() == actualTuple.elements().size()) {
            for (int i = 0; i < expectedTuple.elements().size(); i++) {
                inferMethodGenericBindings(
                        expectedTuple.elements().get(i),
                        actualTuple.elements().get(i),
                        methodGenerics,
                        bindings,
                        where);
            }
            return;
        }

        if (expected instanceof Borrow expectedBorrow && actual instanceof Borrow actualBorrow) {
            inferMethodGenericBindings(
                    expectedBorrow.target(),
                    actualBorrow.target(),
                    methodGenerics,
                    bindings,
                    where);
            return;
        }

        if (expected instanceof Function expectedFunction
                && actual instanceof Function actualFunction
                && expectedFunction.parameters().size() == actualFunction.parameters().size()) {
            for (int i = 0; i < expectedFunction.parameters().size(); i++) {
                inferMethodGenericBindings(
                        expectedFunction.parameters().get(i),
                        actualFunction.parameters().get(i),
                        methodGenerics,
                        bindings,
                        where);
            }
            inferMethodGenericBindings(
                    expectedFunction.result(),
                    actualFunction.result(),
                    methodGenerics,
                    bindings,
                    where);
        }
    }

    private Type resolvedMethodParameter(ResolvedMethod resolved, Ast.Param param) {
        Set<String> generics = new HashSet<>(resolved.owner().genericParameters());
        generics.addAll(resolved.method().genericParameters());
        Type raw = resolveParam(param, generics, resolved.receiverType());
        return resolveSharedGeneric(raw, classGenericBindings(resolved.owner(), resolved.ownerType()));
    }

    private Type resolvedMethodReturn(ResolvedMethod resolved) {
        Set<String> generics = new HashSet<>(resolved.owner().genericParameters());
        generics.addAll(resolved.method().genericParameters());
        Type raw = resolve(resolved.method().returnType(), generics, resolved.receiverType());
        return resolveSharedGeneric(raw, classGenericBindings(resolved.owner(), resolved.ownerType()));
    }

    private Function resolvedMethodFunction(ResolvedMethod resolved) {
        List<Type> parameters = resolved.method().parameters().stream()
                .map(param -> resolvedMethodParameter(resolved, param))
                .toList();
        return new Function(parameters, resolvedMethodReturn(resolved));
    }

    private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.matchesArity(name, arity)) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.MethodDecl candidate = findMethod(parent, name, arity, seen);
            if (candidate != null) {
                seen.remove(klass);
                return candidate;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findMethodsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> methods = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) if (!method.isStatic() && method.name().equals(name)) methods.put(method.arity(), method);
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl method : findMethodsByName(parent, name, seen)) methods.putIfAbsent(method.arity(), method);
        }
        seen.remove(klass);
        return List.copyOf(methods.values());
    }

    private Ast.MethodDecl findStaticFunction(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() && method.matchesArity(name, arity)) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.MethodDecl candidate = findStaticFunction(parent, name, arity, seen);
            if (candidate != null) {
                seen.remove(klass);
                return candidate;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findStaticFunctionsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> functions = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) if (method.isStatic() && method.name().equals(name)) functions.put(method.arity(), method);
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl fn : findStaticFunctionsByName(parent, name, seen)) functions.putIfAbsent(fn.arity(), fn);
        }
        seen.remove(klass);
        return List.copyOf(functions.values());
    }

    private Ast.ClassDecl resolveClassParent(Ast.TypeRef parentRef, Ast.ClassDecl child) {
        if (parentRef.name().equals("Object") || parentRef.name().equals("List")) return null;
        if (parentRef.name().equals("obj") || parentRef.name().equals("arr")) throw new IllegalArgumentException("inline obj/arr values cannot be subclassed; extend Object or List instead");
        Ast.ClassDecl parent = findClass(parentRef.name());
        if (parent == null) throw new IllegalArgumentException("unknown parent class '" + parentRef.name() + "' for " + child.name());
        if (parent == child) throw new IllegalArgumentException("class '" + child.name() + "' cannot extend itself");
        return parent;
    }

    private void mergeMember(Map<String, Type> members, String name, Type type, String owner) {
        Type existing = members.get(name);
        if (existing != null) {
            boolean methodSlot = isMethodSlotKey(name)
                    && existing instanceof Function
                    && type instanceof Function;
            boolean compatible = methodSlot
                    ? existing.equals(type)
                    : assignable(existing, type) && assignable(type, existing);
            if (!compatible) {
                throw new IllegalArgumentException(
                        "conflicting member '" + name + "' in " + owner + ": " + existing + " vs " + type);
            }
        }
        members.put(name, type);
    }

    private boolean isMethodSlotKey(String name) {
        return name != null && name.contains("$arity");
    }

    private Type canonicalizeMethodGenerics(Type type, List<String> methodGenerics) {
        if (methodGenerics.isEmpty()) return type;
        Map<String, Type> canonical = new HashMap<>();
        for (int i = 0; i < methodGenerics.size(); i++) {
            canonical.put(methodGenerics.get(i), new Generic("$method" + i));
        }
        return resolveSharedGeneric(type, canonical);
    }

    private Function functionType(List<Ast.Param> params, Ast.TypeRef returns, Set<String> generics, Type self) {
        return new Function(params.stream().map(p -> resolveParam(p, generics, self)).toList(), resolve(returns, generics, self));
    }

    private Type resolveParam(Ast.Param param, Set<String> generics, Type self) {
        if (!param.structural()) return resolve(param.type(), generics, self);
        Ast.InterfaceDecl iface = findInterface(param.type().name());
        if (iface != null) {
            Named ifaceType = resolveInterfaceView(iface, param.type(), generics, self);
            return interfaceShape(iface, ifaceType, new LinkedHashSet<>());
        }
        Ast.ClassDecl klass = findClass(param.type().name());
        if (klass != null) {
            Type resolved = resolve(param.type(), generics, self);
            if (!(resolved instanceof Named named)) {
                throw new IllegalArgumentException("@Structural class type must be nominal");
            }
            return publicClassShape(klass, canonicalClassView(klass, named), new LinkedHashSet<>());
        }
        throw new IllegalArgumentException("@Structural requires a known class or interface type, got '" + param.type().name() + "'");
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self) {
        return resolve(ref, generics, self, false);
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self, boolean allowNullMarker) {
        if (ref == null) return Unknown.INSTANCE;
        if (ref.isBorrow()) return new Borrow(resolve(ref.borrowedTarget(), generics, self, allowNullMarker), ref.mutableBorrow());
        if (ref.isStringLiteral()) return new StringLiteral(ref.stringLiteralValue());
        if (ref.name().equals("$infer$")) return Unknown.INSTANCE;
        if (ref.name().equals("self")) return self == null ? Unknown.INSTANCE : self;
        if (ref.name().equals("null")) {
            if (!allowNullMarker) throw new IllegalArgumentException("null is not a standalone type; it is only legal as Option<null>");
            return Primitive.NULL;
        }
        if (ref.isUnion()) {
            return Types.unionOf(ref.arguments().stream().map(option -> resolve(option, generics, self, allowNullMarker)).toList());
        }
        if (ref.isTupleType()) {
            return new Tuple(ref.arguments().stream().map(element -> resolve(element, generics, self, allowNullMarker)).toList());
        }
        if (ref.isRecordType()) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Map.Entry<String, Ast.TypeRef> member : ref.recordMembers().entrySet()) {
                members.put(member.getKey(), resolve(member.getValue(), generics, self, allowNullMarker));
            }
            return new Record(members);
        }
        if (generics.contains(ref.name())) return new Generic(ref.name());

        Ast.TypeAliasDecl alias = findTypeAlias(ref.name());
        if (alias != null) {
            if (alias.genericParameters().size() != ref.arguments().size()) {
                throw new IllegalArgumentException("type alias '" + alias.name() + "' expects " + alias.genericParameters().size()
                        + " type argument(s), got " + ref.arguments().size());
            }
            if (!resolvingAliases.add(alias)) throw new IllegalArgumentException("type alias cycle involving '" + alias.name() + "'");
            try {
                Map<String, Ast.TypeRef> substitutions = new HashMap<>();
                for (int i = 0; i < alias.genericParameters().size(); i++) {
                    substitutions.put(alias.genericParameters().get(i), ref.arguments().get(i));
                }
                return resolve(substituteAliasType(alias.target(), substitutions), generics, self, allowNullMarker);
            } finally {
                resolvingAliases.remove(alias);
            }
        }

        return switch (ref.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "int", "uint", "bigint" -> Primitive.INT;
            case "f32", "f64", "float" -> Primitive.FLOAT;
            case "decimal" -> Primitive.DECIMAL;
            case "complex64", "complex128", "complex" -> Primitive.COMPLEX;
            case "bool", "Bool" -> Primitive.BOOL;
            case "string", "String" -> Primitive.STRING;
            case "void" -> Primitive.VOID;
            case "Array", "List" -> {
                if (!ref.inferArguments() && ref.arguments().size() != 1) throw new IllegalArgumentException(ref.name() + " requires exactly one type argument");
                yield new ListType(ref.arguments().isEmpty() ? Unknown.INSTANCE : resolve(ref.arguments().getFirst(), generics, self));
            }
            case "Option" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Option requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self, true);
                if (element == Primitive.VOID) throw new IllegalArgumentException("Option<void> is invalid; use void for no return value");
                yield new Named("Option", List.of(element));
            }
            case "MutexGuard" -> throw new IllegalArgumentException(
                    "MutexGuard<T> is compiler-managed and cannot be named in source declarations; acquire it from lock()/try_lock()/lock_async()");
            case "Mutex" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Mutex requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                if (element == Primitive.VOID) throw new IllegalArgumentException("Mutex<void> is invalid");
                if (element instanceof Borrow) {
                    throw new IllegalArgumentException("Mutex<T> requires an owned value type; borrowed payload types are invalid");
                }
                yield new Named("Mutex", List.of(element));
            }
            case "Future" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Future requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                if (element == Primitive.VOID) throw new IllegalArgumentException("Future<void> is invalid");
                yield new Named("Future", List.of(element));
            }
            case "SharedMutex" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("SharedMutex requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                if (element == Primitive.VOID) throw new IllegalArgumentException("SharedMutex<void> is invalid");
                if (!isSharedSafe(element, new LinkedHashSet<>(), Map.of())) {
                    throw new IllegalArgumentException(
                            "SharedMutex<T> requires a concrete shared-safe type; borrows, Mutex, MutexGuard, Future, closures, and unresolved generic/dynamic values are not shareable");
                }
                yield new Named("SharedMutex", List.of(element));
            }
            case "Fnc" -> {
                List<Type> args = ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList();
                if (args.isEmpty()) throw new IllegalArgumentException("Fnc requires at least a result type");
                yield new Function(args.subList(0, args.size() - 1), args.getLast());
            }
            default -> new Named(ref.name(), ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
        };
    }

    private Ast.TypeRef substituteAliasType(Ast.TypeRef ref, Map<String, Ast.TypeRef> substitutions) {
        Ast.TypeRef replacement = substitutions.get(ref.name());
        if (replacement != null && ref.arguments().isEmpty() && !ref.inferArguments()) return replacement;
        return new Ast.TypeRef(
                ref.name(),
                ref.arguments().stream().map(arg -> substituteAliasType(arg, substitutions)).toList(),
                ref.inferArguments());
    }

    private Named nominalClassType(Ast.ClassDecl klass) {
        return new Named(qualifiedClassName(klass), klass.genericParameters().stream().map(Generic::new).map(Type.class::cast).toList());
    }

    private String qualifiedClassName(Ast.ClassDecl klass) {
        String owner = classOwners.get(klass);
        return owner == null || owner.equals("__root__") ? klass.name() : owner + "." + klass.name();
    }

    private String qualifiedInterfaceName(Ast.InterfaceDecl iface) {
        String owner = interfaceOwners.get(iface);
        return owner == null || owner.equals("__root__") ? iface.name() : owner + "." + iface.name();
    }

    private boolean assignable(Type actual, Type expected) {
        if (actual instanceof Union source) {
            return source.options().stream().allMatch(option -> assignable(option, expected));
        }
        if (expected instanceof Union target) {
            return target.options().stream().anyMatch(option -> assignable(actual, option));
        }
        if (Types.isAssignable(actual, expected)) return true;

        if (actual instanceof Named actualNamed && expected instanceof Record targetShape) {
            Ast.ClassDecl klass = findClass(actualNamed.name());
            return klass != null && Types.isAssignable(
                    publicClassShape(klass, canonicalClassView(klass, actualNamed), new LinkedHashSet<>()),
                    targetShape);
        }

        if (actual instanceof Named actualNamed && expected instanceof Named expectedNamed) {
            Ast.ClassDecl actualClass = findClass(actualNamed.name());
            if (actualClass != null) {
                Ast.ClassDecl expectedClass = findClass(expectedNamed.name());
                if (expectedClass != null && classExtends(actualClass, expectedClass, new LinkedHashSet<>())) return true;
                Ast.InterfaceDecl expectedInterface = findInterface(expectedNamed.name());
                if (expectedInterface != null && classImplements(actualClass, expectedInterface, new LinkedHashSet<>())) return true;
            }
        }
        return false;
    }

    private boolean classExtends(Ast.ClassDecl actual, Ast.ClassDecl expected, Set<Ast.ClassDecl> seen) {
        if (actual == expected) return true;
        if (!seen.add(actual)) return false;
        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, actual);
            if (parent != null && classExtends(parent, expected, seen)) return true;
        }
        return false;
    }

    private boolean classImplements(Ast.ClassDecl klass, Ast.InterfaceDecl expected, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return false;
        for (Ast.TypeRef ifaceRef : klass.interfaces()) {
            Ast.InterfaceDecl iface = findInterface(ifaceRef.name());
            if (iface != null && (iface == expected || interfaceExtends(iface, expected, new LinkedHashSet<>()))) return true;
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null && classImplements(parent, expected, seen)) return true;
        }
        return false;
    }

    private boolean interfaceExtends(Ast.InterfaceDecl actual, Ast.InterfaceDecl expected, Set<Ast.InterfaceDecl> seen) {
        if (actual == expected) return true;
        if (!seen.add(actual)) return false;
        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent != null && interfaceExtends(parent, expected, seen)) return true;
        }
        return false;
    }

    private Ast.FunctionDecl findFunction(String name) {
        if (ambiguousFunctions.contains(name)) throw new IllegalArgumentException("ambiguous function/routine name '" + name + "'; qualify it with its module");
        return functions.get(name);
    }

    private Ast.ClassDecl findClass(String name) {
        if (ambiguousClasses.contains(name)) throw new IllegalArgumentException("ambiguous class name '" + name + "'; qualify it with its module");
        return classes.get(name);
    }

    private Ast.InterfaceDecl findInterface(String name) {
        if (ambiguousInterfaces.contains(name)) throw new IllegalArgumentException("ambiguous interface name '" + name + "'; qualify it with its module");
        return interfaces.get(name);
    }

    private Ast.TypeAliasDecl findTypeAlias(String name) {
        if (ambiguousTypeAliases.contains(name)) throw new IllegalArgumentException("ambiguous type alias '" + name + "'; qualify it with its module");
        return typeAliases.get(name);
    }

    private Set<String> uniqueGenerics(List<String> names, String owner) {
        Set<String> result = new HashSet<>();
        for (String name : names) if (!result.add(name)) throw new IllegalArgumentException("duplicate generic '" + name + "' in " + owner);
        return result;
    }

    private boolean constant(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.UnaryExpr unary) return constant(unary.operand());
        if (expr instanceof Ast.BinaryExpr binary) return constant(binary.left()) && constant(binary.right());
        if (expr instanceof Ast.ConditionalExpr conditional) return constant(conditional.condition()) && constant(conditional.whenTrue()) && constant(conditional.whenFalse());
        if (expr instanceof Ast.ListExpr list) return list.elements().stream().allMatch(this::constant);
        if (expr instanceof Ast.TupleExpr tuple) return tuple.elements().stream().allMatch(this::constant);
        if (expr instanceof Ast.ObjectExpr object) return object.fields().stream().allMatch(field -> constant(field.value()));
        return false;
    }

    private boolean definitelyReturns(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt) return true;
            if (stmt instanceof Ast.IfStmt conditional) {
                boolean allBranches = !conditional.branches().isEmpty()
                        && conditional.branches().stream().allMatch(branch -> definitelyReturns(branch.body()))
                        && !conditional.elseBody().isEmpty()
                        && definitelyReturns(conditional.elseBody());
                if (allBranches) return true;
            }
            if (stmt instanceof Ast.TryStmt attempted) {
                if (definitelyReturns(attempted.finallyBody())) return true;
                if (definitelyReturns(attempted.body()) && definitelyReturns(attempted.catchBody())) return true;
            }
        }
        return false;
    }

    private boolean hasInferredArgs(Ast.TypeRef ref) {
        return ref.inferArguments() || ref.arguments().stream().anyMatch(this::hasInferredArgs);
    }

    private void requireAssignable(Type actual, Type expected, String where) {
        if (!assignable(actual, expected)) throw new IllegalArgumentException(where + " has type " + actual + " but expected " + expected);
    }

    private String methodKey(String name, int arity) {
        return name + "$arity" + arity;
    }

    private void validateRoutineRecursion() {
        for (Ast.FunctionDecl fn : functionOwners.keySet()) {
            if (fn.kind() != Ast.CallableKind.ROUTINE) continue;
            if (reaches(fn, fn, new LinkedHashSet<>())) {
                throw new IllegalArgumentException("routine '" + functionOwners.get(fn) + "." + fn.name()
                        + "' participates in recursion; routines are non-recursive by contract (use fnc or a lambda)");
            }
        }
    }

    private boolean reaches(Ast.FunctionDecl current, Ast.FunctionDecl target, Set<Ast.FunctionDecl> path) {
        if (!path.add(current)) return false;
        String module = functionOwners.get(current);
        for (Ast.FunctionDecl callee : directCallees(current.body(), module)) {
            if (callee == target) return true;
            if (reaches(callee, target, path)) return true;
        }
        path.remove(current);
        return false;
    }

    private Set<Ast.FunctionDecl> directCallees(List<Ast.Stmt> body, String module) {
        Set<Ast.FunctionDecl> result = new LinkedHashSet<>();
        for (Ast.Stmt stmt : body) collectCalls(stmt, module, result);
        return result;
    }

    private void collectCalls(Ast.Stmt stmt, String module, Set<Ast.FunctionDecl> out) {
        if (stmt instanceof Ast.BindingStmt s) collectCalls(s.initializer(), module, out);
        else if (stmt instanceof Ast.DestructureStmt s) collectCalls(s.initializer(), module, out);
        else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) collectCalls(s.value(), module, out);
        else if (stmt instanceof Ast.ExprStmt s) collectCalls(s.expression(), module, out);
        else if (stmt instanceof Ast.DeferStmt s) collectCalls(s.expression(), module, out);
        else if (stmt instanceof Ast.IfStmt s) {
            for (Ast.IfBranch b : s.branches()) {
                collectCalls(b.condition(), module, out);
                for (Ast.Stmt nested : b.body()) collectCalls(nested, module, out);
            }
            for (Ast.Stmt nested : s.elseBody()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.TryStmt s) {
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
            for (Ast.Stmt nested : s.catchBody()) collectCalls(nested, module, out);
            for (Ast.Stmt nested : s.finallyBody()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.ForOfStmt s) {
            collectCalls(s.iterable(), module, out);
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.ForStmt s) {
            if (s.initializer() != null) collectCalls(s.initializer(), module, out);
            if (s.condition() != null) collectCalls(s.condition(), module, out);
            if (s.update() != null) collectCalls(s.update(), module, out);
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
        }
    }

    private void collectCalls(Ast.Expr expr, String module, Set<Ast.FunctionDecl> out) {
        if (expr instanceof Ast.CallExpr call) {
            Ast.FunctionDecl callee = resolveStaticCall(call.callee(), module);
            if (callee != null) out.add(callee);
            collectCalls(call.callee(), module, out);
            for (Ast.Expr arg : call.arguments()) collectCalls(arg, module, out);
        } else if (expr instanceof Ast.BinaryExpr e) {
            collectCalls(e.left(), module, out); collectCalls(e.right(), module, out);
        } else if (expr instanceof Ast.UnaryExpr e) collectCalls(e.operand(), module, out);
        else if (expr instanceof Ast.AssignExpr e) collectCalls(e.value(), module, out);
        else if (expr instanceof Ast.ConditionalExpr e) {
            collectCalls(e.condition(), module, out); collectCalls(e.whenTrue(), module, out); collectCalls(e.whenFalse(), module, out);
        } else if (expr instanceof Ast.MemberExpr e) collectCalls(e.receiver(), module, out);
        else if (expr instanceof Ast.IndexExpr e) { collectCalls(e.receiver(), module, out); collectCalls(e.index(), module, out); }
        else if (expr instanceof Ast.NewExpr e) for (Ast.Expr arg : e.arguments()) collectCalls(arg, module, out);
        else if (expr instanceof Ast.AwaitExpr e) collectCalls(e.expression(), module, out);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr item : e.elements()) collectCalls(item, module, out);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr item : e.elements()) collectCalls(item, module, out);
        else if (expr instanceof Ast.ObjectExpr e) for (Ast.ObjectField f : e.fields()) collectCalls(f.value(), module, out);
        else if (expr instanceof Ast.LambdaExpr e) {
            if (e.expressionBody() != null) collectCalls(e.expressionBody(), module, out);
            if (e.blockBody() != null) for (Ast.Stmt nested : e.blockBody()) collectCalls(nested, module, out);
        }
    }

    private Ast.FunctionDecl resolveStaticCall(Ast.Expr callee, String module) {
        if (callee instanceof Ast.NameExpr name) {
            Ast.FunctionDecl local = functions.get(module + "." + name.name());
            if (local != null) return local;
            return ambiguousFunctions.contains(name.name()) ? null : functions.get(name.name());
        }
        if (callee instanceof Ast.MemberExpr member && member.receiver() instanceof Ast.NameExpr namespace) {
            return functions.get(namespace.name() + "." + member.member());
        }
        return null;
    }

    private static final class Env {
        private final Env parent;
        private final Map<String, Binding> bindings = new HashMap<>();
        private Env(Env parent) { this.parent = parent; }
        private void define(String name, Type type, Ast.BindingKind kind) {
            if (bindings.putIfAbsent(name, new Binding(type, kind)) != null) throw new IllegalArgumentException("duplicate binding '" + name + "'");
        }
        private Binding lookup(String name) {
            Binding binding = bindings.get(name);
            return binding != null ? binding : parent == null ? null : parent.lookup(name);
        }
        private void replace(String name, Type type, Ast.BindingKind kind) {
            if (!bindings.containsKey(name)) throw new IllegalArgumentException("unknown binding '" + name + "'");
            bindings.put(name, new Binding(type, kind));
        }
        private record Binding(Type type, Ast.BindingKind kind) { }
    }
}
