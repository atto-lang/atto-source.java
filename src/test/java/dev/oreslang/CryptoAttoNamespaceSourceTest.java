package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Compile actual on-disk std.crypto policy sources as independent Atto
 * namespace units. Does NOT purport to link them to the crypto host facade.
 */
final class CryptoAttoNamespaceSourceTest {
    private static final List<String> POLICY_SOURCES = List.of(
            "stdlib/std/crypto.atto",
            "stdlib/std/crypto_envelope.atto");

    @Test
    void parseAndTypecheckAttoCryptoNamespaceWithoutModuleSyntax() throws Exception {
        for (String file : POLICY_SOURCES) {
            String source = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            assertTrue(source.startsWith("use namespace crypto;\n"), file);
            assertFalse(source.contains("define module "), file);
            Ast.Program program = Parser.parseStrictAtto(source);
            assertEquals("crypto", program.namespace(), file);
            assertTrue(program.imports().isEmpty(), file);
            assertFalse(program.modules().isEmpty(), file);
            assertTrue(program.modules().stream()
                    .allMatch(module -> module.name().equals(Parser.ROOT_MODULE)), file);
            assertTrue(program.modules().stream()
                    .flatMap(module -> module.declarations().stream())
                    .anyMatch(decl -> decl instanceof Ast.FunctionDecl), file);
            assertDoesNotThrow(
                    () -> TypeChecker.checkTypesWithDiagnostics(program, true), file);
        }
    }

    @Test
    void dottedNamespaceHeaderRemainsRejectedByCompiler() {
        String incorrect = "use namespace std.crypto;\n"
                + "pub fnc test(): bool { return true; }\n";
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parseStrictAtto(incorrect));
        assertTrue(rejected.getMessage().contains("dotted")
                || rejected.getMessage().contains("namespace"));
    }
}
