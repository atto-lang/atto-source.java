package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Real .atto AST + ownership pass, without a source-free mock compiler. */
final class ActorStateBorrowReturnFenceTest {
    private static String fixture(String name) throws Exception {
        String resource = "/ownership/actor-state/" + name;
        try (InputStream source = ActorStateBorrowReturnFenceTest.class
                .getResourceAsStream(resource)) {
            if (source == null) throw new AssertionError("missing .atto fixture: " + resource);
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void rejectedAtOwnershipPass(String fixtureName) throws Exception {
        String source = fixture(fixtureName);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)),
                "the ownership checker must itself reject this actor-state escape");
        // Projected borrows are rejected at the earlier field-path admission
        // gate. Whole-self has no projection and MUST still rely on the
        // independent return-lifetime fence; preserve mutation sensitivity.
        String message = failure.getMessage();
        if (fixtureName.equals("reject-return-self-borrow.atto")) {
            assertTrue(message.contains(
                            "actor-owned state borrow cannot escape its mailbox turn"),
                    "whole actor self must fail the returned-borrow fence: " + message);
        } else {
            assertTrue(message.contains(
                            "actor-owned state borrow cannot escape its mailbox turn")
                            || message.contains(
                                    "actor state field/index borrow requires compiler-lowered"),
                    "projected actor state must fail before return escape: " + message);
        }

        // End-to-end compiler admission must independently reject the same
        // source, even if the type checker rejects earlier on another rule.
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void directlyReturnedActorFieldBorrowCannotEscape() throws Exception {
        rejectedAtOwnershipPass("reject-return-direct-field-borrow.atto");
    }

    @Test
    void LocalAliasDoesNotExtendActorStateLifetime() throws Exception {
        rejectedAtOwnershipPass("reject-return-aliased-field-borrow.atto");
    }

    @Test
    void nestedFieldProjectionCannotEscapeByReturn() throws Exception {
        rejectedAtOwnershipPass("reject-return-nested-field-borrow.atto");
    }

    @Test
    void indexedActorOwnedFieldCannotEscapeByReturn() throws Exception {
        rejectedAtOwnershipPass("reject-return-indexed-field-borrow.atto");
    }

    @Test
    void wholeActorSelfCannotEscapeViaBorrowedReturn() throws Exception {
        rejectedAtOwnershipPass("reject-return-self-borrow.atto");
    }

    @Test
    void safeCopySnapshotFromActorStateRemainsAccepted() throws Exception {
        String source = fixture("valid-snapshot-return.atto");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void unrelatedIncomingBorrowCanStillBeReturnedByFunction() throws Exception {
        String source = fixture("valid-incoming-borrow-return.atto");
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(source)),
                "actor self must be distinguished from ordinary borrowed parameters");
    }
}
