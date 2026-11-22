package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

/**
 * First-source guest checks for PR #1367 primitives + standard crypto import.
 * No reliance on the parent UTF-8/string convenience branch.
 */
final class CryptoMergedGuestSourceTest {
    private static String run(String source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(OresLanguage.ID, source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void shaHmacPbkdf2AndCsprngAreGuestVisible() {
        assertEquals(
                "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=|"
                + "nBluMtwBdfhvSxy4konWYZ3mvuaZ5MN45oMJ7Zehpqs=|"
                + "xeR41ZKIyEGqUw22hFxMjZYok6ABzk4RpJY4c6qYE0o=|32|true",
                run("""
                        import * as crypto from "std.crypto";
                        pub routine main(): void {
                          val digest = crypto.sha256([97, 98, 99]);
                          val mac = crypto.hmac_sha256([107, 101, 121], [97, 98, 99]);
                          val dk = crypto.pbkdf2_hmac_sha256(
                              [112, 97, 115, 115, 119, 111, 114, 100],
                              [115, 97, 108, 116], 4096, 32);
                          stdio.stdout.write(crypto.base64_encode(digest));
                          stdio.stdout.write("|");
                          stdio.stdout.write(crypto.base64_encode(mac));
                          stdio.stdout.write("|");
                          stdio.stdout.write(crypto.base64_encode(dk));
                          stdio.stdout.write("|");
                          val random = crypto.secure_random_bytes(32);
                          stdio.stdout.write(random.size);
                          stdio.stdout.write("|");
                          stdio.stdout.write(crypto.constant_time_equals(digest, digest));
                          return;
                        }
                        """));
    }

    @Test
    void rejectsMalformedCryptoArguments() {
        PolyglotException badByte = assertThrows(PolyglotException.class,
                () -> run("""
                        import * as crypto from "std.crypto";
                        pub routine main(): void {
                          val digest = crypto.sha256([256]);
                          return;
                        }
                        """));
        assertTrue(badByte.getMessage().contains("0..255"));
        PolyglotException excessiveKdf = assertThrows(PolyglotException.class,
                () -> run("""
                        import * as crypto from "std.crypto";
                        pub routine main(): void {
                          val key = crypto.pbkdf2_hmac_sha256([1], [2], 100001, 32);
                          return;
                        }
                        """));
        assertTrue(excessiveKdf.getMessage().contains("iterations"));
        PolyglotException malformedBase64 = assertThrows(PolyglotException.class,
                () -> run("""
                        import * as crypto from "std.crypto";
                        pub routine main(): void {
                          val value = crypto.base64_decode("Zg=!");
                          return;
                        }
                        """));
        assertTrue(malformedBase64.getMessage().contains("Base64"));
    }
}
