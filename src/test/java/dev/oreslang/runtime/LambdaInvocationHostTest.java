package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class LambdaInvocationHostTest {
    @TempDir Path temp;

    private Path source(String body) throws Exception {
        Path file = temp.resolve("handler.ores");
        Files.writeString(file, body);
        return file;
    }

    @Test void invokesQualifiedPublicStringFunctionUnderStrictFaasPolicy() throws Exception {
        Path file = source("""
                define module handler as
                  pub fnc invoke(String event): String {
                    return event + "-ok";
                  }
                end
                """);

        LambdaInvocationHost.InvocationResult result =
                LambdaInvocationHost.invoke(
                        file,
                        "handler.invoke",
                        "ping",
                        ExecutionProfile.serverJit(),
                        Map.of());

        assertEquals("ping-ok", result.response());
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test void keepsGuestLogsOutOfTheResponseChannel() throws Exception {
        Path file = source("""
                define module handler as
                  pub fnc invoke(String event): String {
                    stdio.println("guest-log");
                    return "response";
                  }
                end
                """);

        LambdaInvocationHost.InvocationResult result =
                LambdaInvocationHost.invoke(
                        file,
                        "handler.invoke",
                        "ping",
                        ExecutionProfile.serverJit(),
                        Map.of());

        assertEquals("response", result.response());
        assertTrue(result.stdout().contains("guest-log"));
    }

    @Test void rejectsPrivateOrWronglyTypedHandlers() throws Exception {
        Path privateFile = source("""
                define module handler as
                  fnc invoke(String event): String {
                    return event;
                  }
                end
                """);
        assertThrows(IllegalArgumentException.class, () ->
                LambdaInvocationHost.invoke(
                        privateFile,
                        "handler.invoke",
                        "ping",
                        ExecutionProfile.serverJit(),
                        Map.of()));

        Path intFile = source("""
                define module handler as
                  pub fnc invoke(String event): int {
                    return 7;
                  }
                end
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                LambdaInvocationHost.invoke(
                        intFile,
                        "handler.invoke",
                        "ping",
                        ExecutionProfile.serverJit(),
                        Map.of()));
        assertTrue(failure.getMessage().contains("must return String"));
    }

    @Test void rejectsMalformedFunctionNamesAndOversizedEventsBeforeGuestLaunch() throws Exception {
        Path file = source("""
                define module handler as
                  pub fnc invoke(String event): String {
                    return event;
                  }
                end
                """);

        assertThrows(IllegalArgumentException.class, () ->
                LambdaInvocationHost.invoke(
                        file,
                        "__ores_internal_main_only__",
                        "ping",
                        ExecutionProfile.serverJit(),
                        Map.of()));

        String tooLarge = "x".repeat(LambdaInvocationHost.MAX_PAYLOAD_BYTES + 1);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                LambdaInvocationHost.invoke(
                        file,
                        "handler.invoke",
                        tooLarge,
                        ExecutionProfile.serverJit(),
                        Map.of()));
        assertTrue(failure.getMessage().contains("exceeds"));
    }

    @Test void embeddingInvocationDoesNotRequireMain() throws Exception {
        Path file = source("""
                pub fnc echo(String value): String {
                  return value;
                }
                """);

        Object result = LinkedProgramRunner.invokePublic(
                file,
                "echo",
                java.util.List.of("hello"),
                IsolatePolicy.developer(),
                RuntimePermissions.denyAll(),
                PermissionCheckMode.COMPILE,
                ExecutionProfile.serverJit(),
                java.util.Set.of(),
                Map.of(),
                null,
                null);

        assertEquals("hello", result);
    }
}
