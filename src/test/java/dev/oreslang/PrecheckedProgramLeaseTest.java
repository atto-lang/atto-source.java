package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PrecheckedProgramLeaseTest {
    private static final String SOURCE = """
            pub fnc main(): void {
                return;
            }
            """;

    @Test
    void exactLeaseIsOneShotAndNestedLeaseIsRejected() {
        Ast.Program program = OresCompiler.parseAndTypeCheck(SOURCE);
        Source source = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, SOURCE, "lease.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .buildLiteral());

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build();
             OresLanguage.PrecheckedProgramScope ignored =
                     OresLanguage.openPrecheckedProgram(
                             "lease.ores", SOURCE, program)) {
            IllegalStateException nested = assertThrows(
                    IllegalStateException.class,
                    () -> OresLanguage.openPrecheckedProgram(
                            "nested.ores", SOURCE, program));
            assertTrue(nested.getMessage().contains("nested prechecked"));

            assertDoesNotThrow(() -> context.parse(source));
        }

        // The consumed/closed lease must leave no thread-local residue.
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build();
             OresLanguage.PrecheckedProgramScope ignored =
                     OresLanguage.openPrecheckedProgram(
                             "lease.ores", SOURCE, program)) {
            assertDoesNotThrow(() -> context.parse(source));
        }
    }

    @Test
    void leaseFailsClosedOnIdentityOrContentMismatchAndDoesNotLeak() {
        Ast.Program program = OresCompiler.parseAndTypeCheck(SOURCE);

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            Source wrongIdentity = assertDoesNotThrow(() ->
                    Source.newBuilder(OresLanguage.ID, SOURCE, "wrong.ores")
                            .mimeType(OresLanguage.MIME_TYPE)
                            .buildLiteral());
            try (OresLanguage.PrecheckedProgramScope ignored =
                    OresLanguage.openPrecheckedProgram(
                            "expected.ores", SOURCE, program)) {
                RuntimeException failure = assertThrows(
                        RuntimeException.class,
                        () -> context.parse(wrongIdentity));
                assertTrue(failure.getMessage().contains(
                        "does not match parsed source identity/content"));
            }

            String changed = SOURCE + "\n";
            Source wrongContent = assertDoesNotThrow(() ->
                    Source.newBuilder(OresLanguage.ID, changed, "lease.ores")
                            .mimeType(OresLanguage.MIME_TYPE)
                            .buildLiteral());
            try (OresLanguage.PrecheckedProgramScope ignored =
                    OresLanguage.openPrecheckedProgram(
                            "lease.ores", SOURCE, program)) {
                RuntimeException failure = assertThrows(
                        RuntimeException.class,
                        () -> context.parse(wrongContent));
                assertTrue(failure.getMessage().contains(
                        "does not match parsed source identity/content"));
            }

            Source ordinary = assertDoesNotThrow(() ->
                    Source.newBuilder(OresLanguage.ID, SOURCE, "ordinary.ores")
                            .mimeType(OresLanguage.MIME_TYPE)
                            .buildLiteral());
            assertDoesNotThrow(() -> context.parse(ordinary));
        }
    }
}
