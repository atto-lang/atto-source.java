package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pre-1.0 keyword separation: with=effect upper bound, using=scoped authority. */
final class WithUsingKeywordBoundaryAuditTest {
    private static String actor(String expression) {
        return "define actor Counter as\n"
                + " state { x: int = 0; }\n"
                + " read(): int with state.read { return " + expression + "; }\n"
                + "end\n";
    }

    @Test void bothKeywordsAreGloballyReservedAndNotArbitraryIdentifiers() {
        assertEquals(Token.Type.WITH, new Lexer("with").scan().getFirst().type());
        assertEquals(Token.Type.USING, new Lexer("using").scan().getFirst().type());
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc with(): int { return 1; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc using(): int { return 1; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc foo(): int { val int with = 1; return 1; }"));
    }

    @Test void usingStateOnlyInsideActorInstanceMethods() {
        String scoped = "using state.read(|s| -> { return s.x; })";
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc f(): int with state.read { return " + scoped + "; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define class Box as f(): int with state.read { return " + scoped + "; } end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define actor Counter as state { x: int = 0; } "
                + "static fnc f(): int with state.read { return " + scoped + "; } end"));
        Ast.Program valid = Parser.parse(actor(scoped));
        assertDoesNotThrow(() -> ActorStateContract.verify(valid));
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(valid));
    }

    @Test void repeatedAndMixedEffectClausesAreExplicitErrors() {
        String scoped = "using state.read(|s| -> { return s.x; })";
        for (String declaration : new String[] {
                "with state.read with state.write",
                "with state.read using state.write",
                "using state.read with state.write"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(actor(scoped).replace("with state.read", declaration)));
            assertTrue(error.getMessage().contains("duplicate function effect clause")
                    || error.getMessage().contains("using acquires scoped capabilities"),
                    "incorrect with/using diagnostic: " + error.getMessage());
        }
    }

    @Test void scopedLoanRejectsOwnershipCommandsOnActorState() {
        for (String op : new String[] { "take", "share", "copy", "borrow" }) {
            String source = actor("using state.read(|s| -> { return rt "
                    + op + "(s.x); })");
            assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(Parser.parse(source)),
                    "state-backed rt " + op + " must fail closed");
        }
    }

    @Test void optionalEffectBoundsStillCannotGrantReadWriteAuthority() {
        Ast.Program valid = Parser.parse("""
            define actor Counter as
              state { x: int = 0; }
              update(): int with state.write {
                return using state.write(critical |s| -> {
                  s.x = 2;
                  return 2;
                });
              }
            end
            """);
        assertDoesNotThrow(() -> ActorStateContract.verify(valid));
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(valid));
        Ast.Program illegal = Parser.parse(actor(
                "using state.read(|s| -> { s.x = 2; return s.x; })"));
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(illegal));
    }
}
