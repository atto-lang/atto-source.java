package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class LinkedNominalActorMessageTest {
    @TempDir Path temp;

    private static final String PROTOCOL = """
            pub define enum Messages as
                Delta struct { delta: int } 1;
                Ready 2;
            end
            """;
    private static final String ACTOR = """
            import type Messages from "./protocol.ores";

            define actor Worker as
                receive(ActorMail<Messages> mail): void {
                    val int answer = match mail.value over {
                        on Messages.Delta as (payload) { return payload.delta; }
                        on Messages.Ready as () { return 0; }
                    };
                    stdio.stdout.write(answer);
                    self.end();
                    return;
                }
            end

            pub async routine main(): void {
                val worker = spawn Worker();
                await worker.get_ready();
                worker.send(Messages.Delta(infer struct { delta: 42 }));
                await worker.get_done();
                return;
            }
            """;

    @Test
    void crossFileNominalInboxTypeChecksWithExplicitImport() {
        Map<String, String> sources = Map.of(
                "src/protocol.ores", PROTOCOL,
                "src/main.ores", ACTOR);
        var build = assertDoesNotThrow(() -> new IncrementalCompiler().compile(sources));
        assertEquals(2, build.units().size());
    }

    @Test
    void compiledActorReceivesImportedTaggedMessage() throws Exception {
        Path protocol = temp.resolve("protocol.ores");
        Path main = temp.resolve("main.ores");
        Files.writeString(protocol, PROTOCOL);
        Files.writeString(main, ACTOR);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(main, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void privateEnumCannotBeImported() {
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "src/protocol.ores", PROTOCOL.replace("pub define", "define"),
                        "src/main.ores", ACTOR)));
    }

    @Test
    void mismatchedImportedEnumIsNotAssignableToActorInbox() {
        String second = PROTOCOL.replace("Messages", "ForeignMessages");
        String sender = ACTOR.replace(
                "import type Messages from \"./protocol.ores\";",
                "import type Messages from \"./protocol.ores\";\n"
                        + "import type ForeignMessages from \"./other.ores\";")
                .replace("worker.send(Messages.Delta(", "worker.send(ForeignMessages.Delta(");
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "src/protocol.ores", PROTOCOL,
                        "src/other.ores", second,
                        "src/main.ores", sender)));
    }
    @Test
    void identicalEnumNamesAndTagsFromDifferentSourcesRemainDistinct() {
        String sender = ACTOR.replace(
                "import type Messages from \"./protocol.ores\";",
                "import type Messages from \"./protocol.ores\";\n"
                        + "import type Messages as Other from \"./other.ores\";")
                .replace("worker.send(Messages.Delta(", "worker.send(Other.Delta(");
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "src/protocol.ores", PROTOCOL,
                        "src/other.ores", PROTOCOL,
                        "src/main.ores", sender)));
    }

    @Test
    void explicitlyAliasedEnumCanTypeCheckAndExecuteInActor() throws Exception {
        String aliased = ACTOR.replace(
                "import type Messages from", "import type Messages as Inbox from")
                .replace("ActorMail<Messages>", "ActorMail<Inbox>")
                .replace("on Messages.", "on Inbox.")
                .replace("worker.send(Messages.", "worker.send(Inbox.");
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(Map.of(
                "src/protocol.ores", PROTOCOL, "src/main.ores", aliased)));
        Path protocol = temp.resolve("protocol.ores");
        Path main = temp.resolve("main.ores");
        Files.writeString(protocol, PROTOCOL);
        Files.writeString(main, aliased);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(main, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void linkedParseScopeRejectsByteDriftAndCleansUp() {
        var build = new IncrementalCompiler().compile(Map.of(
                "src/protocol.ores", PROTOCOL,
                "src/main.ores", ACTOR));
        assertNull(dev.oreslang.compiler.OresCompiler.linkedProgramOrNull("src/main.ores", ACTOR));
        try (var scope = dev.oreslang.compiler.OresCompiler.beginLinkedParse(build)) {
            assertSame(build.units().get("src/main.ores").program(),
                    dev.oreslang.compiler.OresCompiler.linkedProgramOrNull("src/main.ores", ACTOR));
            assertThrows(SecurityException.class,
                    () -> dev.oreslang.compiler.OresCompiler.linkedProgramOrNull(
                            "src/main.ores", ACTOR + "\n// unvalidated"));
            assertThrows(IllegalStateException.class,
                    () -> dev.oreslang.compiler.OresCompiler.beginLinkedParse(build));
        }
        assertNull(dev.oreslang.compiler.OresCompiler.linkedProgramOrNull("src/main.ores", ACTOR));
    }

    @Test
    void changesToPublicEnumTagsInvalidateImportedActor() {
        var compiler = new IncrementalCompiler();
        var before = Map.of("src/protocol.ores", PROTOCOL, "src/main.ores", ACTOR);
        compiler.compile(before);
        assertTrue(compiler.compile(before).rebuiltUnits().isEmpty());
        var modified = Map.of(
                "src/protocol.ores", PROTOCOL.replace("} 1;", "} 9;"),
                "src/main.ores", ACTOR);
        var built = compiler.compile(modified);
        assertTrue(built.rebuiltUnits().contains("src/protocol.ores"));
        assertTrue(built.rebuiltUnits().contains("src/main.ores"));
    }

}
