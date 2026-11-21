package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class AttoGuardCorpusGuestTest {
    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("contracts", "guard-v1", name), StandardCharsets.UTF_8);
    }

    @Test
    void canonicalScalarGuardExecutes() throws Exception {
        String text = fixture("canonical-scalar.atto");
        assertTrue(text.contains("x neq 5"), "AttoLang guard fixture must use canonical neq");
        assertFalse(text.contains("x != 5"), "legacy inequality spelling is not canonical");
        TypeChecker.check(Parser.parse(text));
        Source source = Source.newBuilder(OresLanguage.ID, text, "canonical-scalar.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42, context.eval(source).asInt());
        }
    }

    @Test
    void attoEqNeqAndBangEqCompileAndExecuteThroughTheGuestFrontend() throws Exception {
        // Hosted Truffle guest execution only: not a GraalVM Native Image AOT proof.
        String text = """
                pub fnc main(): int {
                  if (3 eq 3 && 3 neq 4 && 3 !eq 5) then
                    return 42;
                  else
                    return 7;
                  fi
                }
                """;
        TypeChecker.check(Parser.parse(text));
        Source source = Source.newBuilder(OresLanguage.ID, text, "atto-equality-operators.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42, context.eval(source).asInt());
        }
    }

    @Test
    void invalidGuardFailurePathsReject() throws Exception {
        for (String name : new String[]{"reject-fallthrough.atto", "reject-else.atto"}) {
            String text = fixture(name);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(text)), name);
        }
    }
}
