package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.VirtualActorActivationRegistry;
import dev.oreslang.runtime.VirtualActorCurrentMainDirectory;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First JVM compiler-backed virtual-Cart *transition* slice.
 *
 * The code inside each physical ActorRuntime worker calls a real compiled
 * guest .atto function. ActorGroup owns the worker and its generation; the
 * directory reads the single runtime ledger. The logical identity survives
 * STOP and the next physical incarnation. No mutable guest state crosses
 * worker boundaries; the quantity starts at zero again on reactivation.
 *
 * This is not yet source-level 'define virtual actor Cart': TypeChecker
 * deliberately rejects protected virtual actor MIR until compiler-owned
 * state, auto-activation, CONTROL and persistence lowering are implemented.
 * It also is not an AOT/native-image or cross-backend parity result.
 */
@Timeout(90)
final class AttoCartPhysicalActivationGuestTest {
    private static final Path CONTRACT_ROOT = Path.of("contracts", "virtual-actor-cart-v1")
            .toAbsolutePath().normalize();
    private static final Map<String, String> SOURCE_BLOBS = Map.of(
            "positive/cart_ops.atto", "1db480666b6bca1233d9a37a6b97a12a7b03ec0e",
            "positive/cart_main.atto", "ef042b5bf1ffae93b13e0a43dc2299901a477d38",
            "negative/virtual_cart_unsupported.atto", "c684bf630127b2e31de39c8c6116838448729f43",
            "expected/cart_main.stdout", "12716757f1d259c38db007611b92538586bc08e4");
    private static final String DOMAIN = "cart-tenant";
    @TempDir Path scratch;

