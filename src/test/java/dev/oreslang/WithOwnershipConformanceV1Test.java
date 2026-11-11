package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Guest-executed, canonical .atto fixtures mirrored from conformance draft #693.
 *  Java tests must be run on this exact PR head before claiming this matrix PASS. */
final class WithOwnershipConformanceV1Test {
    private static String guest(String id) throws Exception {
        return Files.readString(Path.of("fixtures", "with-ownership-v1", id + ".atto"),
                StandardCharsets.UTF_8);
    }
    private static String run(String id) throws Exception {
        var source = Source.newBuilder(OresLanguage.ID, guest(id), id + ".atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        var output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
    @Test
    void canonicalPositiveGuestCases() throws Exception {
        for (var entry : Map.of(
                "record-copy", "34",
                "class-self-copy", "34",
                "class-take", "42",
                "contextual-with", "42",
                "trivia-inert", "42").entrySet()) {
            assertEquals(entry.getValue(), run(entry.getKey()), entry.getKey());
        }
    }
    @Test
    void canonicalOwnershipNegativeCompilerCases() throws Exception {
        for (var id : new String[] {
                "record-use-after-take", "class-implicit-copy",
                "class-borrow-copy", "record-effectful-rhs",
                "class-this-spelling"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest(id))), id);
        }
    }
}
