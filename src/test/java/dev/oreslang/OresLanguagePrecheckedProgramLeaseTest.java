package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OresLanguagePrecheckedProgramLeaseTest {
    private static final String SOURCE = "pub fnc main(): void { return; }";
    private static final String UNIT = "/tmp/prechecked-main.ores";

    @Test
    void leaseIsStrictlyOneShotUntilOwningScopeCloses() {
        Ast.Program program = Parser.parse(SOURCE);

        try (OresLanguage.PrecheckedProgramScope ignored =
                OresLanguage.openPrecheckedProgram(UNIT, SOURCE, program)) {
            assertSame(program, OresLanguage.consumePrecheckedProgram(UNIT, SOURCE));

            IllegalStateException second = assertThrows(
                    IllegalStateException.class,
                    () -> OresLanguage.consumePrecheckedProgram(UNIT, SOURCE));
            assertTrue(second.getMessage().contains("already consumed"));

            IllegalStateException nested = assertThrows(
                    IllegalStateException.class,
                    () -> OresLanguage.openPrecheckedProgram(UNIT, SOURCE, program));
            assertTrue(nested.getMessage().contains("nested"));
        }

        try (OresLanguage.PrecheckedProgramScope ignored =
                OresLanguage.openPrecheckedProgram(UNIT, SOURCE, program)) {
            assertSame(program, OresLanguage.consumePrecheckedProgram(UNIT, SOURCE));
        }
    }

    @Test
    void mismatchedRequestConsumesLeaseAndCannotBeRetried() {
        Ast.Program program = Parser.parse(SOURCE);

        try (OresLanguage.PrecheckedProgramScope ignored =
                OresLanguage.openPrecheckedProgram(UNIT, SOURCE, program)) {
            IllegalStateException mismatch = assertThrows(
                    IllegalStateException.class,
                    () -> OresLanguage.consumePrecheckedProgram(
                            UNIT, SOURCE + " // mismatch"));
            assertTrue(mismatch.getMessage().contains("does not match"));

            IllegalStateException retry = assertThrows(
                    IllegalStateException.class,
                    () -> OresLanguage.consumePrecheckedProgram(UNIT, SOURCE));
            assertTrue(retry.getMessage().contains("already consumed"));
        }
    }

    @Test
    void scopeMustCloseOnOwningThread() throws Exception {
        Ast.Program program = Parser.parse(SOURCE);
        OresLanguage.PrecheckedProgramScope scope =
                OresLanguage.openPrecheckedProgram(UNIT, SOURCE, program);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread other = Thread.ofPlatform().start(() -> {
            try {
                scope.close();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        other.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertTrue(failure.get().getMessage().contains("owning thread"));

        scope.close();
    }
}
