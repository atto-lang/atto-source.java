package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

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
        // Run the actual compiler front-end, including ownership admission.
        return fromVerifiedAst(OresCompiler.parseAndTypeCheck(source));
    }

    private static String fromVerifiedAst(Ast.Program program) {
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
            emitted.add(object("name", function.name(), "params", params,
                    "result_type", type(function.returnType()),
                    "body", expression(ret.value(), 0, new int[]{0})));
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
