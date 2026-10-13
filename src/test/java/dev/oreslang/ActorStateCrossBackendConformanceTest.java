package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.TypeChecker;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Java-side admission tests for a proposed cross-backend actor-state contract.
 * LLVM currently supports a restricted tagged-receive guest subset, not these
 * ordinary actor-method fixtures. This suite does NOT establish Java/LLVM
 * differential source or execution parity.
 */
final class ActorStateCrossBackendConformanceTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("tests", "fixtures",
                "actor-state-parity-" + name + ".ores"));
    }

    @Test void canonicalReadAndWriteFormsCompile() throws Exception {
        for (String kind : new String[] {"read", "write"}) {
            String text = source(kind);
            assertDoesNotThrow(() -> {
                Ast.Program program = Parser.parse(text);
                ActorStateContract.verify(program);
                DeclaredEffectVerifier.verify(program);
                TypeChecker.checkTypes(program);
            }, kind);
        }
    }

    @Test void illegalStateOperationsFailInTheCompilerContract() throws Exception {
        String[][] cases = {
                {"no-with-reject", "requires with state.read/write effect declaration"},
                {"read-mutate-reject", "critical read cannot modify actor state"},
                {"loan-escape-reject", "actor state access capability cannot escape"},
                {"cooperate-reject", "actor state loan cannot cross await"}
        };
        for (String[] testCase : cases) {
            String kind = testCase[0];
            // A parser failure is not evidence of effect/ownership admission.
            Ast.Program program = Parser.parse(source(kind));
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class, () -> {
                ActorStateContract.verify(program);
                DeclaredEffectVerifier.verify(program);
                TypeChecker.checkTypes(program);
            }, kind);
            assertTrue(denied.getMessage().contains(testCase[1]),
                    () -> kind + ": unexpected rejection reason: " + denied.getMessage());
        }
    }
}
