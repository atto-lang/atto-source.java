package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.SingletonSharedDomain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Strict real production-Parser -> checked AST -> snapshot runtime smoke. */
public final class SingletonSnapshotCompilerV1Smoke {
    private static int checks;

    private static void require(boolean truth, String message) {
        checks++;
        if (!truth) throw new AssertionError(message);
    }

    private static void reject(Runnable action, String expected) {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            Throwable current = failure;
            for (int i = 0; i < 8 && current != null; i++, current = current.getCause()) {
                if (current.getMessage() != null
                        && current.getMessage().contains(expected)) return;
            }
            throw new AssertionError("wrong rejection for " + expected, failure);
        }
        throw new AssertionError("did not reject " + expected);
    }

    private static final String SOURCE = """
            define pub singleton Settings as
                const int answer = 42;
                const bool ready = true;
                const string label = "ok";
            end
            """;
    private static final String SAME = """
            define pub singleton Settings as
                const int answer = 42;
                const bool ready = true;
                const string label = "ok";
            end
            """;

    public static void main(String[] args) throws Exception {
        checks = 0;
        checkedParserPublicationAndIdentity();
        adversarialCompilerAdmission();
        concurrentSourceLowering();
        System.out.println("SINGLETON_COMPILER_BRIDGE_ASSERTIONS=" + checks);
        System.out.println("SINGLETON_COMPILER_BRIDGE_PASS=true");
        System.out.println("PARSER=PRODUCTION_PARSER_CHECKONLY");
        System.out.println("NORMAL_GUEST_PARSE=DENIED");
        System.out.println("COMPILED_ACTOR_GUEST=NOT_EXECUTED");
    }

    private static void checkedParserPublicationAndIdentity() {
        var plan = SingletonSnapshotCompilerV1.compile(SOURCE, "pkg/settings.atto");
        var same = SingletonSnapshotCompilerV1.compile(SAME, "pkg/settings.atto");
        var domain = new SingletonSharedDomain();
        require(plan.declarationCount() == 1, "one checked declaration");
        var emitted = plan.publishAsync(domain);
        require(emitted.size() == 1, "one runtime registration");
        var key = emitted.keySet().iterator().next();
        Object first = emitted.get(key).join();
        Object second = same.publishAsync(domain).get(key).join();
        require(first == second, "canonical singleton identity for same source unit");
        require(((Map<?, ?>) first).get("answer").equals(42L), "numeric parsed literal");
        require(((Map<?, ?>) first).get("ready").equals(true), "boolean parsed literal");
        require(((Map<?, ?>) first).get("label").equals("ok"), "string parsed literal");
        checks++;
        try {
            ((Map<?, ?>) first).clear();
            throw new AssertionError("immutable singleton field escaped writable");
        } catch (UnsupportedOperationException expected) {
            // Runtime returned a defensively frozen immutable snapshot.
        }
        var alternate = SingletonSnapshotCompilerV1.compile(SOURCE, "pkg/other.atto");
        var keyOther = alternate.publishAsync(domain).keySet().iterator().next();
        require(!key.equals(keyOther), "distinct compiler-resolved code unit key");
        require(alternate.publishAsync(domain).get(keyOther).join() != first,
                "separate source units cannot alias one identity");
        var otherDomain = new SingletonSharedDomain();
        require(plan.publishAsync(otherDomain).get(key).join() != first,
                "separate domains have separate state roots");

        String altered = SOURCE.replace("answer = 42", "answer = 43");
        var mismatch = SingletonSnapshotCompilerV1.compile(altered, "pkg/settings.atto");
        reject(() -> mismatch.publishAsync(domain).get(key).join(),
                "singleton declaration schema mismatch");
        require(plan.publishAsync(domain).get(key).join() == first,
                "mismatched schema did not mutate canonical publication");
        reject(() -> domain.snapshotOnce(key, () -> Map.of("answer", 999)).join(),
                "singleton declaration schema mismatch");
    }

    private static void adversarialCompilerAdmission() {
        reject(() -> Parser.parse(SOURCE), "ORES-SINGLETON-UNSUPPORTED");
        reject(() -> SingletonSnapshotCompilerV1.compile(
                SOURCE.replace("const int answer", "let int answer"), "pkg/settings.atto"),
                "ORES-SINGLETON-MUTABLE-ALIAS");
        reject(() -> SingletonSnapshotCompilerV1.compile(
                SOURCE.replace("answer = 42", "answer = get_answer()"), "pkg/settings.atto"),
                "ORES-SINGLETON-INIT-EFFECT");
        reject(() -> SingletonSnapshotCompilerV1.compile(
                SOURCE.replace("answer = 42", "answer = Settings.answer"), "pkg/settings.atto"),
                "ORES-SINGLETON-SELF-ESCAPE");
        reject(() -> SingletonSnapshotCompilerV1.compile(
                SOURCE.replace("answer = 42", "answer = #untrusted_symbol"), "pkg/settings.atto"),
                "Symbol literals cannot be interned");
        reject(() -> SingletonSnapshotCompilerV1.compile(
                "import module other from \"other.atto\";\n" + SOURCE, "pkg/settings.atto"),
                "ORES-SINGLETON-CHECKONLY");
        reject(() -> SingletonSnapshotCompilerV1.compile(
                SOURCE + "\nfnc main():int{return 0;}", "pkg/settings.atto"),
                "ORES-SINGLETON-CHECKONLY");
        reject(() -> SingletonSnapshotCompilerV1.compile(SOURCE, "../outside.atto"),
                "noncanonical checked singleton");
        reject(() -> SingletonSnapshotCompilerV1.compile(SOURCE, "/abs/file.atto"),
                "invalid checked singleton");
        reject(() -> SingletonSnapshotCompilerV1.compile(SOURCE, "pkg/../evil.atto"),
                "noncanonical checked singleton");
    }

    private static void concurrentSourceLowering() throws Exception {
        var domain = new SingletonSharedDomain();
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var initializes = new AtomicInteger();
        var done = new ArrayList<CompletableFuture<Object>>();
        try {
            for (int i = 0; i < 48; i++) {
                done.add(CompletableFuture.supplyAsync(() -> {
                    try { start.await(); }
                    catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                    var plan = SingletonSnapshotCompilerV1.compile(SOURCE, "pkg/settings.atto");
                    var values = plan.publishAsync(domain);
                    Object snapshot = values.values().iterator().next().join();
                    initializes.incrementAndGet();
                    return snapshot;
                }, pool));
            }
            start.countDown();
            CompletableFuture.allOf(done.toArray(CompletableFuture[]::new))
                    .orTimeout(15, TimeUnit.SECONDS).join();
            Object canonical = done.get(0).join();
            for (var r : done) {
                require(r.join() == canonical,
                        "concurrent caller gets the same compiler-derived singleton");
            }
            require(initializes.get() == 48, "all concurrent independent callers ran");
        } finally {
            pool.shutdownNow();
        }
    }

    private SingletonSnapshotCompilerV1Smoke() { }
}
