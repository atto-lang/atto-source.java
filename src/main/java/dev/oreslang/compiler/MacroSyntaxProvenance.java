package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiler-only provenance for syntax emitted by macro expansion.
 *
 * <p>The table is keyed by exact AST identity with weak keys. It is diagnostic
 * metadata, never runtime authority, ABI, object layout, or reflection data.
 * Native/runtime artifacts do not need to retain it after semantic checking.</p>
 */
public final class MacroSyntaxProvenance {
    public enum EmissionKind {
        CALL_SITE_SPLICE,
        CONST_QUOTE,
        TEMPLATE_QUOTE,
        HYGIENIC_IDENTIFIER,
        CHECKED_CAPTURE,
        DECLARE_FUNCTION
    }

    public record HygieneIdentity(
            String sourceUnitId,
            String callerModule,
            String macroOwnerModule,
            String macroName,
            Ast.SourceSite invocationSite,
            int expansionOrdinal) {
        public HygieneIdentity {
            if (sourceUnitId == null || sourceUnitId.isBlank()
                    || callerModule == null || callerModule.isBlank()
                    || macroOwnerModule == null || macroOwnerModule.isBlank()
                    || macroName == null || macroName.isBlank()
                    || macroName.indexOf('.') >= 0
                    || invocationSite == null
                    || expansionOrdinal < 0) {
                throw new IllegalArgumentException("invalid canonical macro hygiene identity");
            }
        }

        public String qualifiedMacroName() {
            return macroOwnerModule + "." + macroName;
        }
    }

    public record Origin(
            HygieneIdentity hygiene,
            Ast.SourceSite definitionSite,
            EmissionKind emissionKind) {
        public Origin {
            java.util.Objects.requireNonNull(hygiene, "macro hygiene identity");
            java.util.Objects.requireNonNull(definitionSite, "macro definition source site");
            java.util.Objects.requireNonNull(emissionKind, "macro emission kind");
        }
    }

    private static final ReferenceQueue<Ast.Expr> CLEARED = new ReferenceQueue<>();
    private static final Map<IdentityKey, List<Origin>> ORIGINS = new HashMap<>();
    static final int MAX_PROVENANCE_NODES = 65_536;
    static final int MAX_PROVENANCE_DEPTH = 256;
    private static final ThreadLocal<PendingTree> PENDING = new ThreadLocal<>();

    private static final class PendingTree {
        final ArrayList<Ast.Expr> expressions = new ArrayList<>();
        int totalNodes;
        int depth;

        void visitNode() {
            if (++totalNodes > MAX_PROVENANCE_NODES) {
                throw new IllegalArgumentException(
                        "macro provenance node budget exceeds " + MAX_PROVENANCE_NODES
                                + "; no partial origin publication");
            }
        }

        void enterDepth() {
            if (++depth > MAX_PROVENANCE_DEPTH) {
                throw new IllegalArgumentException(
                        "macro provenance depth budget exceeds " + MAX_PROVENANCE_DEPTH
                                + "; no partial origin publication");
            }
        }
    }

    private MacroSyntaxProvenance() { }

    private static final class IdentityKey extends WeakReference<Ast.Expr> {
        private final int hash;

        private IdentityKey(
                Ast.Expr expression,
                ReferenceQueue<Ast.Expr> queue) {
            super(expression, queue);
            hash = System.identityHashCode(expression);
        }

