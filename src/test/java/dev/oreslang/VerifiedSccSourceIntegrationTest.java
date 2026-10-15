package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportInterfaceCodec;
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

    private static Map<String, Ast.Program> programs(String a, String b) {
        return Map.of("a.ores", Parser.parse(a), "b.ores", Parser.parse(b));
    }

    private static Map<String, ExportInterfaceCodec.Header> headers() {
        return Map.of("a.ores", ENV, "b.ores", ENV);
    }

    @Test void parsedMutuallyImportingFilesAreAdmittedAtomically() {
        var graph = VerifiedSccAdmission.verifySingleScc(
                programs(A, B), headers(), Map.of());
        assertEquals(2, graph.units().size());
        assertEquals(64, graph.interfaceFingerprint().length());
    }

    @Test void parsedProviderReturnTypeMismatchRejectsTheWholeCycle() {
        String badB = B.replace("right(): int", "right(): String")
                .replace("return 7;", "return \"wrong\";");
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedSccAdmission.verifySingleScc(
                        programs(A, badB), headers(), Map.of()));
    }

    @Test void parsedWrongImportedArgumentArityIsNotUnknown() {
        String badA = A.replace("right();", "right(5);");
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedSccAdmission.verifySingleScc(
                        programs(badA, B), headers(), Map.of()));
    }

    @Test void parsedInaccessibleProviderFailsAdmission() {
        String privateB = B.replace("pub fnc right", "fnc right");
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedSccAdmission.verifySingleScc(
                        programs(A, privateB), headers(), Map.of()));
    }

    @Test void parsedUnsupportedImportedNominalTypeFailsClosed() {
        String nominalB = B.replace("right(): int", "right(): Custom")
                .replace("return 7;", "return 7;");
        assertThrows(IllegalArgumentException.class, () ->
                VerifiedSccAdmission.verifySingleScc(
                        programs(A, nominalB), headers(), Map.of()));
    }

    @Test void parsedImportedAliasKeepsTheResolvedProviderSignature() {
        String aliasedA = A.replace(
                "import fnc {right} from \"./b.ores\";",
                "import fnc {right as remoteRight} from \"./b.ores\";")
                .replace("return right();", "return remoteRight();");
        // Parser-specific named-import alias syntax must be validated by
        // the production parser; do not assume this form is supported.
        assertDoesNotThrow(() -> Parser.parse(aliasedA));
        var verified = VerifiedSccAdmission.verifySingleScc(
                programs(aliasedA, B), headers(), Map.of());
        assertEquals(2, verified.units().size());
    }
}
