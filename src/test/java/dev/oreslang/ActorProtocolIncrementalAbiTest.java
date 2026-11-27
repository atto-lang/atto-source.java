package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-only cross-unit ABI digest regression. A type-only actor message
 * contract is public permission metadata, not a runtime field.
 */
final class ActorProtocolIncrementalAbiTest {
    @Test
    void changingThePublicActorReceiveTypeInvalidatesItsAbiDigest() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        String stringActor = """
                define pub actor Worker receives String as
                  receive(ActorMail<String> mail): void { return; }
                end
                """;
        String intActor = """
                define pub actor Worker receives int as
                  receive(ActorMail<int> mail): void { return; }
                end
                """;
        String first = compiler.compile(Map.of("worker.ores", stringActor))
                .units().get("worker.ores").abiDigest();
        String second = compiler.compile(Map.of("worker.ores", intActor))
                .units().get("worker.ores").abiDigest();
        assertNotEquals(first, second,
                "public receives contract change must invalidate dependent callers");
    }

    @Test
    void unchangedPublicActorContractHasStableDigest() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        String source = """
                define pub actor Worker receives String as
                  receive(ActorMail<String> mail): void { return; }
                end
                """;
        var first = compiler.compile(Map.of("worker.ores", source));
        var second = compiler.compile(Map.of("worker.ores", source));
        assertEquals(first.units().get("worker.ores").abiDigest(),
                second.units().get("worker.ores").abiDigest());
        assertTrue(second.reused("worker.ores"));
    }
}
