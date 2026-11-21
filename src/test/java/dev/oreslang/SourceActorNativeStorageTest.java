package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresContext;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
final class SourceActorNativeStorageTest {
    @Test
    void nativeProfileRejectsUntrustedGuestActorBeforeArenaCreation() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            context.initialize(OresLanguage.ID);
            ActorRuntime actors;
            context.enter();
            try {
                actors = OresContext.get(null).actors();
                actors.enableNativeActorStorage();
            } finally { context.leave(); }
            var failure = assertThrows(org.graalvm.polyglot.PolyglotException.class,
                    () -> context.eval(Source.newBuilder(OresLanguage.ID, """
                            define untrusted actor Worker as
                              receive(ActorMail<String> mail): void { self.end(); return; }
                            end
                            pub async routine main(): void {
                              val worker = spawn Worker();
                              await worker.get_ready();
                              return;
                            }
                            """, "native-untrusted-rejected.ores").build()));
            assertTrue(failure.getMessage().contains("not an untrusted isolate adapter"),
                    failure.getMessage());
            assertEquals(0, actors.nativeActorArenasCreated());
        }
    }

    @Test
    void realGuestPrivateAndSharedFieldsUseDistinctNativeArenas() throws Exception {
        for (String kind : new String[] {"actor", "isoactor"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                    .out(output).build()) {
                context.initialize(OresLanguage.ID);
                ActorRuntime actors;
                context.enter();
                try {
                    actors = OresContext.get(null).actors();
                    actors.enableNativeActorStorage();
                } finally { context.leave(); }
                String program = """
                        define %s Counter as
                          let int count;
                          on_start(): void { self.count = 0; return; }
                          receive(ActorMail<String> mail): void {
                            self.count = self.count + 1;
                            stdio.stdout.write(self.count);
                            self.end();
                            return;
                          }
                        end
                        pub async routine main(): void {
                          val left = spawn Counter();
                          await left.get_ready();
                          left.send("go");
                          await left.get_done();
                          stdio.stdout.write("|");
                          val right = spawn Counter();
                          await right.get_ready();
                          right.send("go");
                          await right.get_done();
                          return;
                        }
                        """.formatted(kind);
                context.eval(Source.newBuilder(OresLanguage.ID, program, "native-fields.ores").build());
                assertEquals("1|1", output.toString(StandardCharsets.UTF_8));
                assertEquals(2, actors.nativeActorArenasCreated());
                assertEquals(0, actors.nativeActorMemoryBytes());
            }
        }
    }

    @Test
    void realGuestSameKindPeerAdvancesAfterOtherActorPhysicalDone() throws Exception {
        // Guest-native storage proof using the currently compiled Java actor
        // syntax, not a replacement for the canonical cross-backend .ores blob.
        // Both actors are simultaneously live and share the actor definition,
        // never the native field allocation.
        for (String kind : new String[] {"actor", "isoactor"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                    .out(output).build()) {
                context.initialize(OresLanguage.ID);
                ActorRuntime actors;
                context.enter();
                try {
                    actors = OresContext.get(null).actors();
                    actors.enableNativeActorStorage();
                } finally { context.leave(); }
                // Load the EXACT compiled .ores blob for this actor kind. No
                // template expansion: a conformance receipt can identify the
                // corresponding committed fixture by its Git blob SHA.
                String fixture = "native-same-kind-survivor-" + kind + ".ores";
                String expectedBlob = kind.equals("actor")
                        ? "a2570c0fdd1ea845208e77bc90f5f702a94936a6"
                        : "f058ef5588e200dba3ebce2347ef0bde2b353d69";
                String program;
                try (var resource = SourceActorNativeStorageTest.class.getResourceAsStream(
                        "/actor/" + fixture)) {
                    assertNotNull(resource, "compiled .ores survivor fixture is missing: " + fixture);
                    byte[] bytes = resource.readAllBytes();
                    // Hash the classpath bytes actually supplied to the compiler,
                    // not just the checked-in file path. Reject a stale/mutated
                    // resource before executing or claiming any guest result.
                    MessageDigest digest = MessageDigest.getInstance("SHA-1");
                    digest.update(("blob " + bytes.length + "\0")
                            .getBytes(StandardCharsets.US_ASCII));
                    digest.update(bytes);
                    assertEquals(expectedBlob, HexFormat.of().formatHex(digest.digest()),
                            "compiled resource must match the exact Git blob: " + fixture);
                    program = new String(bytes, StandardCharsets.UTF_8);
                }
                context.eval(Source.newBuilder(OresLanguage.ID, program, fixture).build());
                assertEquals("1|1|2|2", output.toString(StandardCharsets.UTF_8),
                        "A reaches 2 while B remains at 1, then B reaches 2 after A is done");
                assertEquals(2, actors.nativeActorArenasCreated(),
                        "simultaneous same-kind actors must allocate distinct native arenas");
                // The live-actor arena sum alone can be zero after deregistration;
                // also assert both independent, runtime-wide allocation ledgers
                // have been refunded. This does not claim physical scrubbing.
                assertEquals(0, actors.nativeActorMemoryBytes(),
                        "no registered actor may retain native arena charges");
                assertEquals(0, actors.privateMemoryBytes(),
                        "all private actor heap reservations must be refunded");
                assertEquals(0, actors.sharedMemoryBytes(),
                        "shared-kind native actor reservations must be refunded");
            }
        }
    }
}
