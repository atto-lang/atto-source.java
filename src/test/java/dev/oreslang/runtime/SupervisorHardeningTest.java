package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Adversarial admission and lifecycle races for host supervisor CONTROL. */
@Timeout(20)
final class SupervisorHardeningTest {
    private static SupervisorFailureControl.Notice notice(
            ActorRuntime.SupervisorId supervisor, ActorRuntime.ActorGroupId group) {
        return new SupervisorFailureControl.Notice(
                supervisor, group, ActorRuntime.ActorId.create(),
                Optional.empty(), SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                "Fault", "terminal");
    }

    @Test
    void inlineExecutorCannotRunFailureHandlerOrDeadlockOnAdmission() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        AtomicInteger calls = new AtomicInteger();
        SupervisorFailureControl lane = new SupervisorFailureControl(
                supervisor, group, Runnable::run, 2);
        lane.install(n -> calls.incrementAndGet());
        lane.publish(notice(supervisor, group));
        assertEquals(0, calls.get(), "inline control handler crossed a child carrier");
        assertFalse(lane.healthy());
        assertTrue(lane.failure().isPresent());
        assertEquals(1, lane.undelivered());
    }

    @Test
    void inlineExecutorCannotRunSupervisorPolicyOrDecision() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        AtomicInteger calls = new AtomicInteger();
        try (SupervisorProcess process = new SupervisorProcess(
                supervisor, group, Runnable::run, 2,
                n -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            SupervisorProcess.Directive.RESTART);
                },
                r -> calls.incrementAndGet())) {
            assertThrows(IllegalStateException.class,
                    () -> process.accept(notice(supervisor, group)),
                    "synchronous CONTROL scheduling failure must reject admission");
            assertEquals(0, calls.get());
            assertFalse(process.healthy());
            assertTrue(process.failure().orElse("").contains("inline"));
            assertEquals(1, process.undelivered());
            assertEquals(0, process.resolved(),
                    "failed CONTROL must never be marked restarted");
        }
    }

    @Test
    void unmanagedParentWithLiveChildCannotBeAdoptedWithoutAdoptingChild() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> root = runtime.spawnPrivate(
                    factory -> (message, actor) ->
                            actor.runtime().spawnPrivate(
                                    childFactory -> (body, childContext) -> { }));
            root.getReady().get(5, TimeUnit.SECONDS);
            root.send("spawn");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (root.childIds().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(root.childIds().isEmpty());
            assertThrows(IllegalStateException.class, () -> group.manageWorker(root),
                    "admitting only the parent would strand its descendants");
            assertEquals(0, group.workerCount());
            assertTrue(root.supervisorId().isEmpty());
            root.cancel();
            assertTrue(root.awaitTermination(5, TimeUnit.SECONDS));
            group.close();
        }
    }

    @Test
    void managedParentWithLiveChildCannotTransferOutOfSupervision() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> root = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    factory -> (message, actor) ->
                            actor.runtime().spawnPrivate(
                                    childFactory -> (body, childContext) -> { }));
            root.getReady().get(5, TimeUnit.SECONDS);
            root.send("spawn");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (root.childIds().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(root.childIds().isEmpty());
            assertThrows(IllegalStateException.class, () -> group.releaseWorker(root),
                    "release must synchronize with and reject a live child");
            assertEquals(2, group.workerCount());
            assertEquals(group.supervisorId(), root.supervisorId().orElseThrow());
            group.close();
            assertTrue(root.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    @Test
    void creatorActorMaySuperviseOwnChildGroupWithoutLosingParentAncestry() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup parentGroup = runtime.createActorGroup();
            AtomicReference<ActorRuntime.ActorGroup> subGroup = new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
            AtomicReference<SupervisorFailureControl.Notice> received = new AtomicReference<>();

            // Trusted host fixture captures observer references. The child and
            // grandchild factories themselves have no captured host state.
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivateTrusted(
                    factory -> (message, self) -> {
                        if ("open".equals(message)) {
                            ActorRuntime.ActorGroup owned = self.runtime().createActorGroup();
                            subGroup.set(owned);
                            childRef.set(owned.spawnWorker(
                                    ActorRuntime.ActorKind.PRIVATE,
                                    IsolatePolicy.developer(),
                                    childFactory -> (work, childContext) -> {
                                        if ("spawn".equals(work)) {
                                            childContext.runtime().spawnPrivate(
                                                    grandFactory -> (input, grandContext) -> {
                                                        if ("fail".equals(input))
                                                            throw new IllegalStateException("grandchild failed");
                                                    });
                                        }
                                    }));
                        } else if ("poll".equals(message)) {
                            received.set(subGroup.get().pollSupervisorNotice().orElse(null));
                        }
                    });
            parentGroup.manageWorker(supervisor);
            supervisor.getReady().get(5, TimeUnit.SECONDS);
            supervisor.send("open");
            supervisor.drain().get(5, TimeUnit.SECONDS);

            ActorRuntime.ActorGroup owned = subGroup.get();
            assertNotNull(owned);
            assertNotEquals(parentGroup.supervisorId(), owned.supervisorId(),
                    "actor-owned group creates an independent supervisor identity");
            assertEquals(1, parentGroup.workerCount());
            assertEquals(1, owned.workerCount());
            assertEquals(1, owned.memberCount(),
                    "only creator joins its own group; managed descendants do not join implicitly");

            ActorRuntime.ActorRef<String> child = childRef.get();
            child.send("spawn");
            child.drain().get(5, TimeUnit.SECONDS);
            assertEquals(2, owned.workerCount(),
                    "ordinary B -> C spawn continues to inherit B's child group");
            ActorRuntime.ActorRef<?> grandchild = owned.workers().stream()
                    .filter(ref -> !ref.id().equals(child.id()))
                    .findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<String> grandchildString =
                    (ActorRuntime.ActorRef<String>) grandchild;
            grandchildString.send("fail");
            assertTrue(grandchild.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, owned.queuedSupervisorFailures());

            // The host can inspect failure counts but may not consume the
            // actor-supervisor's reserved CONTROL stream.
            assertThrows(SecurityException.class, owned::pollSupervisorNotice);
            supervisor.send("poll");
            supervisor.drain().get(5, TimeUnit.SECONDS);
            SupervisorFailureControl.Notice notice = received.get();
            assertNotNull(notice);
            assertEquals(grandchild.id(), notice.actorId());
            assertEquals(Optional.of(child.id()), notice.spawnedBy());
            assertEquals(owned.supervisorId(), notice.supervisorId());
            assertEquals(SupervisorFailureControl.Kind.TERMINAL_FAILURE, notice.kind());
            assertEquals(0, owned.queuedSupervisorFailures());

            parentGroup.close();
            assertTrue(supervisor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS),
                    "ancestor cancellation still tears down independently supervised descendants");
        }
    }

    @Test
    void controlPublicationIsRuntimeOnlyAndSanitizesBoundedDiagnostics() {
        try {
            var method = SupervisorFailureControl.class.getDeclaredMethod(
                    "publish", SupervisorFailureControl.Notice.class);
            assertFalse(java.lang.reflect.Modifier.isPublic(method.getModifiers()),
                    "untrusted callers must not inject public supervisor CONTROL notices");
        } catch (NoSuchMethodException failure) {
            fail(failure);
        }

        var id = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        SupervisorFailureControl control = new SupervisorFailureControl(
                id, group, runnable -> new Thread(runnable).start(), 2);
        String malicious = "failure\nforged\r" + "🚀".repeat(600);
        control.publish(new SupervisorFailureControl.Notice(
                id, group, ActorRuntime.ActorId.create(), Optional.empty(),
                SupervisorFailureControl.Kind.TERMINAL_FAILURE, "Type",
                malicious));
        var value = control.poll().orElseThrow();
        assertFalse(value.diagnostic().contains("\n"));
        assertFalse(value.diagnostic().contains("\r"));
        assertTrue(value.diagnostic().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
        assertTrue(control.poll().isEmpty(), "CONTROL must be delivered exactly once");
        assertThrows(IllegalStateException.class,
                () -> control.install(ignored -> { }),
                "host handlers cannot steal a CONTROL queue already claimed by the actor");

        SupervisorFailureControl emptyClaim = new SupervisorFailureControl(
                id, group, ignored -> { }, 2);
        assertTrue(emptyClaim.poll().isEmpty(),
                "even an empty actor poll claims exclusive CONTROL ownership");
        assertThrows(IllegalStateException.class,
                () -> emptyClaim.install(ignored -> { }));

        SupervisorFailureControl hostClaim = new SupervisorFailureControl(
                id, group, ignored -> { }, 2);
        hostClaim.install(ignored -> { });
        assertThrows(IllegalStateException.class, hostClaim::poll,
                "actor polling cannot consume host-owned CONTROL");
    }


    @Test
    void untrustedActorCannotMintSupervisorGroupOrReserveControlLane() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            // The policy has no ACTOR_SPAWN capability. Merely opening an
            // empty supervisor group must not bypass the capability check.
            ActorRuntime.ActorRef<String> actor = runtime.spawnUntrusted(
                    factory -> (message, context) -> context.runtime().createActorGroup());
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("try-create-supervisor");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isPresent(),
                    "supervisor authority denial must terminate the offending actor turn");
            assertEquals(0, runtime.actorGroupCount(),
                    "failed untrusted supervisor admission may not reserve a CONTROL lane");
        }
    }

}
