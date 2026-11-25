package dev.oreslang;

import dev.oreslang.compiler.NamespaceActorManifestV1;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real physical multi-file Atto manifest -> parsed AST -> typed critical MIR
 * -> Truffle -> ActorRuntime mailbox. No hand-coded actor business handler.
 * This does not assert ordinary Context.eval/native image execution.
 */
final class AttoNamespaceActorManifestV1Test {
    @TempDir Path project;

    private Path fixture() throws Exception {
        Path source = Path.of("contracts", "declared-actor-stateful-manifest-v1");
        for (String file : List.of(
                ".atto.pkg.toml", "src/actor.atto", "src/state.atto",
                "src/init.atto", "src/receive.atto", "src/critical.atto")) {
            Path target = project.resolve(file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, Files.readString(source.resolve(file)));
        }
        return project.resolve(".atto.pkg.toml");
    }

    private NamespaceActorManifestV1.Verified admit(
            Path manifest, SharedCodeImageStore store) throws Exception {
        return NamespaceActorManifestV1.compile(manifest, store, "CounterActor");
    }

    private static long send(ActorRuntime.ActorRef<Object> actor, long increment)
            throws Exception {
        actor.sendAsync(increment).get(30, TimeUnit.SECONDS);
        var emitted = actor.outputs().next().get(30, TimeUnit.SECONDS);
        assertFalse(emitted.done(), "native Atto receive must emit a reply");
        return (Long) emitted.value().value();
    }

    @Test
    void physicalFiveFileCounterAccumulatesFourMessagesToTen() throws Exception {
        Path manifest = fixture();
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var verified = admit(manifest, store);
            assertEquals("counter@1", verified.packageIdentity());
            assertEquals("counter", verified.namespace());
            assertEquals(5, verified.sourceSha256().size());
            assertEquals(64, verified.manifestSha256().length());
            assertThrows(UnsupportedOperationException.class,
                    () -> verified.sourceSha256().clear());

            var actor = verified.plan().spawn(runtime, IsolatePolicy.developer());
            actor.ready().get(30, TimeUnit.SECONDS);
            assertEquals(1L, send(actor, 1L));
            assertEquals(3L, send(actor, 2L));
            assertEquals(6L, send(actor, 3L));
            assertEquals(10L, send(actor, 4L));
            actor.stop();
            assertTrue(actor.awaitTermination(30, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            assertEquals(1, store.imageCount());
        }
    }

    @Test
    void twoSourceActorsHaveSeparateOwnedSealedStateAndFreshGeneration()
            throws Exception {
        Path manifest = fixture();
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var plan = admit(manifest, store).plan();
            var left = plan.spawn(runtime, IsolatePolicy.developer());
            var right = plan.spawn(runtime, IsolatePolicy.developer());
            left.ready().get(30, TimeUnit.SECONDS);
            right.ready().get(30, TimeUnit.SECONDS);
            assertEquals(10L, send(left, 10L));
            assertEquals(2L, send(right, 2L));
            assertEquals(11L, send(left, 1L));
            assertEquals(5L, send(right, 3L));
            left.stop();
            assertTrue(left.awaitTermination(30, TimeUnit.SECONDS));
            assertThrows(RuntimeException.class, () -> left.send(100L));
            var newGeneration = plan.spawn(runtime, IsolatePolicy.developer());
            newGeneration.ready().get(30, TimeUnit.SECONDS);
            assertEquals(4L, send(newGeneration, 4L));
            right.stop();
            newGeneration.stop();
            assertTrue(right.awaitTermination(30, TimeUnit.SECONDS));
            assertTrue(newGeneration.awaitTermination(30, TimeUnit.SECONDS));
            assertTrue(right.failure().isEmpty());
            assertTrue(newGeneration.failure().isEmpty());
            assertEquals(1, store.imageCount());
        }
    }

