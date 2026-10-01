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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static semantic pass run before Oreslang code is lowered/executed. */
public final class TypeChecker {
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();

    public static Ast.Program check(Ast.Program program) {
        TypeChecker checker = new TypeChecker();
        checker.collect(program);
        checker.validate(program);
        return program;
    }

    private void collect(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    requireUnique(functions, module.name() + "." + fn.name(), fn, "function");
                    functions.putIfAbsent(fn.name(), fn);
                } else if (decl instanceof Ast.ClassDecl klass) {
                    requireUnique(classes, module.name() + "." + klass.name(), klass, "class");
                    classes.putIfAbsent(klass.name(), klass);
                }
            }
        }
    }

    private static <T> void requireUnique(Map<String, T> map, String key, T value, String kind) {
        if (map.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate " + kind + " '" + key + "'");
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(module.name(), klass);
                else if (decl instanceof Ast.FieldDecl field) checkModuleBinding(field);
            }
        }
    }

    private void checkFunction(String module, Ast.FunctionDecl fn) {
        Set<String> generics = uniqueGenerics(fn.genericParameters(), "function " + fn.name());
        Env env = new Env(null);
        for (Ast.Param param : fn.parameters()) env.define(param.name(), resolve(param.type(), generics, null));
        Type returns = resolve(fn.returnType(), generics, null);
        checkBlock(fn.body(), env, generics, returns, null);
        if (returns != Primitive.VOID && !containsReturn(fn.body())) {
            throw new IllegalArgumentException("non-void function '" + module + "." + fn.name() + "' requires an explicit return");
        }
        if (fn.visibility() == Ast.Visibility.PUBLIC && hasInferredArgs(fn.returnType())) {
            throw new IllegalArgumentException("public function '" + fn.name() + "' cannot export unresolved <> type arguments");
        }
    }

    private void checkClass(String module, Ast.ClassDecl klass) {
        Set<String> classGenerics = uniqueGenerics(klass.genericParameters(), "class " + klass.name());
        Type self = classShape(klass, classGenerics);

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
            if (!method.isStatic()) env.define("self", self);
            for (Ast.Param param : method.parameters()) env.define(param.name(), resolve(param.type(), generics, self));
            Type returns = resolve(method.returnType(), generics, self);
            checkBlock(method.body(), env, generics, returns, self);
            if (!method.isAbstract() && returns != Primitive.VOID && !containsReturn(method.body())) {
                throw new IllegalArgumentException("non-void method '" + module + "." + klass.name() + "." + method.name() + "' requires an explicit return");
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
            env.define(binding.name(), declared);
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            if (!(destructure.initializer() instanceof Ast.ListExpr list)) {
                throw new IllegalArgumentException("v0 destructuring requires a statically known list/tuple expression");
            }
            if (list.elements().size() != destructure.bindings().size()) throw new IllegalArgumentException("destructure arity mismatch");
            for (int i = 0; i < destructure.bindings().size(); i++) {
                env.define(destructure.bindings().get(i).name(), typeOf(list.elements().get(i), env, generics, self));
            }
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            Type actual = ret.value() == null ? Primitive.VOID : typeOf(ret.value(), env, generics, self);
            requireAssignable(actual, expectedReturn, "return value");
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) {
            typeOf(expression.expression(), env, generics, self);
            return;
        }
        if (stmt instanceof Ast.DeferStmt defer) {
            typeOf(defer.expression(), env, generics, self);
            return;
        }
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
            caught.define(attempted.errorName(), Unknown.INSTANCE);
            checkBlock(attempted.catchBody(), caught, generics, expectedReturn, self);
            checkBlock(attempted.finallyBody(), env, generics, expectedReturn, self);
        }
    }

    private Type typeOf(Ast.Expr expr, Env env, Set<String> generics, Type self) {
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value == null) return Primitive.NULL;
            if (value instanceof Long) return Primitive.INT;
            if (value instanceof Double) return Primitive.FLOAT;
            if (value instanceof Boolean) return Primitive.BOOL;
            if (value instanceof String) return Primitive.STRING;
            if (value instanceof Ast.Imaginary) return Primitive.COMPLEX;
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.NameExpr name) {
            Type local = env.lookup(name.name());
            if (local != null) return local;
            if (name.name().equals("stdio") || name.name().equals("process")) return new Named(name.name(), List.of());
            if (name.name().equals("print")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            Ast.FunctionDecl fn = functions.get(name.name());
            if (fn != null) return functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
            throw new IllegalArgumentException("unknown name '" + name.name() + "'");
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
                case "==", "!=", "<", "<=", ">", ">=" -> Primitive.BOOL;
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
            Type receiver = typeOf(member.receiver(), env, generics, self);
            if (receiver instanceof Record record) return record.members().getOrDefault(member.member(), Unknown.INSTANCE);
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.NewExpr created) {
            Ast.ClassDecl klass = classes.get(created.type().name());
            return klass == null ? new Named(created.type().name(), List.of()) : classShape(klass, Set.copyOf(klass.genericParameters()));
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            Type awaitedType = typeOf(awaited.expression(), env, generics, self);
            if (awaitedType instanceof Named named && named.name().equals("Future") && named.arguments().size() == 1) return named.arguments().getFirst();
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.ListExpr list) {
            if (list.elements().isEmpty()) return new ListType(Unknown.INSTANCE);
            Type element = typeOf(list.elements().getFirst(), env, generics, self);
            for (int i = 1; i < list.elements().size(); i++) {
                Type next = typeOf(list.elements().get(i), env, generics, self);
                if (!Types.isAssignable(next, element) || !Types.isAssignable(element, next)) element = Unknown.INSTANCE;
            }
            return new ListType(element);
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            Env lambdaEnv = new Env(env);
            List<Type> parameters = new ArrayList<>();
            for (Ast.Param param : lambda.parameters()) {
                Type type = resolve(param.type(), generics, self);
                parameters.add(type);
                lambdaEnv.define(param.name(), type);
            }
            Type result = lambda.expressionBody() == null ? Unknown.INSTANCE : typeOf(lambda.expressionBody(), lambdaEnv, generics, self);
            return new Function(parameters, result);
        }
        return Unknown.INSTANCE;
    }

    private Type numericJoin(Type left, Type right, String op) {
        Type result = Types.numericJoin(left, right);
        if (result == Unknown.INSTANCE) throw new IllegalArgumentException("operator '" + op + "' needs numeric operands");
        return result;
    }

    private Type classShape(Ast.ClassDecl klass, Set<String> generics) {
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.FieldDecl field : klass.fields()) members.put(field.name(), resolve(field.type(), generics, Unknown.INSTANCE));
        for (Ast.MethodDecl method : klass.methods()) members.put(method.name(), functionType(method.parameters(), method.returnType(), generics, Unknown.INSTANCE));
        return new Record(members);
    }

    private Function functionType(List<Ast.Param> params, Ast.TypeRef returns, Set<String> generics, Type self) {
        return new Function(params.stream().map(p -> resolve(p.type(), generics, self)).toList(), resolve(returns, generics, self));
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self) {
        if (ref == null) return Unknown.INSTANCE;
        if (ref.name().equals("self")) return self == null ? Unknown.INSTANCE : self;
        if (generics.contains(ref.name())) return new Generic(ref.name());
        return switch (ref.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "int", "uint", "bigint" -> Primitive.INT;
            case "f32", "f64", "float" -> Primitive.FLOAT;
            case "decimal" -> Primitive.DECIMAL;
            case "complex64", "complex128", "complex" -> Primitive.COMPLEX;
            case "bool", "Bool" -> Primitive.BOOL;
            case "string", "String" -> Primitive.STRING;
            case "void" -> Primitive.VOID;
            case "Array", "List" -> new ListType(ref.arguments().isEmpty() ? Unknown.INSTANCE : resolve(ref.arguments().getFirst(), generics, self));
            case "Fnc" -> {
                List<Type> args = ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList();
                if (args.isEmpty()) yield new Function(List.of(), Unknown.INSTANCE);
                yield new Function(args.subList(0, args.size() - 1), args.getLast());
            }
            default -> new Named(ref.name(), ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
        };
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
        return false;
    }

    private boolean containsReturn(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt) return true;
            if (stmt instanceof Ast.IfStmt conditional) {
                if (conditional.branches().stream().anyMatch(branch -> containsReturn(branch.body())) || containsReturn(conditional.elseBody())) return true;
            }
            if (stmt instanceof Ast.TryStmt attempted && (containsReturn(attempted.body()) || containsReturn(attempted.catchBody()))) return true;
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
        private final Map<String, Type> bindings = new HashMap<>();
        private Env(Env parent) { this.parent = parent; }
        private void define(String name, Type type) {
            if (bindings.putIfAbsent(name, type) != null) throw new IllegalArgumentException("duplicate binding '" + name + "'");
        }
        private Type lookup(String name) {
            Type type = bindings.get(name);
            return type != null ? type : parent == null ? null : parent.lookup(name);
        }
    }
}
