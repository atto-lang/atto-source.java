package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Narrow forward-port of the _blocking compiler admission contract from PR #522. */
final class BlockingSuffixAdmissionForwardPortTest {
    @Test
    void ordinaryCallableCannotImpersonateCooperativeBlockingCall() {
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc suspicious_blocking(): void { return; }
                    """)));
        assertTrue(denied.getMessage().contains("must declare suspends"),
                denied.getMessage());
    }

    @Test
    void ordinaryNonblockingFunctionRemainsAdmitted() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                    fnc legitimate(): void { return; }
                    """)));
    }
}
