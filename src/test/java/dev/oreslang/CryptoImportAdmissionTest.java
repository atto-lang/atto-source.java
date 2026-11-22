package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

/** Exact-match compiler-owned crypto import: not a broad host/file authority. */
final class CryptoImportAdmissionTest {
    private static String reject(String source) {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class,
                    () -> context.eval(OresLanguage.ID, source));
            return failure.getMessage();
        }
    }

    @Test
    void cannotAliasCompilerCryptoNamespace() {
        String message = reject("""
                import * as host_crypto from "std.crypto";
                pub routine main(): void {
                  return;
                }
                """);
        assertTrue(message.contains("std.crypto") || message.contains("crypto import"));
    }

    @Test
    void cannotSelectArbitraryCryptoHostMembersThroughImport() {
        String message = reject("""
                import fnc sha256 from "std.crypto";
                pub routine main(): void {
                  return;
                }
                """);
        assertTrue(message.contains("std.crypto") || message.contains("crypto import"));
    }

    @Test
    void cannotUpgradeSimilarLookingExternalPathToBuiltin() {
        String message = reject("""
                import * as crypto from "std.crypto.extra";
                pub routine main(): void {
                  return;
                }
                """);
        assertTrue(message.contains("import") || message.contains("crypto"));
    }

    @Test
    void duplicateCryptoImportsAreRejected() {
        String message = reject("""
                import * as crypto from "std.crypto";
                import * as crypto from "std.crypto";
                pub routine main(): void {
                  return;
                }
                """);
        assertTrue(message.contains("duplicate"));
    }
}
