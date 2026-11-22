package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.VerifiedSccAdmission;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source parser -> linked imports -> all-unit TypeChecker and OwnershipChecker.
 * These are genuine Oreslang source programs, not synthetic AST factories.
 * They MUST be run in the full GraalVM Maven suite before merge.
 */
final class VerifiedSccSourceIntegrationTest {
    private static final ExportInterfaceCodec.Header ENV =
            new ExportInterfaceCodec.Header("", "compiler21", "x86_64", "strict");

    private static final String A = """
            import fnc {right} from "./b.ores";
            pub fnc left(): int {
                return right();
            }
            """;

    private static final String B = """
            import fnc {left} from "./a.ores";
            pub fnc right(): int {
                return 7;
            }
            """;

    private static VerifiedSccAdmission.VerifiedScc verify(String a, String b) {
        var graph = OresCompiler.parseAndVerifySccSources(
                Map.of("a.ores", a, "b.ores", b), headers(), Map.of());
        assertEquals(1, graph.components().size());
        return graph.components().getFirst();
    }

    private static Map<String, ExportInterfaceCodec.Header> headers() {
        return Map.of("a.ores", ENV, "b.ores", ENV);
    }

    @Test void parsedMutuallyImportingFilesAreAdmittedAtomically() {
        var graph = verify(A, B);
        assertEquals(2, graph.units().size());
        assertEquals(64, graph.interfaceFingerprint().length());
    }

    @Test void parsedProviderReturnTypeMismatchRejectsTheWholeCycle() {
        String badB = B.replace("right(): int", "right(): String")
                .replace("return 7;", "return \"wrong\";");
        assertThrows(IllegalArgumentException.class, () ->
                verify(A, badB));
    }

    @Test void parsedWrongImportedArgumentArityIsNotUnknown() {
        String badA = A.replace("right();", "right(5);");
        assertThrows(IllegalArgumentException.class, () ->
                verify(badA, B));
    }

    @Test void parsedInaccessibleProviderFailsAdmission() {
        String privateB = B.replace("pub fnc right", "fnc right");
        assertThrows(IllegalArgumentException.class, () ->
                verify(A, privateB));
    }

    @Test void parsedUnsupportedImportedNominalTypeFailsClosed() {
        String nominalB = B.replace("right(): int", "right(): Custom")
                .replace("return 7;", "return 7;");
        assertThrows(IllegalArgumentException.class, () ->
                verify(A, nominalB));
    }

    @Test void parsedImportedAliasKeepsTheResolvedProviderSignature() {
        String aliasedA = A.replace(
                "import fnc {right} from \"./b.ores\";",
                "import fnc {right} as remoteRight from \"./b.ores\";")
                .replace("return right();", "return remoteRight();");
        // Parser-specific named-import alias syntax must be validated by
        // the production parser; do not assume this form is supported.
        assertDoesNotThrow(() -> Parser.parse(aliasedA));
        var verified = verify(aliasedA, B);
        assertEquals(2, verified.units().size());
    }
}