    private static Path contract(String relative) throws Exception {
        assertTrue(SOURCE_BLOBS.containsKey(relative),
                "Cart contract source not in the reviewed immutable corpus");
        Path file = CONTRACT_ROOT;
        for (String component : relative.split("/")) {
            assertFalse(component.isBlank() || component.equals(".") || component.equals(".."),
                    "unsafe Cart conformance fixture path");
            file = file.resolve(component);
            assertFalse(Files.isSymbolicLink(file),
                    "symlink in canonical Cart contract: " + relative);
        }
        assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
                "missing canonical Cart contract: " + relative);
        byte[] contents = Files.readAllBytes(file);
        assertTrue(contents.length > 0 && contents.length <= 8192,
                "canonical Cart fixture size budget");
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + contents.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        digest.update(contents);
        assertEquals(SOURCE_BLOBS.get(relative), HexFormat.of().formatHex(digest.digest()),
                "canonical Cart fixture differs from its pinned Git blob");
        return file;
    }

    private static String fixture(String name) throws Exception {
        String folder = name.equals("virtual_cart_unsupported.atto") ? "negative" : "positive";
        return Files.readString(contract(folder + "/" + name), StandardCharsets.UTF_8);
    }

    /** A one-shot bounded host grant per observation prevents test-only TTL flakiness. */
    private static Optional<VirtualActorCurrentMainDirectory.Snapshot> inspect(
            VirtualActorCurrentMainDirectory directory, Object session,
            VirtualActorActivationRegistry.Identity identity) {
        var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
        return directory.inspect(grant, session, identity);
    }

    private static Optional<VirtualActorCurrentMainDirectory.Snapshot> inspectAddress(
            VirtualActorCurrentMainDirectory directory, Object session, String address) {
        var grant = directory.issueGrant(session, TimeUnit.SECONDS.toNanos(5));
        return directory.inspectAddress(grant, session, address);
    }

    private Path stage(String name) throws Exception {
        Path path = scratch.resolve(name);
        Files.writeString(path, fixture(name), StandardCharsets.UTF_8);
        return path;
    }

    /**
     * One Truffle context per physical incarnation. Its guest state is only
     * accessed by that nonreentrant worker; the host closes it after done().
     * This adapter is intentionally test-scoped, not a public unsafe bridge.
     */
    private static final class CompiledCartTurn implements AutoCloseable {
        private final Context context;
        private final Value unit;
        private long quantity;

        CompiledCartTurn(String canonicalAtto) throws Exception {
            // Use the production parser + static checker, not a Java numeric
            // reference implementation posing as compiler-backed evidence.
            TypeChecker.check(Parser.parse(canonicalAtto));
            Context opened = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build();
            try {
                Source source = Source.newBuilder(OresLanguage.ID,
                                canonicalAtto, "cart_ops.atto")
                        .mimeType(OresLanguage.MIME_TYPE).buildLiteral();
                Value parsed = opened.parse(source);
                parsed.execute(OresEvalRootNode.LINK_ONLY_COMMAND);
                parsed.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
                this.unit = parsed;
                this.context = opened;
            } catch (Throwable failure) {
                opened.close(true);
                throw failure;
            }
        }

        long apply(long delta) {
            // The value comes from executing checked AttoLang bytecode/Truffle,
            // NOT a Java quantity += delta operation.
            quantity = unit.execute(OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                    "cart_step", quantity, delta).asLong();
            return quantity;
        }

        @Override public void close() {
            context.close();
        }
    }

    @Test
    void realLinkedAttoCartTransitionExecutesAndProducesExpectedGuestOutput()
            throws Exception {
        stage("cart_ops.atto");
        Path entry = stage("cart_main.atto");
        assertEquals(2, LinkedProgramRunner.validate(entry, Map.of()).units().size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out, err);
        assertEquals(2, result.units().size());
        assertEquals(Files.readString(contract("expected/cart_main.stdout"),
                        StandardCharsets.UTF_8),
                out.toString(StandardCharsets.UTF_8),
                "linked compiled .atto output must match the shared cross-backend vector");
    }

    @Test
    void oneLogicalCartAddressSurvivesPhysicalStopAndNewCompiledGuestActivation()
            throws Exception {
        String source = fixture("cart_ops.atto");
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            try (var directory = new VirtualActorCurrentMainDirectory(group, DOMAIN)) {
                Object session = new Object();
                VirtualActorActivationRegistry.Identity logical =
                        directory.resolve("Cart", "order-42");
                String stableAddress = directory.address(logical);
                assertTrue(inspect(directory, session, logical).isEmpty(),
                        "obtaining a logical address must not start a physical worker");

                AtomicReference<CompiledCartTurn> firstGuest = new AtomicReference<>();
                AtomicLong firstLastOutput = new AtomicLong(-1);
                assertEquals(1L, group.reserveNextVirtualActorGeneration(logical));
                ActorRuntime.ActorRef<Long> first = group.spawnWorker(
                        ActorRuntime.ActorKind.SHARED,
                        IsolatePolicy.developer(),
                        actorContext -> {
                            var guest = new CompiledCartTurn(source);
                            firstGuest.set(guest);
                            return (delta, ctx) -> firstLastOutput.set(guest.apply(delta));
                        });
                first.getReady().get(5, TimeUnit.SECONDS);
                group.bindVirtualActorIdentity(first, logical, 1L);
                first.send(2L);
                first.send(5L);
                first.drain().get(5, TimeUnit.SECONDS);
                assertEquals(7L, firstLastOutput.get(),
                        "real .atto cart_step must run inside the first physical actor");
                assertEquals(first.id(), inspect(directory, session, logical)
                        .orElseThrow().physicalActorId().orElseThrow());
                first.gracefulStop().get(5, TimeUnit.SECONDS);
                first.getDone().get(5, TimeUnit.SECONDS);
                firstGuest.get().close();
                assertThrows(RuntimeException.class, () -> firstGuest.get().apply(9L),
                        "closed physical guest context must not accept stale invocations");

                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.PASSIVATED,
                        inspect(directory, session, logical).orElseThrow().lifecycle());

                AtomicReference<CompiledCartTurn> secondGuest = new AtomicReference<>();
                AtomicLong secondLastOutput = new AtomicLong(-1);
                assertEquals(2L, group.reserveNextVirtualActorGeneration(logical));
                ActorRuntime.ActorRef<Long> second = group.spawnWorker(
                        ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                        actorContext -> {
                            var guest = new CompiledCartTurn(source);
                            secondGuest.set(guest);
                            return (delta, ctx) -> secondLastOutput.set(guest.apply(delta));
                        });
                second.getReady().get(5, TimeUnit.SECONDS);
                group.bindVirtualActorIdentity(second, logical, 2L);
                assertNotEquals(first.id(), second.id(), "physical worker identity must change");
                assertEquals(stableAddress, directory.address(logical),
                        "logical address must not encode physical ActorId or generation");
                assertEquals(2L, inspectAddress(directory, session, stableAddress)
                        .orElseThrow().activationGeneration());
                assertEquals(second.id(), inspect(directory, session, logical)
                        .orElseThrow().physicalActorId().orElseThrow());
                assertFalse(group.isCurrentVirtualActorGeneration(logical, 1L));
                assertThrows(IllegalStateException.class, () -> first.send(99L),
                        "retired physical actor ref cannot target the new activation");

                second.send(3L);
                second.drain().get(5, TimeUnit.SECONDS);
                assertEquals(3L, secondLastOutput.get(),
                        "guest state was not restored: only stable ID survives this slice");
                second.gracefulStop().get(5, TimeUnit.SECONDS);
                second.getDone().get(5, TimeUnit.SECONDS);
                secondGuest.get().close();
                assertEquals(VirtualActorCurrentMainDirectory.Lifecycle.PASSIVATED,
                        inspect(directory, session, logical).orElseThrow().lifecycle());
            }
            group.close();
        }
    }

    @Test
    @Timeout(60)
    void guestCartOverflowTriggersSanitizedTerminalControlAndFencesReactivation()
            throws Exception {
        String source = fixture("cart_ops.atto");
        try (ExecutorService control = Executors.newSingleThreadExecutor();
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     ActorRuntime.DispatcherConfig.defaults(),
                     ActorRuntime.TurnExecutor.direct(),
                     task -> {
                         var admission = new CompletableFuture<Void>();
                         control.execute(() -> {
                             try {
                                 task.run();
                                 admission.complete(null);
                             } catch (Throwable problem) {
                                 admission.completeExceptionally(problem);
                             }
                         });
                         return dev.oreslang.runtime.OresFuture.from(admission);
                     })) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            var identity = new VirtualActorActivationRegistry.Identity(
                    DOMAIN, "Cart", "overflow-terminal");
            AtomicReference<ActorRuntime.VirtualActorTerminalNotice> delivered =
                    new AtomicReference<>();
            CountDownLatch notified = new CountDownLatch(1);
            CompletableFuture<Void> supervisorPolicy = new CompletableFuture<>();
            group.installVirtualActorTerminalHandler(notice -> {
                delivered.set(notice);
                notified.countDown();
                return supervisorPolicy;
            });
            AtomicReference<CompiledCartTurn> ownedGuest = new AtomicReference<>();
            AtomicLong lastSuccessful = new AtomicLong(-1L);
            assertEquals(1L, group.reserveNextVirtualActorGeneration(identity));
            ActorRuntime.ActorRef<Long> physical = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    actorContext -> {
                        var guest = new CompiledCartTurn(source);
                        ownedGuest.set(guest);
                        return (Long delta, ActorRuntime.ActorContext<Long> ctx) ->
                                lastSuccessful.set(guest.apply(delta));
                    });
            physical.getReady().get(5, TimeUnit.SECONDS);
            group.bindVirtualActorIdentity(physical, identity, 1L);
            physical.send(Long.MAX_VALUE);
            physical.drain().get(5, TimeUnit.SECONDS);
            assertEquals(Long.MAX_VALUE, lastSuccessful.get());

            physical.send(1L); // Checked i64 guest overflow must terminate.
            assertThrows(ExecutionException.class,
                    () -> physical.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(notified.await(5, TimeUnit.SECONDS),
                    "real .atto guest failure must reach supervisor CONTROL");
            var receipt = delivered.get();
            assertEquals(identity, receipt.identity());
            assertEquals(physical.id(), receipt.actorId());
            assertEquals(1L, receipt.generation());
            assertEquals(ActorRuntime.VirtualActorTerminalKind.TERMINAL_FAILURE, receipt.kind());
            assertFalse(receipt.toString().contains("9223372036854775807"),
                    "CONTROL cannot leak guest data or values");
            assertThrows(IllegalStateException.class,
                    () -> group.reserveNextVirtualActorGeneration(identity),
                    "pending asynchronous CONTROL must block a newer generation");

            ownedGuest.get().close(); // After physical finalization, not before.
            supervisorPolicy.complete(null);
            long successor = -1L;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try {
                    successor = group.reserveNextVirtualActorGeneration(identity);
                    break;
                } catch (IllegalStateException pending) {
                    Thread.sleep(10);
                }
            }
            assertEquals(2L, successor, "CONTROL acknowledgement should release restart gate");
            group.abortVirtualActorGeneration(identity, successor);
            group.close();
        }
    }

    @Test
    void protectedVirtualActorIsStillRejectedBeforeAnyUnfencedLowering()
            throws Exception {
        String source = fixture("virtual_cart_unsupported.atto");
        Ast.Program parsed = Parser.parse(source);
        assertInstanceOf(Ast.VirtualActorDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(parsed));
        assertTrue(error.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"),
                "compiler must not silently alias virtual actor to an ordinary class");

        Path file = stage("virtual_cart_unsupported.atto");
        assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.validate(file, Map.of()),
                "negative compile fixture must fail before any guest execution");
    }

    @Test
    void cartGuestCheckedOverflowCannotWrapIntoNewActorState() throws Exception {
        String source = fixture("cart_ops.atto");
        try (CompiledCartTurn cart = new CompiledCartTurn(source)) {
            assertEquals(Long.MAX_VALUE, cart.apply(Long.MAX_VALUE));
            assertThrows(RuntimeException.class, () -> cart.apply(1),
                    "AttoLang checked integer addition must reject overflow");
        }
    }
}
