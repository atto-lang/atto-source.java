package atto.compiler.asyncv1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typed, bounded AST for the pure (non-suspending) inline-task subset.
 *
 * Unlike the original literal-only examples, bodies may have multiple
 * statements, nested inline scopes, lexical locals and checked arithmetic.
 * This is NOT a TaskScope scheduler, general Atto frontend, ownership checker,
 * cancellation implementation or a suspending MIR backend. Unsupported
 * constructs reject before generating a guest artifact.
 */
public final class AttoInlineTaskV2Compiler {
    private AttoInlineTaskV2Compiler() {}

    private static final int MAX_SOURCE = 8192;
    private static final int MAX_TOKENS = 384;
    private static final int MAX_DEPTH = 16;
    private static final Pattern INLINE_MAIN_HEADER = Pattern.compile(
        "\\A\\s*pub\\s+(?:susp\\s+)?fnc\\s+main\\s*\\(");
    private static final Pattern INLINE_TASK_TOKENS = Pattern.compile(
        "\\bsync\\s+task\\b");
    private static final Pattern TOKENS = Pattern.compile(
        "\\s+|[a-zA-Z_][a-zA-Z_0-9]*|[0-9]+|[{}():;=<>+*\\-]");
    private static final Set<String> RESERVED = Set.of(
        "task", "sync", "do", "pub", "fnc", "susp", "await", "return",
        "val", "let", "const", "int", "void", "bool", "true", "false",
        "Future", "using", "defer", "spawn", "raise", "panic", "throw",
        "class", "actor", "match", "select", "if", "else", "while", "for",
        "critical", "critical_read", "critical_write", "new", "static",
        "null", "final", "main", "namespace", "import"
    );

    // Only standalone pure main sources are admitted to the expanded parser.
    // Legacy helper/Future fixtures remain owned by their original closed-world
    // compiler path; this prevents a fallback changing their source contract.
    static boolean candidate(String source) {
        if (source == null || source.length() > MAX_SOURCE) return false;
        // Routing must use the grammar's token boundaries, not a literal
        // substring: whitespace/newlines between sync and task are legal.
        // The AST parser still validates the entire source before codegen.
        return INLINE_MAIN_HEADER.matcher(source).find()
            && INLINE_TASK_TOKENS.matcher(source).find();
    }

    private static AttoAsyncV1Compiler.Reject reject(String code) {
        return new AttoAsyncV1Compiler.Reject(code);
    }

    private interface Expr {}
    private record NumberExpr(int value) implements Expr {}
    private record NameExpr(String name) implements Expr {}
    private record ArithmeticExpr(char op, Expr left, Expr right) implements Expr {}
    private record InlineExpr(Block body) implements Expr {}
    private interface Stmt {}
    private record Binding(String name, Expr value) implements Stmt {}
    private record InlineVoid(Block body) implements Stmt {}
    private record ReturnStmt(Expr value) implements Stmt {}
    private record Block(List<Stmt> statements) {}
    public enum MirOp { INLINE_TASK_ENTER, INLINE_TASK_EXIT, SCALAR_BIND, CHECKED_ARITHMETIC, RETURN_INLINE, RETURN_MAIN }
    public record Plan(String javaSource, List<MirOp> typedOps) {
        public Plan { typedOps = List.copyOf(typedOps); }
    }

