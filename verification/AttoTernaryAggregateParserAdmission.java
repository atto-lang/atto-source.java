import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

/** Executable production-parser probe, independent of runtime and JUnit. */
public final class AttoTernaryAggregateParserAdmission {
    private static int checks;
    private static Ast.Expr expression(String source) {
        var program = Parser.parseStrictAtto("fnc f(bool c): void { val v = " + source + "; return; }");
        var function = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        return ((Ast.BindingStmt) function.body().getFirst()).initializer();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void kind(String source, Ast.ObjectKind left, Ast.ObjectKind right) {
        var conditional = (Ast.ConditionalExpr) expression(source);
        require(((Ast.ObjectExpr) conditional.whenTrue()).kind() == left, source + " left kind");
        require(((Ast.ObjectExpr) conditional.whenFalse()).kind() == right, source + " right kind");
    }
    private static void reject(String source, String diagnostic) {
        try {
            expression(source);
            throw new AssertionError("accepted " + source);
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains(diagnostic), source + ": " + expected.getMessage());
        }
    }
    public static void main(String[] args) {
        var struct = Ast.ObjectKind.INFERRED_STRUCT;
        var record = Ast.ObjectKind.RECORD;
        var explicit = Ast.ObjectKind.EXPLICIT_STRUCT;
        kind("c ? struct{status: 42} : struct{status: 7}", struct, struct);
        kind("c ? Record.of(status: 42) : Record.of(status: 7)", record, record);
        kind("c ? struct{} : struct{}", struct, struct);
        kind("c ? Record.of() : Record.of()", record, record);
        kind("c ? { return struct{status: 42}; } : struct{status: 7}", struct, struct);
        kind("c ? Record.of(status: 7) : { ^ Record.of(status: 42); }", record, record);
        kind("c ? struct{status: int}{status: 42} : struct{status: int}{status: 7}", explicit, explicit);
        var nested = (Ast.ConditionalExpr) expression("c ? Record.of(status: false ? 7 : 42) : Record.of(status: 0)");
        require(((Ast.ObjectExpr) nested.whenTrue()).fields().getFirst().value() instanceof Ast.ConditionalExpr, "inner colon");
        var scalar = (Ast.ConditionalExpr) expression("c ? { return 42; } : { ^ 7; }");
        require(scalar.whenTrue() instanceof Ast.LiteralExpr && scalar.whenFalse() instanceof Ast.LiteralExpr, "arm result is not function return");
        var assoc = (Ast.ConditionalExpr) expression("c ? 1 : c ? 2 : 3");
        require(assoc.whenFalse() instanceof Ast.ConditionalExpr, "right associativity");
        reject("c ? {} : {}", "E_VALUE_ARM");
        reject("c ? 42 : {}", "E_VALUE_ARM");
        reject("c ? {status: 42} : Record.of(status: 7)", "E_VALUE_ARM");
        reject("c ? Record.of(status: 7) : {status: 42}", "E_VALUE_ARM");
        reject("c ? {\"status\" => 42} : Record.of(status: 7)", "E_VALUE_ARM");
        reject("c ? {42} : 7", "E_VALUE_ARM");
        reject("c ? { val x = 42; return x; } : 7", "E_VALUE_ARM");
        reject("c ? struct{status: 1, status: 2} : struct{status: 3}", "duplicate");
        reject("c ? Record.of(status: 1, status: 2) : Record.of(status: 3)", "duplicate");
        System.out.println("TERNARY_AGGREGATE_PARSER_CHECKS=" + checks);
    }
}