        @Override public int hashCode() { return hash; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityKey key)) return false;
            Ast.Expr value = get();
            return value != null && value == key.get();
        }
    }

    private static void expunge() {
        Reference<? extends Ast.Expr> collected;
        while ((collected = CLEARED.poll()) != null) {
            ORIGINS.remove(collected);
        }
    }

    public static synchronized void record(Ast.Expr expression, Origin origin) {
        java.util.Objects.requireNonNull(expression, "generated macro expression");
        java.util.Objects.requireNonNull(origin, "macro syntax origin");
        expunge();
        IdentityKey key = new IdentityKey(expression, CLEARED);
        List<Origin> existing = ORIGINS.get(key);
        ArrayList<Origin> next =
                existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        if (!next.contains(origin)) next.add(origin);
        ORIGINS.put(key, List.copyOf(next));
    }

    public static synchronized List<Origin> lookup(Ast.Expr expression) {
        java.util.Objects.requireNonNull(expression, "macro provenance expression");
        expunge();
        List<Origin> origins = ORIGINS.get(new IdentityKey(expression, null));
        return origins == null ? List.of() : origins;
    }

    /**
     * Preserve prior compiler-issued provenance when a supported syntax leaf
     * is copied to a fresh AST identity. Composite copying is intentionally
     * handled by the expander's bounded admission, not by this metadata helper.
     */
    public static synchronized void copyOrigins(Ast.Expr source, Ast.Expr target) {
        java.util.Objects.requireNonNull(source, "source syntax expression");
        java.util.Objects.requireNonNull(target, "copied syntax expression");
        if (source == target) {
            throw new IllegalArgumentException("syntax provenance copy requires a fresh AST identity");
        }
        expunge();
        List<Origin> origins = ORIGINS.get(new IdentityKey(source, null));
        if (origins == null || origins.isEmpty()) return;
        for (Origin origin : origins) {
            record(target, origin);
        }
    }

    /**
     * Attach one successful expansion origin to the complete emitted subtree.
     * Nested expansion origins are preserved and the outer origin is appended.
     * The class monitor is held for the full traversal so concurrent diagnostic
     * readers cannot observe a partially published origin tree.
     */
    public static synchronized void recordTree(Ast.Expr expression, Origin origin) {
        if (expression == null) return;
        java.util.Objects.requireNonNull(origin, "macro syntax origin");
        PendingTree pending = PENDING.get();
        boolean outermost = pending == null;
        if (outermost) {
            pending = new PendingTree();
            PENDING.set(pending);
        }
        try {
            pending.visitNode();
            pending.enterDepth();
            pending.expressions.add(expression);

        if (expression instanceof Ast.BinaryExpr e) {
            recordTree(e.left(), origin);
            recordTree(e.right(), origin);
        } else if (expression instanceof Ast.UnaryExpr e) {
            recordTree(e.operand(), origin);
        } else if (expression instanceof Ast.AssignExpr e) {
            recordTree(e.target(), origin);
            recordTree(e.value(), origin);
        } else if (expression instanceof Ast.ConditionalExpr e) {
            recordTree(e.condition(), origin);
            recordTree(e.whenTrue(), origin);
            recordTree(e.whenFalse(), origin);
        } else if (expression instanceof Ast.MatchExpr e) {
            recordTree(e.subject(), origin);
            for (Ast.MatchValueArm arm : e.arms()) {
                recordTree(arm.guard(), origin);
                recordTree(arm.value(), origin);
            }
        } else if (expression instanceof Ast.TypeTestExpr e) {
            recordTree(e.value(), origin);
        } else if (expression instanceof Ast.PatternTestExpr e) {
            recordTree(e.value(), origin);
        } else if (expression instanceof Ast.CastExpr e) {
            recordTree(e.value(), origin);
        } else if (expression instanceof Ast.SpreadExpr e) {
            recordTree(e.expression(), origin);
        } else if (expression instanceof Ast.CallExpr e) {
            recordTree(e.callee(), origin);
            for (Ast.Expr argument : e.arguments()) recordTree(argument, origin);
        } else if (expression instanceof Ast.MacroInvocationExpr e) {
            for (Ast.MacroArgument argument : e.arguments()) {
                if (argument instanceof Ast.MacroConstArgument a) {
                    recordTree(a.expression(), origin);
                } else if (argument instanceof Ast.MacroSyntaxExprArgument a) {
                    recordTree(a.expression(), origin);
                }
            }
        } else if (expression instanceof Ast.RuntimeCallExpr e) {
            for (Ast.Expr argument : e.arguments()) recordTree(argument, origin);
        } else if (expression instanceof Ast.MemberExpr e) {
            recordTree(e.receiver(), origin);
        } else if (expression instanceof Ast.IndexExpr e) {
            recordTree(e.receiver(), origin);
            recordTree(e.index(), origin);
        } else if (expression instanceof Ast.NewExpr e) {
            for (Ast.Expr argument : e.arguments()) recordTree(argument, origin);
        } else if (expression instanceof Ast.AwaitExpr e) {
            recordTree(e.expression(), origin);
        } else if (expression instanceof Ast.TaskExpr e) {
            recordStatements(e.body(), origin);
        } else if (expression instanceof Ast.TrapExpr e) {
            recordTree(e.expression(), origin);
        } else if (expression instanceof Ast.ChannelOpExpr e) {
            recordTree(e.channel(), origin);
            recordTree(e.value(), origin);
            recordStatements(e.callbackBody(), origin);
        } else if (expression instanceof Ast.DynamicSelectExpr e) {
            recordTree(e.cases(), origin);
        } else if (expression instanceof Ast.ListExpr e) {
            for (Ast.Expr item : e.elements()) recordTree(item, origin);
        } else if (expression instanceof Ast.TupleExpr e) {
            for (Ast.Expr item : e.elements()) recordTree(item, origin);
        } else if (expression instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField field : e.fields()) recordTree(field.value(), origin);
        } else if (expression instanceof Ast.LambdaExpr e) {
            recordTree(e.expressionBody(), origin);
            if (e.blockBody() != null) recordStatements(e.blockBody(), origin);
        }
            // No global state is changed until the entire traversal succeeds.
            // Nested recordTree calls append to this same collection.
            if (outermost) {
                for (Ast.Expr node : pending.expressions) record(node, origin);
            }
        } finally {
            pending.depth--;
            if (outermost) PENDING.remove();
        }
    }

    private static void recordStatements(
            List<Ast.Stmt> statements,
            Origin origin) {
        PendingTree pending = PENDING.get();
        if (pending == null) {
            throw new IllegalStateException("macro provenance traversal has no active transaction");
        }
        pending.enterDepth();
        try {
        for (Ast.Stmt statement : statements) {
            pending.visitNode();
            if (statement instanceof Ast.BindingStmt s) {
                recordTree(s.initializer(), origin);
            } else if (statement instanceof Ast.DestructureStmt s) {
                recordTree(s.initializer(), origin);
            } else if (statement instanceof Ast.ReturnStmt s) {
                recordTree(s.value(), origin);
            } else if (statement instanceof Ast.YieldStmt s) {
                recordTree(s.value(), origin);
            } else if (statement instanceof Ast.ExprStmt s) {
                recordTree(s.expression(), origin);
            } else if (statement instanceof Ast.DeferStmt s) {
                recordTree(s.expression(), origin);
            } else if (statement instanceof Ast.BlockStmt s) {
                recordStatements(s.body(), origin);
            } else if (statement instanceof Ast.TaskScopeStmt s) {
                recordStatements(s.body(), origin);
            } else if (statement instanceof Ast.UsingBorrowStmt s) {
                recordTree(s.resource(), origin);
                recordStatements(s.body(), origin);
            } else if (statement instanceof Ast.IfStmt s) {
                for (Ast.IfBranch branch : s.branches()) {
                    recordTree(branch.condition(), origin);
                    recordStatements(branch.body(), origin);
                }
                recordStatements(s.elseBody(), origin);
            } else if (statement instanceof Ast.MatchStmt s) {
                recordTree(s.subject(), origin);
                for (Ast.MatchArm arm : s.arms()) {
                    recordTree(arm.guard(), origin);
                    recordStatements(arm.body(), origin);
                }
            } else if (statement instanceof Ast.SwitchStmt s) {
                recordTree(s.subject(), origin);
                for (Ast.SwitchCase arm : s.cases()) {
                    for (Ast.Expr constant : arm.constants()) {
                        recordTree(constant, origin);
                    }
                    recordStatements(arm.body(), origin);
                }
                recordStatements(s.defaultBody(), origin);
            } else if (statement instanceof Ast.TryStmt s) {
                recordStatements(s.body(), origin);
                recordStatements(s.catchBody(), origin);
                recordStatements(s.finallyBody(), origin);
            } else if (statement instanceof Ast.ForOfStmt s) {
                recordTree(s.iterable(), origin);
                recordStatements(s.body(), origin);
            } else if (statement instanceof Ast.ForOfDestructureStmt s) {
                recordTree(s.iterable(), origin);
                recordStatements(s.body(), origin);
            } else if (statement instanceof Ast.ForStmt s) {
                if (s.initializer() != null) {
                    recordStatements(List.of(s.initializer()), origin);
                }
                recordTree(s.condition(), origin);
                recordTree(s.update(), origin);
                recordStatements(s.body(), origin);
            } else if (statement instanceof Ast.DoWhileStmt s) {
                recordStatements(s.body(), origin);
                recordTree(s.condition(), origin);
            } else if (statement instanceof Ast.LoopStmt s) {
                recordStatements(s.body(), origin);
            } else if (statement instanceof Ast.SelectStmt s) {
                for (Ast.SelectArm arm : s.arms()) {
                    recordTree(arm.channel(), origin);
                    recordTree(arm.value(), origin);
                    recordStatements(arm.body(), origin);
                }
            }
        }
        } finally {
            pending.depth--;
        }
    }
}