    private static final class Parser {
        private final List<String> tokens = new ArrayList<>();
        private int cursor;
        Parser(String source) {
            if (source == null || source.length() > MAX_SOURCE)
                throw reject("E_ASYNC_SOURCE_SIZE");
            Matcher m = TOKENS.matcher(source);
            int at = 0;
            while (at < source.length()) {
                m.region(at, source.length());
                if (!m.lookingAt()) throw reject("E_ASYNC_UNKNOWN_TOKEN");
                if (!m.group().isBlank()) {
                    tokens.add(m.group());
                    if (tokens.size() > MAX_TOKENS) throw reject("E_ASYNC_TOKEN_BUDGET");
                }
                at = m.end();
            }
        }
        boolean is(String t) { return cursor < tokens.size() && tokens.get(cursor).equals(t); }
        String next() {
            if (cursor >= tokens.size()) throw reject("E_ASYNC_INCOMPLETE");
            return tokens.get(cursor++);
        }
        void expect(String t) {
            if (!next().equals(t)) throw reject("E_ASYNC_UNEXPECTED_TOKEN");
        }
        String ident() {
            String t = next();
            if (!t.matches("[a-z_][a-z_0-9]*") || t.length() > 80)
                throw reject("E_ASYNC_INVALID_IDENTIFIER");
            if (t.equals("sync")) throw reject("E_RESERVED_SYNC");
            if (RESERVED.contains(t)
                    || AttoAsyncV1Compiler.FORBIDDEN_IDENTIFIERS.contains(t))
                throw reject("E_RESERVED_IDENTIFIER");
            return t;
        }
        int number() {
            String t = next();
            if (!t.matches("[0-9]{1,10}")) throw reject("E_ASYNC_INTEGER_RANGE");
            try { return Integer.parseInt(t); }
            catch (NumberFormatException e) { throw reject("E_ASYNC_INTEGER_RANGE"); }
        }
        Block main() {
            expect("pub");
            // Suspending callers may contain proven-pure inline scopes. The
            // expanded path itself never admits a suspending child operation.
            if (is("susp")) expect("susp");
            expect("fnc"); expect("main"); expect("("); expect(")");
            expect(":"); expect("int");
            Block b = block(0);
            if (cursor != tokens.size()) throw reject("E_ASYNC_TRAILING_SOURCE");
            return b;
        }
        Block block(int depth) {
            if (depth > MAX_DEPTH) throw reject("E_INLINE_NESTING_BUDGET");
            expect("{");
            List<Stmt> list = new ArrayList<>();
            while (!is("}")) {
                if (cursor == tokens.size()) throw reject("E_ASYNC_INCOMPLETE");
                if (list.size() > MAX_TOKENS) throw reject("E_ASYNC_TOKEN_BUDGET");
                if (is("val")) {
                    expect("val");
                    if (is("int")) expect("int");
                    String name = ident();
                    expect("=");
                    Expr value = expr(depth + 1);
                    expect(";");
                    list.add(new Binding(name, value));
                } else if (is("do")) {
                    expect("do"); expect("sync"); expect("task");
                    if (is("<")) {
                        expect("<");
                        if (!is("void")) throw reject("E_TASK_RETURN_TYPE");
                        expect("void"); expect(">");
                    }
                    list.add(new InlineVoid(block(depth + 1)));
                    if (is(";")) expect(";");
                } else if (is("return")) {
                    expect("return");
                    Expr value = is(";") ? null : expr(depth + 1);
                    expect(";");
                    list.add(new ReturnStmt(value));
                } else if (is("sync")) {
                    // Bare inline expressions cannot stand as statements.
                    throw reject("E_SYNTAX");
                } else if (is("await") || is("using") || is("defer")
                    || is("task") || is("spawn") || is("critical")
                    || is("while") || is("for") || is("if") || is("let")) {
                    throw reject("E_UNSUPPORTED_INLINE_EFFECT");
                } else throw reject("E_ASYNC_UNEXPECTED_TOKEN");
            }
            expect("}");
            return new Block(List.copyOf(list));
        }
        Expr expr(int depth) {
            return additive(depth + 1);
        }
        Expr additive(int depth) {
            Expr left = product(depth + 1);
            while (is("+") || is("-")) {
                char op = next().charAt(0);
                left = new ArithmeticExpr(op, left, product(depth + 1));
            }
            return left;
        }
        Expr product(int depth) {
            Expr left = atom(depth + 1);
            while (is("*")) {
                next();
                left = new ArithmeticExpr('*', left, atom(depth + 1));
            }
            return left;
        }
        Expr atom(int depth) {
            if (depth > MAX_DEPTH * 4) throw reject("E_INLINE_NESTING_BUDGET");
            if (is("(")) {
                expect("("); Expr e = expr(depth + 1); expect(")"); return e;
            }
            if (is("sync")) {
                expect("sync"); expect("task");
                if (is("<")) {
                    expect("<");
                    if (!is("int")) throw reject("E_TASK_RETURN_TYPE");
                    expect("int"); expect(">");
                }
                return new InlineExpr(block(depth + 1));
            }
            if (is("-")) {
                expect("-");
                // Expression unary negation uses runtime checked subtraction.
                return new ArithmeticExpr('-', new NumberExpr(0), atom(depth + 1));
            }
            if (cursor < tokens.size() && tokens.get(cursor).matches("[0-9]+"))
                return new NumberExpr(number());
            return new NameExpr(ident());
        }
    }

    private static final class Scope {
        final Scope parent;
        final Map<String, String> vars = new HashMap<>();
        Scope(Scope parent) { this.parent = parent; }
        void put(String name, String generated) {
            if (vars.putIfAbsent(name, generated) != null)
                throw reject("E_ASYNC_DUPLICATE_LOCAL");
        }
        String get(String name) {
            String found = vars.get(name);
            if (found != null) return found;
            if (parent != null) return parent.get(name);
            throw reject("E_ASYNC_UNKNOWN_LOCAL");
        }
    }

