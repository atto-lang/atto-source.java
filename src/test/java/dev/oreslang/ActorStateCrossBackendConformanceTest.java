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
 * Identical source bytes also live in the LLVM source conformance fixtures.
 * Do not "fix" backend parity by adding Java-only spellings to these cases.
 */
final class ActorStateCrossBackendConformanceTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("tests", "fixtures",
                "actor-state-parity-" + name + ".ores"));
    }

    @Test void canonicalReadAndWriteFormsCompile() throws Exception {
        for (String kind : new String[] {"read", "write"}) {
            String text = source(kind);
            assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(text)), kind);
        }
    }

    @Test void illegalStateOperationsFailInTheCompilerContract() throws Exception {
        for (String kind : new String[] {"no-with-reject", "read-mutate-reject",
                "loan-escape-reject", "cooperate-reject"}) {
            // Parsing must succeed. A rejection at the parser rather than
            // ownership/effect admission would be a false conformance pass.
            Ast.Program program = Parser.parse(source(kind));
            assertThrows(IllegalArgumentException.class, () -> {
                ActorStateContract.verify(program);
                DeclaredEffectVerifier.verify(program);
                TypeChecker.checkTypes(program);
            }, kind);
        }
    }
}
