package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-owned DOP v1 admission, not a Java/GraalVM inverted guest proof.
 * Canonical fixture sources are shared byte-for-byte with LLVM PR #1403,
 * but Java does not yet have nominal inverted-struct guest lowering.
 */
final class DopInvertedGuestAdmissionTest {
    private static final String MISSING_BACKEND =
            "DOP_INVERTED_BACKEND_UNSUPPORTED";

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("contracts", "dop-inverted-v1", name));
    }

    private static void rejectsWithoutGuestFallback(String source) {
        var parser = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(parser.getMessage().contains(MISSING_BACKEND),
                "compiler admission must identify the missing inverted backend");

        var compiler = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(compiler.getMessage().contains(MISSING_BACKEND),
                "the compiler entrypoint must reject before creating guest IR");
    }

    @Test
    void canonicalSwapReuseCannotMasqueradeAsJavaGuestParity() throws Exception {
        String source = fixture("swap-reuse.atto");
        assertTrue(source.contains("rows.erase_swap(0)"));
        assertTrue(source.contains("return rows.get(1, value)"));
        rejectsWithoutGuestFallback(source);
    }

    @Test
    void canonicalRemovedIndexTrapCannotMasqueradeAsJavaGuestParity() throws Exception {
        String source = fixture("removed-index-trap.atto");
        assertTrue(source.contains("rows.erase_swap(0)"));
        rejectsWithoutGuestFallback(source);
    }

    @Test
    void alsoRejectsInvertedClassAndTriviaBetweenDeclarationTokens() {
        rejectsWithoutGuestFallback(
                "define /* gap */ inverted class Vec as end");
        rejectsWithoutGuestFallback(
                "pub define inverted struct Vec as let x: int; end");
    }

    @Test
    void ContextualNameDoesNotBanOrdinaryIdentifiers() {
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc inverted(): int { return 42; }"));
    }
}
