package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Negative safety checks for both direct handle_state and lowered using scopes.
 * 'with' is an effect bound; it never extends a live state loan's lifetime.
 */
final class WithUsingLoanSuspensionAuditTest {
    private static String actor(String expression, String effects) {
        return "define actor Counter as\n"
                + "state { x: int = 0; }\n"
                + "read(): int " + effects + " { return " + expression + "; }\n"
                + "end\n";
    }

    private static void accepted(String expression, String effects) {
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse(actor(expression, effects))));
    }

    private static void rejected(String expression, String effects) {
        var parsed = Parser.parse(actor(expression, effects));
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(parsed));
    }

    @Test void unmarkedHandleStateCallbackIsReadOnly() {
        accepted("self.handle_state(|s| -> { return s.x; })", "with state.read");
        rejected("self.handle_state(|s| -> { s.x = 1; return s.x; })",
                "with state.read, state.write");
    }

    @Test void bothLexicalSpellingsRejectLiveLoanSuspension() {
        rejected("self.handle_state(|s| -> { await task(); return s.x; })",
                "with state.read, suspend");
        rejected("using state.read(|s| -> { await task(); return s.x; })",
                "with state.read, suspend");
        rejected("using state.write(critical |s| -> { await task(); return s.x; })",
                "with state.read, state.write, suspend");
    }

    @Test void stateLoansCannotBeNestedEvenWhenBothAreReads() {
        rejected("using state.read(|s| -> { return using state.read(|t| -> { return t.x; }); })",
                "with state.read");
    }

    @Test void actorsWithoutStateStillRejectForgedStateMembers() {
        String withoutState = "define actor Empty as "
                + "read(): int with state.read { return self.state.x; } end";
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(withoutState)));
    }

    @Test void storageFreeActorsCannotForgeStateCapabilitiesOrIntrinsic() {
        var noState = Parser.parse("define actor Empty as "
                + "read(): int with state.read { "
                + "return self.handle_state(|s| -> { return 1; }); "
                + "} end");
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(noState));
        var reserved = Parser.parse("define actor Empty as "
                + "handle_state(): int { return 1; } end");
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(reserved));
    }
}
