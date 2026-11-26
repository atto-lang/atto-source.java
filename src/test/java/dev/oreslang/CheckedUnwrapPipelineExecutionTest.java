package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class CheckedUnwrapPipelineExecutionTest {
    // Compatibility execution is not strict .atto admission.
    private static final String GUEST = """
        fnc input(bool present): Option<int> {
            stdio.println(9);
            return present ? Some(7) : None;
        }
        fnc choose(bool present): int {
            return trap! input(present).unwrap() |> match<int> over
                on Ok(n): int -> n,
                on Err(): int -> 42,
            end;
        }
        pub fnc main(): void {
            stdio.println(choose(true));
            stdio.println(choose(false));
            return;
        }
        """;

    @Test
    void dynamicPipedCheckedUnwrapActuallyExecutesBothArmsOnce() throws Exception {
        var output = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, GUEST, "checked-unwrap-pipeline.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (var context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("9\n7\n9\n42", output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void strictSourceGateRemainsFailClosedUntilTypedEffectAdmission() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(GUEST));
        assertTrue(failure.getMessage().contains("ATTO_STRICT_TYPED_TRAP_UNSUPPORTED"),
                failure::getMessage);
    }
}
