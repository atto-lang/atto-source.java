package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canonical compiler-side declaration manifest. This is metadata only: it does
 * not schedule activities, execute workflows, persist history or imply that
 * any command has a durable runtime implementation.
 */
public final class DurableExecutionManifest {
    private final byte[] canonicalBytes;
    private final String sha256;

    private DurableExecutionManifest(byte[] canonicalBytes) {
        this.canonicalBytes = canonicalBytes.clone();
        this.sha256 = digest(canonicalBytes);
    }

    public static DurableExecutionManifest analyze(Ast.Program program) {
        return DurableDeclarationAnalyzer.analyze(program);
    }

    static DurableExecutionManifest empty() {
        return new DurableExecutionManifest("{\"format\":\"atto.durable.manifest.v1\",\"activities\":[],\"workflows\":[]}".getBytes(StandardCharsets.UTF_8));
    }

    static DurableExecutionManifest fromValidated(Ast.Program program) {
        List<String> activities = new ArrayList<>();
        List<String> workflows = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ActivityDecl activity) {
                    activities.add(activityJson(module.name(), activity));
                } else if (declaration instanceof Ast.WorkflowDecl workflow) {
                    workflows.add(workflowJson(module.name(), workflow));
                }
            }
        }
        activities.sort(Comparator.naturalOrder());
        workflows.sort(Comparator.naturalOrder());
        String json = "{\"format\":\"atto.durable.manifest.v1\",\"activities\":["
                + String.join(",", activities) + "],\"workflows\":["
                + String.join(",", workflows) + "]}";
        return new DurableExecutionManifest(json.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] canonicalBytes() { return canonicalBytes.clone(); }
    public String sha256() { return sha256; }

    private static String activityJson(String module, Ast.ActivityDecl activity) {
        List<String> methods = new ArrayList<>();
        for (Ast.MethodDecl method : activity.body().methods()) {
            methods.add(methodJson(method));
        }
        methods.sort(Comparator.naturalOrder());
        return "{\"id\":" + quote(module + "." + activity.name())
                + ",\"version\":1,\"operations\":[" + String.join(",", methods) + "]}";
    }

    private static String workflowJson(String module, Ast.WorkflowDecl workflow) {
        List<String> methods = new ArrayList<>();
        for (Ast.MethodDecl method : workflow.body().methods()) methods.add(methodJson(method));
        methods.sort(Comparator.naturalOrder());
        List<String> commands = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        int historyOrder = 0;
        for (Ast.WorkflowCommand command : workflow.commands()) {
            if (command instanceof Ast.QueryCommand query) {
                queries.add(queryJson(query));
            } else {
                commands.add(commandJson(historyOrder++, command));
            }
        }
        queries.sort(Comparator.naturalOrder());
        return "{\"id\":" + quote(module + "." + workflow.name())
                + ",\"version\":1,\"state\":[],\"members\":["
                + String.join(",", methods) + "],\"commands\":["
                + String.join(",", commands) + "],\"queries\":["
                + String.join(",", queries) + "]}";
    }

    private static String methodJson(Ast.MethodDecl method) {
        List<String> parameters = method.parameters().stream()
                .map(p -> quote(typeName(p.type())))
                .toList();
        List<String> parameterNames = method.parameters().stream()
                .map(p -> "{\"name\":" + quote(p.name())
                        + ",\"type\":" + quote(typeName(p.type()))
                        + ",\"mutable\":" + p.mutable()
                        + ",\"structural\":" + p.structural() + "}")
                .toList();
        return "{\"name\":" + quote(method.name())
                + ",\"visibility\":" + quote(method.visibility().name().toLowerCase(java.util.Locale.ROOT))
                + ",\"static\":" + method.isStatic()
                + ",\"arity\":" + method.parameters().size()
                + ",\"parameters\":[" + String.join(",", parameterNames) + "]"
                + ",\"returns\":" + quote(typeName(method.returnType()))
                + ",\"suspends\":" + method.suspends() + "}";
    }

    private static String queryJson(Ast.QueryCommand query) {
        List<String> parameters = query.parameters().stream()
                .map(p -> "{\"name\":" + quote(p.name())
                        + ",\"type\":" + quote(typeName(p.type()))
                        + ",\"mutable\":" + p.mutable()
                        + ",\"structural\":" + p.structural() + "}")
                .toList();
        return "{\"endpoint\":" + quote(query.endpoint())
                + ",\"parameters\":[" + String.join(",", parameters) + "]"
                + ",\"returns\":" + quote(typeName(query.returnType())) + "}";
    }

    private static String commandJson(int order, Ast.WorkflowCommand command) {
        String id = null;
        String kind;
        String target = "";
        List<Ast.Expr> arguments = List.of();
        if (command instanceof Ast.ActivityStepCommand c) { id = c.commandId(); kind = "activity_step"; target = c.activityName() + "." + c.operation(); arguments = c.arguments(); }
        else if (command instanceof Ast.TimerCommand c) { id = c.commandId(); kind = "timer"; arguments = List.of(c.delay()); }
        else if (command instanceof Ast.WaitForEventCommand c) { id = c.commandId(); kind = "wait_event"; target = c.eventType(); }
        else if (command instanceof Ast.SignalCommand c) { id = c.commandId(); kind = "signal"; target = c.endpoint(); arguments = c.arguments(); }
        else if (command instanceof Ast.QueryCommand c) { kind = "query"; target = c.endpoint(); }
        else if (command instanceof Ast.UpdateCommand c) { id = c.commandId(); kind = "update"; target = c.endpoint(); arguments = c.arguments(); }
        else if (command instanceof Ast.ChildWorkflowCommand c) { id = c.commandId(); kind = "child_workflow"; target = c.workflowName(); arguments = List.of(c.input()); }
        else if (command instanceof Ast.ContinueAsNewCommand c) { id = c.commandId(); kind = "continue_as_new"; arguments = List.of(c.input()); }
        else if (command instanceof Ast.CompensationCommand c) { id = c.commandId(); kind = "compensation"; target = c.activityName() + "." + c.operation(); arguments = c.arguments(); }
        else throw new IllegalArgumentException("unknown workflow command node " + command.getClass().getName());
        String stableId = id == null ? "" : ",\"id\":" + quote(id);
        return "{\"order\":" + order + ",\"kind\":" + quote(kind)
                + stableId + ",\"target\":" + quote(target)
                + ",\"arguments\":[" + String.join(",", arguments.stream()
                        .map(DurableExecutionManifest::expressionJson).toList()) + "]}";
    }

    private static String expressionJson(Ast.Expr expression) {
        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof String text) return "{\"kind\":\"string\",\"value\":" + quote(text) + "}";
            if (value instanceof Boolean bool) return "{\"kind\":\"bool\",\"value\":" + bool + "}";
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return "{\"kind\":\"int\",\"value\":" + value + "}";
            }
            if (value instanceof Float || value instanceof Double) {
                double number = ((Number) value).doubleValue();
                if (!Double.isFinite(number)) throw new IllegalArgumentException("non-finite workflow command literal");
                return "{\"kind\":\"float\",\"value\":" + Double.toString(number) + "}";
            }
            if (value instanceof dev.oreslang.runtime.OresSymbol symbol) {
                return "{\"kind\":\"symbol\",\"value\":" + quote(symbol.toString()) + "}";
            }
            throw new IllegalArgumentException("workflow command literal is not canonical/serializable: "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        if (expression instanceof Ast.NameExpr name) {
            if (name.name().startsWith("$")) throw new IllegalArgumentException("internal names are not workflow command values");
            return "{\"kind\":\"name\",\"value\":" + quote(name.name()) + "}";
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            return "{\"kind\":\"unary\",\"op\":" + quote(unary.operator())
                    + ",\"value\":" + expressionJson(unary.operand()) + "}";
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            return "{\"kind\":\"binary\",\"op\":" + quote(binary.operator())
                    + ",\"left\":" + expressionJson(binary.left())
                    + ",\"right\":" + expressionJson(binary.right()) + "}";
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            return "{\"kind\":\"conditional\",\"condition\":" + expressionJson(conditional.condition())
                    + ",\"true\":" + expressionJson(conditional.whenTrue())
                    + ",\"false\":" + expressionJson(conditional.whenFalse()) + "}";
        }
        if (expression instanceof Ast.ListExpr list) {
            return "{\"kind\":\"list\",\"items\":[" + String.join(",", list.elements().stream()
                    .map(DurableExecutionManifest::expressionJson).toList()) + "]}";
        }
        if (expression instanceof Ast.TupleExpr tuple) {
            return "{\"kind\":\"tuple\",\"items\":[" + String.join(",", tuple.elements().stream()
                    .map(DurableExecutionManifest::expressionJson).toList()) + "]}";
        }
        throw new IllegalArgumentException("workflow command expression is outside the canonical serializable subset: "
                + expression.getClass().getSimpleName());
    }

    private static String typeName(Ast.TypeRef type) {
        if (type == null) return "void";
        if (type.arguments().isEmpty()) return type.name();
        return type.name() + "<" + String.join(",", type.arguments().stream()
                .map(DurableExecutionManifest::typeName).toList()) + ">";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
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

    private static String digest(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
