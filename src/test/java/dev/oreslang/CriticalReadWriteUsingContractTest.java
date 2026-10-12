package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** State capabilities are acquired by using; with is only the effect bound. */
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

    @Test void wideWithCannotUpgradeReadOnlyAuthority() {
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

    @Test void plainCallbacksReadAndCriticalCallbacksWriteWithoutUpgradingWith() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                program("", "with state.read", "return s.v;"))));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                program("critical", "with state.read, state.write",
                        "s.v = 3; return s.v;"))));
        // A plain callback stays readonly even with an effect declaration
        // that permits writes elsewhere in the method.
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(
                        program("", "with state.read, state.write",
                                "s.v = 3; return s.v;"))));
    }

    @Test void stateNamespaceRemainsCompletelySealed() {
        assertThrows(IllegalArgumentException.class, () ->
                ActorStateContract.verify(Parser.parse(
                        "define actor A as state { v: int = 0; } "
                        + "get(): int with state.read { return self.state.v; } end")));
    }

    @Test void readToWriteUpgradeRejected() {
        String nested = program("critical read", "with state.read, state.write",
                "self.handle_state(critical write |w| -> { w.v = 3; }); return s.v;");
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(nested)));
    }

    @Test void usingIsScopedAcquisitionAndWithIsEffectsOnly() {
        String read = """
                define actor Counter as
                    state { v: int = 4; }
                    get(): int with state.read {
                        return using state.read(|s| -> s.v);
                    }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(read)));
        String write = """
                define actor Counter as
                    state { v: int = 4; }
                    set(): int with state.write {
                        return using state.write(critical |s| -> { s.v = 5; return 5; });
                    }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(write)));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(read.replace("with state.read", "using state.read")));
    }

    @Test void scopedUsingCannotUpgradeOrOmitCriticalWriteAuthority() {
        String read = """
                define actor Counter as
                    state { v: int = 0; }
                    bad(): int with state.read, state.write {
                        return using state.read(critical |s| -> { s.v = 3; return s.v; });
                    }
                end
                """;
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(read));
        String unmarkedWrite = read.replace("using state.read(critical |s|",
                "using state.write(|s|");
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(unmarkedWrite));
    }

    @Test void nonStateCriticalRemainsDistinctFromStateModes() {
        // Ordinary critical is a separate scheduling primitive; the parser
        // preserves it as EXCLUSIVE, not as a state READ or WRITE capability.
        assertEquals(Ast.CriticalMode.EXCLUSIVE,
                callback(program("critical", "with state.read", "return s.v;")).criticalMode());
    }
}
