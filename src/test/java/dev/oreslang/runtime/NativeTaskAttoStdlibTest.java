package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual compiler-linked Atto std/task module, not a host policy simulation. */
@Timeout(45)
final class NativeTaskAttoStdlibTest {
    @Test
    void taskModuleUsesPackagedNativeAttoWithClosedImportIdentity() throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("task.atto"));
        assertEquals("@std/task.atto", CoreLibrarySources.unitId("std/task"));
        assertEquals(CoreLibrarySources.unitId("std/task"),
                CoreLibrarySources.unitId("ores.std/task.atto"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/task.ores"));
        String source = CoreLibrarySources.source("std/task");
        var parsed = MixedSourceUnit.parse("@std/task.atto", "task.atto", source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
        assertFalse(source.contains("java:"));
    }

    @Test
    void nativeTaskPolicyAndActualChildTaskExecuteInGuest() throws Exception {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(
                Path.of("src/test/resources/stdlib-parity/task-v1.atto"),
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), output, errors);
        assertTrue(linked.units().containsKey("@std/task.atto"),
                "std/task must be compiled from .atto, never substituted by Java");
        assertEquals("42", output.toString(StandardCharsets.UTF_8).trim(),
                () -> errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void explicitTaskPayloadProducesAnAwaitableFuture() throws Exception {
        Path path = Path.of("src/test/resources/stdlib-parity/typed-task-main.atto");
        String text = Files.readString(path);
        OresCompiler.parseAndTypeCheck(text);
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            var source = Source.newBuilder(OresLanguage.ID, text, path.getFileName().toString())
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertEquals(42L, context.eval(source).asLong());
        }
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(text.replace("task<int>", "task<bool>")));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(text.replace("val Future<int>", "val Future<bool>")));
        // A scheduled source task exposes Future<T>, not the historical
        // Task<T> result wrapper (runtime bookkeeping is a separate concern).
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(text.replace("val Future<int>", "val Task<int>")));

    }

    @Test
    void checkedEntryDoesNotAdmitAnUncheckedLinkedDependency(@TempDir Path root) throws Exception {
        Path dependency = root.resolve("helper.ores");
        Files.writeString(dependency, "define module helper as pub fnc answer(): int { return 42; } end");
        Path main = root.resolve("main.atto");
        Files.writeString(main, """
                import module helper from "./helper.ores";
                pub fnc main(): int { return 42; }
                """);
        var failure = assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.run(
                main, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(failure.getMessage().contains("must resolve to a checked Atto source unit"),
                failure::getMessage);

        Path bridge = root.resolve("bridge.atto");
        Files.writeString(bridge, """
                import module helper from "./helper.ores";
                define module bridge as pub fnc answer(): int { return 42; } end
                """);
        Files.writeString(main, """
                import module bridge from "./bridge.atto";
                pub fnc main(): int { return 42; }
                """);
        var transitive = assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.run(
                main, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(transitive.getMessage().contains("checked import './helper.ores'"), transitive::getMessage);
    }
}
