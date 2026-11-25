package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V0 shadow hook is emitted from the existing production ownership pass.
 * A trace is not a second checker and cannot authorize guest execution.
 */
final class BorrowSamePassShadowV0Test {
    private static final String SIMPLE = """
            define class Resource as
              pub val int id = 7;
            end
            fnc good(Resource resource): int {
              let Resource moved = rt take resource;
              return moved.id;
            }
            """;

    @Test
    void acceptedMoveIsObservedInExistingTraversal() {
        Ast.Program program = Parser.parse(SIMPLE);
        List<OwnershipChecker.BorrowShadowEventV0> events = new ArrayList<>();
        assertSame(program, OwnershipChecker.checkWithBorrowShadowV0(program, events::add));
        assertTrue(events.stream().anyMatch(event -> "MOVE".equals(event.kind())),
                "same-pass trace must observe an accepted affine move");
        assertTrue(events.stream().noneMatch(event -> event.sharedLoans() < 0));
    }

    @Test
    void aSinkFailurePreventsSuccessfulOwnershipCheck() {
        Ast.Program program = Parser.parse(SIMPLE);
        IllegalStateException marker = new IllegalStateException("shadow sink denied event");
        IllegalStateException raised = assertThrows(IllegalStateException.class,
                () -> OwnershipChecker.checkWithBorrowShadowV0(program, event -> {
                    if ("MOVE".equals(event.kind())) throw marker;
                }));
        assertSame(marker, raised);
    }

    @Test
    void missingSinkIsRejectedInsteadOfSilentlyDisablingRequestedAudit() {
        Ast.Program program = Parser.parse(SIMPLE);
        assertThrows(NullPointerException.class,
                () -> OwnershipChecker.checkWithBorrowShadowV0(program, null));
    }

    @Test
    void laterFunctionFailureDoesNotPublishEarlierSuccessfulMove() {
        String invalid = SIMPLE + """
                fnc bad(Resource resource): int {
                  let Resource moved = rt take resource;
                  return resource.id;
                }
                """;
        Ast.Program program = Parser.parse(invalid);
        List<OwnershipChecker.BorrowShadowEventV0> events = new ArrayList<>();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.checkWithBorrowShadowV0(program, events::add));
        assertTrue(error.getMessage().toLowerCase().contains("moved"),
                "must fail in semantic ownership pass: " + error.getMessage());
        assertTrue(events.isEmpty(),
                "no accepted trace must escape a rejected later function");
    }

    @Test
    void successfulCompleteProgramPublishesSamePassMoves() {
        String accepted = SIMPLE + """
                fnc also_good(Resource resource): int {
                  let Resource moved = rt take resource;
                  return moved.id;
                }
                """;
        Ast.Program program = Parser.parse(accepted);
        List<OwnershipChecker.BorrowShadowEventV0> events = new ArrayList<>();
        assertSame(program, OwnershipChecker.checkWithBorrowShadowV0(program, events::add));
        assertTrue(events.stream().filter(event -> "MOVE".equals(event.kind())).count() >= 2,
                "events must publish after checking all functions");
    }
}
