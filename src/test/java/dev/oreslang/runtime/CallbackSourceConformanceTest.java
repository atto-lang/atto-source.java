package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class CallbackSourceConformanceTest {

    private static final String RESOURCE =
            "/conformance/execution-context-callbacks/v1/callback_sync_resolve.ores";

    @Test
    void exactSyncResolveFixtureCompilesAndExecutesThroughRealSourceRunner()
            throws Exception {
        URL resource = CallbackSourceConformanceTest.class.getResource(RESOURCE);
        assertNotNull(resource, "missing canonical callback source fixture");
        assertEquals("file", resource.getProtocol(),
                "test fixture must be materialized as a real source file");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        LinkedProgramRunner.RunResult result = LinkedProgramRunner.runWithExitStatus(
                Path.of(resource.toURI()),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                stdout,
                stderr);

        assertEquals(0, result.exitStatus());
        assertEquals(
                String.join("\n",
                        "before_callback",
                        "callback_enter",
                        "callback_exit",
                        "after_callback",
                        "after_await",
                        "42",
                        ""),
                stdout.toString(StandardCharsets.UTF_8));
    }
}
