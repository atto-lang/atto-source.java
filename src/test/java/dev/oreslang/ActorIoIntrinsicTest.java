package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorIoIntrinsicTest {
    private static void check(String code, String name) throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code, name).build());
        }
    }

    @Test
    void actorIoOutIsIntrinsicAndAwaitable() throws Exception {
        check("""
                pub async actor fnc worker(string value) -> void {
                  await io.out.write(value);
                  return;
                }

                pub routine main(): void {
                  return;
                }
                """, "actor-io-out.ores");
    }

    @Test
    void processStdioIsRejectedInsideActor() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub actor fnc worker(string value) -> void {
                          stdio.println(value);
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-process-stdio.ores"));
        assertTrue(failure.getMessage().contains("process-level I/O"), failure::getMessage);
    }

    @Test
    void rootIoIsNotAnAmbientProcessAlias() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub routine main(): void {
                          io.out.write("wrong");
                          return;
                        }
                        """, "root-actor-io.ores"));
        assertTrue(failure.getMessage().contains("unknown name 'io'"), failure::getMessage);
    }

    @Test
    void actorIoIntrinsicCannotBeShadowedByAParameter() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub actor fnc worker(string io) -> void {
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-shadow.ores"));
        assertTrue(
                failure.getMessage().contains("reserved actor execution intrinsic"),
                failure::getMessage);
    }

    @Test
    void unimplementedActorChannelsFailClosedAtTypecheck() {
        PolyglotException failure = assertThrows(PolyglotException.class, () ->
                check("""
                        pub async actor fnc worker(string value) -> void {
                          await io.err.write(value);
                          return;
                        }

                        pub routine main(): void {
                          return;
                        }
                        """, "actor-io-err-reserved.ores"));
        assertTrue(failure.getMessage().contains("reserved but not implemented"), failure::getMessage);
    }
}
