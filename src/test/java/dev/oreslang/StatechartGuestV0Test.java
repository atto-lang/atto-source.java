package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class StatechartGuestV0Test {
    private static String run(String filename) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                Path.of("examples", filename),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void pureSymbolsHierarchyLeafPriorityAndUnhandledStutterExecuteAsGuest() throws Exception {
        assertEquals("STATECHART_GUEST_PASS\n", run("statechart-symbol-v0.ores")
                .replace("\r\n", "\n"));
    }

    @Test
    void duplicateEventRuleMustRejectAtGuestRuntime() {
        assertInvalid("statechart-symbol-invalid-duplicate.ores", "duplicate state/event transition");
    }

    @Test
    void cyclicParentHierarchyMustRejectAtGuestRuntime() {
        assertInvalid("statechart-symbol-invalid-cycle.ores", "cyclic parent hierarchy");
    }

    @Test
    void unknownTransitionTargetMustRejectAtGuestRuntime() {
        assertInvalid("statechart-symbol-invalid-target.ores", "unknown source or target");
    }

    @Test
    void undeclaredActiveStateMustRejectAtGuestRuntime() {
        assertInvalid("statechart-symbol-invalid-active.ores", "active state is undeclared");
    }

    @Test
    void bypassingCheckedFactoryCannotBypassStepValidation() {
        assertInvalid("statechart-symbol-invalid-raw.ores", "duplicate state Symbol");
    }

    @Test
    void unknownParentIndexMustRejectAtGuestRuntime() {
        assertInvalid("statechart-symbol-invalid-parent.ores", "unknown parent index");
    }

    private static void assertInvalid(String filename, String expectedMessage) {
        Exception thrown = assertThrows(Exception.class, () -> run(filename), filename);
        StringBuilder messages = new StringBuilder();
        for (Throwable cursor = thrown; cursor != null; cursor = cursor.getCause()) {
            messages.append(cursor).append("\n");
        }
        assertTrue(messages.toString().contains(expectedMessage),
                () -> filename + ": expected '" + expectedMessage + "' in " + messages);
    }
}
