package dev.oreslang;

import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.SharedCodeImageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual runtime dispatch of the checked, compiler-derived .atto receive MIR.
 *
 * These tests execute a real ActorRuntime mailbox and actor-owned direct
 * memory, NOT a host Counter handler. The physical Atto main entry remains
 * uncompiled in this bounded V1 slice and is NOT claimed to execute here.
 */
final class ActorCriticalStateGuestV1Test {
    @TempDir Path root;

    private PackageNamespaceBuild physicalBuild() throws Exception {
        Path fixture = Path.of("contracts", "declared-actor-stateful-counter-v2");
        for (String path : new String[] {
                ".atto.pkg.toml", "src/counter/contract.atto",
                "src/counter/actor.atto", "src/counter/state.atto",
                "src/counter/receive.atto", "src/counter/method.atto",
                "src/counter/main.atto" }) {
            Path to = root.resolve(path);
            Files.createDirectories(to.getParent());
            Files.writeString(to, Files.readString(fixture.resolve(path)));
        }
        return new PackageNamespaceBuild(
                OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of()));
    }

    private static List<Long> outputs(ActorRuntime.ActorRef<Object> worker, int count)
            throws Exception {
        List<Long> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var step = worker.outputs().next().get(10, TimeUnit.SECONDS);
            assertFalse(step.done(), "expected a real actor output, not EOF");
            var output = step.value();
            assertEquals(worker.id(), output.actorId());
            values.add(assertInstanceOf(Long.class, output.value()));
        }
        return List.copyOf(values);
    }

    @Test
    void physicalAttoReceiveExecutesOnActorMailboxAndOwnerHeap() throws Exception {
        var build = physicalBuild();
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime()) {
            var compiled = build.compileCriticalStateActorGuestV1(images);
            var worker = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, compiled, "Counter");
            worker.getReady().get(10, TimeUnit.SECONDS);
            for (long message : new long[] { 1, 2, 3, 4 }) worker.send(message);
            assertEquals(List.of(1L, 3L, 6L, 10L), outputs(worker, 4));
            worker.getDone().get(10, TimeUnit.SECONDS);
            assertFalse(worker.isAlive(), "source self.end must retire its actor");
            assertEquals(1, images.imageCount());
        }
        // The ordinary full-package compiler must NOT treat this bounded
        // source receive test as permission to execute unlowered main.atto.
        var denied = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(denied.getMessage().contains("DECLARED_ACTOR_CODEGEN_UNSUPPORTED"));
    }

    @Test
    void twoInstancesUseDifferentActorOwnedHeapAndMailbox() throws Exception {
        var build = physicalBuild();
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime()) {
            var compiled = build.compileCriticalStateActorGuestV1(images);
            var a = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, compiled, "Counter");
            var b = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, compiled, "Counter");
            a.getReady().get(10, TimeUnit.SECONDS);
            b.getReady().get(10, TimeUnit.SECONDS);
            a.send(1L); a.send(2L); a.send(3L);
            b.send(7L); b.send(8L);
            assertEquals(List.of(1L, 3L, 6L), outputs(a, 3));
            assertEquals(List.of(7L, 15L), outputs(b, 2));
            a.send(4L);
            assertEquals(List.of(10L), outputs(a, 1));
            a.getDone().get(10, TimeUnit.SECONDS);
            b.send(-4L);
            assertEquals(List.of(11L), outputs(b, 1));
            b.send(4L);
            assertEquals(List.of(15L), outputs(b, 1));
            b.getDone().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void i64OverflowIsCheckedBeforePersistentStateMutation() throws Exception {
        var build = physicalBuild();
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime()) {
            var compiled = build.compileCriticalStateActorGuestV1(images);
            var overflowing = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, compiled, "Counter");
            var peer = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, compiled, "Counter");
            overflowing.getReady().get(10, TimeUnit.SECONDS);
            peer.getReady().get(10, TimeUnit.SECONDS);
            overflowing.send(Long.MAX_VALUE);
            assertEquals(List.of(Long.MAX_VALUE), outputs(overflowing, 1));
            overflowing.send(1L);
            assertThrows(ExecutionException.class,
                    () -> overflowing.getDone().get(10, TimeUnit.SECONDS));
            peer.send(2L);
            peer.send(4L);
            assertEquals(List.of(2L, 6L), outputs(peer, 2));
            peer.getDone().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void executableCriticalMirUsesChangedAttoSchemaAndStopPredicate() throws Exception {
        var build = physicalBuild();
        Path state = root.resolve("src/counter/state.atto");
        Path ingress = root.resolve("src/counter/receive.atto");
        Files.writeString(state, Files.readString(state)
                .replace("let int total : 0;", "let int count : 17;"));
        Files.writeString(ingress, Files.readString(ingress)
                .replace("s.total", "s.count")
                .replace("if mail.value == 4; do", "if mail.value == 2; do"));
        var manifestMir = build.lowerCriticalStateMirV1();
        assertEquals("count", manifestMir.field());
        assertEquals(17L, manifestMir.initialValue());
        assertEquals(2L, manifestMir.stopOnMailValue());
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime()) {
            var compiled = build.compileCriticalStateActorGuestV1(images);
            var worker = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, compiled, "Counter");
            worker.getReady().get(10, TimeUnit.SECONDS);
            worker.send(7L);
            worker.send(2L);
            assertEquals(List.of(24L, 26L), outputs(worker, 2),
                    "actor output must follow the changed Atto source operands");
            worker.getDone().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void privateActorKindCannotSpoofSharedDeclaredSourceKind() throws Exception {
        var build = physicalBuild();
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime()) {
            var compiled = build.compileCriticalStateActorGuestV1(images);
            var forgedKind = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, compiled, "Counter");
            assertThrows(ExecutionException.class,
                    () -> forgedKind.getReady().get(10, TimeUnit.SECONDS));
            assertTrue(forgedKind.failure().isPresent(),
                    "incorrect actor kind must fail startup before state emission");
        }
    }

    @Test
    void runtimeInitMustNotBeSilentlySkippedByReceiveOnlyLowerer() throws Exception {
        var build = physicalBuild();
        Path ingress = root.resolve("src/counter/receive.atto");
        String source = Files.readString(ingress);
        String prefix = "namespace counter;\n";
        String init = "external mtd (Counter c) init(): void { return; }\n";
        assertTrue(source.startsWith(prefix));
        Files.writeString(ingress, prefix + init + source.substring(prefix.length()));
        try (var images = new SharedCodeImageStore()) {
            assertThrows(IllegalArgumentException.class,
                    () -> build.compileCriticalStateActorGuestV1(images));
            assertEquals(0, images.imageCount(),
                    "an unsupported lifecycle hook must never publish a code image");
        }
    }

    @Test
    void nonI64MailNeverCoercesOrPublishesASpuriousOutput() throws Exception {
        var build = physicalBuild();
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime()) {
            var compiled = build.compileCriticalStateActorGuestV1(images);
            var worker = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, compiled, "Counter");
            worker.getReady().get(10, TimeUnit.SECONDS);
            worker.send("4");
            assertThrows(ExecutionException.class,
                    () -> worker.getDone().get(10, TimeUnit.SECONDS));
            assertTrue(worker.failure().isPresent());
        }
    }
}
