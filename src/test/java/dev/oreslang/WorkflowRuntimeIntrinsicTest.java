package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Compiler admission tests. Full Truffle guest execution is a separate gate. */
final class WorkflowRuntimeIntrinsicTest {
    private static final String BODY = """
        pub routine main(): int {
          val pending = rt workflow_request([
            "probe-1", "INSPECT", "tenant-a", "run-1",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            1, 0, 0, "", "", "", "", "", "", "scoped-cap"
          ]);
          return 0;
        }
        """;

    @Test void parserAcceptsExplicitIntrinsic() {
        assertDoesNotThrow(() -> Parser.parse(BODY));
    }

    @Test void parserRejectsIncorrectStaticPayloadShape() {
        String wrong = BODY.replace(
                "1, 0, 0, \"\", \"\", \"\", \"\", \"\", \"\", \"scoped-cap\"",
                "1, 0, 0, \"\", \"\", \"\", \"\", \"\", \"scoped-cap\"");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(wrong));
        assertTrue(failure.getMessage().contains("exactly 15"), failure.getMessage());
    }

    @Test void deniedUnlessWorkflowStorageCapabilityIsExplicit() {
        var program = Parser.parse(BODY);
        SecurityException failure = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(failure.getMessage().contains("workflow_request"), failure.getMessage());
        assertDoesNotThrow(() -> CapabilityChecker.check(program,
                IsolatePolicy.developer().withCapabilities(
                        IsolatePolicy.Capability.WORKFLOW_STORAGE)));
    }

    @Test void forgedRuntimeIntrinsicSpellingCannotAccessOtherOperations() {
        String bad = BODY.replace("workflow_request", "workflow_open");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(bad));
        assertTrue(failure.getMessage().contains("unknown rt operation"), failure.getMessage());
    }
}
