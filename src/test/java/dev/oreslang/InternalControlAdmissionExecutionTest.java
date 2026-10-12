package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Malformed or unknown reserved commands may never dispatch the backwards
 * compatible implicit main entrypoint or mutate source startup state.
 */
final class InternalControlAdmissionExecutionTest {
    private static final String SOURCE = """
            pub fnc main(): int {
              stdio.println("UNEXPECTED-MAIN");
              return 42;
            }
            """;

    @Test
    void unknownAndMalformedInternalCommandsFailWithoutRunningMain() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            Value parsed = context.parse(Source.newBuilder(OresLanguage.ID, SOURCE,
                    "malformed-host-control.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            parsed.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            Object[][] commands = {
                    { "__ores_internal_unknown__" },
                    { OresEvalRootNode.LINK_ONLY_COMMAND, "surplus" },
                    { OresEvalRootNode.INIT_ONLY_COMMAND, "surplus" },
                    { OresEvalRootNode.MAIN_ONLY_COMMAND, "surplus" },
                    { OresEvalRootNode.REGISTER_IMPORT_COMMAND, "only-path" },
                    { OresEvalRootNode.REGISTER_IMPORT_COMMAND, "path", 1 },
                    { OresEvalRootNode.INVOKE_PUBLIC_COMMAND },
                    { OresEvalRootNode.INVOKE_PUBLIC_COMMAND, 1 }
            };
            for (Object[] command : commands) {
                PolyglotException rejected = assertThrows(PolyglotException.class,
                        () -> parsed.execute(command));
                assertTrue(rejected.getMessage().contains("internal Oreslang host control command"),
                        "unexpected failure for reserved command: " + command[0]);
                assertEquals("", output.toString(),
                        "malformed command must not reach guest main");
            }
        }
    }
}
