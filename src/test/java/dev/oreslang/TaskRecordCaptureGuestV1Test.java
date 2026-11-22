package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Java compiler admission and real Graal guest canary for independent
 * Task captures. No reflection into an isolated trait helper is used here.
 *
 * A type-only READONLY record is a view, not a proven frozen backing store.
 * This test does not claim general region/NLL or full AOT lowering.
 */
final class TaskRecordCaptureGuestV1Test {
    private static final Path FIXTURES =
            Path.of("tests/fixtures/task-record-capture-v1");

    private static String source(String name) throws Exception {
        assertTrue(name.matches("[a-z0-9-]+\\.atto"), "fixed fixture file name required");
        Path fixture = FIXTURES.resolve(name);
        assertTrue(Files.isRegularFile(fixture) && !Files.isSymbolicLink(fixture),
                "missing or symlinked source fixture " + fixture);
        return Files.readString(fixture, StandardCharsets.UTF_8);
    }

    @Test
    void readonlyRecordRejectsAtCompilerOwnershipBoundaryNotParser() throws Exception {
        String program = source("readonly-record-task-reject.atto");
        assertDoesNotThrow(() -> Parser.parse(program),
                "the fixture must reach semantic ownership checking");
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        String message = rejected.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("independent task capture 'view'")
                        && message.contains("non-Send execution capability"),
                "a parser, missing type, or unsupported syntax error is not a borrow proof: "
                        + message);
    }

    @Test
    void ownedStructPositiveStillHasCompilerTaskCaptureAdmission() throws Exception {
        String program = source("owned-struct-task-accept.atto");
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(Ast.CheckedTaskCaptures.requireProgramEpoch(checked) > 0,
                "admitted Task must have a successful whole-program ownership epoch");
    }

    @Test
    void scalarCaptureRunsInActualGraalGuest() throws Exception {
        String program = source("scalar-copy-task-guest.atto");
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(Ast.CheckedTaskCaptures.requireProgramEpoch(checked) > 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(true).out(output).build()) {
            context.eval(Source.newBuilder("ores", program,
                    "scalar-copy-task-guest.atto").build());
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8),
                "positive guest proof must actually execute, not just parse");
    }
}
