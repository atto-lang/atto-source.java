package atto.compiler.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Closed-world, signed-int64 Atto functional-pipeline source compiler.
 *
 * This is an independently gated bootstrap subset, NOT the full actor/JVM
 * frontend. It lowers checked Atto calls and ordered val bindings into Java
 * source that javac and GraalVM Native Image compile into actual guest code.
 * No Atto guest expression is interpreted by the Java host at runtime.
 */
public final class AttoFunctionalPipelineCompiler {
    private static final int MAX_SOURCE_BYTES = 65536;
    private static final int MAX_TOKENS = 8192;
    private static final int MAX_DEPTH = 32;
    private static final Pattern TOKENS = Pattern.compile(
        "\\s+|//[^\\n]*|\\|>|<\\||->|[A-Za-z_][A-Za-z_0-9]*|[0-9]+|[{}():;,+*/=\\-]");
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z_0-9]*");
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");
    private static final Set<String> RESERVED =
        Set.of("fnc", "pub", "int", "return", "val", "bool", "true", "false");

    private AttoFunctionalPipelineCompiler() {}

    public static final class Reject extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public final String code;
        Reject(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private sealed interface Expr permits Num, Ref, Binary, Call {}
    private record Num(long value) implements Expr {}
    private record Ref(String name) implements Expr {}
    private record Binary(String op, Expr lhs, Expr rhs) implements Expr {}
    private record Call(String name, List<Expr> args) implements Expr {}
    private record Binding(String name, Expr initializer) {}
    private record Function(String name, List<String> params,
                            List<Binding> locals, Expr result) {}

    private static Reject reject(String code, String why) {
        return new Reject(code, why);
    }

    private static final class Parser {
        final List<String> tokens = new ArrayList<>();
        int index;

        Parser(String source) {
            if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES)
                throw reject("E_LIMIT", "source exceeds 64 KiB");
            Matcher scanner = TOKENS.matcher(source);
            int offset = 0;
            while (offset < source.length()) {
                scanner.region(offset, source.length());
                if (!scanner.lookingAt()) throw reject("E_LEX", "unsupported source token");
                String token = scanner.group();
                if (!token.isBlank() && !token.startsWith("//")) tokens.add(token);
                if (tokens.size() > MAX_TOKENS) throw reject("E_LIMIT", "token budget");
                offset = scanner.end();
            }
            tokens.add("<eof>");
        }

        String peek() { return tokens.get(index); }
        boolean eat(String value) {
            if (!peek().equals(value)) return false;
            index++;
            return true;
        }
        void take(String value) {
            if (!eat(value)) throw reject("E_PARSE", "expected " + value);
        }
        String name() {
            String n = peek();
            if (!NAME.matcher(n).matches() || RESERVED.contains(n))
                throw reject("E_NAME", "expected a safe snake_case name");
            index++;
            return n;
        }

        List<Expr> arguments(int depth) {
            take("(");
            List<Expr> result = new ArrayList<>();
            if (!peek().equals(")")) {
                do {
                    result.add(expr(0, depth + 1, ""));
                    if (result.size() > 8) throw reject("E_LIMIT", "8 call arguments");
                } while (eat(","));
            }
            take(")");
            return result;
        }

        Expr expr(int min, int depth, String direction) {
            if (depth > MAX_DEPTH) throw reject("E_LIMIT", "expression recursion budget");
            Expr left;
            if (eat("(")) {
                left = expr(0, depth + 1, "");
                take(")");
            } else if (eat("-")) {
                left = new Binary("-", new Num(0), expr(30, depth + 1, direction));
            } else if (NUMBER.matcher(peek()).matches()) {
                try { left = new Num(Long.parseLong(peek())); }
                catch (NumberFormatException e) { throw reject("E_LITERAL", "i64 overflow"); }
                index++;
            } else if (peek().equals("true") || peek().equals("false")) {
                throw reject("E_TYPE", "int64-only profile rejects boolean values");
            } else {
                String n = name();
                left = peek().equals("(") ? new Call(n, arguments(depth)) : new Ref(n);
            }

            while (true) {
                int precedence = switch (peek()) {
                    case "*", "/" -> 20;
                    case "+", "-" -> 10;
                    case "|>", "<|" -> 5;
                    default -> -1;
                };
                if (precedence < min) break;
                String op = peek();
                index++;
                if (op.equals("|>") || op.equals("<|")) {
                    if (!direction.isEmpty() && !direction.equals(op))
                        throw reject("E_PARSE", "mixed functional directions require parentheses");
                    direction = op;
                    if (op.equals("|>")) {
                        String target = name();
                        List<Expr> args = peek().equals("(")
                            ? arguments(depth + 1) : new ArrayList<>();
                        if (args.size() >= 8) throw reject("E_LIMIT", "8 call arguments");
                        args.add(0, left);
                        left = new Call(target, List.copyOf(args));
                    } else {
                        String target;
                        List<Expr> args;
                        if (left instanceof Ref r) {
                            target = r.name();
                            args = new ArrayList<>();
                        } else if (left instanceof Call c) {
                            target = c.name();
                            args = new ArrayList<>(c.args());
                        } else throw reject("E_PARSE", "reverse callee must be a named function");
                        if (args.size() >= 8) throw reject("E_LIMIT", "8 call arguments");
                        Expr rhs = expr(precedence, depth + 1, op);
                        args.add(0, rhs);
                        left = new Call(target, List.copyOf(args));
                    }
                } else {
                    if (op.equals("/")) throw reject("E_UNSUPPORTED", "division requires a checked lowering");
                    left = new Binary(op, left, expr(precedence + 1, depth + 1, direction));
                }
            }
            return left;
        }

        List<Function> parse() {
            List<Function> functions = new ArrayList<>();
            while (!peek().equals("<eof>")) {
                eat("pub");
                take("fnc");
                String functionName = name();
                take("(");
                List<String> params = new ArrayList<>();
                if (!peek().equals(")")) {
                    do {
                        take("int");
                        String param = name();
                        if (params.contains(param)) throw reject("E_DUP_PARAM", param);
                        params.add(param);
                        if (params.size() > 8) throw reject("E_LIMIT", "8 function parameters");
                    } while (eat(","));
                }
                take(")");
                boolean colon = eat(":");
                if (!colon) take("->");
                take("int");
                List<Binding> locals = new ArrayList<>();
                Expr result;
                if (eat("{")) result = brace(locals);
                else if (colon && eat("->")) {
                    if (eat("{")) result = brace(locals);
                    else { result = expr(0, 0, ""); take(";"); }
                } else throw reject("E_PARSE", "expected braces or single inline arrow expression");
                functions.add(new Function(functionName, List.copyOf(params),
                    List.copyOf(locals), result));
                if (functions.size() > 64) throw reject("E_LIMIT", "64 functions");
            }
            return functions;
        }

        private Expr brace(List<Binding> locals) {
            while (eat("val")) {
                String name = name();
                take("=");
                Expr initializer = expr(0, 0, "");
                take(";");
                if (locals.size() >= 4) throw reject("E_LIMIT", "4 immutable locals");
                locals.add(new Binding(name, initializer));
            }
            take("return");
            Expr result = expr(0, 0, "");
            take(";");
            take("}");
            return result;
        }
    }

    private static void check(List<Function> functions) {
        Map<String, Function> symbols = new LinkedHashMap<>();
        for (Function f : functions) {
            if (symbols.putIfAbsent(f.name(), f) != null)
                throw reject("E_DUP_FUNCTION", f.name());
        }
        Function main = symbols.get("main");
        if (main == null || !main.params().isEmpty())
            throw reject("E_ENTRY", "main() must exist with no parameters");

        Map<String, Integer> state = new HashMap<>();
        for (Function f : functions) checkFunction(f, symbols, state);
    }

    private static void checkFunction(Function f, Map<String, Function> symbols,
                                      Map<String, Integer> state) {
        int status = state.getOrDefault(f.name(), 0);
        if (status == 2) return;
        if (status == 1) throw reject("E_CYCLE", f.name());
        state.put(f.name(), 1);
        Set<String> available = new HashSet<>(f.params());
        for (Binding local : f.locals()) {
            checkExpr(local.initializer(), available, symbols, state);
            if (!available.add(local.name()))
                throw reject("E_DUP_LOCAL", local.name());
        }
        checkExpr(f.result(), available, symbols, state);
        state.put(f.name(), 2);
    }

    private static void checkExpr(Expr expr, Set<String> available,
                                  Map<String, Function> symbols, Map<String, Integer> state) {
        if (expr instanceof Num) return;
        if (expr instanceof Ref r) {
            if (!available.contains(r.name())) throw reject("E_SYMBOL", r.name());
        } else if (expr instanceof Binary b) {
            checkExpr(b.lhs(), available, symbols, state);
            checkExpr(b.rhs(), available, symbols, state);
        } else if (expr instanceof Call c) {
            Function target = symbols.get(c.name());
            if (target == null) throw reject("E_SYMBOL", c.name());
            if (target.params().size() != c.args().size()) throw reject("E_ARITY", c.name());
            for (Expr a : c.args()) checkExpr(a, available, symbols, state);
            checkFunction(target, symbols, state);
        } else throw reject("E_TYPE", "unrecognized expression");
    }

    private static String emitExpr(Expr expr, Function fn) {
        if (expr instanceof Num n) return n.value() + "L";
        if (expr instanceof Ref r) {
            return fn.params().contains(r.name()) ? "p_" + r.name() : "v_" + r.name();
        }
        if (expr instanceof Binary b) {
            String lhs = emitExpr(b.lhs(), fn);
            String rhs = emitExpr(b.rhs(), fn);
            String exact = switch (b.op()) {
                case "+" -> "addExact";
                case "-" -> "subtractExact";
                case "*" -> "multiplyExact";
                default -> throw reject("E_UNSUPPORTED", "checked arithmetic operator");
            };
            return "Math." + exact + "(" + lhs + ", " + rhs + ")";
        }
        if (expr instanceof Call c) {
            List<String> args = new ArrayList<>();
            for (Expr a : c.args()) args.add(emitExpr(a, fn));
            return "f_" + c.name() + "(" + String.join(", ", args) + ")";
        }
        throw reject("E_TYPE", "unrecognized expression");
    }

    private static String emit(List<Function> functions) {
        StringBuilder out = new StringBuilder();
        out.append("// Generated from checked Atto source. No guest source interpreter.\n");
        out.append("public final class AttoFunctionalPipelineGuest {\n");
        out.append("  private AttoFunctionalPipelineGuest() {}\n");
        for (Function f : functions) {
            List<String> params = new ArrayList<>();
            for (String p : f.params()) params.add("long p_" + p);
            out.append("  public static long f_").append(f.name())
                .append("(").append(String.join(", ", params)).append(") {\n");
            for (Binding local : f.locals()) {
                out.append("    final long v_").append(local.name())
                    .append(" = ").append(emitExpr(local.initializer(), f)).append(";\n");
            }
            out.append("    return ").append(emitExpr(f.result(), f)).append(";\n");
            out.append("  }\n");
        }
        out.append("  public static void main(String[] args) {\n");
        out.append("    if (args.length != 0) System.exit(64);\n");
        out.append("    System.exit(Math.toIntExact(f_main()));\n");
        out.append("  }\n}\n");
        return out.toString();
    }

    /** Publish a deterministic source file only after full syntax/type checks. */
    public static int compile(Path source, Path output) {
        Path temp = null;
        try {
            if (!source.toString().endsWith(".atto")
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(source)
                    || Files.size(source) > MAX_SOURCE_BYTES
                    || !output.getFileName().toString().equals("AttoFunctionalPipelineGuest.java"))
                throw reject("E_SOURCE", "source/output admission");
            Path parent = output.toAbsolutePath().getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(output, LinkOption.NOFOLLOW_LINKS))
                throw reject("E_OUTPUT", "output must be new in a real directory");
            byte[] bytes = Files.readAllBytes(source);
            String text = new String(bytes, StandardCharsets.UTF_8);
            String generated = emit(new Parser(text).parse());
            // The parser must prove its call graph before any artifact exists.
            List<Function> typed = new Parser(text).parse();
            check(typed);
            generated = emit(typed);
            temp = Files.createTempFile(parent, ".atto-pipeline-", ".tmp");
            Files.writeString(temp, generated, StandardCharsets.UTF_8);
            Files.createLink(output, temp);
            System.out.println("ATTO_JAVA_PIPELINE_SOURCE_SHA256="
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            return 0;
        } catch (IOException | NoSuchAlgorithmException | IllegalArgumentException e) {
            System.err.println(e instanceof Reject r ? r.code + ": " + r.getMessage()
                : "E_IO: " + e.getMessage());
            return 2;
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {
                    System.err.println("E_CLEANUP: could not clean temporary artifact");
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) System.exit(64);
        System.exit(compile(Path.of(args[0]), Path.of(args[1])));
    }
}