    @Test
    void untrackedSourcesAndMissingConstituentsAreRejected() throws Exception {
        Path manifest = fixture();
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            Path added = project.resolve("src/extra.atto");
            Files.writeString(added, "namespace counter; declare actor Rogue;");
            assertThrows(IllegalArgumentException.class,
                    () -> admit(manifest, store));
            Files.delete(added);
            Files.delete(project.resolve("src/critical.atto"));
            assertThrows(IllegalArgumentException.class,
                    () -> admit(manifest, store));
        }
    }

    @Test
    void sourceNamespaceAndUnexpectedImportsCannotJoinAnActor() throws Exception {
        Path manifest = fixture();
        Path method = project.resolve("src/receive.atto");
        String original = Files.readString(method);
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            Files.writeString(method, original.replace(
                    "use namespace counter;", "namespace other;"));
            var wrong = assertThrows(IllegalArgumentException.class,
                    () -> admit(manifest, store));
            assertTrue(wrong.getMessage().contains("cross-namespace"), wrong.getMessage());
            Files.writeString(method, "use namespace counter;\n"
                    + "import class Files from \"java:java.nio.file.Files\";\n"
                    + original.substring("use namespace counter;".length()));
            assertThrows(RuntimeException.class,
                    () -> admit(manifest, store));
        }
    }

    @Test
    void criticalStateEscapesAndUnsafeIngressMutationsNeverAcquireExecution()
            throws Exception {
        Path manifest = fixture();
        Path critical = project.resolve("src/critical.atto");
        Path receive = project.resolve("src/receive.atto");
        String originalCritical = Files.readString(critical);
        String originalReceive = Files.readString(receive);
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            Files.writeString(critical, originalCritical.replace(
                    "s.value = s.value + delta;", "c.value = c.value + delta;"));
            assertThrows(RuntimeException.class, () -> admit(manifest, store));
            Files.writeString(critical, originalCritical.replace(
                    "s.value = s.value + delta;",
                    "await something(); s.value = s.value + delta;"));
            assertThrows(RuntimeException.class, () -> admit(manifest, store));
            Files.writeString(critical, originalCritical);
            Files.writeString(receive, originalReceive.replace(
                    "c.add(mail.value)", "c.add(1)"));
            assertThrows(RuntimeException.class, () -> admit(manifest, store));
        }
    }

    @Test
    void symlinkedOrOversizedSourceIsNeverLoaded() throws Exception {
        Path manifest = fixture();
        Path init = project.resolve("src/init.atto");
        Path outside = project.resolve("outside.atto");
        Files.writeString(outside, Files.readString(init));
        Files.delete(init);
        Files.createSymbolicLink(init, outside);
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            assertThrows(IllegalArgumentException.class,
                    () -> admit(manifest, store));
            Files.delete(init);
            Files.writeString(init, "A".repeat(1024 * 1024 + 1));
            assertThrows(IllegalArgumentException.class,
                    () -> admit(manifest, store));
        }
    }

    @Test
    void manifestRootRedirectAndMissingMetadataCannotSelectAmbientFiles()
            throws Exception {
        Path manifest = fixture();
        String good = Files.readString(manifest);
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            Files.writeString(manifest, good.replace(
                    "root = \".\"", "root = \"..\""));
            assertThrows(IllegalArgumentException.class,
                    () -> admit(manifest, store));
            Files.writeString(manifest, good.replace(
                    "name = \"counter\"", "name = \"\""));
            assertThrows(IllegalArgumentException.class,
                    () -> admit(manifest, store));
            Files.writeString(manifest, good.replace(
                    "roots = [\"src\"]", "roots = [\"../outside\"]"));
            assertThrows(RuntimeException.class,
                    () -> admit(manifest, store));
        }
    }

    @Test
    void sourceMutationProducesNewContentAddressedCodeImage() throws Exception {
        Path manifest = fixture();
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            var first = admit(manifest, store);
            Path state = project.resolve("src/state.atto");
            Files.writeString(state, Files.readString(state).replace(
                    "let int value : 0;", "let int value : 9;"));
            var second = admit(manifest, store);
            assertNotEquals(first.sourceSha256(), second.sourceSha256());
            assertEquals(first.manifestSha256(), second.manifestSha256());
            assertEquals(2, store.imageCount());
        }
    }
}
