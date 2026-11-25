package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable front-end fixtures. These prove ONLY conservative compile-time
 * actor-state place admission, not field-level runtime RW grant lowering.
 */
final class ActorStateTypedProjectionOwnershipTest {
    private static String fixture(String name) throws Exception {
        String resource = "/ownership/actor-state/" + name;
        try (InputStream stream = ActorStateTypedProjectionOwnershipTest.class
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("mandatory .atto fixture absent: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void approved(String name) throws Exception {
        String source = fixture(name);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)),
                "valid typed actor path must survive the real type and ownership passes");
    }

    private static void rejectedByOwnership(String name, String diagnostic)
            throws Exception {
        String source = fixture(name);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(diagnostic),
                "ownership checker must reject the intended path: " + failure.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)),
                "full compiler must also reject the same unsafe .atto source");
    }

    @Test
    void literalIndexHasAStableTypedActorElementPath() throws Exception {
        approved("valid-stable-index-borrow.atto");
    }

    @Test
    void immutableConstLiteralIndexHasAStableActorElementPath() throws Exception {
        approved("valid-const-index-borrow.atto");
    }

    @Test
    void independentImmutableActorFieldLoansCanCoexistWithoutWrite() throws Exception {
        approved("valid-multiple-state-reads.atto");
    }

    @Test
    void nestedActorFieldBorrowTracksTypedProjection() throws Exception {
        approved("valid-nested-field-read-borrow.atto");
    }

    @Test
    void ordinaryNonActorBorrowRetainsLegacyDynamicIndexSupport() throws Exception {
        approved("valid-ordinary-dynamic-index-borrow.atto");
    }

    @Test
    void mutableLocalIndexCannotMintActorStateElementBorrow() throws Exception {
        rejectedByOwnership("reject-mutable-state-index-borrow.atto",
                "actor-state indexed borrow requires a nonnegative constant");
    }

    @Test
    void parameterIndexCannotMintActorStateElementBorrow() throws Exception {
        rejectedByOwnership("reject-parameter-state-index-borrow.atto",
                "actor-state indexed borrow requires a nonnegative constant");
    }

    @Test
    void negativeConstantIndexCannotMintActorStateElementBorrow() throws Exception {
        rejectedByOwnership("reject-negative-state-index-borrow.atto",
                "actor-state indexed borrow requires a nonnegative constant");
    }

    @Test
    void wholeActorOwnerReadLoanBlocksEvenSiblingStateWrites() throws Exception {
        rejectedByOwnership("reject-conflicting-actor-write-during-read.atto",
                "while immutably borrowed");
    }
}
