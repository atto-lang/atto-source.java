package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.Tuple;
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
    private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, String> classOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.InterfaceDecl, String> interfaceOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, Record> classShapeCache = new IdentityHashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousInterfaces = new HashSet<>();
    private final Set<String> importedValues = new HashSet<>();

    public static Ast.Program check(Ast.Program program) {
        TypeChecker checker = new TypeChecker();
        checker.validateImports(program);
        checker.collect(program);
        checker.validate(program);
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
                    putQualified(functions, ambiguousFunctions, module.name(), fn.name(), fn, "function");
                } else if (decl instanceof Ast.ClassDecl klass) {
                    putQualified(classes, ambiguousClasses, module.name(), klass.name(), klass, "class");
                    classOwners.put(klass, module.name());
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    putQualified(interfaces, ambiguousInterfaces, module.name(), iface.name(), iface, "interface");
                    interfaceOwners.put(iface, module.name());
                }
            }
        }
    }

    private static <T> void putQualified(Map<String, T> map, Set<String> ambiguous, String module, String name, T value, String kind) {
        String qualified = module + "." + name;
        if (map.putIfAbsent(qualified, value) != null) throw new IllegalArgumentException("duplicate " + kind + " '" + qualified + "'");
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            ambiguous.add(name);
            map.remove(name);
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ClassDecl klass : classOwners.keySet()) classShape(klass, new LinkedHashSet<>());

        for (Ast.ModuleDecl module : program.modules()) {
            checkModuleAdherence(module);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(module.name(), klass);
                else if (decl instanceof Ast.InterfaceDecl iface) checkInterface(iface);
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
                Record expected = interfaceShape(iface, Set.copyOf(iface.genericParameters()));
                if (!Types.isAssignable(actual, expected)) {
                    throw new IllegalArgumentException("module '" + module.name() + "' does not adhere to interface '" + ref.name() + "': expected " + expected + " but got " + actual);
                }
            }
        }
    }

    private Record moduleShape(Ast.ModuleDecl module) {
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                mergeMember(members, fn.name(), functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null), "module " + module.name());
            } else if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
                Type type = field.type() == null ? typeOf(field.initializer(), new Env(null), Set.of(), null) : resolve(field.type(), Set.of(), null);
                mergeMember(members, field.name(), type, "module " + module.name());
            }
        }
        return new Record(members);
    }

    private void checkInterface(Ast.InterfaceDecl iface) {
        Set<String> generics = uniqueGenerics(iface.genericParameters(), "interface " + iface.name());
        Set<String> names = new HashSet<>();
        for (Ast.InterfaceMember member : iface.members()) {
            String name = member instanceof Ast.InterfaceFunctionDecl fn ? fn.name() : ((Ast.InterfaceFieldDecl) member).name();
            if (!names.add(name)) throw new IllegalArgumentException("duplicate interface member '" + iface.name() + "." + name + "'");
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                Set<String> all = new HashSet<>(generics);
                for (String generic : fn.genericParameters()) if (!all.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in interface " + iface.name() + "." + fn.name());
                functionType(fn.parameters(), fn.returnType(), all, null);
            } else resolve(((Ast.InterfaceFieldDecl) member).type(), generics, null);
        }
    }

    private void checkFunction(String module, Ast.FunctionDecl fn) {
        Set<String> generics = uniqueGenerics(fn.genericParameters(), "function " + fn.name());
        Env env = new Env(null);
        for (Ast.Param param : fn.parameters()) env.define(param.name(), resolve(param.type(), generics, null), Ast.BindingKind.VAL);
        Type returns = resolve(fn.returnType(), generics, null);
        checkBlock(fn.body(), env, generics, returns, null);
        if (returns != Primitive.VOID && !definitelyReturns(fn.body())) {
            throw new IllegalArgumentException("non-void function '" + module + "." + fn.name() + "' must explicitly return on every path");
        }
        if (fn.visibility() == Ast.Visibility.PUBLIC && hasInferredArgs(fn.returnType())) {
            throw new IllegalArgumentException("public function '" + fn.name() + "' cannot export unresolved <> type arguments");
        }
    }

    private void checkClass(String module, Ast.ClassDecl klass) {
        Set<String> classGenerics = uniqueGenerics(klass.genericParameters(), "class " + klass.name());
        Type self = classShape(klass, new LinkedHashSet<>());

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
            Set<String> generics = new HashSet<>(classGenerics);
            for (String generic : method.genericParameters()) {
                if (!generics.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in " + klass.name() + "." + method.name());
            }

            if (method.explicitReceiverType() != null) {
                Ast.TypeRef receiverRef = method.explicitReceiverType();
                boolean namesEnclosingClass = receiverRef.name().equals(klass.name()) || receiverRef.name().equals("self");
                if (!namesEnclosingClass) {
                    Type receiver = resolve(receiverRef, generics, self);
                    requireAssignable(self, receiver, "explicit self receiver in " + klass.name() + "." + method.name());
                }
            }

            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", self, Ast.BindingKind.VAL);
            for (Ast.Param param : method.parameters()) env.define(param.name(), resolve(param.type(), generics, self), Ast.BindingKind.VAL);
            Type returns = resolve(method.returnType(), generics, self);
            checkBlock(method.body(), env, generics, returns, self);
            if (!method.isAbstract() && returns != Primitive.VOID && !definitelyReturns(method.body())) {
                throw new IllegalArgumentException("non-void method '" + module + "." + klass.name() + "." + method.name() + "' must explicitly return on every path");
            }
        }

        Set<String> implemented = new HashSet<>();
        for (Ast.TypeRef interfaceRef : klass.interfaces()) {
            if (!implemented.add(interfaceRef.name())) throw new IllegalArgumentException("duplicate implemented interface '" + interfaceRef.name() + "' on " + klass.name());
            Ast.InterfaceDecl iface = findInterface(interfaceRef.name());
            if (iface == null) throw new IllegalArgumentException("unknown interface '" + interfaceRef.name() + "' implemented by " + klass.name());
            Record expected = interfaceShape(iface, Set.copyOf(iface.genericParameters()));
            Record actual = publicClassShape(klass, new LinkedHashSet<>());
            if (!Types.isAssignable(actual, expected)) {
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
            Type actual = typeOf(binding.initializer(), env, generics, self);
            Type declared = binding.declaredType() == null ? actual : resolve(binding.declaredType(), generics, self);
            requireAssignable(actual, declared, "initializer for " + binding.name());
            if (binding.kind() == Ast.BindingKind.CONST && !constant(binding.initializer())) {
                throw new IllegalArgumentException("const '" + binding.name() + "' needs a compile-time constant initializer");
            }
            env.define(binding.name(), declared, binding.kind());
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            Type source = typeOf(destructure.initializer(), env, generics, self);
            if (source instanceof Tuple tuple) {
                if (tuple.elements().size() != destructure.bindings().size()) throw new IllegalArgumentException("destructure arity mismatch");
                for (int i = 0; i < destructure.bindings().size(); i++) {
                    Ast.DestructureBinding binding = destructure.bindings().get(i);
                    env.define(binding.name(), tuple.elements().get(i), binding.kind());
                }
            } else if (source instanceof ListType list) {
                for (Ast.DestructureBinding binding : destructure.bindings()) env.define(binding.name(), list.element(), binding.kind());
            } else throw new IllegalArgumentException("destructuring requires a tuple or array/list value");
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            Type actual = ret.value() == null ? Primitive.VOID : typeOf(ret.value(), env, generics, self);
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
        }
    }

    private Type typeOf(Ast.Expr expr, Env env, Set<String> generics, Type self) {
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value == null) throw new IllegalArgumentException("standalone null values are forbidden; use Option<T>");
            if (value instanceof Long) return Primitive.INT;
            if (value instanceof Double) return Primitive.FLOAT;
            if (value instanceof Boolean) return Primitive.BOOL;
            if (value instanceof String) return Primitive.STRING;
            if (value instanceof Ast.Imaginary) return Primitive.COMPLEX;
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.NameExpr name) {
            Env.Binding local = env.lookup(name.name());
            if (local != null) return local.type();
            if (name.name().equals("stdio") || name.name().equals("process")) return new Named(name.name(), List.of());
            if (name.name().equals("print")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            Ast.ModuleDecl moduleNamespace = modules.get(name.name());
            if (moduleNamespace != null) return moduleShape(moduleNamespace);
            if (importedValues.contains(name.name())) return Unknown.INSTANCE;
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) return functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
            throw new IllegalArgumentException("unknown name '" + name.name() + "'");
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            if (!(assignment.target() instanceof Ast.NameExpr name)) throw new IllegalArgumentException("assignment target must be a local name");
            Env.Binding binding = env.lookup(name.name());
            if (binding == null) throw new IllegalArgumentException("cannot assign unknown name '" + name.name() + "'");
            if (binding.kind() != Ast.BindingKind.LET) throw new IllegalArgumentException("cannot reassign " + binding.kind().name().toLowerCase() + " binding '" + name.name() + "'");
            Type value = typeOf(assignment.value(), env, generics, self);
            requireAssignable(value, binding.type(), "assignment to " + name.name());
            return binding.type();
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            Type operand = typeOf(unary.operand(), env, generics, self);
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
                    if (!(Types.isNumeric(left) && Types.isNumeric(right)) && !(left == Primitive.STRING && right == Primitive.STRING)) {
                        throw new IllegalArgumentException("comparison operands must both be numeric or both strings");
                    }
                    yield Primitive.BOOL;
                }
                case "+" -> left == Primitive.STRING && right == Primitive.STRING ? Primitive.STRING : numericJoin(left, right, "+");
                case "-", "*", "/", "%" -> numericJoin(left, right, binary.operator());
                default -> Unknown.INSTANCE;
            };
        }
        if (expr instanceof Ast.CallExpr call) {
            Type callee = typeOf(call.callee(), env, generics, self);
            if (!(callee instanceof Function fn)) return Unknown.INSTANCE;
            if (fn.parameters().size() != call.arguments().size()) throw new IllegalArgumentException("call arity mismatch");
            for (int i = 0; i < fn.parameters().size(); i++) {
                requireAssignable(typeOf(call.arguments().get(i), env, generics, self), fn.parameters().get(i), "argument " + (i + 1));
            }
            return fn.result();
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("stdio")
                    && (member.member().equals("print") || member.member().equals("println"))) {
                return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("process")) return Unknown.INSTANCE;
            if (member.receiver() instanceof Ast.NameExpr name && importedValues.contains(name.name())) return Unknown.INSTANCE;
            Type receiver = typeOf(member.receiver(), env, generics, self);
            if (receiver instanceof Record record) {
                Type result = record.members().get(member.member());
                if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
                return result;
            }
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            Type receiver = typeOf(indexed.receiver(), env, generics, self);
            Type index = typeOf(indexed.index(), env, generics, self);
            requireAssignable(index, Primitive.INT, "array/list index");
            if (receiver instanceof ListType list) return list.element();
            if (receiver instanceof Tuple tuple) return tuple.elements().stream().reduce(Unknown.INSTANCE, this::commonType);
            throw new IllegalArgumentException("indexing requires an array/list or tuple");
        }
        if (expr instanceof Ast.NewExpr created) {
            Ast.ClassDecl klass = findClass(created.type().name());
            if (klass == null) return new Named(created.type().name(), created.type().arguments().stream().map(a -> resolve(a, generics, self)).toList());
            List<Ast.FieldDecl> fields = effectiveFields(klass, new LinkedHashSet<>());
            if (created.arguments().size() > fields.size()) throw new IllegalArgumentException("constructor for " + klass.name() + " received too many positional fields");
            for (int i = 0; i < fields.size(); i++) {
                Ast.FieldDecl field = fields.get(i);
                if (i < created.arguments().size()) {
                    requireAssignable(typeOf(created.arguments().get(i), env, generics, self), resolve(field.type(), Set.copyOf(klass.genericParameters()), classShape(klass, new LinkedHashSet<>())), "constructor field " + field.name());
                } else if (field.initializer() == null) {
                    throw new IllegalArgumentException("constructor for " + klass.name() + " is missing field '" + field.name() + "'");
                }
            }
            return classShape(klass, new LinkedHashSet<>());
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            Type awaitedType = typeOf(awaited.expression(), env, generics, self);
            if (awaitedType instanceof Named named && named.name().equals("Future") && named.arguments().size() == 1) return named.arguments().getFirst();
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.ListExpr list) {
            if (list.elements().isEmpty()) return new ListType(Unknown.INSTANCE);
            Type element = typeOf(list.elements().getFirst(), env, generics, self);
            for (int i = 1; i < list.elements().size(); i++) element = commonType(element, typeOf(list.elements().get(i), env, generics, self));
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
                Type type = resolve(param.type(), generics, self);
                parameters.add(type);
                lambdaEnv.define(param.name(), type, Ast.BindingKind.VAL);
            }
            Type result = lambda.expressionBody() == null ? Unknown.INSTANCE : typeOf(lambda.expressionBody(), lambdaEnv, generics, self);
            return new Function(parameters, result);
        }
        return Unknown.INSTANCE;
    }

    private Type commonType(Type a, Type b) {
        if (Types.isAssignable(b, a) && Types.isAssignable(a, b)) return a;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        return Unknown.INSTANCE;
    }

    private Type numericJoin(Type left, Type right, String op) {
        Type result = Types.numericJoin(left, right);
        if (result == Unknown.INSTANCE) throw new IllegalArgumentException("operator '" + op + "' needs numeric operands");
        return result;
    }

    private Record classShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        Record cached = classShapeCache.get(klass);
        if (cached != null) return cached;
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                for (Map.Entry<String, Type> inherited : classShape(parent, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), inherited.getValue(), "multiple inheritance of " + klass.name());
                }
            }
        }
        Set<String> generics = Set.copyOf(klass.genericParameters());
        for (Ast.FieldDecl field : klass.fields()) mergeMember(members, field.name(), resolve(field.type(), generics, Unknown.INSTANCE), "class " + klass.name());
        for (Ast.MethodDecl method : klass.methods()) mergeMember(members, method.name(), functionType(method.parameters(), method.returnType(), generics, Unknown.INSTANCE), "class " + klass.name());
        stack.remove(klass);
        Record result = new Record(members);
        classShapeCache.put(klass, result);
        return result;
    }

    private Record publicClassShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                for (Map.Entry<String, Type> inherited : publicClassShape(parent, stack).members().entrySet()) mergeMember(members, inherited.getKey(), inherited.getValue(), "public inheritance of " + klass.name());
            }
        }
        Set<String> generics = Set.copyOf(klass.genericParameters());
        for (Ast.FieldDecl field : klass.fields()) if (field.visibility() == Ast.Visibility.PUBLIC) mergeMember(members, field.name(), resolve(field.type(), generics, Unknown.INSTANCE), "class " + klass.name());
        for (Ast.MethodDecl method : klass.methods()) if (method.visibility() == Ast.Visibility.PUBLIC) mergeMember(members, method.name(), functionType(method.parameters(), method.returnType(), generics, Unknown.INSTANCE), "class " + klass.name());
        stack.remove(klass);
        return new Record(members);
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

    private Ast.ClassDecl resolveClassParent(Ast.TypeRef parentRef, Ast.ClassDecl child) {
        if (parentRef.name().equals("Object") || parentRef.name().equals("List")) return null;
        if (parentRef.name().equals("obj") || parentRef.name().equals("arr")) throw new IllegalArgumentException("inline obj/arr values cannot be subclassed; extend Object or List instead");
        Ast.ClassDecl parent = findClass(parentRef.name());
        if (parent == null) throw new IllegalArgumentException("unknown parent class '" + parentRef.name() + "' for " + child.name());
        if (parent == child) throw new IllegalArgumentException("class '" + child.name() + "' cannot extend itself");
        return parent;
    }

    private Record interfaceShape(Ast.InterfaceDecl iface, Set<String> generics) {
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                Set<String> all = new HashSet<>(generics);
                all.addAll(fn.genericParameters());
                mergeMember(members, fn.name(), functionType(fn.parameters(), fn.returnType(), all, null), "interface " + iface.name());
            } else if (member instanceof Ast.InterfaceFieldDecl field) {
                mergeMember(members, field.name(), resolve(field.type(), generics, null), "interface " + iface.name());
            }
        }
        return new Record(members);
    }

    private void mergeMember(Map<String, Type> members, String name, Type type, String owner) {
        Type existing = members.get(name);
        if (existing != null && (!Types.isAssignable(existing, type) || !Types.isAssignable(type, existing))) {
            throw new IllegalArgumentException("conflicting member '" + name + "' in " + owner + ": " + existing + " vs " + type);
        }
        members.put(name, type);
    }

    private Function functionType(List<Ast.Param> params, Ast.TypeRef returns, Set<String> generics, Type self) {
        return new Function(params.stream().map(p -> resolve(p.type(), generics, self)).toList(), resolve(returns, generics, self));
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self) {
        return resolve(ref, generics, self, false);
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self, boolean allowNullMarker) {
        if (ref == null) return Unknown.INSTANCE;
        if (ref.name().equals("self")) return self == null ? Unknown.INSTANCE : self;
        if (ref.name().equals("null")) {
            if (!allowNullMarker) throw new IllegalArgumentException("null is not a standalone type; it is only legal as Option<null>");
            return Primitive.NULL;
        }
        if (generics.contains(ref.name())) return new Generic(ref.name());
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
            case "Fnc" -> {
                List<Type> args = ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList();
                if (args.isEmpty()) throw new IllegalArgumentException("Fnc requires at least a result type");
                yield new Function(args.subList(0, args.size() - 1), args.getLast());
            }
            default -> new Named(ref.name(), ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
        };
    }

    private Ast.FunctionDecl findFunction(String name) {
        if (ambiguousFunctions.contains(name)) throw new IllegalArgumentException("ambiguous function name '" + name + "'; qualify it with its module");
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

    private Set<String> uniqueGenerics(List<String> names, String owner) {
        Set<String> result = new HashSet<>();
        for (String name : names) if (!result.add(name)) throw new IllegalArgumentException("duplicate generic '" + name + "' in " + owner);
        return result;
    }

    private boolean constant(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.UnaryExpr unary) return constant(unary.operand());
        if (expr instanceof Ast.BinaryExpr binary) return constant(binary.left()) && constant(binary.right());
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
        if (!Types.isAssignable(actual, expected)) throw new IllegalArgumentException(where + " has type " + actual + " but expected " + expected);
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
        private record Binding(Type type, Ast.BindingKind kind) { }
    }
}
