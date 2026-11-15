package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.ArrayList;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Narrow source-derived effect closure for statically resolved zero-argument
 * scalar functions. This is NOT a general HIR/MIR effect checker and NOT
 * real-time or Native Image certification.
 *
 * No client may supply call edges or mark a callee pure. Input is reparsed,
 * typechecked and ownership-checked through the real Oreslang frontend.
 * Unhandled syntax contributes UNKNOWN_CALL instead of becoming effect-free.
 */
public final class ScalarSourceEffectFactsV1 {
    private ScalarSourceEffectFactsV1() { }

    private static final String ROOT = Parser.ROOT_MODULE + "/";
    private static final int MAX_NODES = 100_000;
    private static final int MAX_NESTING = 64;

    /**
     * A source-bound informational result, not a runtime capability.
     * Construction is compiler-owned: public record constructors would allow
     * clients to forge an empty effect set and call requireNoEffects().
     */
    public static final class Report {
        private final Map<String, Set<EffectContracts.Effect>> closure;
        private final String sourceSha256;

        private Report(Map<String, Set<EffectContracts.Effect>> closure, String sourceSha256) {
            var copy = new LinkedHashMap<String, Set<EffectContracts.Effect>>();
            Objects.requireNonNull(closure, "closure").forEach(
                    (k, v) -> copy.put(Objects.requireNonNull(k), Set.copyOf(v)));
            this.closure = Collections.unmodifiableMap(copy);
            this.sourceSha256 = Objects.requireNonNull(sourceSha256, "source sha256");
        }

        public Map<String, Set<EffectContracts.Effect>> closure() {
            return closure;
        }

        /** Informational compiler summary; does not authorize external code. */
        public Set<EffectContracts.Effect> effectsOf(String functionName) {
            Set<EffectContracts.Effect> effects = closure.get(ROOT + functionName + "/0");
            if (effects == null) throw new IllegalArgumentException("unknown scalar function");
            return effects;
        }

        /**
         * Reject using a proof from another source revision. Empty effects
         * alone do not establish bounded CPU, termination or hard-RT admission.
         */
        public void requireNoEffects(String functionName, String checkedSource) {
            if (!sourceSha256.equals(sha256(checkedSource))) {
                throw new IllegalArgumentException("source identity differs from checked effect facts");
            }
            var effects = effectsOf(functionName);
            if (!effects.isEmpty()) {
                throw new IllegalArgumentException(
                        "unproven scalar effect closure for " + functionName + ": " + effects);
            }
        }
    }

