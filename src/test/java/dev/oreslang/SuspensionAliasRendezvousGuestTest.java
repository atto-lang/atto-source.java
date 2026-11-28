package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
final class SuspensionAliasRendezvousGuestTest {
    @Test
    void everyAliasPreservesZeroCapacityChannelRendezvousAndTaskLocals() throws Exception {
        for (String effect : List.of("suspends", "susp", "⧖", "∂")) {
            String program = """
                    %1$s fnc produce(Channel<int> ch, int count, int tag): int {
                      for (let i = 0; i < count; i = i + 1) {
                        await (nb writech ch, (tag <<< 16) + i);
                        rt cooperate;
                      }
                      return count;
                    }
                    %1$s fnc consume(Channel<int> ch, int count): int {
                      let int sum = 0;
                      for (let i = 0; i < count; i = i + 1) {
                        val int packed = await (nb readch ch);
                        sum = sum + (packed >>> 16) + (packed & 65535);
                      }
                      return sum;
                    }
                    pub %1$s fnc main(): void {
                      val Channel<int> ch = Channel.new<int>(0);
                      let Future<int> a = task { return produce(ch, 40, 1); };
                      let Future<int> b = task { return produce(ch, 40, 2); };
                      let Future<int> c = task { return consume(ch, 80); };
                      val int total = await c;
                      val int sent = (await a) + (await b);
                      stdio.stdout.write(sent);
                      stdio.stdout.write("|");
                      stdio.stdout.write(total);
                      return;
                    }
                    """.formatted(effect);
            for (int repetition = 0; repetition < 3; repetition++) {
                var output = new ByteArrayOutputStream();
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false).out(output).build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, program, "alias-rendezvous.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                }
                assertEquals("80|1680", output.toString(StandardCharsets.UTF_8),
                        effect + " repetition " + repetition);
            }
        }
    }
}
