package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Real root scheduler continuations for source-authored Atto async actor awaits. */
@Timeout(30)
final class NamespaceActorAsyncGuestV1Test {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/async-scheduler-actor-result.atto"));
    }

    private static long run(String text) throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            var input = Source.newBuilder(OresLanguage.ID, text,
                    "async-native-actor-result.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            return context.eval(input).asLong();
        }
    }

    @Test
    void realAttoAsyncMainAwaitsTwoActorsWithOwnedComputedOutput() throws Exception {
        assertEquals(42L, run(source()));
        assertEquals(99L, run(source().replace("a.send_async(41)", "a.send_async(98)")));
        assertEquals(8L, run(source().replace(
                "return (await a.outputs().next()).value.value;",
                "return (await b.outputs().next()).value.value;")));
    }

    @Test
    void asyncRootCanRunTwiceWithoutStaleActorOrCodeImage() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            var unit = Source.newBuilder(OresLanguage.ID, source(),
                    "async-repeated-context.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertEquals(42L, context.eval(unit).asLong());
            assertEquals(42L, context.eval(unit).asLong());
        }
    }

    @Test
    void asyncActorOverflowFailsWithoutPublishingSuccessfulResult() throws Exception {
        String overflow = source().replace("a.send_async(41)",
                "a.send_async(9223372036854775807)");
        assertThrows(RuntimeException.class, () -> run(overflow));
    }

    @Test
    void rejectedActorAsyncMutationsNeverCreateGuestControlOrStateAuthority()
            throws Exception {
        String good = source();
        String[] invalid = {
                good.replace("pub async fnc main()", "pub fnc main()"),
                good.replace("const a = spawn Meter()", "let a = spawn Meter()"),
                good.replace("await a.send_async(41);", "a.send_async(41);"),
                good.replace("await a.send_async(41);", "await a.send(41);"),
                good.replace("await a.send_async(41);", "await a.send_async(mail);"),
                good.replace("await a.send_async(41);", "await a.send_async(41, 42);"),
                good.replace("await b.send_async(7);", "await a.send_async(7);"),
                good.replace("await b.send_async(7);", ""),
                good.replace("return (await a.outputs().next()).value.value;",
                        "return a.output_value();"),
                good.replace("return (await a.outputs().next()).value.value;",
                        "return (await a.outputs().next()).value;"),
                good.replace("return (await a.outputs().next()).value.value;",
                        "return (await a.outputs().next()).value.value + 1;"),
                good.replace("return (await a.outputs().next()).value.value;",
                        "return (await other.outputs().next()).value.value;"),
                good.replace("return (await a.outputs().next()).value.value;",
                        "return (await a.outputs().next()).value.value; a.send(1);"),
                good.replace("p.send(mail.value + 1);", "p.send(p.state.received);"),
                good.replace("p.send(mail.value + 1);", "p.send(mail.value + 1); p.send(mail.value);"),
                good.replace("p.send(mail.value + 1);", "other.send(mail.value + 1);"),
                good.replace("p.debit(mail.value);\n    p.send(mail.value + 1);",
                        "p.send(mail.value + 1);\n    p.debit(mail.value);"),
                good.replace("await a.send_async(41);", "await a.cancel();"),
                good.replace("await b.send_async(7);", "await b.get_done();"),
                good.replace("return (await a.outputs().next()).value.value;",
                        "return (await a.outputs().next()).value.actorId;")
        };
        for (int i = 0; i < invalid.length; i++) {
            int idx = i;
            assertThrows(RuntimeException.class, () -> run(invalid[idx]),
                    "unverified async actor guest source variant " + i);
        }
    }

    @Test
    void normalUnverifiedTypeCheckerStaysFailClosed() throws Exception {
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source())));
        assertTrue(rejected.getMessage().contains("LOWERING_UNSUPPORTED"),
                rejected.getMessage());
    }

    @Test
    void schedulerCompilerContainsNoBlockingHostFutureBridge() throws Exception {
        String compiler = Files.readString(Path.of(
                "src/main/java/dev/oreslang/compiler/NamespaceActorAsyncGuestV1.java"));
        assertTrue(compiler.contains("OresScheduler.await("));
        assertTrue(compiler.contains("sendAsync("));
        assertTrue(compiler.contains(".outputs().next()"));
        assertFalse(compiler.contains(".awaitTermination("));
        assertFalse(compiler.contains(".join("));
        assertFalse(compiler.contains(".get(5,"));
        assertFalse(compiler.contains("Thread.sleep("));
        assertFalse(compiler.contains("CompletionStage.toCompletableFuture("));
    }
}
