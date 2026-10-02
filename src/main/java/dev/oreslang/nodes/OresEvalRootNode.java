package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Executable Truffle root. Parsing and static checks happen before this node is created. */
public final class OresEvalRootNode extends RootNode {
    private final Ast.Program program;

    public OresEvalRootNode(OresLanguage language, Ast.Program program) {
        super(language);
        this.program = program;
    }

    @Override public String getName() { return "ores-eval"; }
    @Override public boolean isInternal() { return true; }

    @Override
    public Object execute(VirtualFrame frame) {
        return executeBoundary(OresContext.get(this), frame.getArguments());
    }

    @TruffleBoundary
    private Object executeBoundary(OresContext context, Object[] arguments) {
        CapabilityChecker.check(program, context.isolatePolicy());
        return new Evaluator(program, context).execute(arguments);
    }

    private static final class Evaluator {
        private final Ast.Program program;
        private final OresContext context;
        private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
        private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
        private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
        private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
        private final Set<String> ambiguousFunctions = new LinkedHashSet<>();
        private final Set<String> ambiguousClasses = new LinkedHashSet<>();
        private final Set<String> ambiguousTypeAliases = new LinkedHashSet<>();

        private Evaluator(Ast.Program program, OresContext context) {
            this.program = program;
            this.context = context;
            indexDeclarations();
        }

        private void indexDeclarations() {
            for (Ast.ModuleDecl module : program.modules()) {
                modules.put(module.name(), module);
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                    else if (decl instanceof Ast.ClassDecl klass) index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                    else if (decl instanceof Ast.TypeAliasDecl alias) index(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias);
                }
            }
        }

        private static <T> void index(Map<String, T> map, Set<String> ambiguous, String module, String name, T value) {
            map.put(module + "." + name, value);
            T previous = map.putIfAbsent(name, value);
            if (previous != null && previous != value) {
                ambiguous.add(name);
                map.remove(name);
            }
        }

        private Ast.FunctionDecl findFunction(String name) {
            if (ambiguousFunctions.contains(name)) throw new IllegalArgumentException("ambiguous function " + name + "; qualify it with its module");
            return functions.get(name);
        }

        private Ast.ClassDecl findClass(String name) {
            if (ambiguousClasses.contains(name)) throw new IllegalArgumentException("ambiguous class " + name + "; qualify it with its module");
            return classes.get(name);
        }

        private Ast.TypeAliasDecl findTypeAlias(String name) {
            if (ambiguousTypeAliases.contains(name)) throw new IllegalArgumentException("ambiguous type alias " + name + "; qualify it with its module");
            return typeAliases.get(name);
        }

        private Object execute(Object[] arguments) {
            Ast.FunctionDecl main = findFunction("main");
            if (main == null) return null;
            return callFunction(main, List.of(arguments));
        }

