package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Breaking pre-1.0 state contract: access modes grant authority, with bounds effects. */
final class CriticalReadWriteUsingContractTest {
    private static String program(String mode, String effects, String body) {
        return "define actor Counter as\n"
                + " state { v: int = 0; }\n"
                + " pub get(): int " + effects + " { return self.handle_state("
                + mode + " |s| -> { " + body + " }); }\n"
                + "end\n";
    }

    private static Ast.LambdaExpr callback(String source) {
        Ast.ClassDecl klass = (Ast.ClassDecl)
                Parser.parse(source).modules().getFirst().declarations().getFirst();
        Ast.CallExpr call = (Ast.CallExpr)
                ((Ast.ReturnStmt) klass.methods().getFirst().body().getFirst()).value();
        return (Ast.LambdaExpr) call.arguments().getFirst();
    }

    @Test void modesAreDistinctInTheAstAndReadIsEffectBounded() {
        String read = program("critical read", "with state.read", "return s.v;");
        assertEquals(Ast.CriticalMode.READ, callback(read).criticalMode());
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(read)));
        assertEquals(Ast.CriticalMode.WRITE,
                callback(program("critical write", "with state.read", "return s.v;")).criticalMode());
    }

    @Test void wideUsingCannotUpgradeReadOnlyAuthority() {
        String forbidden = program("critical read", "with state.read, state.write",
                "s.v = 4; return s.v;");
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(forbidden)));
        assertThrows(IllegalArgumentException.class,
                () -> DeclaredEffectVerifier.verify(Parser.parse(forbidden)));
    }

    @Test void writingNeedsWriteEffectButNotAnImplicitRead() {
        String write = program("critical write", "with state.write",
                "s.v = 4; return 4;");
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(write)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(
                        program("critical write", "with state.read",
                                "s.v = 4; return 4;"))));
    }

    @Test void incrementNeedsReadAndWriteEffects() {
        String increment = "s.v = s.v + 1; return s.v;";
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                program("critical write", "with state.read, state.write", increment))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(
                        program("critical write", "with state.write", increment))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(
                        program("critical write", "with state.read", increment))));
    }

    @Test void bareCriticalRequestsWriteAndImplicitCallbackIsReadonly() {
        // 'critical' is an exclusive write-capable loan, although the
        // callback may legitimately perform only a read.
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse(
                program("critical", "with state.read", "return s.v;"))));
        // An unmarked callback is a permitted immutable read scope.
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                program("", "with state.read", "return s.v;"))));
        // Neither form permits mutation without appropriate scoped authority.
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(
                        program("", "with state.read, state.write",
                                "s.v = 2; return s.v;"))));
    }

    @Test void stateNamespaceRemainsCompletelySealed() {
        assertThrows(IllegalArgumentException.class, () ->
                ActorStateContract.verify(Parser.parse(
                        "define actor A as state { v: int = 0; } "
                        + "get(): int using state.read { return self.state.v; } end")));
    }

    @Test void readToWriteUpgradeRejected() {
        String nested = program("critical read", "with state.read, state.write",
                "self.handle_state(critical write |w| -> { w.v = 3; }); return s.v;");
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(nested)));
    }

    @Test void nonStateCriticalRemainsDistinctFromStateModes() {
        // Ordinary critical is a separate scheduling primitive; the parser
        // preserves it as EXCLUSIVE, not as a state READ or WRITE capability.
        assertEquals(Ast.CriticalMode.EXCLUSIVE,
                callback(program("critical", "with state.read", "return s.v;")).criticalMode());
    }
}
