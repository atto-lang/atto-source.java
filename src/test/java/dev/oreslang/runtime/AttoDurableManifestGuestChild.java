package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Child JVM launched by AttoDurableManifestProcessKillTest. The receipt is
 * emitted only AFTER actual linked .atto guest execution returned from its
 * awaited filesystem operation. No Java host shortcut writes the manifest.
 */
public final class AttoDurableManifestGuestChild {
    private AttoDurableManifestGuestChild() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || (!args[0].equals("write-hold") && !args[0].equals("replay"))) {
            throw new IllegalArgumentException("mode and private root required");
        }
        Path root = Path.of(args[1]).toRealPath();
        Path manifest = root.resolve("classic.meta");
        String path = manifest.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String body = args[0].equals("write-hold")
                ? "await fs.write_text_durable_atomic_async(\"" + path + "\", \"generation=7\");"
                : "stdio.println(await fs.read_text_bounded_async(\"" + path + "\", 1024));";
        String source = "pub async routine main(): void {\n  "
                + body + "\n  return;\n}\n";
        OresCompiler.parseAndTypeCheck(source);
        Path sourceFile = root.resolve("process-child.atto");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString());
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(sourceFile, policy, permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                output, new ByteArrayOutputStream());

        if (args[0].equals("write-hold")) {
            // stdout is a process-boundary receipt from the completed guest.
            System.out.println("ATTO_GUEST_FSYNC_AND_DIRECTORY_SYNC_COMPLETED");
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        } else {
            System.out.println("ATTO_GUEST_REPLAY=" + output.toString(StandardCharsets.UTF_8).trim());
            System.out.flush();
        }
    }
}
