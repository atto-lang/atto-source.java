package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.oreslang.types.Types.*;

public final class TypeChecker {
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Function> functions = new HashMap<>();

    public static Ast.Program check(Ast.Program program) {
        TypeChecker checker = new TypeChecker();
        checker.collect(program);
        checker.validate(program);
        return program;
    }

    private void collect(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass) {
                    putUnique(classes, module.name() + "." + klass.name(), klass, "class");
                    classes.putIfAbsent(klass.name(), klass);
                } else if (decl instanceof Ast.FunctionDecl fn) {
                    Function type = functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
                    putUnique(functions, module.name() + "." + fn.name(), type, "function");
                    functions.putIfAbsent(fn.name(), type);
                }
            }
        }
    }

    private <T> void putUnique(Map<String, T> map, String name, T value, String kind) {
        if (map.putIfAbsent(name, value) != null) throw new IllegalArgumentException("duplicate " + kind + " '" + name + "'");
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(module.name(), klass);
                else if (decl instanceof Ast.FieldDecl field) checkModuleField(field);
            }
        }
    }

    private void checkModuleField(Ast.FieldDecl field) {
        if (field.initializer() == null) throw new IllegalArgumentException("module binding '" + field.name() + "' requires initializer");
        Type actual = expressionType(field.initializer(), new Env(), Set.of(), null);
        if (field.type() != null) requireAssignable(actual, resolve(field.type(), Set.of(), null), "initializer for " + field.name());
        if (field.bindingKind() == Ast.BindingKind.CONST && !isCompileTimeConstant(field.initializer())) {
            throw new IllegalArgumentException("const '" + field.name() + "' requires a compile-time constant initializer");
        }
    }

    private void checkFunction(String moduleName, Ast.FunctionDecl fn) {
        Set<String> generics = uniqueGenerics(fn.genericParameters(), "function " + fn.name());
        Env env = new Env();
        for (Ast.Param param : fn.parameters()) env.define(param.name(), resolve(param.type(), generics, null), Ast.BindingKind.VAL);
        Type expectedReturn = resolve(fn.returnType(), generics, null);
        checkBlock(fn.body(), env, generics, expectedReturn, null);
        if (expectedReturn != Primitive.VOID && !containsReturn(fn.body())) {
            throw new IllegalArgumentException("non-void function '" + moduleName + "." + fn.name() + "' has no explicit return");
        }
        if (fn.visibility() == Ast.Visibility.PUBLIC && containsInferredGeneric(fn.returnType())) {
            throw new IllegalArgumentException("public function '" + fn.name() + "' must resolve inferred generic return arguments");
        }
    }

    private void checkClass(String moduleName, Ast.ClassDecl klass) {
        Set<String> generics = uniqueGenerics(klass.genericParameters(), "class " + klass.name());
        Type selfType = classShape(klass, generics);

        for (Ast.FieldDecl field : klass.fields()) {
            Type declared = resolve(field.type(), generics, selfType);
            if (field.initializer() != null) {
                Type actual = expressionType(field.initializer(), new Env(), generics, selfType);
                requireAssignable(actual, declared, "field initializer " + klass.name() + "." + field.name());
            }
            if (field.bindingKind() == Ast.BindingKind.CONST && field.initializer() != null && !isCompileTimeConstant(field.initializer())) {
                throw new IllegalArgumentException("const field '" + field.name() + "' requires a compile-time constant initializer");
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            Set<String> methodGenerics = new HashSet<>(generics);
            if (!methodGenerics.addAll(method.genericParameters())) {
                throw new IllegalArgumentException("method generic shadows an existing generic in " + klass.name() + "." + method.name());
            }
            if (method.explicitReceiverType() != null) {
                Type receiver = resolve(method.explicitReceiverType(), methodGenerics, selfType);
                requireAssignable(selfType, receiver, "explicit self receiver in " + klass.name() + "." + method.name());
            }
            Env env = new Env();
            if (!method.isStatic()) env.define("self", selfType, Ast.BindingKind.VAL);
            for (Ast.Param param : method.parameters()) env.define(param.name(), resolve(param.type(), methodGenerics, selfType), Ast.BindingKind.VAL);
            Type expected = resolve(method.returnType(), methodGenerics, selfType);
            checkBlock(method.body(), env, methodGenerics, expected, selfType);
            if (!method.isAbstract() && expected != Primitive.VOID && !containsReturn(method.body())) {
                throw new IllegalArgumentException("non-void method '" + moduleName + "." + klass.name() + "." + method.name() + "' has no explicit return");
            }
        }
    }

    private Type classShape(Ast.ClassDecl klass, Set<String> generics) {
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.FieldDecl field : klass.fields()) members.put(field.name(), resolve(field.type(), generics, Unknown.INSTANCE));
        for (Ast.MethodDecl method : klass.methods()) {
            members.put(method.name(), functionType(method.parameters(), method.returnType(), generics, Unknown.INSTANCE));
        }
        return new Record(members);
    }

    private Function functionType(List<Ast.Param> params, Ast.TypeRef returnType, Set<String> generics, Type selfType) {
        List<Type> parameterTypes = params.stream().map(p -> resolve(p.type(), generics, selfType)).toList();
        return new Function(parameterTypes, resolve(returnType, generics, selfType));
    }

    private Set<String> uniqueGenerics(List<String> names, String owner) {
        Set<String> result = new HashSet<>();
        for (String name : names) if (!result.add(name)) throw new IllegalArgumentException("duplicate generic '" + name + "' in " + owner);
        return result;
    }

    private void checkBlock(List<Ast.Stmt> statements, Env env, Set<String> generics, Type expectedReturn, Type selfType) {
        Env local = env.child();
        for (Ast.Stmt statement : statements) checkStatement(statement, local, generics, expectedReturn, selfType);
    }

    private void checkStatement(Ast.Stmt statement, Env env, Set<String> generics, Type expectedReturn, Type selfType) {
        if (statement instanceof Ast.BindingStmt binding) {
            Type actual = expressionType(binding.initializer(), env, generics, selfType);
            Type declared = binding.declaredType() == null ? actual : resolve(binding.declaredType(), generics, selfType);
            requireAssignable(actual, declared, "initializer for " + binding.name());
            if (binding.kind() == Ast.BindingKind.CONST && !isCompileTimeConstant(binding.initializer())) {
                throw new IllegalArgumentException("const '" + binding.name() + "' requires a compile-time constant initializer");
            }
            env.define(binding.name(), declared, binding.kind());
            return;
        }
        if (statement instanceof Ast.DestructureStmt destructure) {
            Type actual = expressionType(destructure.initializer(), env, generics, selfType);
            List<Type> elements = actual instanceof Tuple tuple ? tuple.elements()
                    : destructure.initializer() instanceof Ast.ListExpr list
                    ? list.elements().stream().map(e -> expressionType(e, env, generics, selfType)).toList()
                    : null;
            if (elements == null) throw new IllegalArgumentException("destructuring requires a tuple/list-like statically known value in v0");
            if (elements.size() != destructure.bindings().size()) throw new IllegalArgumentException("destructure arity mismatch");
            for (int i = 0; i < elements.size(); i++) env.define(destructure.bindings().get(i).name(), elements.get(i), destructure.bindings().get(i).kind());
            return;
        }
        if (statement instanceof Ast.ReturnStmt ret) {
            Type actual = ret.value() == null ? Primitive.VOID : expressionType(ret.value(), env, generics, selfType);
            requireAssignable(actual, expectedReturn, "return value");
            return;
        }
        if (statement instanceof Ast.ExprStmt expr) {
            expressionType(expr.expression(), env, generics, selfType);
            return;
        }
        if (statement instanceof Ast.DeferStmt defer) {
            expressionType(defer.expression(), env, generics, selfType);
            return;
        }
        if (statement instanceof Ast.IfStmt ifStmt) {
            for (Ast.IfBranch branch : ifStmt.branches()) {
                requireAssignable(expressionType(branch.condition(), env, generics, selfType), Primitive.BOOL, "if condition");
                checkBlock(branch.body(), env, generics, expectedReturn, selfType);
            }
            checkBlock(ifStmt.elseBody(), env, generics, expectedReturn, selfType);
            return;
        }
        if (statement instanceof Ast.TryStmt tryStmt) {
            checkBlock(tryStmt.body(), env, generics, expectedReturn, selfType);
            Env catchEnv = env.child();
            catchEnv.define(tryStmt.errorName(), Unknown.INSTANCE, Ast.BindingKind.VAL);
            checkBlock(tryStmt.catchBody(), catchEnv, generics, expectedReturn, selfType);
            checkBlock(tryStmt.finallyBody(), env, generics, expectedReturn, selfType);
        }
    }

    private Type expressionType(Ast.Expr expression, Env env, Set<String> generics, Type selfType) {
        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value == null) return Primitive.NULL;
            if (value instanceof Long) return Primitive.INT;
            if (value instanceof Double) return Primitive.FLOAT;
            if (value instanceof Boolean) return Primitive.BOOL;
            if (value instanceof String) return Primitive.STRING;
            if (value instanceof Ast.Imaginary) return Primitive.COMPLEX;
            return Unknown.INSTANCE;
        }
        if (expression instanceof Ast.NameExpr name) {
            Type local = env.lookup(name.name());
            if (local != null) return local;
            Function function = functions.get(name.name());
            if (function != null) return function;
            if (name.name().equals("stdio") || name.name().equals("process")) return new Named(name.name(), List.of());
            throw new IllegalArgumentException("unknown name '" + name.name() + "'");
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            Type operand = expressionType(unary.operand(), env, generics, selfType);
            if (unary.operator().equals("!")) {
                requireAssignable(operand, Primitive.BOOL, "! operand");
                return Primitive.BOOL;
            }
            if (!isNumeric(operand)) throw new IllegalArgumentException("unary " + unary.operator() + " requires numeric operand");
            return operand;
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            Type left = expressionType(binary.left(), env, generics, selfType);
            Type right = expressionType(binary.right(), env, generics, selfType);
            return switch (binary.operator()) {
                case ",", "|" -> {
                    requireAssignable(left, Primitive.BOOL, "boolean operator");
                    requireAssignable(right, Primitive.BOOL, "boolean operator");
                    yield Primitive.BOOL;
                }
                case "==", "!=", "<", "<=", ">", ">=" -> Primitive.BOOL;
                case "+" -> left == Primitive.STRING && right == Primitive.STRING ? Primitive.STRING : requireNumericJoin(left, right, "+");
                case "-", "*", "/", "%" -> requireNumericJoin(left, right, binary.operator());
                default -> Unknown.INSTANCE;
            };
        }
        if (expression instanceof Ast.CallExpr call) {
            Type callee = expressionType(call.callee(), env, generics, selfType);
            if (!(callee instanceof Function fn)) return Unknown.INSTANCE;
            if (fn.parameters().size() != call.arguments().size()) throw new IllegalArgumentException("call arity mismatch");
            for (int i = 0; i < fn.parameters().size(); i++) {
                requireAssignable(expressionType(call.arguments().get(i), env, generics, selfType), fn.parameters().get(i), "argument " + (i + 1));
            }
            return fn.result();
        }
        if (expression instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("stdio")) {
                if (member.member().equals("print") || member.member().equals("println")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("process")) return Unknown.INSTANCE;
            Type receiver = expressionType(member.receiver(), env, generics, selfType);
            if (receiver instanceof Record record) {
                Type type = record.members().get(member.member());
                if (type != null) return type;
            }
            return Unknown.INSTANCE;
        }
        if (expression instanceof Ast.NewExpr created) {
            Ast.ClassDecl klass = classes.get(created.type().name());
            if (klass == null) return new Named(created.type().name(), created.type().arguments().stream().map(a -> resolve(a, generics, selfType)).toList());
            return classShape(klass, Set.copyOf(klass.genericParameters()));
        }
        if (expression instanceof Ast.AwaitExpr awaited) {
            Type type = expressionType(awaited.expression(), env, generics, selfType);
            if (type instanceof Named named && named.name().equals("Future") && named.arguments().size() == 1) return named.arguments().getFirst();
            return Unknown.INSTANCE;
        }
        if (expression instanceof Ast.ListExpr list) {
            if (list.elements().isEmpty()) return new ListType(Unknown.INSTANCE);
            Type element = expressionType(list.elements().getFirst(), env, generics, selfType);
            for (int i = 1; i < list.elements().size(); i++) {
                Type next = expressionType(list.elements().get(i), env, generics, selfType);
                if (!isAssignable(next, element) || !isAssignable(element, next)) element = Unknown.INSTANCE;
            }
            return new ListType(element);
        }
        if (expression instanceof Ast.LambdaExpr lambda) {
            Env lambdaEnv = env.child();
            List<Type> params = new ArrayList<>();
            for (Ast.Param param : lambda.parameters()) {
                Type type = resolve(param.type(), generics, selfType);
                lambdaEnv.define(param.name(), type, Ast.BindingKind.VAL);
                params.add(type);
            }
            Type result = lambda.expressionBody() == null ? Unknown.INSTANCE : expressionType(lambda.expressionBody(), lambdaEnv, generics, selfType);
            return new Function(params, result);
        }
        return Unknown.INSTANCE;
    }

    private Type requireNumericJoin(Type left, Type right, String operator) {
        Type joined = numericJoin(left, right);
        if (joined == Unknown.INSTANCE) throw new IllegalArgumentException("operator '" + operator + "' requires numeric operands");
        return joined;
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type selfType) {
        if (ref == null) return Unknown.INSTANCE;
        if (ref.name().equals("self")) return selfType == null ? Unknown.INSTANCE : selfType;
        if (generics.contains(ref.name())) return new Generic(ref.name());
        return switch (ref.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "int", "uint", "bigint" -> Primitive.INT;
            case "f32", "f64", "float" -> Primitive.FLOAT;
            case "decimal" -> Primitive.DECIMAL;
            case "complex64", "complex128", "complex" -> Primitive.COMPLEX;
            case "bool", "Bool" -> Primitive.BOOL;
            case "string", "String" -> Primitive.STRING;
            case "void" -> Primitive.VOID;
            case "Array", "List" -> new ListType(ref.arguments().isEmpty() ? Unknown.INSTANCE : resolve(ref.arguments().getFirst(), generics, selfType));
            case "Fnc" -> {
                List<Type> args = ref.arguments().stream().map(a -> resolve(a, generics, selfType)).toList();
                if (args.isEmpty()) yield new Function(List.of(), Unknown.INSTANCE);
                yield new Function(args.subList(0, args.size() - 1), args.getLast());
            }
            default -> new Named(ref.name(), ref.arguments().stream().map(a -> resolve(a, generics, selfType)).toList());
        };
    }

    private boolean containsInferredGeneric(Ast.TypeRef ref) {
        if (ref.inferArguments()) return true;
        return ref.arguments().stream().anyMatch(this::containsInferredGeneric);
    }

    private void requireAssignable(Type actual, Type expected, String context) {
        if (!isAssignable(actual, expected)) throw new IllegalArgumentException(context + " has type " + actual + " but expected " + expected);
    }

    private boolean containsReturn(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt) return true;
            if (stmt instanceof Ast.IfStmt ifStmt) {
                if (ifStmt.branches().stream().anyMatch(b -> containsReturn(b.body())) || containsReturn(ifStmt.elseBody())) return true;
            }
            if (stmt instanceof Ast.TryStmt tryStmt && (containsReturn(tryStmt.body()) || containsReturn(tryStmt.catchBody()))) return true;
        }
        return false;
    }

    private boolean isCompileTimeConstant(Ast.Expr expression) {
        if (expression instanceof Ast.LiteralExpr) return true;
        if (expression instanceof Ast.UnaryExpr unary) return isCompileTimeConstant(unary.operand());
        if (expression instanceof Ast.BinaryExpr binary) return isCompileTimeConstant(binary.left()) && isCompileTimeConstant(binary.right());
        if (expression instanceof Ast.ListExpr list) return list.elements().stream().allMatch(this::isCompileTimeConstant);
        return false;
    }

    private static final class Env {
        private final Env parent;
        private final Map<String, Binding> bindings = new HashMap<>();

        private Env() { this.parent = null; }
        private Env(Env parent) { this.parent = parent; }

        private Env child() { return new Env(this); }

        private void define(String name, Type type, Ast.BindingKind kind) {
            if (bindings.putIfAbsent(name, new Binding(type, kind)) != null) throw new IllegalArgumentException("duplicate binding '" + name + "'");
        }

        private Type lookup(String name) {
            Binding binding = bindings.get(name);
            if (binding != null) return binding.type();
            return parent == null ? null : parent.lookup(name);
        }
    }

    private record Binding(Type type, Ast.BindingKind kind) { }
}
