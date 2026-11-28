package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class DirectCallableAwaitGuestTest {
    @Test void closedLambdaAwaitProducesScalarWithoutNewTask() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, """
                    pub suspends fnc main(): void {
                        const int value = await || -> 33;
                        const int other = await (|| -> 6 * 7);
                        stdio.stdout.write(value);
                        stdio.stdout.write("|");
                        stdio.stdout.write(other);
                        return;
                    }
                    """, "direct-callable-await.atto").mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        assertEquals("33|42",out.toString(StandardCharsets.UTF_8));
    }

    @Test void unsupportedCallableShapesDoNotBecomeIdentityAwait() {
        for (String body : new String[]{
                "const int result = await |int x| -> x;",
                "const int result = await || -> true;",
                "const int captured = 33; const int result = await || -> captured;",
                "const bool result = await || -> 33;",
                "const int result = await || -> { return 33; };"
        }) assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("pub suspends fnc main(): void { "+body+" return; }"),body);
    }
}
