package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Message-passing shared state without shared mutable memory: one task owns a
 * Map<Bytes, Bytes>; clients send requests that carry their own reply
 * Channel. Requires Channel<T> (T Send), Bytes, Map and Tuple[...] to be Send.
 */
final class AttoChannelReplyStoreGuestTest {

    private static final String STORE = """
            pub async fnc store_loop(Channel<Tuple[int, Bytes, Bytes, Channel<Bytes>]> requests): void {
              let Map<Bytes, Bytes> data = ~map<Bytes, Bytes>();
              loop {
                val req = await (nb readch requests);
                val int kind = req[0];
                if kind == 0 then return; fi
                val Channel<Bytes> reply = req[3];
                if kind == 1 then
                  data.set(req[1], req[2]);
                  await (nb writech reply, Bytes.from_string("OK"));
                fi
                if kind == 2 then
                  val found = data.get_optional(req[1]);
                  if found.is_some() then
                    await (nb writech reply, found.unwrap());
                  else
                    await (nb writech reply, Bytes.empty());
                  fi
                fi
              }
            }

            pub async fnc client(Channel<Tuple[int, Bytes, Bytes, Channel<Bytes>]> requests, string name): string {
              val Channel<Bytes> mine = Channel.new<Bytes>(1);
              await (nb writech requests, (1, Bytes.from_string(name), Bytes.from_string("v-" + name), mine));
              val ok = await (nb readch mine);
              await (nb writech requests, (2, Bytes.from_string(name), Bytes.empty(), mine));
              val v = await (nb readch mine);
              return ok.to_utf8().unwrap() + ":" + v.to_utf8().unwrap();
            }

            pub async routine main(): void {
              val Channel<Tuple[int, Bytes, Bytes, Channel<Bytes>]> requests =
                  Channel.new<Tuple[int, Bytes, Bytes, Channel<Bytes>]>(64);
              task scope {
                let Future<void> store = task { await store_loop(requests); return; };
                let Future<string> a = task { return await client(requests, "a"); };
                let Future<string> b = task { return await client(requests, "b"); };
                stdio.println(await a);
                stdio.println(await b);
                val Channel<Bytes> unused = Channel.new<Bytes>(1);
                await (nb writech requests, (0, Bytes.empty(), Bytes.empty(), unused));
              }
              return;
            }
            """;

    @Test
    void requestsCarryReplyChannelsToATaskOwnedBytesMap() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(STORE));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, STORE, "channel_reply_store.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("OK:v-a\nOK:v-b", out.toString(StandardCharsets.UTF_8).replace("\r", "").trim());
    }

    @Test
    void channelOfNonSendPayloadCannotCrossTaskBoundary() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async fnc holder(Channel<Tuple[int, Future<int>]> c): void { return; }
                        pub async routine main(): void { return; }
                        """));
        assertTrue(failure.getMessage().contains("task-safe"), failure.getMessage());
    }

    @Test
    void channelsStillCannotCrossActorMailboxes() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define actor Bad as
                          receive(ActorMail<Channel<int>> mail): void { return; }
                        end
                        pub async routine main(): void { return; }
                        """));
        assertTrue(failure.getMessage().contains("cannot use Channel across an actor boundary"),
                failure.getMessage());
    }

    @Test
    void bytesWrittenToAChannelAreMoved() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async routine main(): void {
                          val Channel<Tuple[int, Bytes]> c = Channel.new<Tuple[int, Bytes]>(4);
                          val b = Bytes.from_string("x");
                          await (nb writech c, (1, b));
                          stdio.println(b.size());
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("use of moved value 'b'"), failure.getMessage());
    }
}
