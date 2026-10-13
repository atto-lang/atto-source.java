package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Scoped authority is separate from the `with` effect upper bound. */
final class WithEffectsAndScopedUsingStateTest {
    private static String actor(String effects, String body) {
        return "define actor Counter as\n"
                + "  state { x: int = 0; }\n"
                + "  pub run(): int " + effects + " { return " + body + "; }\n"
                + "end\n";
    }

    private static void accepts(String source) {
        Ast.Program ast = Parser.parse(source);
        ActorStateContract.verify(ast);
        TypeChecker.checkTypes(ast);
    }

    @Test void withDeclaresEffectsAndUsingAcquiresReadCapability() {
        String source = actor("with state.read",
                "using state.read(|s| -> { return s.x; })");
        accepts(source);
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
        Ast.MethodDecl method = klass.methods().getFirst();
        assertTrue(method.annotations().stream()
                .anyMatch(a -> a.name().equals("$with$")
                    && a.arguments().getFirst().name().equals("state.read")));
        Ast.CallExpr call = (Ast.CallExpr)
                ((Ast.ReturnStmt) method.body().getFirst()).value();
        Ast.LambdaExpr callback = (Ast.LambdaExpr) call.arguments().getFirst();
        assertEquals(Ast.CriticalMode.NONE, callback.criticalMode(),
                "unmarked using-read lambda is normalized to READ by the sealed verifier");
        assertInstanceOf(Ast.MemberExpr.class, call.callee());
        assertEquals("handle_state", ((Ast.MemberExpr) call.callee()).member());
    }

    @Test void writeNeedsCriticalAndMatchingEffectBound() {
        accepts(actor("with state.write",
                "using state.write(critical |s| -> { s.x = 7; return 7; })"));
        accepts(actor("with state.read, state.write",
                "using state.write(critical |s| -> { s.x = s.x + 1; return s.x; })"));
        assertThrows(IllegalArgumentException.class,
                () -> accepts(actor("with state.read",
                    "using state.write(critical |s| -> { s.x = 7; return 7; })")));
        assertThrows(IllegalArgumentException.class,
                () -> accepts(actor("with state.write",
                    "using state.write(critical |s| -> { s.x = s.x + 1; return s.x; })")));
    }

    @Test void effectsNeverGrantReadScopeWriteAuthority() {
        assertThrows(IllegalArgumentException.class,
                () -> accepts(actor("with state.read, state.write",
                    "using state.read(|s| -> { s.x = 10; return s.x; })")));
        assertThrows(IllegalArgumentException.class,
                () -> accepts(actor("with state.read",
                    "using state.read(|s| -> { return s; })")));
    }

    @Test void legacyUsingSignatureIsRejectedAndBlockEffectIsAccepted() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(actor("using state.read",
                    "using state.read(|s| -> s.x)")));
        assertDoesNotThrow(() -> Parser.parse(
                "fnc work(): void with io, block { return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(actor("with state.read using state.write",
                    "using state.read(|s| -> s.x)")));
    }

    @Test void malformedAndForgedCapabilitiesFailBeforeExecution() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(actor("with state.read",
                    "using state.write(|s| -> { return s.x; })")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(actor("with state.read",
                    "using state.read(critical |s| -> { return s.x; })")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(actor("with state.read",
                    "using state.take(|s| -> { return s.x; })")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(actor("with state.read, state.read",
                    "using state.read(|s| -> { return s.x; })")));
        assertThrows(IllegalArgumentException.class,
                () -> accepts(actor("with state.read", "self.state.x")));
    }
}
