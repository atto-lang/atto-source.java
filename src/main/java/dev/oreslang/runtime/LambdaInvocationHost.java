package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provider-neutral Oreslang function invocation boundary.
 *
 * <p>Version 1 deliberately uses the existing String -> String adapter envelope.
 * It executes one public function inside the strict FaaS isolate policy with no
 * scoped host-I/O permissions and captures guest stdout/stderr separately from
 * the returned response. Provider transports (AWS, HTTP, queue workers, etc.)
 * remain adapters outside this class.</p>
 */
public final class LambdaInvocationHost {
    /** Hard host-side bound before creating a guest context. */
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    private static final Pattern FUNCTION_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private LambdaInvocationHost() { }

    public record InvocationResult(
            String response,
            String stdout,
            String stderr) { }

    public static InvocationResult invoke(
            Path entryFile,
            String functionName,
            String event) throws IOException {
        ExecutionProfile profile = ExecutionProfile.parse(
                NativeBuildMode.defaultExecutionMode(), "server");
        return invoke(entryFile, functionName, event, profile, Map.of());
    }

    public static InvocationResult invoke(
            Path entryFile,
            String functionName,
            String event,
            ExecutionProfile profile,
            Map<String, String> environment) throws IOException {
        if (entryFile == null) throw new IllegalArgumentException("lambda entry file is required");
        if (functionName == null || !FUNCTION_NAME.matcher(functionName).matches()) {
            throw new IllegalArgumentException(
                    "lambda function must be a public Oreslang function name");
        }
        requireBoundedUtf8(event, "lambda event");

        ByteArrayOutputStream guestOut = new ByteArrayOutputStream();
        ByteArrayOutputStream guestErr = new ByteArrayOutputStream();

        Object value = LinkedProgramRunner.invokePublic(
                entryFile,
                functionName,
                List.of(event),
                IsolatePolicy.strictFaas(),
                RuntimePermissions.denyAll(),
                PermissionCheckMode.COMPILE,
                profile,
                Set.of(),
                Map.copyOf(environment),
                guestOut,
                guestErr);

        if (!(value instanceof String response)) {
            String actual = value == null ? "void/null" : value.getClass().getSimpleName();
            throw new IllegalArgumentException(
                    "Oreslang lambda v1 must return String, got " + actual);
        }
        requireBoundedUtf8(response, "lambda response");

        return new InvocationResult(
                response,
                guestOut.toString(StandardCharsets.UTF_8),
                guestErr.toString(StandardCharsets.UTF_8));
    }

    private static void requireBoundedUtf8(String value, String label) {
        if (value == null) throw new IllegalArgumentException(label + " cannot be null");
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    label + " exceeds " + MAX_PAYLOAD_BYTES + " UTF-8 bytes");
        }
    }
}
