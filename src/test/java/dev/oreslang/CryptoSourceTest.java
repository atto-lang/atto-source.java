package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

final class CryptoSourceTest {
    @Test
    void guestCryptoMatchesPortableVectorsAndReusesSaltedPassword() {
        assertEquals(
                "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=|"
                        + "97yD9DBThCSxMpjmqm+xQ+9NWaFJRhdZl0edvC0aPNg=|"
                        + "xeR41ZKIyEGqUw22hFxMjZYok6ABzk4RpJY4c6qYE0o=|true",
                run("""
                        pub routine main(): void {
                          val List<int> abc = "abc".utf8_bytes();
                          val List<int> digest = crypto.sha256(abc);
                          stdio.stdout.write(crypto.base64_encode(digest));
                          stdio.stdout.write("|");

                          val List<int> mac = crypto.hmac_sha256(
                              "key".utf8_bytes(),
                              "The quick brown fox jumps over the lazy dog".utf8_bytes());
                          stdio.stdout.write(crypto.base64_encode(mac));
                          stdio.stdout.write("|");

                          val List<int> salted = crypto.pbkdf2_hmac_sha256(
                              "password".utf8_bytes(),
                              "salt".utf8_bytes(),
                              4096,
                              32);
                          stdio.stdout.write(crypto.base64_encode(salted));
                          stdio.stdout.write("|");

                          // The same affine List remains usable after both
                          // read-only crypto calls; SCRAM needs this property.
                          val List<int> client_key =
                              crypto.hmac_sha256(salted, "Client Key".utf8_bytes());
                          val List<int> server_key =
                              crypto.hmac_sha256(salted, "Server Key".utf8_bytes());
                          stdio.stdout.write(
                              crypto.constant_time_equals(client_key, server_key) == false);
                          return;
                        }
                        """));
    }

    @Test
    void base64RoundTripAndRandomBytesAreGuestVisible() {
        assertEquals("hello|32", run("""
                pub routine main(): void {
                  val List<int> decoded = crypto.base64_decode("aGVsbG8=");
                  stdio.stdout.write(decoded.utf8_string());
                  stdio.stdout.write("|");
                  val List<int> nonce = crypto.secure_random_bytes(32);
                  stdio.stdout.write(nonce.size);
                  return;
                }
                """));
    }

    @Test
    void cryptoRejectsMalformedInputsAndExcessivePbkdf2Cost() {
        PolyglotException base64 = assertThrows(
                PolyglotException.class,
                () -> run("""
                        pub routine main(): void {
                          val bytes = crypto.base64_decode("aGVsbG8=\n");
                          return;
                        }
                        """));
        assertTrue(base64.getMessage().contains("Base64"));

        PolyglotException byteRange = assertThrows(
                PolyglotException.class,
                () -> run("""
                        pub routine main(): void {
                          val digest = crypto.sha256([256]);
                          return;
                        }
                        """));
        assertTrue(byteRange.getMessage().contains("0..255"));

        PolyglotException cost = assertThrows(
                PolyglotException.class,
                () -> run("""
                        pub routine main(): void {
                          val derived = crypto.pbkdf2_hmac_sha256(
                              [1], [2], 100001, 32);
                          return;
                        }
                        """));
        assertTrue(cost.getMessage().contains("iterations"));
    }

    private static String run(String source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(OresLanguage.ID, source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
