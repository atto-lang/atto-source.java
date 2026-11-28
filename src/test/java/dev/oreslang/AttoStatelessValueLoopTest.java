package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

final class AttoStatelessValueLoopTest {
    private static long execute(String source) throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try (Context ctx = Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                // Host Truffle diagnostics are not guest stderr. Keep both visible.
                .logHandler(System.err).out(out).err(err).build()) {
            var value = ctx.eval(Source.newBuilder(OresLanguage.ID, source, "value-loop.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            assertTrue(value.fitsInLong(), "expected i64 guest result");
            assertEquals("", out.toString(StandardCharsets.UTF_8));
            assertEquals("", err.toString(StandardCharsets.UTF_8));
            return value.asLong();
        }
    }

    @Test void exactSharedFixture() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("tests/parity/value-loop-recur-v1/230-stateless-value-loop.atto"));
        var digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        assertEquals("2c0359a6d2876958d6fb6831eb0dac3c6548c15b", HexFormat.of().formatHex(digest.digest(bytes)));
        assertEquals(42L, execute(new String(bytes, StandardCharsets.UTF_8)));
    }

    @Test void arithmeticAndLazyBranch() throws Exception {
        assertEquals(42L, execute("pub fnc main(): int { return loop { break 20+22; }; }"));
        assertEquals(42L, execute("pub fnc main(): int { return true ? loop { break 42; } : loop { break 9223372036854775807+1; }; }"));
    }

    @Test void contextualCallableRemainsCallable() throws Exception {
        assertEquals(42L, execute("fnc loop(int n): int { return n; } pub fnc main(): int { return loop(42); }"));
    }

    @Test void invalidFormsFailClosed() {
        for (String expression : new String[] {
                "loop { break; }", "loop { break 42; break 1; }",
                "loop { break f; }", "loop { break 42; }(1)"}) {
            assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> execute(
                    "fnc f(int x): int {return x;} pub fnc main(): int {return " + expression + ";}"), expression);
        }
    }
}
