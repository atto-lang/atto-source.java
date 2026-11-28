package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual linked guest for the bounded explicit fresh-array constructor profile. */
final class ArrayConstructorCrossBackendGuestTest {
    private static final Path ROOT = Path.of("tests", "fixtures", "class-array-constructor-v1");
    @Test
    void independentFreshArraysExecuteAsGuest() throws Exception {
        var fixture = ROOT.resolve("class-explicit-array-constructor.atto");
        LinkedProgramRunner.validate(fixture, Map.of());
        var result = LinkedProgramRunner.runWithExitStatus(fixture, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, result.exitStatus());
    }
    @Test
    void duplicateMissingAndOutOfOrderFreshArrayWritesReject() {
        for (String reason : new String[]{"order", "duplicate", "missing"}) {
            var fixture = ROOT.resolve("class-explicit-array-constructor-" + reason + "-reject.atto");
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(fixture, Map.of()), reason);
        }
    }
    @Test
    void rejectsSelfDependentAndArbitraryCallBeforeFinalInitialization() {
        for (String expression : new String[]{"self.a", "make()"}) {
            String source = "define class Box as pub final mut Array<int> a; "
                    + "constructor() { self.a = " + expression + "; } end "
                    + "pub fnc main(): int { return 42; }";
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.parser.Parser.parse(source), expression);
            assertTrue(failure.getMessage().contains("E_INIT"), failure::getMessage);
        }
    }

    @Test
    void rejectsUnverifiedFreshAllocationProfilesBeforeEvaluation() {
        for (String[] row : new String[][]{
                {"pub final Array<int> a;", "new Array<int>()"},
                {"pub final mut Array<int> a;", "new Array<int>(1)"},
                {"pub final mut Array<int> a;", "new Array<String>()"},
                {"pub final mut Array<String> a;", "new Array<String>()"},
        }) {
            String source = "define class Box as " + row[0] +
                    " constructor() { self.a = " + row[1] + "; } end pub fnc main(): int { return 42; }";
            assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.parser.Parser.parse(source), source);
        }
    }
}
