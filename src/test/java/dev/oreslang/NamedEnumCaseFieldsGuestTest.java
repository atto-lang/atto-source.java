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

/** Real AttoLang guest tests, not a source-free alias-emulation model. */
final class NamedEnumCaseFieldsGuestTest {
    private static String fixture(String name) throws Exception {
        String path = "/atto/enum-pattern-alias-v1/" + name + ".atto";
        try (InputStream stream = NamedEnumCaseFieldsGuestTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing .atto guest fixture " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void namedFieldsAndWholeCaseAliasRunWithGuestEvaluator() throws Exception {
        String program = fixture("accept-named-copy-case-alias");
        OwnershipChecker.check(TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, program, "named-enum-case.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("42\n7\n0\n8",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void unknownNamedCaseFieldFailsDuringStaticChecking() throws Exception {
        String source = fixture("reject-unknown-named-field");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("unknown enum payload field"), failure.getMessage());
    }

    @Test
    void duplicateCaseFieldAndAliasCollisionFailDuringParsing() throws Exception {
        for (String name : new String[] {
                "reject-duplicate-named-field", "reject-colliding-named-case-alias" }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(fixture(name)), name);
            assertTrue(failure.getMessage().contains("duplicate enum pattern field")
                        || failure.getMessage().contains("collides with field binding"),
                    name + ": " + failure.getMessage());
        }
    }

    @Test
    void fieldRenameIsDeferredInsteadOfBeingSilentlyInterpretedAsCast() throws Exception {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("reject-field-rename-v1")));
        assertTrue(failure.getMessage().contains("field-level renaming"), failure.getMessage());
    }

    @Test
    void nonCopyBytesCannotBeTakenThroughBorrowedCaseView() throws Exception {
        String source = fixture("reject-unsafe-bytes-case-alias");
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(TypeChecker.check(Parser.parse(source))));
    }

    @Test
    void duplicatedNamedCaseArmIsRejectedAsUnreachable() {
        String source = """
                define enum WorkerMsg { Run { job_id: int }, Stop }
                fnc bad(WorkerMsg msg): int {
                    return match msg over {
                        on Run { job_id } { return job_id; }
                        on Run { job_id } as r { return r.job_id; }
                        on Stop { return 0; }
                    };
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("unreachable match arm"),
                failure.getMessage());
    }

    @Test
    void unknownFieldOnNarrowedWholeCaseAliasIsRejected() {
        String source = """
                define enum WorkerMsg { Run { job_id: int }, Stop }
                fnc bad(WorkerMsg msg): int {
                    return match msg over {
                        on Run { job_id } as r { return r.nonexistent; }
                        on Stop { return 0; }
                    };
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("unknown narrowed enum field"),
                failure.getMessage());
    }

    @Test
    void shorthandWholeCopyPayloadCaseAliasRunsAsGuest() throws Exception {
        String program = fixture("accept-short-copy-case-alias");
        OwnershipChecker.check(TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, program, "short-copy-case.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("99", output.toString(StandardCharsets.UTF_8)
                .trim().replace("\r", ""));
    }
}
