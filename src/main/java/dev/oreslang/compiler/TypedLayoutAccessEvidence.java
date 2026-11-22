package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Source-derived access observations from admitted Oreslang syntax/semantics.
 *
 * Deliberately NOT a layout-IR exporter: the current AST lacks verified
 * physical layout, vector width, row cardinality, profile hotness, relocation
 * rights and complete alias/effect facts. This pass must NEVER mark the Rust
 * layout IR's evidence_complete=true. Member syntax may denote a method or
 * property; it is not independently proof of a physical field offset.
 */
public final class TypedLayoutAccessEvidence {
    private TypedLayoutAccessEvidence() { }

    public record LoopObservation(
            String owner, String binding, String iterable,
            List<String> directMemberSelectors,
            boolean supportedDirectBody,
            boolean wholeBindingObserved,
            String limitation) {
        public LoopObservation { directMemberSelectors = List.copyOf(directMemberSelectors); }
    }

    public record Report(
            List<LoopObservation> loops, String assuranceScope,
            boolean completeForLayoutRecommendations) {
        public Report { loops = List.copyOf(loops); }
    }

    private static final int MAX_SOURCE_CODE_UNITS = 1_048_576;
    private static final int MAX_STATEMENTS = 16_384;
    private static final int MAX_LOOPS = 1_024;
    private static final int MAX_MEMBERS_PER_LOOP = 256;

    /** The only public entry point: type and ownership admission run first. */
    public static Report inspectSource(String source) {
        Objects.requireNonNull(source, "source");
        if (source.length() > MAX_SOURCE_CODE_UNITS)
            throw new IllegalArgumentException("layout source admission exceeds size budget");
        return inspectAdmitted(OresCompiler.parseAndTypeCheck(source));
    }

    static Report inspectAdmitted(Ast.Program admitted) {
        Objects.requireNonNull(admitted);
        var loops = new ArrayList<LoopObservation>();
        int[] statementsSeen = {0};
        for (Ast.ModuleDecl module : admitted.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    scan(fn.body(), module.name() + "::" + fn.name(), loops, statementsSeen);
                } else if (declaration instanceof Ast.ClassDecl clazz) {
                    for (Ast.MethodDecl method : clazz.methods()) {
                        scan(method.body(), module.name() + "::" + clazz.name()
                                + "." + method.name(), loops, statementsSeen);
                    }
                    if (clazz.constructor() != null) {
                        scan(clazz.constructor().body(),
                                module.name() + "::" + clazz.name() + ".constructor",
                                loops, statementsSeen);
                    }
                }
            }
        }
        return new Report(loops, "typed_oreslang_member_syntax_only",
                false /* Missing alias, hotness, physical-layout and profile evidence */);
    }

    private static void scan(List<Ast.Stmt> body, String owner,
                             List<LoopObservation> loops, int[] statementsSeen) {
        var pending = new ArrayDeque<Ast.Stmt>();
        for (Ast.Stmt stmt : body) pending.addLast(stmt);
        while (!pending.isEmpty()) {
            if (++statementsSeen[0] > MAX_STATEMENTS)
                throw new IllegalArgumentException("layout syntax traversal budget exceeded");
            Ast.Stmt stmt = pending.removeFirst();
            if (stmt instanceof Ast.ForOfStmt forOf) {
                if (loops.size() >= MAX_LOOPS)
                    throw new IllegalArgumentException("layout loop observation budget exceeded");
                loops.add(classify(owner, forOf));
                pending.addAll(forOf.body());
            } else if (stmt instanceof Ast.BlockStmt block) {
                pending.addAll(block.body());
            } else if (stmt instanceof Ast.LoopStmt loop) {
                pending.addAll(loop.body());
            } else if (stmt instanceof Ast.ForStmt counted) {
                pending.addAll(counted.body());
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches())
                    pending.addAll(branch.body());
                pending.addAll(conditional.elseBody());
            } else if (stmt instanceof Ast.TryStmt guarded) {
                pending.addAll(guarded.body());
                pending.addAll(guarded.catchBody());
                pending.addAll(guarded.finallyBody());
            } else if (stmt instanceof Ast.SwitchStmt sw) {
                for (Ast.SwitchCase arm : sw.cases()) pending.addAll(arm.body());
                pending.addAll(sw.defaultBody());
            } else if (stmt instanceof Ast.MatchStmt match) {
                for (Ast.MatchArm arm : match.arms()) pending.addAll(arm.body());
            }
            // Other control flow remains explicitly outside this narrow
            // observation pass. The report never claims global completeness.
        }
    }

    private static LoopObservation classify(String owner, Ast.ForOfStmt forOf) {
        String iterable = forOf.iterable() instanceof Ast.NameExpr name
                ? name.name() : "<computed/unknown>";
        boolean supported = forOf.iterable() instanceof Ast.NameExpr
                && !forOf.asyncIteration() && forOf.body().size() <= MAX_STATEMENTS;
        boolean whole = false;
        var members = new LinkedHashSet<String>();
        for (Ast.Stmt stmt : forOf.body()) {
            Ast.Expr expression;
            if (stmt instanceof Ast.BindingStmt binding) {
                expression = binding.initializer();
            } else if (stmt instanceof Ast.ExprStmt item) {
                expression = item.expression();
            } else {
                // Nested bodies, closure captures, writes, awaits, calls and
                // early control transfers require CFG/alias/effect analyses.
                supported = false;
                continue;
            }
            if (expression instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr name
                    && name.name().equals(forOf.bindingName())) {
                if (!members.contains(member.member())
                        && members.size() >= MAX_MEMBERS_PER_LOOP)
                    throw new IllegalArgumentException("layout member access budget exceeded");
                members.add(member.member());
            } else if (expression instanceof Ast.NameExpr name
                    && name.name().equals(forOf.bindingName())) {
                whole = true;
                supported = false;
            } else if (!(expression instanceof Ast.LiteralExpr)
                    && !(expression instanceof Ast.NameExpr)) {
                supported = false;
            }
        }
        return new LoopObservation(owner, forOf.bindingName(), iterable,
                List.copyOf(members), supported && !whole, whole,
                supported && !whole ? "physical_layout_and_hotness_unproven"
                        : "control_flow_alias_or_access_effects_unproven");
    }
}