        private Object callFunction(Ast.FunctionDecl fn, List<?> args) {
            if (args.size() != fn.parameters().size()) {
                if (fn.parameters().isEmpty() && args.size() == 1 && args.getFirst() instanceof Object[] array && array.length == 0) args = List.of();
                else throw new IllegalArgumentException("function " + fn.name() + " expects " + fn.parameters().size() + " arguments, got " + args.size());
            }
            Env env = new Env(null);
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return enforceReturnedShape(fn.returnType(), signal.value, "function " + fn.name());
            }
        }

        private Object callMethod(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            if (args.size() != method.parameters().size()) throw new IllegalArgumentException("method " + method.name() + " arity mismatch");
            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", receiver, Ast.BindingKind.VAL);
            for (int i = 0; i < method.parameters().size(); i++) {
                Ast.Param param = method.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(method.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return enforceReturnedShape(method.returnType(), signal.value, "method " + method.name());
            }
        }

        private void executeBlock(List<Ast.Stmt> statements, Env parent) {
            Env env = new Env(parent);
            ArrayDeque<Ast.Expr> deferred = new ArrayDeque<>();
            try {
                for (Ast.Stmt stmt : statements) executeStatement(stmt, env, deferred);
            } finally {
                while (!deferred.isEmpty()) eval(deferred.pop(), env);
            }
        }

        private void executeStatement(Ast.Stmt stmt, Env env, ArrayDeque<Ast.Expr> deferred) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (binding.initializer() instanceof Ast.LambdaExpr) {
                    env.reserve(binding.name(), binding.kind());
                    env.initialize(binding.name(), eval(binding.initializer(), env));
                } else {
                    env.define(binding.name(), eval(binding.initializer(), env), binding.kind());
                }
                return;
            }
            if (stmt instanceof Ast.DestructureStmt destructure) {
                Object value = eval(destructure.initializer(), env);
                if (destructure.kind() == Ast.DestructureKind.ARRAY) {
                    List<?> items = asSequence(value);
                    if (items.size() != destructure.bindings().size()) throw new IllegalArgumentException("destructure arity mismatch");
                    for (int i = 0; i < items.size(); i++) {
                        Ast.DestructureBinding binding = destructure.bindings().get(i);
                        if (!binding.discard()) env.define(binding.name(), items.get(i), binding.kind());
                    }
                } else {
                    for (Ast.DestructureBinding binding : destructure.bindings()) {
                        if (binding.discard()) continue;
                        env.define(binding.name(), objectField(value, binding.name()), binding.kind());
                    }
                }
                return;
            }
            if (stmt instanceof Ast.ReturnStmt ret) throw new ReturnSignal(ret.value() == null ? null : eval(ret.value(), env));
            if (stmt instanceof Ast.ExprStmt expression) { eval(expression.expression(), env); return; }
            if (stmt instanceof Ast.DeferStmt defer) { deferred.push(defer.expression()); return; }
            if (stmt instanceof Ast.IfStmt ifStmt) {
                for (Ast.IfBranch branch : ifStmt.branches()) {
                    if (truth(eval(branch.condition(), env))) { executeBlock(branch.body(), env); return; }
                }
                executeBlock(ifStmt.elseBody(), env);
                return;
            }
            if (stmt instanceof Ast.TryStmt tried) {
                try { executeBlock(tried.body(), env); }
                catch (ReturnSignal signal) { throw signal; }
                catch (RuntimeException failure) {
                    Env catchEnv = new Env(env);
                    catchEnv.define(tried.errorName(), failure, Ast.BindingKind.VAL);
                    executeBlock(tried.catchBody(), catchEnv);
                } finally { executeBlock(tried.finallyBody(), env); }
                return;
            }
            if (stmt instanceof Ast.ForOfStmt loop) {
                Object iterable = eval(loop.iterable(), env);
                for (Object item : iterableValues(iterable)) {
                    context.schedulerSafepoint();
                    Env iteration = new Env(env);
                    iteration.define(loop.bindingName(), item, loop.bindingKind());
                    executeBlock(loop.body(), iteration);
                }
                return;
            }
            if (stmt instanceof Ast.ForStmt loop) {
                Env loopEnv = new Env(env);
                if (loop.initializer() != null) executeStatement(loop.initializer(), loopEnv, new ArrayDeque<>());
                while (loop.condition() == null || truth(eval(loop.condition(), loopEnv))) {
                    context.schedulerSafepoint();
                    executeBlock(loop.body(), loopEnv);
                    if (loop.update() != null) eval(loop.update(), loopEnv);
                }
            }
        }

        private Object eval(Ast.Expr expr, Env env) {
            if (expr instanceof Ast.LiteralExpr literal) {
                if (literal.value() == null) throw new IllegalArgumentException("standalone null values are forbidden");
                if (literal.value() instanceof Ast.Imaginary imaginary) return new Complex(0.0, imaginary.coefficient());
                return literal.value();
            }
            if (expr instanceof Ast.NameExpr name) {
                Object local = env.lookup(name.name());
                if (local != Env.MISSING) return local;
                if (name.name().equals("stdio")) return new StdioFacade(context);
                if (name.name().equals("process")) return new ProcessFacade(context);
                if (name.name().equals("print")) return (Invokable) args -> {
                    context.requireCapability(IsolatePolicy.Capability.STDOUT, "print");
                    requireOne(args, "print"); context.output().print(display(args.getFirst())); context.output().flush(); return null;
                };
                if (name.name().equals("Some")) return (Invokable) args -> {
                    requireOne(args, "Some");
                    return new OptionValue(true, args.getFirst());
                };
                if (name.name().equals("None")) return new OptionValue(false, null);
                Ast.ModuleDecl module = modules.get(name.name());
                if (module != null) return new ModuleFacade(module);
                Ast.ClassDecl klass = findClass(name.name());
                if (klass != null) return new ClassFacade(klass);
                Ast.FunctionDecl fn = findFunction(name.name());
                if (fn != null) return (Invokable) args -> callFunction(fn, args);
                throw new IllegalArgumentException("unknown name " + name.name());
            }
            if (expr instanceof Ast.AssignExpr assignment) {
                Object value = eval(assignment.value(), env);
                if (assignment.target() instanceof Ast.NameExpr target) {
                    env.assign(target.name(), value);
                    return value;
                }
                if (assignment.target() instanceof Ast.MemberExpr target) {
                    Object receiver = eval(target.receiver(), env);
                    if (receiver instanceof OresObject object) {
                        if (!object.fields.containsKey(target.member())) throw new IllegalArgumentException("unknown field " + target.member());
                        object.fields.put(target.member(), value);
                        return value;
                    }
                    throw new IllegalArgumentException("member assignment requires a class instance");
                }
                if (assignment.target() instanceof Ast.IndexExpr target) {
                    Object receiver = eval(target.receiver(), env);
                    Object index = eval(target.index(), env);
                    if (!(index instanceof Number number)) throw new IllegalArgumentException("index must be an integer");
                    int i = Math.toIntExact(number.longValue());
                    if (receiver instanceof List<?> raw) {
                        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) raw;
                        list.set(i, value);
                        return value;
                    }
                    throw new IllegalArgumentException("indexed assignment requires a mutable array/list");
                }
                throw new IllegalArgumentException("unsupported assignment target");
            }
            if (expr instanceof Ast.ConditionalExpr conditional) {
                return truth(eval(conditional.condition(), env))
                        ? eval(conditional.whenTrue(), env)
                        : eval(conditional.whenFalse(), env);
            }
            if (expr instanceof Ast.UnaryExpr unary) {
                Object value = eval(unary.operand(), env);
                return switch (unary.operator()) {
                    case "&", "&mut" -> value;
                    case "!" -> !truth(value); case "+" -> value; case "-" -> negate(value);
                    default -> throw new IllegalArgumentException("unsupported unary operator " + unary.operator());
                };
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                if (binary.operator().equals(",")) return truth(eval(binary.left(), env)) && truth(eval(binary.right(), env));
                if (binary.operator().equals("|")) return truth(eval(binary.left(), env)) || truth(eval(binary.right(), env));
                return binary(binary.operator(), eval(binary.left(), env), eval(binary.right(), env));
            }
            if (expr instanceof Ast.CallExpr call) {
                if (call.callee() instanceof Ast.MemberExpr builtin
                        && builtin.receiver() instanceof Ast.NameExpr namespace
                        && namespace.name().equals("List")
                        && builtin.member().equals("of")) {
                    Object[] values = new Object[call.arguments().size()];
                    for (int i = 0; i < call.arguments().size(); i++) values[i] = eval(call.arguments().get(i), env);
                    return Arrays.asList(values);
                }
                if (call.callee() instanceof Ast.MemberExpr builtin
                        && builtin.receiver() instanceof Ast.NameExpr namespace
                        && namespace.name().equals("Map")
                        && builtin.member().equals("of")) {
                    if ((call.arguments().size() & 1) != 0) {
                        throw new IllegalArgumentException("Map.of requires alternating string keys and values");
                    }
                    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                    for (int i = 0; i < call.arguments().size(); i += 2) {
                        Object keyValue = eval(call.arguments().get(i), env);
                        if (!(keyValue instanceof String key)) {
                            throw new IllegalArgumentException("Map.of record keys must be strings");
                        }
                        Object value = eval(call.arguments().get(i + 1), env);
                        if (result.putIfAbsent(key, value) != null) {
                            throw new IllegalArgumentException("duplicate Map.of record key '" + key + "'");
                        }
                    }
                    return result;
                }
                if (call.callee() instanceof Ast.MemberExpr methodCall) {
                    Object receiver = eval(methodCall.receiver(), env);
                    List<Object> args = call.arguments().stream().map(arg -> eval(arg, env)).toList();
                    if (receiver instanceof OresObject object) {
                        return invokeMethod(object, methodCall.member(), args);
                    }
                    if (receiver instanceof ClassFacade klass) {
                        return invokeStaticFunction(klass.klass(), methodCall.member(), args);
                    }
                    Object callee = member(receiver, methodCall.member());
                    if (!(callee instanceof Invokable invokable)) throw new IllegalArgumentException("value is not callable: " + callee);
                    return invokable.call(args);
                }
                Object callee = eval(call.callee(), env);
                List<Object> args = call.arguments().stream().map(arg -> eval(arg, env)).toList();
                if (!(callee instanceof Invokable invokable)) throw new IllegalArgumentException("value is not callable: " + callee);
                return invokable.call(args);
            }
            if (expr instanceof Ast.MemberExpr member) return member(eval(member.receiver(), env), member.member());
            if (expr instanceof Ast.IndexExpr indexed) {
                Object receiver = eval(indexed.receiver(), env);
                Object index = eval(indexed.index(), env);
                if (!(index instanceof Number number)) throw new IllegalArgumentException("index must be an integer");
                int i = Math.toIntExact(number.longValue());
                if (receiver instanceof List<?> list) return list.get(i);
                if (receiver instanceof Object[] array) return array[i];
                throw new IllegalArgumentException("value is not indexable: " + receiver);
            }
            if (expr instanceof Ast.NewExpr created) {
                Ast.ClassDecl klass = findClass(created.type().name());
                if (klass == null) throw new IllegalArgumentException("unknown class " + created.type().name());
                List<Object> args = created.arguments().stream().map(arg -> eval(arg, env)).toList();
                List<Ast.FieldDecl> classFields = effectiveFields(klass, new LinkedHashSet<>());
                if (args.size() > classFields.size()) throw new IllegalArgumentException("too many constructor arguments for " + klass.name());
                LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                for (int i = 0; i < classFields.size(); i++) {
                    Ast.FieldDecl field = classFields.get(i);
                    Object value;
                    if (i < args.size()) value = args.get(i);
                    else if (field.initializer() != null) value = eval(field.initializer(), env);
                    else throw new IllegalArgumentException("missing constructor field " + klass.name() + "." + field.name());
                    fields.put(field.name(), value);
                }
                return new OresObject(klass, fields);
            }
            if (expr instanceof Ast.AwaitExpr awaited) {
                Object value = eval(awaited.expression(), env);
                if (value instanceof CompletionStage<?> stage) return stage.toCompletableFuture().join();
                return value;
            }
            if (expr instanceof Ast.ListExpr list) {
                ArrayList<Object> result = new ArrayList<>(list.elements().size());
                for (Ast.Expr item : list.elements()) result.add(eval(item, env));
                return result;
            }
            if (expr instanceof Ast.TupleExpr tuple) {
                Object[] values = new Object[tuple.elements().size()];
                for (int i = 0; i < tuple.elements().size(); i++) values[i] = eval(tuple.elements().get(i), env);
                return Arrays.asList(values);
            }
            if (expr instanceof Ast.ObjectExpr object) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                for (Ast.ObjectField field : object.fields()) {
                    if (result.putIfAbsent(field.name(), eval(field.value(), env)) != null) throw new IllegalArgumentException("duplicate obj field " + field.name());
                }
                return Map.copyOf(result);
            }
            if (expr instanceof Ast.LambdaExpr lambda) {
                Env captured = env.snapshot();
                return (Invokable) args -> {
                    if (args.size() != lambda.parameters().size()) throw new IllegalArgumentException("lambda arity mismatch");
                    Env local = new Env(captured);
                    for (int i = 0; i < lambda.parameters().size(); i++) {
                        Ast.Param param = lambda.parameters().get(i);
                        local.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
                    }
                    if (lambda.expressionBody() != null) return eval(lambda.expressionBody(), local);
                    try { executeBlock(lambda.blockBody(), local); return null; }
                    catch (ReturnSignal signal) { return signal.value; }
                };
            }
            throw new IllegalArgumentException("unsupported expression " + expr);
        }

        private Object member(Object receiver, String name) {
            if (receiver instanceof StdioFacade stdio) {
                return switch (name) {
                    case "print" -> (Invokable) stdio::print;
                    case "println" -> (Invokable) stdio::println;
                    case "stdout" -> new StdoutFacade(stdio.context());
                    default -> throw new IllegalArgumentException("unknown stdio member " + name);
                };
            }
            if (receiver instanceof StdoutFacade stdout) {
                return switch (name) {
                    case "write" -> (Invokable) stdout::write;
                    case "println" -> (Invokable) stdout::println;
                    default -> throw new IllegalArgumentException("unknown stdout member " + name);
                };
            }
            if (receiver instanceof ProcessFacade process) {
                return switch (name) {
                    case "context_id" -> process.contextId();
                    case "descriptor" -> process.descriptor();
                    case "share_readonly" -> (Invokable) process::shareReadonly;
                    default -> throw new IllegalArgumentException("unknown process member " + name);
                };
            }
            if (receiver instanceof ModuleFacade namespace) return moduleMember(namespace.module, name);
            if (receiver instanceof ClassFacade klass) {
                List<Ast.MethodDecl> functions = findStaticFunctionsByName(klass.klass(), name, new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    return (Invokable) args -> callStaticFunction(klass.klass(), fn, args);
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function " + klass.klass().name() + "." + name + " must be called so arity can select it");
                throw new IllegalArgumentException("unknown static member " + klass.klass().name() + "." + name);
            }
            if (receiver instanceof OresObject object) {
                if (object.fields.containsKey(name)) return object.fields.get(name);
                return new BoundMethod(object, name);
            }
            if (receiver instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) throw new IllegalArgumentException("unknown obj member " + name);
                return map.get(name);
            }
            throw new IllegalArgumentException("cannot access member '" + name + "' on " + receiver);
        }

        private Object invokeMethod(OresObject receiver, String name, List<Object> args) {
            Ast.MethodDecl method = findMethod(receiver.klass, name, args.size(), new LinkedHashSet<>());
            if (method == null) throw new IllegalArgumentException("no method " + receiver.klass.name() + "." + name + " with arity " + args.size());
            return callMethod(receiver, method, args);
        }

        private Object invokeStaticFunction(Ast.ClassDecl klass, String name, List<Object> args) {
            Ast.MethodDecl fn = findStaticFunction(klass, name, args.size(), new LinkedHashSet<>());
            if (fn == null) throw new IllegalArgumentException("no static function " + klass.name() + "." + name + " with arity " + args.size());
            return callStaticFunction(klass, fn, args);
        }

        private Object callStaticFunction(Ast.ClassDecl klass, Ast.MethodDecl fn, List<?> args) {
            if (!fn.isStatic()) throw new IllegalArgumentException("not a static class function: " + klass.name() + "." + fn.name());
            if (args.size() != fn.parameters().size()) throw new IllegalArgumentException("static function " + fn.name() + " arity mismatch");
            Env env = new Env(null);
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return enforceReturnedShape(fn.returnType(), signal.value, "static function " + klass.name() + "." + fn.name());
            }
        }

        /**
         * Go-style method value: one shared method definition per class plus a
         * tiny (receiver, method-name) pair only when a method is extracted as
         * a first-class callback. Direct receiver.method(...) calls allocate no
         * bound-method object.
         */
        private final class BoundMethod implements Invokable {
            private final OresObject receiver;
            private final String methodName;

            private BoundMethod(OresObject receiver, String methodName) {
                this.receiver = receiver;
                this.methodName = methodName;
            }

            @Override public Object call(List<Object> arguments) {
                return invokeMethod(receiver, methodName, arguments);
            }
        }

        private Object moduleMember(Ast.ModuleDecl module, String name) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass && klass.name().equals(name)) {
                    return new ClassFacade(klass);
                }
                if (decl instanceof Ast.FunctionDecl fn && fn.name().equals(name) && fn.visibility() == Ast.Visibility.PUBLIC) {
                    return (Invokable) args -> callFunction(fn, args);
                }
                if (decl instanceof Ast.FieldDecl field && field.name().equals(name) && field.visibility() == Ast.Visibility.PUBLIC) {
                    if (field.initializer() == null) throw new IllegalArgumentException("module field has no initializer: " + module.name() + "." + name);
                    return eval(field.initializer(), new Env(null));
                }
            }
            throw new IllegalArgumentException("module '" + module.name() + "' does not export '" + name + "'");
        }

        private List<Ast.FieldDecl> effectiveFields(Ast.ClassDecl klass, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            LinkedHashMap<String, Ast.FieldDecl> result = new LinkedHashMap<>();
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) throw new IllegalArgumentException("unknown parent class " + parentRef.name());
                for (Ast.FieldDecl field : effectiveFields(parent, seen)) result.putIfAbsent(field.name(), field);
            }
            for (Ast.FieldDecl field : klass.fields()) result.put(field.name(), field);
            seen.remove(klass);
            return List.copyOf(result.values());
        }

        private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            for (Ast.MethodDecl method : klass.methods()) {
                if (!method.isStatic() && method.name().equals(name) && method.parameters().size() == arity) {
                    seen.remove(klass);
                    return method;
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
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

        private Ast.MethodDecl findStaticFunction(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            for (Ast.MethodDecl fn : klass.methods()) {
                if (fn.isStatic() && fn.name().equals(name) && fn.parameters().size() == arity) {
                    seen.remove(klass);
                    return fn;
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
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
            LinkedHashMap<Integer, Ast.MethodDecl> result = new LinkedHashMap<>();
            for (Ast.MethodDecl fn : klass.methods()) {
                if (fn.isStatic() && fn.name().equals(name)) result.put(fn.parameters().size(), fn);
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) continue;
                for (Ast.MethodDecl fn : findStaticFunctionsByName(parent, name, seen)) result.putIfAbsent(fn.parameters().size(), fn);
            }
            seen.remove(klass);
            return List.copyOf(result.values());
        }

        private List<?> iterableValues(Object value) {
            if (value instanceof List<?> list) return list;
            if (value instanceof Object[] array) return Arrays.asList(array);
            if (value instanceof OresObject object) {
                Ast.MethodDecl iterator = findMethod(object.klass, "Symbol.iterator", 0, new LinkedHashSet<>());
                if (iterator == null) throw new IllegalArgumentException("value has no [Symbol.iterator]()");
                Object produced = callMethod(object, iterator, List.of());
                return iterableValues(produced);
            }
            throw new IllegalArgumentException("value is not iterable");
        }

        private Object binary(String op, Object left, Object right) {
            return switch (op) {
                case "+" -> add(left, right); case "-" -> numeric(left, right, '-'); case "*" -> numeric(left, right, '*');
                case "/" -> numeric(left, right, '/'); case "%" -> numeric(left, right, '%');
                case "==" -> Objects.equals(left, right); case "!=" -> !Objects.equals(left, right);
                case "<" -> compare(left, right) < 0; case "<=" -> compare(left, right) <= 0;
                case ">" -> compare(left, right) > 0; case ">=" -> compare(left, right) >= 0;
                default -> throw new IllegalArgumentException("unsupported operator " + op);
            };
        }

        private Object add(Object left, Object right) {
            if (left instanceof String && right instanceof String) return ((String) left) + right;
            return numeric(left, right, '+');
        }

        private Object numeric(Object left, Object right, char op) {
            if (left instanceof Complex || right instanceof Complex) {
                Complex a = asComplex(left), b = asComplex(right);
                return switch (op) {
                    case '+' -> a.add(b); case '-' -> a.sub(b); case '*' -> a.mul(b); case '/' -> a.div(b);
                    default -> throw new IllegalArgumentException("operator " + op + " is not supported for complex numbers");
                };
            }
            if (!(left instanceof Number a) || !(right instanceof Number b)) throw new IllegalArgumentException("numeric operator requires numbers");
            boolean integral = isIntegral(a) && isIntegral(b) && op != '/';
            if (integral) {
                long x = a.longValue(), y = b.longValue();
                return switch (op) { case '+' -> x + y; case '-' -> x - y; case '*' -> x * y; case '%' -> x % y; default -> throw new IllegalArgumentException("bad numeric operator"); };
            }
            double x = a.doubleValue(), y = b.doubleValue();
            return switch (op) { case '+' -> x + y; case '-' -> x - y; case '*' -> x * y; case '/' -> x / y; case '%' -> x % y; default -> throw new IllegalArgumentException("bad numeric operator"); };
        }

        private Object negate(Object value) {
            if (value instanceof Complex c) return new Complex(-c.real, -c.imaginary);
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return -((Number) value).longValue();
            if (value instanceof Number number) return -number.doubleValue();
            throw new IllegalArgumentException("unary - requires a number");
        }

        private int compare(Object left, Object right) {
            if (left instanceof Number a && right instanceof Number b) return Double.compare(a.doubleValue(), b.doubleValue());
            if (left instanceof String a && right instanceof String b) return a.compareTo(b);
            throw new IllegalArgumentException("values are not comparable");
        }

        private boolean truth(Object value) { if (value instanceof Boolean b) return b; throw new IllegalArgumentException("condition must be bool"); }
        private boolean isIntegral(Number value) { return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long; }
        private Complex asComplex(Object value) { if (value instanceof Complex c) return c; if (value instanceof Number n) return new Complex(n.doubleValue(),0); throw new IllegalArgumentException("value is not numeric"); }
        private Object enforceReturnedShape(Ast.TypeRef returnType, Object value, String callable) {
            if (returnType == null) return value;
            Ast.TypeRef expanded = expandRuntimeAlias(returnType, new LinkedHashSet<>());
            if (!runtimeMatches(expanded, value)) {
                throw new IllegalArgumentException(callable + " returned a value incompatible with declared type " + returnType);
            }
            if (expanded.isTuple()) return asSequence(value);
            if (expanded.isRecord() && value instanceof Map<?, ?> map) return Map.copyOf(map);
            return value;
        }

        private boolean runtimeMatches(Ast.TypeRef type, Object value) {
            if (type == null || type.name().equals("$infer$")) return true;
            type = expandRuntimeAlias(type, new LinkedHashSet<>());

            if (type.isBorrow()) return runtimeMatches(type.borrowedTarget(), value);
            if (type.isStringLiteral()) return value instanceof String string && string.equals(type.stringLiteralValue());
            if (type.isUnion()) {
                for (Ast.TypeRef member : type.arguments()) if (runtimeMatches(member, value)) return true;
                return false;
            }
            if (type.isTuple()) {
                List<?> items = sequenceValue(value);
                if (items == null || items.size() != type.arguments().size()) return false;
                for (int i = 0; i < items.size(); i++) {
                    if (!runtimeMatches(type.arguments().get(i), items.get(i))) return false;
                }
                return true;
            }
            if (type.isRecord()) {
                if (!(value instanceof Map<?, ?>) && !(value instanceof OresObject)) return false;
                for (Map.Entry<String, Ast.TypeRef> member : type.recordMembers().entrySet()) {
                    Object fieldValue;
                    if (value instanceof Map<?, ?> map) {
                        if (!map.containsKey(member.getKey())) return false;
                        fieldValue = map.get(member.getKey());
                    } else {
                        OresObject object = (OresObject) value;
                        if (!object.fields.containsKey(member.getKey())) return false;
                        fieldValue = object.fields.get(member.getKey());
                    }
                    if (!runtimeMatches(member.getValue(), fieldValue)) return false;
                }
                return true;
            }

            return switch (type.name()) {
                case "void" -> value == null;
                case "null" -> value == null;
                case "bool", "Bool" -> value instanceof Boolean;
                case "string", "String" -> value instanceof String;
                case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "int", "uint", "bigint" ->
                        value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
                case "f32", "f64", "float", "decimal" -> value instanceof Number;
                case "complex64", "complex128", "complex" -> value instanceof Number || value instanceof Complex;
                case "Array", "List" -> {
                    List<?> items = sequenceValue(value);
                    if (items == null) yield false;
                    if (type.arguments().isEmpty()) yield true;
                    Ast.TypeRef elementType = type.arguments().getFirst();
                    boolean matches = true;
                    for (Object item : items) matches &= runtimeMatches(elementType, item);
                    yield matches;
                }
                case "Option" -> {
                    if (!(value instanceof OptionValue option)) yield false;
                    if (!option.present()) yield true;
                    yield type.arguments().isEmpty() || runtimeMatches(type.arguments().getFirst(), option.value());
                }
                case "Fnc" -> value instanceof Invokable;
                default -> true; // Nominal/custom values are already statically checked.
            };
        }

        private Ast.TypeRef expandRuntimeAlias(Ast.TypeRef type, Set<Ast.TypeAliasDecl> stack) {
            if (type == null) return Ast.TypeRef.inferred();
            Ast.TypeAliasDecl alias = findTypeAlias(type.name());
            if (alias != null && alias.genericParameters().size() == type.arguments().size()) {
                if (!stack.add(alias)) return type;
                Map<String, Ast.TypeRef> substitutions = new HashMap<>();
                for (int i = 0; i < alias.genericParameters().size(); i++) {
                    substitutions.put(alias.genericParameters().get(i), type.arguments().get(i));
                }
                Ast.TypeRef expanded = expandRuntimeAlias(substituteRuntimeAlias(alias.target(), substitutions), stack);
                stack.remove(alias);
                return expanded;
            }
            if (type.arguments().isEmpty()) return type;
            return new Ast.TypeRef(
                    type.name(),
                    type.arguments().stream().map(arg -> expandRuntimeAlias(arg, stack)).toList(),
                    type.inferArguments());
        }

        private Ast.TypeRef substituteRuntimeAlias(Ast.TypeRef type, Map<String, Ast.TypeRef> substitutions) {
            Ast.TypeRef replacement = substitutions.get(type.name());
            if (replacement != null && type.arguments().isEmpty() && !type.inferArguments()) return replacement;
            return new Ast.TypeRef(
                    type.name(),
                    type.arguments().stream().map(arg -> substituteRuntimeAlias(arg, substitutions)).toList(),
                    type.inferArguments());
        }

        private List<?> sequenceValue(Object value) {
            if (value instanceof List<?> list) return list;
            if (value instanceof Object[] array) return Arrays.asList(array);
            return null;
        }

        private List<?> asSequence(Object value) {
            List<?> sequence = sequenceValue(value);
            if (sequence != null) return sequence;
            throw new IllegalArgumentException("array destructuring requires a list/array value");
        }

        private Object objectField(Object value, String field) {
            if (value instanceof Map<?, ?> map) {
                if (!map.containsKey(field)) throw new IllegalArgumentException("object destructure is missing field '" + field + "'");
                return map.get(field);
            }
            if (value instanceof OresObject object) {
                if (!object.fields.containsKey(field)) throw new IllegalArgumentException("object destructure is missing field '" + field + "'");
                return object.fields.get(field);
            }
            throw new IllegalArgumentException("object destructuring requires an object/map value");
        }

        private String display(Object value) { return value instanceof Complex c ? c.toString() : String.valueOf(value); }
    }

    @FunctionalInterface private interface Invokable { Object call(List<Object> arguments); }

    private static final class Env {
        private static final Object MISSING = new Object();
        private final Env parent;
        private final Map<String, Slot> slots = new HashMap<>();
        private Env(Env parent) { this.parent = parent; }
        private void define(String name, Object value, Ast.BindingKind kind) {
            if (slots.putIfAbsent(name, new Slot(value, kind)) != null) throw new IllegalArgumentException("duplicate binding " + name);
        }
        private void reserve(String name, Ast.BindingKind kind) {
            if (slots.putIfAbsent(name, new Slot(MISSING, kind)) != null) throw new IllegalArgumentException("duplicate binding " + name);
        }
        private void initialize(String name, Object value) {
            Slot slot = slots.get(name);
            if (slot == null) throw new IllegalArgumentException("unknown binding " + name);
            slot.value = value;
        }
        private Object lookup(String name) { Slot s=slots.get(name); return s!=null?s.value:parent==null?MISSING:parent.lookup(name); }
        private void assign(String name, Object value) {
            Slot slot = slots.get(name);
            if (slot != null) {
                if (slot.kind != Ast.BindingKind.LET) throw new IllegalArgumentException("cannot reassign " + slot.kind.name().toLowerCase() + " binding " + name);
                slot.value = value;
                return;
            }
            if (parent != null) { parent.assign(name, value); return; }
            throw new IllegalArgumentException("unknown binding " + name);
        }
        private Env snapshot() { Env cp=new Env(parent==null?null:parent.snapshot()); cp.slots.putAll(slots); return cp; }
    }

    private static final class Slot {
        private Object value;
        private final Ast.BindingKind kind;
        private Slot(Object value, Ast.BindingKind kind) { this.value=value; this.kind=kind; }
    }

    private static final class ReturnSignal extends RuntimeException {
        private final Object value;
        private ReturnSignal(Object value) { super(null,null,false,false); this.value=value; }
    }

    private record Complex(double real, double imaginary) {
        private Complex add(Complex o){return new Complex(real+o.real,imaginary+o.imaginary);}
        private Complex sub(Complex o){return new Complex(real-o.real,imaginary-o.imaginary);}
        private Complex mul(Complex o){return new Complex(real*o.real-imaginary*o.imaginary,real*o.imaginary+imaginary*o.real);}
        private Complex div(Complex o){double d=o.real*o.real+o.imaginary*o.imaginary;return new Complex((real*o.real+imaginary*o.imaginary)/d,(imaginary*o.real-real*o.imaginary)/d);}
        @Override public String toString(){return real+(imaginary<0?"":"+")+imaginary+"i";}
    }

    private static final class OresObject {
        private final Ast.ClassDecl klass; private final Map<String,Object> fields;
        private OresObject(Ast.ClassDecl klass, Map<String,Object> fields){this.klass=klass;this.fields=fields;}
        @Override public String toString(){return klass.name()+fields;}
    }

    private record ModuleFacade(Ast.ModuleDecl module) { }
    private record ClassFacade(Ast.ClassDecl klass) { }
    private record OptionValue(boolean present, Object value) {
        @Override public String toString(){return present ? "Some(" + value + ")" : "None";}
    }
    private record StdioFacade(OresContext context) {
        private Object print(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.print");requireOne(args,"stdio.print");context.output().print(String.valueOf(args.getFirst()));context.output().flush();return null;}
        private Object println(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.println");requireOne(args,"stdio.println");context.output().println(String.valueOf(args.getFirst()));return null;}
    }
    private record StdoutFacade(OresContext context) {
        private Object write(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.stdout.write");requireOne(args,"stdio.stdout.write");context.output().print(String.valueOf(args.getFirst()));context.output().flush();return null;}
        private Object println(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.stdout.println");requireOne(args,"stdio.stdout.println");context.output().println(String.valueOf(args.getFirst()));return null;}
    }
    private record ProcessFacade(OresContext context) {
        private String contextId(){context.requireCapability(IsolatePolicy.Capability.PROCESS_INFO,"process.context_id");return context.contextId().toString();}
        private Map<String,Object> descriptor(){context.requireCapability(IsolatePolicy.Capability.PROCESS_INFO,"process.descriptor");return context.processDescriptor();}
        private Object shareReadonly(List<Object> args){context.requireCapability(IsolatePolicy.Capability.ACTOR_SHARE_READONLY,"process.share_readonly");requireOne(args,"process.share_readonly");return context.actors().shareReadonly(args.getFirst());}
    }
    private static void requireOne(List<Object> args,String name){if(args.size()!=1)throw new IllegalArgumentException(name+" expects one argument");}
}
