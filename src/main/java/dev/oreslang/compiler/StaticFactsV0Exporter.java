package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EXPERIMENTAL closed-world scalar typed-AST to ores-static-facts/v0 adapter.
 *
 * This is deliberately narrower than Oreslang. It uses the real compiler's
 * type/ownership admission and rejects anything it cannot account for.
 * No reflection, regex parsing, hand-authored proof claims or backend fallback.
 *
 * Not a security certificate, a loop-termination proof, or source-spanned MIR:
 * the current Ast does not carry verified instruction source spans or lowering
 * metadata for scheduler safepoints. Such claims MUST NOT be fabricated here.
 */
public final class StaticFactsV0Exporter {
    private StaticFactsV0Exporter() { }

    private record Loop(String id) { }
    private record Edge(String from, String to) { }
    private record Procedure(String id, List<Loop> loops) { }

    /** Type/ownership-check *before* analysis. Only the supported closed subset is admitted. */
    public static String fromSource(String source) {
        if (source == null) throw new IllegalArgumentException("source is null");
        Ast.Program checked = OresCompiler.parseAndTypeCheck(source);
        return new Exporter(source, checked).render();
    }

    private static final class Exporter {
        private final String source;
        private final Ast.Program checked;
        private final Map<String, String> symbols = new HashMap<>();
        private final List<Procedure> procedures = new ArrayList<>();
        private final Set<Edge> edges = new LinkedHashSet<>();
        private final Set<String> functionNames = new HashSet<>();

        Exporter(String source, Ast.Program checked) {
            this.source = source;
            this.checked = checked;
        }

