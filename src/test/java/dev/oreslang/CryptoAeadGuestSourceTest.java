package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

/** Real Atto source evaluated inside the Java/Graal compiler guest runtime. */
final class CryptoAeadGuestSourceTest {
    private static String run(String source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(OresLanguage.ID, source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aesAndChaChaRoundTripAndTamperFails() {
        assertEquals("true|true|true|true", run("""
                import * as crypto from "std.crypto";
                        pub routine main(): void {
                  val List<int> aad = [1, 2, 3];
                  val List<int> message = [23, 42, 99];
                  val aes = crypto.aead_new_key(1);
                  val sealed1 = crypto.aead_seal(aes, aad, message);
                  val bytes1 = sealed1.unwrap();
                  val opened1 = crypto.aead_open(aes, aad, bytes1);
                  stdio.stdout.write(opened1.is_ok());
                  stdio.stdout.write("|");
                  val opened2 = crypto.aead_open(aes, [99], bytes1);
                  stdio.stdout.write(opened2.is_err());
                  stdio.stdout.write("|");
                  crypto.aead_close(aes);
                  val chacha = crypto.aead_new_key(2);
                  val sealed2 = crypto.aead_seal(chacha, aad, message);
                  val bytes2 = sealed2.unwrap();
                  val opened3 = crypto.aead_open(chacha, aad, bytes2);
                  stdio.stdout.write(opened3.is_ok());
                  stdio.stdout.write("|");
                  val opened4 = crypto.aead_open(chacha, aad, [1, 2, 3]);
                  stdio.stdout.write(opened4.is_err());
                  crypto.aead_close(chacha);
                  return;
                }
                """));
    }

    @Test
    void rejectsInvalidAlgorithmAndMalformedBytes() {
        PolyglotException algorithm = assertThrows(PolyglotException.class,
                () -> run("""
                        import * as crypto from "std.crypto";
                        pub routine main(): void {
                          val key = crypto.aead_new_key(0);
                          return;
                        }
                        """));
        assertTrue(algorithm.getMessage().contains("algorithm"));
        PolyglotException byteRange = assertThrows(PolyglotException.class,
                () -> run("""
                        import * as crypto from "std.crypto";
                        pub routine main(): void {
                          val key = crypto.aead_new_key(1);
                          val sealed = crypto.aead_seal(key, [], [256]);
                          return;
                        }
                        """));
        assertTrue(byteRange.getMessage().contains("0..255"));
    }

    @Test
    void refusesUseAfterExplicitKeyRetirement() {
        PolyglotException error = assertThrows(PolyglotException.class,
                () -> run("""
                        import * as crypto from "std.crypto";
                        pub routine main(): void {
                          val key = crypto.aead_new_key(1);
                          crypto.aead_close(key);
                          val sealed = crypto.aead_seal(key, [], []);
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("key")
                || error.getMessage().contains("move")
                || error.getMessage().contains("taken"));
    }

    @Test
    void deniesCryptoOperationsWithoutExplicitCryptoCapability() {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID, new String[] {"--ores-capabilities=STDOUT"})
                .build()) {
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> context.eval(OresLanguage.ID, """
                            import * as crypto from "std.crypto";
                        pub routine main(): void {
                              val key = crypto.aead_new_key(1);
                              return;
                            }
                            """));
            assertTrue(failure.getMessage().contains("CRYPTO")
                    || failure.getMessage().contains("capability"));
        }
    }

    @Test
    void preventsOwnershipCopyOfOpaqueKey() {
        PolyglotException error = assertThrows(PolyglotException.class,
                () -> run("""
                        import * as crypto from "std.crypto";
                        pub routine main(): void {
                          val key = crypto.aead_new_key(1);
                          val stolen = rt copy key;
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("copy") || error.getMessage().contains("move"));
    }
}
