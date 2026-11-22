package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-to-real-JVM-process bridge proof. Distinct from host-only runner
 * tests: a genuine Atto guest source invokes process.spawn and awaits Results.
 * Requires an executing CI runner; this source is not an execution receipt.
 */
final class AttoChildProcessGuestTest {
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String javaExecutable() {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", "java" + suffix)
                .toAbsolutePath().normalize().toString();
    }

    private static String workingDirectory() {
        return Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize().toString();
    }

    private static String testClassPath() {
        try {
            Class<?> probe = Class.forName(
                    "dev.oreslang.runtime.NativeChildProcessesTest$ChildProcessProbe");
            return Path.of(probe.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toString();
        } catch (ClassNotFoundException | java.net.URISyntaxException failure) {
            throw new IllegalStateException("cannot locate compiled child fixture", failure);
        }
    }

    private static String guest(boolean expectDenied) {
        String command = quote(javaExecutable());
        String cp = quote(testClassPath());
        String probe = quote("dev.oreslang.runtime.NativeChildProcessesTest$ChildProcessProbe");
        String cwd = quote(workingDirectory());
        return """
                pub async routine main(): int {
                  val attempted = await process.spawn(%s, ["-cp", %s, %s, "exit42"], %s);
                  if attempted.is_err() then return %d; fi
                  val child = attempted.unwrap();
                  val captured = await child.collect_output();
                  if captured.is_err() then return 80; fi
                  val status = await child.wait();
                  if status.is_err() then return 81; fi
                  if captured.unwrap().stdout == "" then return 82; fi
                  if captured.unwrap().stderr == "" then return 83; fi
                  return status.unwrap().code;
                }
                """.formatted(command, cp, probe, cwd, expectDenied ? 42 : 99);
    }

    private static int evaluate(String source, boolean runGrant) throws Exception {
        String[] args = runGrant ? new String[] {
                "--ores-capabilities=CHILD_PROCESS,FILESYSTEM_READ",
                "--ores-permission-run-allow=" + javaExecutable(),
                "--ores-permission-read-allow=" + workingDirectory()
        } : new String[] {
                "--ores-capabilities=CHILD_PROCESS,FILESYSTEM_READ",
                "--ores-permission-run-deny=*",
                "--ores-permission-read-allow=" + workingDirectory()
        };
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID, args).build()) {
            return context.eval(Source.newBuilder(OresLanguage.ID, source, "std-process-guest.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build()).asInt();
        }
    }

    @Test void guestExecutesAndAwaitsRealChild() throws Exception {
        String source = guest(false);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(42, evaluate(source, true));
    }

    @Test void guestReceivesTypedDeniedResult() throws Exception {
        String source = guest(true);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(42, evaluate(source, false));
    }
}
