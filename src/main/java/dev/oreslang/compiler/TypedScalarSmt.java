package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Experimental source-to-SMT interface backed by the actual checked AST.
 *
 * This is NOT a proof that Java/Graal execution refines the AST or that the
 * compiler's type/ownership checker is sound. Unsupported source is rejected.
 * This intentionally supports only pure signed-i64 min/max/clamp functions.
 */
public final class TypedScalarSmt {
    private TypedScalarSmt() { }

    private static final Map<String, Integer> ARITY = Map.of(
            "min_int", 2, "max_int", 2, "clamp_int", 3);
    private static final Map<String, String> COMPARISONS = Map.of(
            "<", "bvslt", "<=", "bvsle", ">", "bvsgt", ">=", "bvsge");
    private static final Map<String, String> CONTRACT = Map.of(
            "min_int", "(ite (bvsle arg0 arg1) arg0 arg1)",
            "max_int", "(ite (bvsge arg0 arg1) arg0 arg1)",
            "clamp_int", "(ite (bvsgt arg1 arg2) arg1 "
                + "(ite (bvslt arg0 arg1) arg1 (ite (bvsgt arg0 arg2) arg2 arg0)))");
    private static final Map<String, String> MUTANT = Map.of(
            "min_int", "(ite (bvsgt x y) x y)",
            "max_int", "(ite (bvslt x y) x y)",
            "clamp_int", "(ite (bvsgt y z) y (ite (bvslt x y) y x))");

    public static String fromSource(String source, String module, String function) {
        // Important: the real parser, type checker, and ownership admission
        // run before any IR node is imported into the proof domain.
        return fromCheckedProgram(OresCompiler.parseAndTypeCheck(source), module, function);
    }

    /**
     * Takes a caller-owned fully checked Program. Callers must not hand this
     * method an AST created directly from Parser.parse without checking it.
     * Exposing this separately is useful for a future typed HIR adapter.
     */
    public static String fromCheckedProgram(Ast.Program checked, String module, String function) {
        if (checked == null || module == null || module.isBlank()
                || !ARITY.containsKey(function)) {
            throw new IllegalArgumentException("unsupported scalar proof target");
        }
        Ast.FunctionDecl target = null;
        int found = 0;
        for (Ast.ModuleDecl m : checked.modules()) {
            if (!module.equals(m.name())) continue;
            for (Ast.Decl d : m.declarations()) {
                if (d instanceof Ast.FunctionDecl f && function.equals(f.name())) {
                    found++;
                    target = f;
                }
            }
        }
        require(found == 1 && target != null, "missing or ambiguous function");
        require(target.kind() == Ast.CallableKind.FNC
                        && target.visibility() == Ast.Visibility.PUBLIC
                        && target.actorKind() == Ast.ActorKind.NONE
                        && !target.async() && !target.suspends() && !target.generator()
                        && !target.structural() && !target.nonLexical() && !target.trapped()
                        && target.genericParameters().isEmpty()
                        && target.annotations().isEmpty(),
                "effects, generics, annotations or nonstandard callable semantics unsupported");
        require(simpleInt(target.returnType()), "return type must be exact int");
        require(target.parameters().size() == ARITY.get(function), "wrong arity");

        Map<String, String> arguments = new HashMap<>();
        for (int i = 0; i < target.parameters().size(); i++) {
            Ast.Param p = target.parameters().get(i);
            require(simpleInt(p.type()) && !p.structural() && !p.mutable(),
                    "only immutable value int parameters supported");
            require(p.name() != null && !p.name().isBlank()
                            && arguments.putIfAbsent(p.name(), "arg" + i) == null,
                    "duplicate/invalid input name");
        }

        String implementation = lower(target.body(), null, arguments);
        require(implementation != null, "non-returning control-flow path");
        String[] variables = {"x", "y", "z"};
        int n = target.parameters().size();
        StringBuilder smt = new StringBuilder("(set-logic QF_BV)\n");
        smt.append("(define-fun implementation (");
        for (int i = 0; i < n; i++) {
            if (i > 0) smt.append(' ');
            smt.append("(arg").append(i).append(" (_ BitVec 64))");
        }
        smt.append(") (_ BitVec 64) ").append(implementation).append(")\n");
        smt.append("(define-fun contract (");
        for (int i = 0; i < n; i++) {
            if (i > 0) smt.append(' ');
            smt.append("(arg").append(i).append(" (_ BitVec 64))");
        }
        smt.append(") (_ BitVec 64) ").append(CONTRACT.get(function)).append(")\n");
        StringBuilder callArguments = new StringBuilder();
        for (int i = 0; i < n; i++) {
            smt.append("(declare-const ").append(variables[i]).append(" (_ BitVec 64))\n");
            callArguments.append(' ').append(variables[i]);
        }
        smt.append("(push 1)\n(assert (distinct (implementation")
                .append(callArguments).append(") (contract")
                .append(callArguments).append(")))\n(check-sat)\n(pop 1)\n");
        smt.append("(push 1)\n(assert (distinct ")
                .append(MUTANT.get(function)).append(" (contract")
                .append(callArguments).append(")))\n(check-sat)\n(pop 1)\n");
        return smt.toString();
    }

    private static boolean simpleInt(Ast.TypeRef type) {
        return type != null && "int".equals(type.name())
                && type.arguments().isEmpty() && !type.inferArguments();
    }

    private static String lower(List<Ast.Stmt> statements, String continuation,
                                Map<String, String> arguments) {
        String result = continuation;
        for (int i = statements.size() - 1; i >= 0; i--) {
            Ast.Stmt s = statements.get(i);
            if (s instanceof Ast.ReturnStmt r) {
                require(i == statements.size() - 1, "unreachable statements after return");
                result = parameterName(r.value(), arguments);
            } else if (s instanceof Ast.IfStmt conditional) {
                require(conditional.branches().size() == 1,
                        "only single-condition if branches supported");
                Ast.IfBranch branch = conditional.branches().getFirst();
                String cond = comparison(branch.condition(), arguments);
                String yes = lower(branch.body(), result, arguments);
                String no = lower(conditional.elseBody(), result, arguments);
                require(yes != null && no != null,
                        "non-returning branch or unsupported fallthrough");
                result = "(ite " + cond + " " + yes + " " + no + ")";
            } else {
                throw new IllegalArgumentException("unsupported typed AST statement: "
                        + s.getClass().getSimpleName());
            }
        }
        return result;
    }

    private static String comparison(Ast.Expr condition, Map<String, String> args) {
        require(condition instanceof Ast.BinaryExpr, "condition must be a binary comparison");
        Ast.BinaryExpr binary = (Ast.BinaryExpr) condition;
        String op = COMPARISONS.get(binary.operator());
        require(op != null, "unsupported comparison operator");
        return "(" + op + " " + parameterName(binary.left(), args)
                + " " + parameterName(binary.right(), args) + ")";
    }

    private static String parameterName(Ast.Expr value, Map<String, String> args) {
        require(value instanceof Ast.NameExpr, "only direct parameter expressions supported");
        String name = ((Ast.NameExpr) value).name();
        String slot = args.get(name);
        require(slot != null, "unknown or shadowed parameter");
        return slot;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
