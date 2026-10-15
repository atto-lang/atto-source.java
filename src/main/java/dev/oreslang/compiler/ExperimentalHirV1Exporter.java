package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.CheckedI64SourceHeader;
import dev.oreslang.parser.Parser;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Experimental, opt-in adapter from the REAL Oreslang Java AST to common-source
 * ores-hir 1.0.0-experimental. Not a backend or a stable interop format.
 *
 * The deliberately tiny admitted subset has i64/bool parameters and returns,
 * a single direct return expression, checked arithmetic, and plain calls.
 * Unsupported constructs FAIL CLOSED. Nothing falls back to interpreted code.
 */
public final class ExperimentalHirV1Exporter {
    private ExperimentalHirV1Exporter() { }

    private static final List<String> FEATURES =
            List.of("bool.v1", "checked-i64.v1", "control-flow.v1", "functions.v1");

    public static String fromSource(String source) {
        if (source == null || source.getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw unsupported("source absent or exceeds 1 MiB");
        }
        // Use the SAME fail-closed source negotiation as the actual Graal
        // evaluator. In particular, reject malformed/displaced directives
        // before parsing rather than advertising HIR for legacy wrapping code.
        boolean checkedI64Runtime = CheckedI64SourceHeader.isEnabled(source);
        return fromVerifiedAst(OresCompiler.parseAndTypeCheck(source), checkedI64Runtime);
    }

    private static String fromVerifiedAst(Ast.Program program) {
        return fromVerifiedAst(program, false);
    }