    private static final class Lower {
        final StringBuilder code = new StringBuilder();
        final List<MirOp> ops = new ArrayList<>();
        int counter;
        String fresh(String category) {
            if (counter >= MAX_TOKENS * 3) throw reject("E_ASYNC_TOKEN_BUDGET");
            return "atto_" + category + "_" + counter++;
        }
        void line(int indent, String text) {
            code.append("  ".repeat(indent)).append(text).append('\n');
        }
        String eval(Expr e, Scope scope, int indent) {
            if (e instanceof NumberExpr n) return Integer.toString(n.value());
            if (e instanceof NameExpr n) return scope.get(n.name());
            if (e instanceof ArithmeticExpr a) {
                String l = eval(a.left(), scope, indent);
                // Freeze the LHS result before evaluating any nested RHS.
                String saved = fresh("value");
                line(indent, "int " + saved + " = " + l + ";");
                String r = eval(a.right(), scope, indent);
                String op = switch (a.op()) {
                    case '+' -> "addExact";
                    case '-' -> "subtractExact";
                    case '*' -> "multiplyExact";
                    default -> throw reject("E_UNSUPPORTED_INLINE_EFFECT");
                };
                String result = fresh("value");
                line(indent, "int " + result + " = Math." + op + "(" + saved + ", " + r + ");");
                ops.add(MirOp.CHECKED_ARITHMETIC);
                return result;
            }
            if (e instanceof InlineExpr task) {
                String value = fresh("result");
                String label = fresh("inline");
                line(indent, "int " + value + ";");
                line(indent, label + ": {");
                ops.add(MirOp.INLINE_TASK_ENTER);
                block(task.body(), new Scope(scope), indent + 1, label, value, false);
                ops.add(MirOp.INLINE_TASK_EXIT);
                line(indent, "}");
                return value;
            }
            throw reject("E_UNSUPPORTED_INLINE_EFFECT");
        }
        // Every block is checked before guest emission. For an int-returning
        // block, the last reachable statement must be return <int>.
        void block(Block b, Scope scope, int indent, String label,
                   String destination, boolean voidBlock) {
            boolean returned = false;
            for (Stmt statement : b.statements()) {
                if (returned) throw reject("E_ASYNC_UNREACHABLE");
                if (statement instanceof Binding bind) {
                    String rhs = eval(bind.value(), scope, indent);
                    String variable = fresh("local");
                    scope.put(bind.name(), variable);
                    line(indent, "int " + variable + " = " + rhs + ";");
                    ops.add(MirOp.SCALAR_BIND);
                } else if (statement instanceof InlineVoid task) {
                    String innerLabel = fresh("inline");
                    line(indent, innerLabel + ": {");
                    ops.add(MirOp.INLINE_TASK_ENTER);
                    block(task.body(), new Scope(scope), indent + 1, innerLabel, null, true);
                    ops.add(MirOp.INLINE_TASK_EXIT);
                    line(indent, "}");
                } else if (statement instanceof ReturnStmt ret) {
                    if (voidBlock) {
                        if (ret.value() != null) throw reject("E_TASK_RETURN_TYPE");
                        line(indent, "break " + label + ";");
                    } else {
                        if (ret.value() == null) throw reject("E_TASK_RETURN_TYPE");
                        String value = eval(ret.value(), scope, indent);
                        if (label == null) {
                            line(indent, "return " + value + ";");
                            ops.add(MirOp.RETURN_MAIN);
                        } else {
                            line(indent, destination + " = " + value + ";");
                            line(indent, "break " + label + ";");
                            ops.add(MirOp.RETURN_INLINE);
                        }
                    }
                    returned = true;
                } else throw reject("E_UNSUPPORTED_INLINE_EFFECT");
            }
            if (!voidBlock && !returned) throw reject("E_TASK_RETURN_TYPE");
        }
        Plan finish(Block parsed) {
            line(0, "public final class AttoAsyncV1Guest {");
            line(1, "public static int execute() {");
            block(parsed, new Scope(null), 2, null, null, false);
            line(1, "}");
            line(1, "public static void main(String[] args) {");
            line(2, "if (args.length != 0) throw new IllegalArgumentException();");
            line(2, "int result = execute();");
            line(2, "System.out.println(\"ATTO_ASYNC_V1_RESULT=\" + result);");
            line(2, "System.out.println(\"ATTO_ASYNC_SCOPE=PURE_INLINE_TYPED_V2\");");
            line(2, "System.exit(result);");
            line(1, "}");
            line(0, "}");
            return new Plan(code.toString(), ops);
        }
    }

    public static Plan compilePlan(String source) {
        if (!candidate(source)) throw reject("E_UNSUPPORTED_INLINE_PROFILE");
        return new Lower().finish(new Parser(source).main());
    }

    public static String compile(String source) {
        return compilePlan(source).javaSource();
    }
}
