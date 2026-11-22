package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class WithExpressionGuestTest {

    private static String readGuest(String filename) {
        try {
            return Files.readString(Path.of("src", "test", "resources", "with", filename),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String run(String guest) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "with-ownership.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException rejects(String guest) {
        return assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

    @Test
    void copyUpdatePreservesOriginalAndEvaluatesOriginalField() throws Exception {
        assertEquals("101120", run(readGuest("record-copy-preserves-original.atto")));
    }

    @Test
    void plainCopyValueAndChainedUpdateExecuteAsExpressions() throws Exception {
        assertEquals("104237", run(readGuest("record-plain-chained.atto")));
    }

    @Test
    void explicitTakeConstructsNewValueAndCannotReadMovedOriginal() throws Exception {
        assertEquals("912", run(readGuest("record-take.atto")));
        IllegalArgumentException failure = rejects(readGuest("record-take-use-after-move.atto"));
        assertTrue(failure.getMessage().contains("moved"), failure::getMessage);
    }

    @Test
    void unknownFieldAndWrongReplacementTypeReject() {
        IllegalArgumentException unknown = rejects(readGuest("record-unknown-field.atto"));
        assertTrue(unknown.getMessage().contains("unknown with field"), unknown::getMessage);

        IllegalArgumentException wrongType = rejects(readGuest("record-wrong-type.atto"));
        assertTrue(wrongType.getMessage().contains("with field"), wrongType::getMessage);
    }

    @Test
    void rejectsBorrowAndNonRecordAndSideEffectfulRhs() {
        IllegalArgumentException borrowed = rejects(readGuest("record-borrow-rejected.atto"));
        assertTrue(borrowed.getMessage().contains("synchronous, effect-safe"),
                borrowed::getMessage);

        IllegalArgumentException struct = rejects(readGuest("struct-rejected.atto"));
        assertTrue(struct.getMessage().contains("readonly structural record"),
                struct::getMessage);

        IllegalArgumentException call = rejects(readGuest("record-effectful-rhs-rejected.atto"));
        assertTrue(call.getMessage().contains("non-suspending, effect-safe"),
                call::getMessage);
    }

    @Test
    void immutableClassSelfCopyReconstructsNewObject() throws Exception {
        assertEquals("101120", run(readGuest("class-self-copy.atto")));
    }

    @Test
    void explicitTakeOfImmutableClassInvalidatesOriginal() throws Exception {
        assertEquals("4220", run(readGuest("class-take.atto")));
        IllegalArgumentException failure = rejects(readGuest("class-take-use-after-move.atto"));
        assertTrue(failure.getMessage().contains("moved"), failure::getMessage);
    }

    @Test
    void nominalUpdatesRejectPlainOrMutableOrBorrowedClass() {
        IllegalArgumentException plain = rejects(readGuest("class-implicit-copy-rejected.atto"));
        assertTrue(plain.getMessage().contains("rt copy or rt take"), plain::getMessage);

        IllegalArgumentException mutable = rejects(readGuest("class-mutable-copy-rejected.atto"));
        assertTrue(mutable.getMessage().contains("compiler-certified immutable"),
                mutable::getMessage);
    }

    @Test
    void readonlyRecordUpdateUsesOriginalRhsValuesInSourceOrder() throws Exception {
        assertEquals("6115", run(readGuest("record-original-rhs-order.atto")));
    }

    @Test
    void effectfulOrSuspendableWithReceiverFailsClosed() {
        for (String file : java.util.List.of(
                "with-receiver-call-rejected.atto",
                "with-receiver-await-rejected.atto")) {
            IllegalArgumentException failure = rejects(readGuest(file));
            assertTrue(failure.getMessage().contains("with receiver must be a synchronous"),
                    () -> file + ": " + failure.getMessage());
        }
    }

    @Test
    void nominalCopyDoesNotLaunderAliasesOrPrivateFields() {
        assertNotNull(rejects(readGuest("with-scalar-alias-rejected.atto")));
        IllegalArgumentException privateField =
                rejects(readGuest("with-private-field-rejected.atto"));
        assertTrue(privateField.getMessage().contains("private"),
                privateField::getMessage);
    }

    @Test
    void borrowedSelfAndLiveLoansCannotBeConsumedByWith() {
        for (String file : java.util.List.of(
                "with-take-self-rejected.atto",
                "with-borrow-live-rejected.atto")) {
            IllegalArgumentException failure = rejects(readGuest(file));
            assertTrue(failure.getMessage().contains("borrow")
                    || failure.getMessage().contains("self")
                    || failure.getMessage().contains("move"),
                    () -> file + ": " + failure.getMessage());
        }
    }

    @Test
    void inheritedNominalLayoutsCannotUseDerivedCopyShortcut() {
        IllegalArgumentException failure =
                rejects(readGuest("with-inherited-class-rejected.atto"));
        assertTrue(failure.getMessage().contains("immutable scalar-field class")
                        || failure.getMessage().contains("copy contract"),
                failure::getMessage);
    }

    @Test
    void rejectsMoveOnlyRecordContentWithoutCopyContract() {
        IllegalArgumentException failure = rejects(readGuest("record-move-only-field-rejected.atto"));
        assertTrue(failure.getMessage().contains("rt copy")
                || failure.getMessage().contains("with v1"),
                failure::getMessage);
    }
}
