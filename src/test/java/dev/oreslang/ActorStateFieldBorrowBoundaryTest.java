package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real parser and compiler ownership negatives for actor-owned projections.
 * Expected failures are compiler diagnostics, not a simulated runtime model.
 */
final class ActorStateFieldBorrowBoundaryTest {
    private static String atto(String name) throws Exception {
        String path = "/ownership/actor-state/" + name;
        try (InputStream stream =
                ActorStateFieldBorrowBoundaryTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new AssertionError("mandatory .atto fixture not found: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void deniesUnloweredFieldLoan(String name) throws Exception {
        String source = atto(name);
        // Assert the actual ownership pass is responsible for this rejection.
        var ownerError = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(ownerError.getMessage().contains(
                        "actor state field/index borrow requires compiler-lowered"),
                ownerError.getMessage());
        // And the normal compiler entrypoint must not bypass that pass.
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void persistentActorFieldBorrowNeedsPhysicalRegionLoan() throws Exception {
        deniesUnloweredFieldLoan("reject-stored-borrow.atto");
    }

    @Test
    void transientActorFieldBorrowCannotBypassMissingPhysicalRegionLoan()
            throws Exception {
        deniesUnloweredFieldLoan("reject-transient-borrow.atto");
    }

    @Test
    void nestedArrayProjectionCannotManufactureUnsafeFieldLoan()
            throws Exception {
        deniesUnloweredFieldLoan("reject-index-projection-borrow.atto");
    }

    @Test
    void legacyUnaryProjectedBorrowCannotBypassActorRegionLoanGate() {
        // Legacy .ores syntax remains parsed, but is never an escape hatch
        // around the canonical .atto physical field-loan requirement.
        String legacy = """
                define actor Ledger as
                  state {
                    let List<int> entries;
                  }
                  receive(ActorMail<String> mail): void {
                    val borrow List<int> alias = &self.entries;
                    self.end();
                    return;
                  }
                end
                """;
        var rejection = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(legacy)));
        assertTrue(rejection.getMessage().contains(
                        "actor state field/index borrow requires compiler-lowered"),
                rejection.getMessage());
    }

    @Test
    void primitiveI64RuntimeBackingDoesNotAuthorizeActorFieldBorrow()
            throws Exception {
        deniesUnloweredFieldLoan("reject-borrow-managed-i64.atto");
    }

    @Test
    void nestedActorFieldTakeMustRejectBeforeBackendLowering() throws Exception {
        String source = atto("reject-nested-take-field.atto");
        var rejection = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(rejection.getMessage().contains(
                        "rt take cannot transfer actor-owned state"),
                rejection.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void ordinaryCopyScalarReadRemainsAcceptedByOwnershipChecker()
            throws Exception {
        assertDoesNotThrow(() ->
                TypeChecker.check(Parser.parse(atto("valid-field-copy-snapshot.atto"))));
    }

    @Test
    void ordinaryClassFieldAndIndexBorrowStillPassesCompiler() {
        // Existing nonactor projections have a root-tracked lexical loan.
        // Actor-state rejection must not turn off that independently proven
        // and already exercised language feature.
        String ordinary = """
                define class Item as
                  pub val int number;
                end

                define class Holder as
                  pub let mut List<Item> items;
                end

                fnc inspect(borrow Item item): int {
                  return item.number;
                }

                fnc valueAt(borrow Holder holder, int i): int {
                  val borrow Item view = rt borrow holder.items[i];
                  return inspect(view);
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(ordinary)));
    }

    @Test
    void actualGraalGuestExecutesActorFieldCopySnapshot() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID,
                atto("valid-field-copy-snapshot.atto"),
                "valid-field-copy-snapshot.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("5", output.toString(StandardCharsets.UTF_8),
                "an immutable scalar snapshot must not require a borrowed state pointer");
    }
}
