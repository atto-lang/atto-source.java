package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Java frontend/type/ownership analysis of guest .atto source.
 * This test does not claim a Bytes transport runtime, actor receive execution,
 * LLVM Native AOT, or a measured zero-allocation profile.
 */
final class BorrowedEnumCaseLoanTest {
    private static String fixture(String name) throws Exception {
        String path = "/atto/enum-pattern-alias-v1/" + name + ".atto";
        try (InputStream input = BorrowedEnumCaseLoanTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing guest fixture " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void bytesPatternUsesScopedImmutableLoansAndCopyScalarProjections() throws Exception {
        String guest = fixture("accept-borrowed-bytes-case");
        assertDoesNotThrow(() -> OwnershipChecker.check(TypeChecker.check(Parser.parse(guest))));
    }

    @Test
    void movingTheMessageWhileCaseBorrowIsLiveIsRejected() throws Exception {
        String guest = fixture("reject-borrowed-case-take-subject");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(TypeChecker.check(Parser.parse(guest))));
        assertTrue(failure.getMessage().contains("borrow"), failure.getMessage());
    }

    @Test
    void movingTheBorrowedBytesProjectionIsRejected() throws Exception {
        String guest = fixture("reject-borrowed-case-take-field");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(TypeChecker.check(Parser.parse(guest))));
        assertTrue(failure.getMessage().contains("borrow"), failure.getMessage());
    }

    @Test
    void caseBorrowCannotCrossCooperativeSuspension() throws Exception {
        String guest = fixture("reject-borrowed-case-suspend");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(TypeChecker.check(Parser.parse(guest))));
        assertTrue(failure.getMessage().contains("cooperate")
                        && failure.getMessage().contains("borrow"), failure.getMessage());
    }

    @Test
    void returningBorrowedPayloadDoesNotOutliveMatchArm() throws Exception {
        String guest = fixture("reject-borrowed-case-return");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(TypeChecker.check(Parser.parse(guest))));
        assertTrue(failure.getMessage().contains("borrowed enum case")
                        || failure.getMessage().contains("cannot return borrow"), failure.getMessage());
    }
}