    private static String fromVerifiedAst(Ast.Program program, boolean checkedI64Runtime) {
        if (program.namespace() != null || !program.imports().isEmpty()) {
            throw unsupported("namespaces/imports");
        }
        List<Ast.FunctionDecl> functions = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            if (!Parser.ROOT_MODULE.equals(module.name())
                    || !module.annotations().isEmpty() || !module.contracts().isEmpty()) {
                throw unsupported("non-root module or module metadata");
            }
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl function)) {
                    throw unsupported("non-function declarations");
                }
                functions.add(function);
            }
        }
        if (functions.isEmpty() || functions.size() > 32) {
            throw unsupported("function count");
        }
        functions.sort(Comparator.comparing(Ast.FunctionDecl::name));
        Set<String> names = new HashSet<>();
        List<Object> emitted = new ArrayList<>();
        for (Ast.FunctionDecl function : functions) {
            if (!identifier(function.name()) || !names.add(function.name())) {
                throw unsupported("duplicate or malformed function name");
            }
            if (function.kind() != Ast.CallableKind.FNC || function.async()
                    || function.suspends() || function.generator() || function.structural()
                    || function.nonLexical() || function.trapped()
                    || function.actorKind() != Ast.ActorKind.NONE
                    || !function.genericParameters().isEmpty()
                    || !function.annotations().isEmpty()) {
                throw unsupported("callable feature outside experimental subset");
            }
            List<Object> params = new ArrayList<>();
            Set<String> paramNames = new HashSet<>();
            for (Ast.Param param : function.parameters()) {
                if (param.mutable() || param.structural() || !identifier(param.name())
                        || !paramNames.add(param.name())) {
                    throw unsupported("parameter mode, name or overloading");
                }
                params.add(object("name", param.name(), "type", type(param.type())));
            }
            if (params.size() > 32 || function.body().size() != 1
                    || !(function.body().get(0) instanceof Ast.ReturnStmt ret)
                    || ret.value() == null) {
                throw unsupported("function body must have exactly one return");
            }
            // Existing GraalVM integer operations wrap on overflow. Export as
            // checked-i64 only when interval analysis proves wrap impossible
            // for EVERY possible i64 parameter value.
            if (!checkedI64Runtime) proveI64Safe(ret.value(), 0);
            emitted.add(object("name", function.name(), "params", params,
                    "result_type", type(function.returnType()),
                    "body", expression(ret.value(), 0, new int[]{0})));
        }
        // HIR v1 forbids direct and mutual recursion. Fail at emission,
        // rather than publishing an artifact that the shared verifier rejects.
        Map<String, Ast.FunctionDecl> declarations = new LinkedHashMap<>();
        for (Ast.FunctionDecl function : functions) {
            declarations.put(function.name(), function);
        }
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (Ast.FunctionDecl function : functions) {
            Ast.ReturnStmt returned = (Ast.ReturnStmt) function.body().get(0);
            Set<String> calls = new HashSet<>();
            collectCalls(returned.value(), declarations, calls);
            edges.put(function.name(), calls);
        }
        Set<String> completed = new HashSet<>();
        for (String name : edges.keySet()) {
            rejectCycles(name, edges, new HashSet<>(), completed);
        }
        Map<String, Object> envelope = object(
                "ir_family", "ores-hir", "schema_version", "1.0.0-experimental",
                "module_id", "source", "required_features", FEATURES, "functions", emitted);
        String json = json(envelope);
        if (json.getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw unsupported("IR exceeds 1 MiB");
        }
        return json + "\n";
    }

    private static Map<String, Object> expression(Ast.Expr expr, int depth, int[] budget) {
        if (depth > 48 || ++budget[0] > 2048) throw unsupported("expression depth/size");
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof Boolean bool) return object("kind", "bool", "value", bool);
            if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                return object("kind", "int", "value", value.toString());
            }
            throw unsupported("non-i64 literal");
        }
        if (expr instanceof Ast.NameExpr name && identifier(name.name())) {
            return object("kind", "var", "name", name.name());
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            String op = switch (binary.operator()) {
                case "+" -> "add"; case "-" -> "sub"; case "*" -> "mul";
                case "<" -> "lt"; case "==" -> "eq";
                default -> throw unsupported("binary operator: " + binary.operator());
            };
            return object("kind", "bin", "op", op,
                    "lhs", expression(binary.left(), depth + 1, budget),
                    "rhs", expression(binary.right(), depth + 1, budget));
        }
        if (expr instanceof Ast.CallExpr call
                && !call.typeArgumentsPresent()
                && call.callee() instanceof Ast.NameExpr name && identifier(name.name())) {
            List<Object> args = new ArrayList<>();
            if (call.arguments().size() > 32) throw unsupported("call arity");
            for (Ast.Expr arg : call.arguments()) args.add(expression(arg, depth + 1, budget));
            return object("kind", "call", "function", name.name(), "args", args);
        }
        throw unsupported("expression outside experimental typed HIR subset");
    }

    private static final BigInteger I64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger I64_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private record Range(BigInteger lo, BigInteger hi) {
        private static Range unknown() { return new Range(I64_MIN, I64_MAX); }
        private static Range literal(long value) {
            BigInteger n = BigInteger.valueOf(value);
            return new Range(n, n);
        }
    }

    /** Conservative universal proof; NO source-parameter values are assumed. */
    private static Range proveI64Safe(Ast.Expr expr, int depth) {
        if (depth > 48) throw unsupported("arithmetic proof depth");
        if (expr instanceof Ast.LiteralExpr literal) {
            if (literal.value() instanceof Number n) return Range.literal(n.longValue());
            return Range.unknown();
        }
        if (expr instanceof Ast.NameExpr) return Range.unknown();
        if (expr instanceof Ast.CallExpr call) {
            for (Ast.Expr arg : call.arguments()) proveI64Safe(arg, depth + 1);
            // Every callee is proved separately for its FULL i64 domain.
            return Range.unknown();
        }
        if (!(expr instanceof Ast.BinaryExpr op)) {
            throw unsupported("expression has no checked-i64 proof");
        }
        Range a = proveI64Safe(op.left(), depth + 1);
        Range b = proveI64Safe(op.right(), depth + 1);
        BigInteger low, high;
        switch (op.operator()) {
            case "+" -> {
                low = a.lo().add(b.lo());
                high = a.hi().add(b.hi());
            }
            case "-" -> {
                low = a.lo().subtract(b.hi());
                high = a.hi().subtract(b.lo());
            }
            case "*" -> {
                BigInteger[] values = {
                    a.lo().multiply(b.lo()), a.lo().multiply(b.hi()),
                    a.hi().multiply(b.lo()), a.hi().multiply(b.hi())
                };
                low = values[0];
                high = values[0];
                for (BigInteger v : values) {
                    low = low.min(v);
                    high = high.max(v);
                }
            }
            case "<", "==" -> { return Range.unknown(); }
            default -> throw unsupported("operator has no checked-i64 proof");
        }
        if (low.compareTo(I64_MIN) < 0 || high.compareTo(I64_MAX) > 0) {
            throw unsupported("possible signed i64 overflow; checked guest arithmetic is not implemented");
        }
        return new Range(low, high);
    }

    private static void collectCalls(Ast.Expr expr,
                                     Map<String, Ast.FunctionDecl> declarations,
                                     Set<String> edges) {
        if (expr instanceof Ast.BinaryExpr binary) {
            collectCalls(binary.left(), declarations, edges);
            collectCalls(binary.right(), declarations, edges);
        } else if (expr instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.NameExpr name) {
            Ast.FunctionDecl target = declarations.get(name.name());
            if (target == null || target.parameters().size() != call.arguments().size()) {
                throw unsupported("unknown callee or invalid direct-call arity");
            }
            edges.add(name.name());
            for (Ast.Expr argument : call.arguments()) collectCalls(argument, declarations, edges);
        }
    }

    private static void rejectCycles(String name, Map<String, Set<String>> edges,
                                     Set<String> visiting, Set<String> completed) {
        if (completed.contains(name)) return;
        if (!visiting.add(name)) throw unsupported("recursive function call graph");
        for (String callee : edges.get(name)) {
            rejectCycles(callee, edges, visiting, completed);
        }
        visiting.remove(name);
        completed.add(name);
    }

    private static String type(Ast.TypeRef type) {
        if (type == null || !type.arguments().isEmpty() || type.inferArguments()) {
            throw unsupported("complex/implicit type");
        }
        return switch (type.name()) {
            // ONLY an opt-in prototype mapping: production Oreslang 'int'
            // semantics must be reviewed before this becomes a stable ABI.
            case "int" -> "i64";
            case "bool", "boolean" -> "bool";
            default -> throw unsupported("type: " + type.name());
        };
    }

    private static boolean identifier(String s) {
        return s != null && s.length() <= 128 && s.matches("[A-Za-z_][A-Za-z_0-9]*");
    }

    private static Map<String, Object> object(Object... fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            m.put((String) fields[i], fields[i + 1]);
        }
        return m;
    }

    private static String json(Object value) {
        if (value instanceof String s) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\\' -> out.append("\\\\");
                    case '"' -> out.append("\\\"");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
                        else out.append(c);
                    }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof List<?> list) {
            List<String> entries = new ArrayList<>();
            for (Object item : list) entries.add(json(item));
            return "[" + String.join(",", entries) + "]";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (var entry : map.entrySet()) {
                entries.add(json(entry.getKey()) + ":" + json(entry.getValue()));
            }
            return "{" + String.join(",", entries) + "}";
        }
        throw unsupported("unserializable IR value");
    }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException("experimental HIR v1 rejects: " + reason);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: ExperimentalHirV1Exporter <source.ores>");
            System.exit(2);
        }
        String source = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        System.out.print(fromSource(source));
    }
}