        String render() {
            if (checked.namespace() != null || !checked.imports().isEmpty()
                    || checked.modules().size() != 1) {
                throw unsupported("imports, namespaces, and multi-module programs");
            }
            Ast.ModuleDecl module = checked.modules().getFirst();
            if (!module.annotations().isEmpty() || !module.contracts().isEmpty()) {
                throw unsupported("module annotations and contracts");
            }
            if (module.declarations().isEmpty() || module.declarations().size() > 10_000) {
                throw unsupported("empty or oversized procedure collection");
            }
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FunctionDecl fn)) {
                    throw unsupported("non-function declaration " + decl.getClass().getSimpleName());
                }
                if (fn.actorKind() != Ast.ActorKind.NONE || fn.async() || fn.suspends()
                        || fn.generator() || fn.structural() || fn.nonLexical() || fn.trapped()
                        || !fn.genericParameters().isEmpty() || !fn.annotations().isEmpty()) {
                    throw unsupported("non-scalar or effectful function " + fn.name());
                }
                scalarType(fn.returnType(), true);
                for (Ast.Param param : fn.parameters()) {
                    scalarType(param.type(), false);
                    if (param.structural() || param.mutable()) {
                        throw unsupported("mutable or structural parameter " + param.name());
                    }
                }
                String name = symbol(module.name(), fn.name(), fn.parameters().size());
                String key = fn.name() + "/" + fn.parameters().size();
                if (symbols.putIfAbsent(key, name) != null) {
                    throw unsupported("ambiguous nominal overload " + key);
                }
                functionNames.add(fn.name());
            }
            for (Ast.Decl decl : module.declarations()) {
                Ast.FunctionDecl fn = (Ast.FunctionDecl) decl;
                String caller = symbols.get(fn.name() + "/" + fn.parameters().size());
                for (Ast.Param param : fn.parameters()) {
                    if (functionNames.contains(param.name())) {
                        throw unsupported("function name shadowed by parameter " + param.name());
                    }
                }
                List<Loop> loops = new ArrayList<>();
                visitStatements(fn.body(), caller, loops);
                procedures.add(new Procedure(caller, List.copyOf(loops)));
            }
            procedures.sort(Comparator.comparing(Procedure::id));
            List<Edge> orderedEdges = new ArrayList<>(edges);
            orderedEdges.sort(Comparator.comparing(Edge::from).thenComparing(Edge::to));
            StringBuilder result = new StringBuilder(512);
            result.append("{\"version\":\"ores-static-facts/v0\",\"unit\":");
            quote(result, sha256(source));
            result.append(",\"procedures\":[");
            for (int i = 0; i < procedures.size(); i++) {
                if (i > 0) result.append(',');
                Procedure p = procedures.get(i);
                result.append("{\"id\":");
                quote(result, p.id());
                result.append(",\"loops\":[");
                for (int j = 0; j < p.loops().size(); j++) {
                    if (j > 0) result.append(',');
                    result.append("{\"id\":");
                    quote(result, p.loops().get(j).id());
                    // Unknown is deliberate: a reachable exit != termination proof.
                    // False means no trusted source-level safepoint proof was emitted.
                    result.append(",\"exit\":\"unknown\",\"safepoint\":false}");
                }
                result.append("]}");
            }
            result.append("],\"edges\":[");
            for (int i = 0; i < orderedEdges.size(); i++) {
                if (i > 0) result.append(',');
                Edge e = orderedEdges.get(i);
                result.append("{\"from\":");
                quote(result, e.from());
                result.append(",\"to\":");
                quote(result, e.to());
                result.append(",\"kind\":\"call\",\"fanout\":1,\"guard\":\"unproved\"}");
            }
            result.append("],\"taint_sources\":[],\"flows\":[],\"sinks\":[],\"permission_checks\":[]}");
            return result.toString();
        }

        private void visitStatements(List<Ast.Stmt> body, String caller, List<Loop> loops) {
            for (Ast.Stmt stmt : body) {
                if (stmt instanceof Ast.ReturnStmt ret) {
                    visitExpr(ret.value(), caller);
                } else if (stmt instanceof Ast.BindingStmt binding) {
                    scalarType(binding.declaredType(), false);
                    if (functionNames.contains(binding.name())) {
                        throw unsupported("function name shadowed by local " + binding.name());
                    }
                    visitExpr(binding.initializer(), caller);
                } else if (stmt instanceof Ast.ExprStmt expression) {
                    visitExpr(expression.expression(), caller);
                } else if (stmt instanceof Ast.BlockStmt block) {
                    visitStatements(block.body(), caller, loops);
                } else if (stmt instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch branch : conditional.branches()) {
                        visitExpr(branch.condition(), caller);
                        visitStatements(branch.body(), caller, loops);
                    }
                    visitStatements(conditional.elseBody(), caller, loops);
                } else if (stmt instanceof Ast.ForStmt loop) {
                    addLoop(loops);
                    if (loop.initializer() != null) {
                        visitStatements(List.of(loop.initializer()), caller, loops);
                    }
                    visitExpr(loop.condition(), caller);
                    visitExpr(loop.update(), caller);
                    visitStatements(loop.body(), caller, loops);
                } else if (stmt instanceof Ast.DoWhileStmt loop) {
                    addLoop(loops);
                    visitStatements(loop.body(), caller, loops);
                    visitExpr(loop.condition(), caller);
                } else if (stmt instanceof Ast.LoopStmt loop) {
                    addLoop(loops);
                    visitStatements(loop.body(), caller, loops);
                } else if (!(stmt instanceof Ast.BreakStmt || stmt instanceof Ast.ContinueStmt)) {
                    throw unsupported("statement " + stmt.getClass().getSimpleName());
                }
            }
        }

        private void addLoop(List<Loop> loops) {
            if (loops.size() >= 20_000) throw unsupported("too many loops");
            loops.add(new Loop("L" + (loops.size() + 1)));
        }

        private void visitExpr(Ast.Expr expr, String caller) {
            if (expr == null) return;
            if (expr instanceof Ast.NameExpr) return;
            if (expr instanceof Ast.LiteralExpr literal) {
                if (!(literal.value() instanceof Long || literal.value() instanceof Boolean)) {
                    throw unsupported("non-scalar literal");
                }
                return;
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                visitExpr(binary.left(), caller);
                visitExpr(binary.right(), caller);
            } else if (expr instanceof Ast.UnaryExpr unary) {
                visitExpr(unary.operand(), caller);
            } else if (expr instanceof Ast.AssignExpr assignment) {
                if (!(assignment.target() instanceof Ast.NameExpr)) {
                    throw unsupported("non-scalar assignment target");
                }
                visitExpr(assignment.value(), caller);
            } else if (expr instanceof Ast.ConditionalExpr conditional) {
                visitExpr(conditional.condition(), caller);
                visitExpr(conditional.whenTrue(), caller);
                visitExpr(conditional.whenFalse(), caller);
            } else if (expr instanceof Ast.CallExpr call) {
                if (!(call.callee() instanceof Ast.NameExpr target) || call.typeArgumentsPresent()) {
                    throw unsupported("indirect, member or generic call");
                }
                String callee = symbols.get(target.name() + "/" + call.arguments().size());
                if (callee == null) throw unsupported("unresolved or external call " + target.name());
                for (Ast.Expr arg : call.arguments()) visitExpr(arg, caller);
                edges.add(new Edge(caller, callee));
                if (edges.size() > 50_000) throw unsupported("too many call edges");
            } else {
                throw unsupported("expression " + expr.getClass().getSimpleName());
            }
        }

        private static void scalarType(Ast.TypeRef type, boolean allowVoid) {
            if (type == null || !type.arguments().isEmpty()
                    || !(type.name().equals("int") || type.name().equals("bool")
                        || (allowVoid && type.name().equals("void")))) {
                throw unsupported("non-scalar or inferred type");
            }
        }

        private static String symbol(String module, String name, int arity) {
            String result = module + "." + name + "/" + arity;
            if (result.length() > 256) throw unsupported("symbol ID exceeds schema limit");
            return result;
        }

        private static IllegalArgumentException unsupported(String feature) {
            return new IllegalArgumentException("ORES-ANALYZE-UNSUPPORTED: "
                    + feature + " has no verified v0 typed-fact lowering");
        }

        private static String sha256(String source) {
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256")
                        .digest(source.getBytes(StandardCharsets.UTF_8));
                return "sha256:" + HexFormat.of().formatHex(bytes);
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 unavailable", failure);
            }
        }

        private static void quote(StringBuilder out, String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append("\\u");
                            out.append(String.format(java.util.Locale.ROOT, "%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }
}
