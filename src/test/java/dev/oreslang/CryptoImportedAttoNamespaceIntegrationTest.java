package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.StandardCryptoNamespace;
import dev.oreslang.parser.Parser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

/**
 * Real multi-file .atto source policy loaded from packaged resources and
 * invoked through the same imported crypto facade as trusted host primitives.
 * A passing test is guest interpretation; it is NOT Graal Native Image.
 */
final class CryptoImportedAttoNamespaceIntegrationTest {
    private static String run(String code) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(OresLanguage.ID, code);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void compiledResourceCopiesMatchSourceFilesExactly() throws Exception {
        for (String name : List.of("crypto.atto", "crypto_envelope.atto")) {
            String resource = "/dev/oreslang/std/std/" + name;
            byte[] packaged;
            try (var input = StandardCryptoNamespace.class.getResourceAsStream(resource)) {
                assertNotNull(input, "packaged Atto source missing: " + resource);
                packaged = input.readAllBytes();
            }
            assertArrayEquals(Files.readAllBytes(Path.of("stdlib/std", name)), packaged,
                    "packaged Atto source differs from independently maintained .atto file");
        }
    }

    @Test
    void mergedNamespaceHasDistinctValidatedPublicPolicyMembers() {
        Ast.Program checked = StandardCryptoNamespace.checkedProgram();
        assertEquals("crypto", checked.namespace());
        assertEquals(1, checked.modules().size());
        assertEquals(Parser.ROOT_MODULE, checked.modules().getFirst().name());
        assertEquals(20, checked.modules().getFirst().declarations().size());
        assertNotNull(StandardCryptoNamespace.signature("aead_parameters_valid"));
        assertNotNull(StandardCryptoNamespace.signature("crypto_envelope_tag_offset"));
        assertNull(StandardCryptoNamespace.signature("aead_seal"));
        assertNull(StandardCryptoNamespace.signature("sha256"));
    }

    @Test
    void invokesPolicyFromBothAttoSourceFilesUsingOneNamespaceImport() {
        assertEquals("CRYPTO_STDLIB_ATTO_LINK_PASS\n", run("""
                import * as crypto from "std.crypto";

                pub routine main(): void {
                  if crypto.aead_parameters_valid(1, 32, 12, 16) eq false then
                    stdio.println("BAD-AES-PARAMETERS");
                    return;
                  fi
                  if crypto.aead_parameters_valid(2, 16, 12, 16) then
                    stdio.println("SHORT-KEY-ACCEPTED");
                    return;
                  fi
                  if crypto.aead_seal_size_admissible(1, 0, 16) then
                    stdio.println("SHORT-CAPACITY-ACCEPTED");
                    return;
                  fi
                  if crypto.crypto_envelope_size_admissible(28, 0) eq false then
                    stdio.println("EMPTY-ENVELOPE-REJECTED");
                    return;
                  fi
                  if crypto.crypto_envelope_ciphertext_bytes(28) neq 0 then
                    stdio.println("BAD-CIPHERTEXT-SIZE");
                    return;
                  fi
                  if crypto.crypto_envelope_tag_offset(28) neq 12 then
                    stdio.println("BAD-TAG-OFFSET");
                    return;
                  fi
                  if crypto.crypto_envelope_tag_offset(27) neq -1 then
                    stdio.println("TRUNCATED-ENVELOPE-ACCEPTED");
                    return;
                  fi
                  if crypto.hkdf_sha256_admissible(32, 0, 0, 8161) then
                    stdio.println("OVERLONG-HKDF-ACCEPTED");
                    return;
                  fi
                  stdio.println("CRYPTO_STDLIB_ATTO_LINK_PASS");
                  return;
                }
                """));
    }

    @Test
    void rejectsUnknownNamespaceMemberWithoutHostFallback() {
        PolyglotException error = assertThrows(PolyglotException.class,
                () -> run("""
                        import * as crypto from "std.crypto";
                        pub routine main(): void {
                          val value = crypto.untrusted_crypto_extension(1);
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("crypto")
                || error.getMessage().contains("unknown"));
    }

    @Test
    void rejectsPolicyCallsWithUnverifiedArguments() {
        PolyglotException error = assertThrows(PolyglotException.class,
                () -> run("""
                        import * as crypto from "std.crypto";
                        pub routine main(): void {
                          val admitted = crypto.aead_nonce_bytes("a string");
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("int")
                || error.getMessage().contains("type"));
    }
}