    private static String sha256(String source) {
        Objects.requireNonNull(source, "source");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required for compiler source identity", unavailable);
        }
    }

    public static Report fromSource(String source) {
        if (source == null || source.length() > 1_048_576) {
            throw new IllegalArgumentException("source exceeds effect-analysis budget");
        }
        Ast.Program parsed = Parser.parse(source);
        // Admission runs both before and after type checking: frontend
        // normalization must not erase a namespace or module contract that
        // changes source-level symbol identity.
        requireClosedRoot(parsed);
        Ast.Program checked = TypeChecker.check(parsed);
        Ast.ModuleDecl module = requireClosedRoot(checked);

        var declarations = module.declarations();
        var symbols = new LinkedHashMap<String, Ast.FunctionDecl>();
        for (Ast.Decl declaration : declarations) {
            if (!(declaration instanceof Ast.FunctionDecl fn)) {
                throw new IllegalArgumentException(
                        "scalar effect proof does not admit nominal declarations or actor code");
            }
            if (fn.kind() != Ast.CallableKind.FNC
                    || fn.actorKind() != Ast.ActorKind.NONE
                    || !fn.parameters().isEmpty() || !fn.genericParameters().isEmpty()
                    || !fn.genericSpecs().isEmpty() || !fn.annotations().isEmpty()
                    || fn.structural() || fn.nonLexical() || !fn.effects().isEmpty()
                    || !hasAdmittedReturnType(fn.returnType())) {
                throw new IllegalArgumentException(
                        "scalar effect proof does not admit unsupported callable kind, signature, metadata or result type");
            }
            String key = ROOT + fn.name() + "/0";
            if (symbols.putIfAbsent(key, fn) != null) {
                throw new IllegalArgumentException("duplicate scalar call identity: " + key);
            }
        }
        if (symbols.isEmpty() || symbols.size() > 1024) {
            throw new IllegalArgumentException("scalar function count outside proof budget");
        }

        var graph = new EffectContracts.Graph();
        var scanner = new Scanner(symbols.keySet());
        for (var entry : symbols.entrySet()) {
            Ast.FunctionDecl fn = entry.getValue();
            scanner.reset();
            if (fn.async() || fn.suspends() || fn.generator()) {
                scanner.direct.add(EffectContracts.Effect.SUSPEND);
            }
            if (fn.trapped()) scanner.direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            scanner.statements(fn.body(), 0);
            graph.add(EffectContracts.Callable.inferred(
                    entry.getKey(), scanner.direct, scanner.calls));
        }
        return new Report(graph.infer(), sha256(source));
    }

    /** This v1 subset cannot prove boxing, union tags or dynamic returns. */
    private static boolean hasAdmittedReturnType(Ast.TypeRef type) {
        if (type == null || !type.arguments().isEmpty() || type.inferArguments()) {
            return false;
        }
        return switch (type.name()) {
            case "int", "bool", "void", "string" -> true;
            default -> false;
        };
    }

    private static Ast.ModuleDecl requireClosedRoot(Ast.Program program) {
        // This checker knows only the unqualified unannotated file-root
        // namespace, not imported or annotated module lookup.
        if (program.namespace() != null || !program.imports().isEmpty()
                || program.modules().size() != 1) {
            throw new IllegalArgumentException(
                    "scalar effect proof requires one unqualified root module without imports");
        }
        Ast.ModuleDecl module = program.modules().getFirst();
        if (!Parser.ROOT_MODULE.equals(module.name())
                || !module.annotations().isEmpty() || !module.contracts().isEmpty()) {
            throw new IllegalArgumentException(
                    "scalar effect proof forbids module annotations, contracts and non-root modules");
        }
        return module;
    }

    private static final class Scanner {
        final Set<String> known;
        final EnumSet<EffectContracts.Effect> direct =
                EnumSet.noneOf(EffectContracts.Effect.class);
        final List<EffectContracts.Call> calls = new ArrayList<>();
        int nodes;

        Scanner(Set<String> known) {
            this.known = Set.copyOf(known);
        }

        void reset() {
            direct.clear();
            calls.clear();
            nodes = 0;
        }

        void budget(int depth) {
            if (++nodes > MAX_NODES || depth > MAX_NESTING) {
                throw new IllegalArgumentException(
                        "scalar effect source exceeds static traversal budget");
            }
        }

        void statements(List<Ast.Stmt> statements, int depth) {
            budget(depth);
            for (Ast.Stmt stmt : statements) statement(stmt, depth + 1);
        }

        void statement(Ast.Stmt stmt, int depth) {
            budget(depth);
            if (stmt instanceof Ast.ReturnStmt ret) {
                expression(ret.value(), depth + 1);
            } else if (stmt instanceof Ast.ExprStmt expr) {
                expression(expr.expression(), depth + 1);
            } else if (stmt instanceof Ast.BlockStmt block) {
                statements(block.body(), depth + 1);
            } else if (stmt instanceof Ast.YieldStmt yield) {
                direct.add(EffectContracts.Effect.SUSPEND);
                expression(yield.value(), depth + 1);
            } else if (stmt instanceof Ast.TaskScopeStmt task) {
                direct.add(EffectContracts.Effect.SUSPEND);
                direct.add(EffectContracts.Effect.SCHEDULER);
                statements(task.body(), depth + 1);
            } else {
                // This includes lexical binding/shadowing, defer, match,
                // select, loops, exception handling and borrowed scopes.
                // Until resolved HIR facts exist, no one may infer purity.
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
        }

        void expression(Ast.Expr expr, int depth) {
            budget(depth);
            if (expr == null) return; // void return
            if (expr instanceof Ast.LiteralExpr literal) {
                Object v = literal.value();
                if (v instanceof String) direct.add(EffectContracts.Effect.ALLOC);
                else if (!(v instanceof Integer) && !(v instanceof Long)
                        && !(v instanceof Boolean)) {
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
            } else if (expr instanceof Ast.CallExpr call) {
                // Only direct name/zero-argument calls can be proven here.
                // Any inferred receiver, pipeline, generic argument, alias,
                // callback, named argument or indirect call is UNKNOWN.
                if (call.callee() instanceof Ast.NameExpr target
                        && call.origin() == Ast.CallOrigin.DIRECT
                        && call.arguments().isEmpty()
                        && call.typeArguments().isEmpty()
                        && !call.typeArgumentsPresent()) {
                    String symbol = ROOT + target.name() + "/0";
                    if (known.contains(symbol)) calls.add(EffectContracts.Call.direct(symbol));
                    else direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                } else {
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
            } else if (expr instanceof Ast.AwaitExpr await) {
                direct.add(EffectContracts.Effect.SUSPEND);
                expression(await.expression(), depth + 1);
            } else if (expr instanceof Ast.TaskExpr task) {
                direct.add(EffectContracts.Effect.SUSPEND);
                direct.add(EffectContracts.Effect.SCHEDULER);
                direct.add(EffectContracts.Effect.ALLOC);
                statements(task.body(), depth + 1);
            } else if (expr instanceof Ast.NewExpr) {
                direct.add(EffectContracts.Effect.ALLOC);
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            } else if (expr instanceof Ast.ChannelOpExpr
                    || expr instanceof Ast.DynamicSelectExpr) {
                direct.add(EffectContracts.Effect.SUSPEND);
                direct.add(EffectContracts.Effect.IO);
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            } else {
                // Includes operators until typed scalar evidence, runtime
                // intrinsics, lambdas, assignment, indexing, interpolated
                // forms, aggregate layouts and all future AST additions.
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
        }
    }
}
