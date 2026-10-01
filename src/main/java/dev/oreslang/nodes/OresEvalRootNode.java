package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.OresContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Initial executable Truffle root. The frontend is already statically checked;
 * this v0 evaluator deliberately keeps semantic lowering in one boundary while
 * dedicated specializing Truffle expression nodes are added incrementally.
 */
public final class OresEvalRootNode extends RootNode {
    private final Ast.Program program;

    public OresEvalRootNode(OresLanguage language, Ast.Program program) {
        super(language);
        this.program = program;
    }

    @Override
    public String getName() {
        return "ores-eval";
    }

    @Override
    public boolean isInternal() {
        return true;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return executeBoundary(OresContext.get(this), frame.getArguments());
    }

    @TruffleBoundary
    private Object executeBoundary(OresContext context, Object[] arguments) {
        return new Evaluator(program, context).execute(arguments);
    }

    private static final class Evaluator {
        private final Ast.Program program;
        private final OresContext context;
        private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
        private final Map<String, Ast.ClassDecl> classes = new HashMap<>();

        private Evaluator(Ast.Program program, OresContext context) {
            this.program = program;
            this.context = context;
            indexDeclarations();
        }

        private void indexDeclarations() {
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) {
                        functions.put(module.name() + "." + fn.name(), fn);
                        functions.putIfAbsent(fn.name(), fn);
                    } else if (decl instanceof Ast.ClassDecl klass) {
                        classes.put(module.name() + "." + klass.name(), klass);
                        classes.putIfAbsent(klass.name(), klass);
                    }
                }
            }
        }

        private Object execute(Object[] arguments) {
            Ast.FunctionDecl main = functions.get("main");
            if (main == null) return null;
            return callFunction(main, List.of(arguments));
        }

        private Object callFunction(Ast.FunctionDecl fn, List<?> args) {
            if (args.size() != fn.parameters().size()) {
                if (fn.parameters().isEmpty() && args.size() == 1 && args.getFirst() instanceof Object[] array && array.length == 0) {
                    args = List.of();
                } else {
                    throw new IllegalArgumentException("function " + fn.name() + " expects " + fn.parameters().size() + " arguments, got " + args.size());
                }
            }
            Env env = new Env(null);
            for (int i = 0; i < fn.parameters().size(); i++) env.define(fn.parameters().get(i).name(), args.get(i), Ast.BindingKind.VAL);
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return signal.value;
            }
        }

        private Object callMethod(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            if (args.size() != method.parameters().size()) throw new IllegalArgumentException("method " + method.name() + " arity mismatch");
            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", receiver, Ast.BindingKind.VAL);
            for (int i = 0; i < method.parameters().size(); i++) env.define(method.parameters().get(i).name(), args.get(i), Ast.BindingKind.VAL);
            try {
                executeBlock(method.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return signal.value;
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
                env.define(binding.name(), eval(binding.initializer(), env), binding.kind());
                return;
            }
            if (stmt instanceof Ast.DestructureStmt destructure) {
                Object value = eval(destructure.initializer(), env);
                List<?> items = asSequence(value);
                if (items.size() != destructure.bindings().size()) throw new IllegalArgumentException("destructure arity mismatch");
                for (int i = 0; i < items.size(); i++) {
                    Ast.DestructureBinding binding = destructure.bindings().get(i);
                    env.define(binding.name(), items.get(i), binding.kind());
                }
                return;
            }
            if (stmt instanceof Ast.ReturnStmt ret) throw new ReturnSignal(ret.value() == null ? null : eval(ret.value(), env));
            if (stmt instanceof Ast.ExprStmt expression) {
                eval(expression.expression(), env);
                return;
            }
            if (stmt instanceof Ast.DeferStmt defer) {
                deferred.push(defer.expression());
                return;
            }
            if (stmt instanceof Ast.IfStmt ifStmt) {
                for (Ast.IfBranch branch : ifStmt.branches()) {
                    if (truth(eval(branch.condition(), env))) {
                        executeBlock(branch.body(), env);
                        return;
                    }
                }
                executeBlock(ifStmt.elseBody(), env);
                return;
            }
            if (stmt instanceof Ast.TryStmt tried) {
                try {
                    executeBlock(tried.body(), env);
                } catch (ReturnSignal signal) {
                    throw signal;
                } catch (RuntimeException failure) {
                    Env catchEnv = new Env(env);
                    catchEnv.define(tried.errorName(), failure, Ast.BindingKind.VAL);
                    executeBlock(tried.catchBody(), catchEnv);
                } finally {
                    executeBlock(tried.finallyBody(), env);
                }
            }
        }

        private Object eval(Ast.Expr expr, Env env) {
            if (expr instanceof Ast.LiteralExpr literal) {
                if (literal.value() instanceof Ast.Imaginary imaginary) return new Complex(0.0, imaginary.coefficient());
                return literal.value();
            }
            if (expr instanceof Ast.NameExpr name) {
                Object local = env.lookup(name.name());
                if (local != Env.MISSING) return local;
                if (name.name().equals("stdio")) return new StdioFacade(context);
                if (name.name().equals("process")) return new ProcessFacade(context);
                if (name.name().equals("print")) return (Invokable) args -> {
                    if (args.size() != 1) throw new IllegalArgumentException("print expects one argument");
                    context.output().print(display(args.getFirst()));
                    context.output().flush();
                    return null;
                };
                Ast.FunctionDecl fn = functions.get(name.name());
                if (fn != null) return (Invokable) args -> callFunction(fn, args);
                throw new IllegalArgumentException("unknown name " + name.name());
            }
            if (expr instanceof Ast.UnaryExpr unary) {
                Object value = eval(unary.operand(), env);
                return switch (unary.operator()) {
                    case "!" -> !truth(value);
                    case "+" -> value;
                    case "-" -> negate(value);
                    default -> throw new IllegalArgumentException("unsupported unary operator " + unary.operator());
                };
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                if (binary.operator().equals(",")) return truth(eval(binary.left(), env)) && truth(eval(binary.right(), env));
                if (binary.operator().equals("|")) return truth(eval(binary.left(), env)) || truth(eval(binary.right(), env));
                Object left = eval(binary.left(), env);
                Object right = eval(binary.right(), env);
                return binary(binary.operator(), left, right);
            }
            if (expr instanceof Ast.CallExpr call) {
                Object callee = eval(call.callee(), env);
                List<Object> args = call.arguments().stream().map(arg -> eval(arg, env)).toList();
                if (!(callee instanceof Invokable invokable)) throw new IllegalArgumentException("value is not callable: " + callee);
                return invokable.call(args);
            }
            if (expr instanceof Ast.MemberExpr member) {
                Object receiver = eval(member.receiver(), env);
                return member(receiver, member.member());
            }
            if (expr instanceof Ast.NewExpr created) {
                Ast.ClassDecl klass = classes.get(created.type().name());
                if (klass == null) throw new IllegalArgumentException("unknown class " + created.type().name());
                List<Object> args = created.arguments().stream().map(arg -> eval(arg, env)).toList();
                LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                for (int i = 0; i < klass.fields().size(); i++) {
                    Ast.FieldDecl field = klass.fields().get(i);
                    Object value = i < args.size() ? args.get(i) : field.initializer() == null ? null : eval(field.initializer(), env);
                    fields.put(field.name(), value);
                }
                return new OresObject(klass, fields);
            }
            if (expr instanceof Ast.AwaitExpr awaited) {
                Object value = eval(awaited.expression(), env);
                if (value instanceof CompletionStage<?> stage) return stage.toCompletableFuture().join();
                return value;
            }
            if (expr instanceof Ast.ListExpr list) return list.elements().stream().map(item -> eval(item, env)).toList();
            if (expr instanceof Ast.LambdaExpr lambda) {
                Env captured = env.snapshot();
                return (Invokable) args -> {
                    if (args.size() != lambda.parameters().size()) throw new IllegalArgumentException("lambda arity mismatch");
                    Env local = new Env(captured);
                    for (int i = 0; i < lambda.parameters().size(); i++) local.define(lambda.parameters().get(i).name(), args.get(i), Ast.BindingKind.VAL);
                    if (lambda.expressionBody() != null) return eval(lambda.expressionBody(), local);
                    try {
                        executeBlock(lambda.blockBody(), local);
                        return null;
                    } catch (ReturnSignal signal) {
                        return signal.value;
                    }
                };
            }
            throw new IllegalArgumentException("unsupported expression " + expr);
        }

        private Object member(Object receiver, String name) {
            if (receiver instanceof StdioFacade stdio) {
                return switch (name) {
                    case "print" -> (Invokable) args -> stdio.print(args);
                    case "println" -> (Invokable) args -> stdio.println(args);
                    default -> throw new IllegalArgumentException("unknown stdio member " + name);
                };
            }
            if (receiver instanceof ProcessFacade process) {
                return switch (name) {
                    case "context_id" -> process.contextId();
                    case "descriptor" -> process.descriptor();
                    case "share_readonly" -> (Invokable) args -> process.shareReadonly(args);
                    default -> throw new IllegalArgumentException("unknown process member " + name);
                };
            }
            if (receiver instanceof OresObject object) {
                if (object.fields.containsKey(name)) return object.fields.get(name);
                Ast.MethodDecl method = object.klass.methods().stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
                if (method != null) return (Invokable) args -> callMethod(object, method, args);
                throw new IllegalArgumentException("unknown member " + object.klass.name() + "." + name);
            }
            if (receiver instanceof Map<?, ?> map) return map.get(name);
            throw new IllegalArgumentException("cannot access member '" + name + "' on " + receiver);
        }

        private Object binary(String op, Object left, Object right) {
            return switch (op) {
                case "+" -> add(left, right);
                case "-" -> numeric(left, right, '-');
                case "*" -> numeric(left, right, '*');
                case "/" -> numeric(left, right, '/');
                case "%" -> numeric(left, right, '%');
                case "==" -> Objects.equals(left, right);
                case "!=" -> !Objects.equals(left, right);
                case "<" -> compare(left, right) < 0;
                case "<=" -> compare(left, right) <= 0;
                case ">" -> compare(left, right) > 0;
                case ">=" -> compare(left, right) >= 0;
                default -> throw new IllegalArgumentException("unsupported operator " + op);
            };
        }

        private Object add(Object left, Object right) {
            if (left instanceof String && right instanceof String) return ((String) left) + right;
            return numeric(left, right, '+');
        }

        private Object numeric(Object left, Object right, char op) {
            if (left instanceof Complex || right instanceof Complex) {
                Complex a = asComplex(left);
                Complex b = asComplex(right);
                return switch (op) {
                    case '+' -> a.add(b);
                    case '-' -> a.sub(b);
                    case '*' -> a.mul(b);
                    case '/' -> a.div(b);
                    default -> throw new IllegalArgumentException("operator " + op + " is not supported for complex numbers");
                };
            }
            if (!(left instanceof Number a) || !(right instanceof Number b)) throw new IllegalArgumentException("numeric operator requires numbers");
            boolean integral = isIntegral(a) && isIntegral(b) && op != '/';
            if (integral) {
                long x = a.longValue(), y = b.longValue();
                return switch (op) {
                    case '+' -> x + y;
                    case '-' -> x - y;
                    case '*' -> x * y;
                    case '%' -> x % y;
                    default -> throw new IllegalArgumentException("bad numeric operator");
                };
            }
            double x = a.doubleValue(), y = b.doubleValue();
            return switch (op) {
                case '+' -> x + y;
                case '-' -> x - y;
                case '*' -> x * y;
                case '/' -> x / y;
                case '%' -> x % y;
                default -> throw new IllegalArgumentException("bad numeric operator");
            };
        }

        private Object negate(Object value) {
            if (value instanceof Complex complex) return new Complex(-complex.real, -complex.imaginary);
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return -((Number) value).longValue();
            if (value instanceof Number number) return -number.doubleValue();
            throw new IllegalArgumentException("unary - requires a number");
        }

        private int compare(Object left, Object right) {
            if (left instanceof Number a && right instanceof Number b) return Double.compare(a.doubleValue(), b.doubleValue());
            if (left instanceof String a && right instanceof String b) return a.compareTo(b);
            throw new IllegalArgumentException("values are not comparable");
        }

        private boolean truth(Object value) {
            if (value instanceof Boolean bool) return bool;
            throw new IllegalArgumentException("condition must be bool");
        }

        private boolean isIntegral(Number value) {
            return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
        }

        private Complex asComplex(Object value) {
            if (value instanceof Complex complex) return complex;
            if (value instanceof Number number) return new Complex(number.doubleValue(), 0.0);
            throw new IllegalArgumentException("value is not numeric");
        }

        private List<?> asSequence(Object value) {
            if (value instanceof List<?> list) return list;
            if (value instanceof Object[] array) return List.of(array);
            throw new IllegalArgumentException("value is not destructurable");
        }

        private String display(Object value) {
            if (value instanceof Complex complex) return complex.toString();
            return String.valueOf(value);
        }
    }

    @FunctionalInterface
    private interface Invokable {
        Object call(List<Object> arguments);
    }

    private static final class Env {
        private static final Object MISSING = new Object();
        private final Env parent;
        private final Map<String, Slot> slots = new HashMap<>();

        private Env(Env parent) { this.parent = parent; }

        private void define(String name, Object value, Ast.BindingKind kind) {
            if (slots.putIfAbsent(name, new Slot(value, kind)) != null) throw new IllegalArgumentException("duplicate binding " + name);
        }

        private Object lookup(String name) {
            Slot slot = slots.get(name);
            if (slot != null) return slot.value;
            return parent == null ? MISSING : parent.lookup(name);
        }

        private Env snapshot() {
            Env copyParent = parent == null ? null : parent.snapshot();
            Env copy = new Env(copyParent);
            copy.slots.putAll(slots);
            return copy;
        }
    }

    private record Slot(Object value, Ast.BindingKind kind) { }

    private static final class ReturnSignal extends RuntimeException {
        private final Object value;
        private ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private record Complex(double real, double imaginary) {
        private Complex add(Complex other) { return new Complex(real + other.real, imaginary + other.imaginary); }
        private Complex sub(Complex other) { return new Complex(real - other.real, imaginary - other.imaginary); }
        private Complex mul(Complex other) {
            return new Complex(real * other.real - imaginary * other.imaginary,
                    real * other.imaginary + imaginary * other.real);
        }
        private Complex div(Complex other) {
            double d = other.real * other.real + other.imaginary * other.imaginary;
            return new Complex((real * other.real + imaginary * other.imaginary) / d,
                    (imaginary * other.real - real * other.imaginary) / d);
        }
        @Override public String toString() {
            return real + (imaginary < 0 ? "" : "+") + imaginary + "i";
        }
    }

    private static final class OresObject {
        private final Ast.ClassDecl klass;
        private final Map<String, Object> fields;
        private OresObject(Ast.ClassDecl klass, Map<String, Object> fields) {
            this.klass = klass;
            this.fields = fields;
        }
        @Override public String toString() { return klass.name() + fields; }
    }

    private record StdioFacade(OresContext context) {
        private Object print(List<Object> args) {
            requireOne(args, "stdio.print");
            context.output().print(String.valueOf(args.getFirst()));
            context.output().flush();
            return null;
        }
        private Object println(List<Object> args) {
            requireOne(args, "stdio.println");
            context.output().println(String.valueOf(args.getFirst()));
            return null;
        }
    }

    private record ProcessFacade(OresContext context) {
        private String contextId() { return context.contextId().toString(); }
        private Map<String, Object> descriptor() { return context.processDescriptor(); }
        private Object shareReadonly(List<Object> args) {
            requireOne(args, "process.share_readonly");
            return context.actors().shareReadonly(args.getFirst());
        }
    }

    private static void requireOne(List<Object> args, String name) {
        if (args.size() != 1) throw new IllegalArgumentException(name + " expects one argument");
    }
}
