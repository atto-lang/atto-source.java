package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.parser.Parser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * Real Atto namespace source is evaluated, not rewritten in Java.
 * This verifies standalone .atto policy functions; std.crypto importing
 * those functions from a separate compilation unit needs its own linker gate.
 */
final class CryptoAttoNamespaceGuestTest {
    private static String evaluate(String file, String programBody) throws Exception {
        String library = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        String source = library + "\n" + programBody + "\n";
        assertEquals("crypto", Parser.parseStrictAtto(source).namespace());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(OresLanguage.ID, source);
        }
        return output.toString(StandardCharsets.UTF_8).trim();
    }

    @Test
    void executesAeadAndKdfBoundsFromAttoSource() throws Exception {
        String output = evaluate("stdlib/std/crypto.atto", """
                pub routine main(): void {
                  if aead_parameters_valid(1, 32, 12, 16) eq false then
                    stdio.println("FAIL-AES-VALID");
                    return;
                  fi
                  if aead_parameters_valid(2, 32, 12, 16) eq false then
                    stdio.println("FAIL-CHACHA-VALID");
                    return;
                  fi
                  if aead_parameters_valid(1, 16, 12, 16) then
                    stdio.println("FAIL-SHORT-KEY");
                    return;
                  fi
                  if aead_seal_size_admissible(16777216, 65536, 16777232) eq false then
                    stdio.println("FAIL-MAX-SIZE");
                    return;
                  fi
                  if aead_open_size_admissible(15, 0, 0) then
                    stdio.println("FAIL-TRUNCATED-TAG");
                    return;
                  fi
                  if hkdf_sha256_admissible(32, 0, 0, 8161) then
                    stdio.println("FAIL-HKDF-LIMIT");
                    return;
                  fi
                  if pbkdf2_hmac_sha256_admissible(8, 8, 100001, 32) then
                    stdio.println("FAIL-PBKDF2-LIMIT");
                    return;
                  fi
                  stdio.println("CRYPTO_ATTO_POLICY_PASS");
                  return;
                }
                """);
        assertEquals("CRYPTO_ATTO_POLICY_PASS", output);
    }

    @Test
    void executesEnvelopeLayoutFromAttoSource() throws Exception {
        String output = evaluate("stdlib/std/crypto_envelope.atto", """
                pub routine main(): void {
                  if crypto_envelope_size_admissible(28, 0) eq false then
                    stdio.println("FAIL-EMPTY-ENVELOPE");
                    return;
                  fi
                  if crypto_envelope_ciphertext_bytes(28) neq 0 then
                    stdio.println("FAIL-EMPTY-CIPHERTEXT");
                    return;
                  fi
                  if crypto_envelope_tag_offset(28) neq 12 then
                    stdio.println("FAIL-TAG-OFFSET");
                    return;
                  fi
                  if crypto_envelope_size_admissible(27, 0) then
                    stdio.println("FAIL-TRUNCATION");
                    return;
                  fi
                  stdio.println("CRYPTO_ATTO_ENVELOPE_PASS");
                  return;
                }
                """);
        assertEquals("CRYPTO_ATTO_ENVELOPE_PASS", output);
    }
}
