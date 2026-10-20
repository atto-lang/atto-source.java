package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-substrate proof for a future separate source-level
 * `define supervisor S as ... end` declaration.
 *
 * These tests intentionally do not claim that the source grammar or
 * supervisor guest callbacks are executable.
 */
@Timeout(25)
final class ExplicitSupervisorDeclarationProofTest {
    @Test
    void explicitSupervisorSLaunchesBWithoutPromotingNearbyActorA() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup parentOfA = runtime.createActorGroup();
            ActorRuntime.ActorGroup supervisorS = runtime.createActorGroup();
            AtomicReference<Throwable> unauthorizedSpawn = new AtomicReference<>();
            AtomicReference<Throwable> unauthorizedControl = new AtomicReference<>();

            // A and S are declared in the same Java fixture, but no implicit
            // supervisor capability is obtained by lexical proximity.
            // Trusted factory is used only to inject a hostile host handle.
            ActorRuntime.ActorRef<String> actorA = runtime.spawnPrivateTrusted(
                    factory -> (message, self) -> {
                        if (!"probe".equals(message)) return;
                        try {
                            supervisorS.spawnWorker(
                                    ActorRuntime.ActorKind.PRIVATE,
                                    IsolatePolicy.developer(),
                                    child -> (work, context) -> { });
                        } catch (Throwable denial) {
                            unauthorizedSpawn.set(denial);
                        }
                        try {
                            supervisorS.installSupervisorProcess(
                                    1,
                                    ignored -> CompletableFuture.completedFuture(
                                            SupervisorProcess.Directive.ACKNOWLEDGE),
                                    ignored -> { });
                        } catch (Throwable denial) {
                            unauthorizedControl.set(denial);
                        }
                    });
            parentOfA.manageWorker(actorA);
            actorA.getReady().get(5, TimeUnit.SECONDS);
            actorA.send("probe");
            actorA.drain().get(5, TimeUnit.SECONDS);
            assertInstanceOf(SecurityException.class, unauthorizedSpawn.get());
            assertInstanceOf(SecurityException.class, unauthorizedControl.get());
            assertEquals(0, supervisorS.workerCount(),
                    "failed unauthorized spawn must not publish a child");

            CountDownLatch decided = new CountDownLatch(1);
            AtomicReference<SupervisorProcess.Resolution> resolution =
                    new AtomicReference<>();
            AtomicReference<Boolean> callbackInActor = new AtomicReference<>();
            SupervisorProcess process = supervisorS.installSupervisorProcess(
                    8,
                    notice -> CompletableFuture.completedFuture(
                            SupervisorProcess.Directive.ACKNOWLEDGE),
                    result -> {
                        callbackInActor.set(ActorRuntime.inActorExecution());
                        resolution.set(result);
                        decided.countDown();
                    });

            // The supervisor's trusted launch path creates B directly under S.
            ActorRuntime.ActorRef<String> actorB = supervisorS.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    factory -> (message, self) -> {
                        if ("fail".equals(message)) {
                            throw new IllegalStateException("B terminal failure");
                        }
                    });
            actorB.getReady().get(5, TimeUnit.SECONDS);
            assertEquals(parentOfA.supervisorId(),
                    actorA.supervisorId().orElseThrow());
            assertEquals(supervisorS.supervisorId(),
                    actorB.supervisorId().orElseThrow());
            assertNotEquals(actorA.supervisorId(), actorB.supervisorId(),
                    "same-file declaration cannot change explicit ownership");

            actorB.send("fail");
            assertTrue(decided.await(10, TimeUnit.SECONDS));
            SupervisorProcess.Resolution event = resolution.get();
            assertNotNull(event);
            assertEquals(supervisorS.supervisorId(),
                    event.notice().supervisorId());
            assertEquals(supervisorS.id(), event.notice().groupId());
            assertEquals(actorB.id(), event.notice().actorId());
            assertEquals(SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                    event.notice().kind());
            assertEquals(Boolean.FALSE, callbackInActor.get(),
                    "supervisor CONTROL must not re-enter A or B");
            assertEquals(0, parentOfA.queuedSupervisorFailures(),
                    "B failure belongs to S, not A's parent group");
            assertEquals(1, process.resolved());
            assertEquals(0, process.undelivered());

            supervisorS.close();
            parentOfA.close();
            process.close();
        }
    }

    @Test
    void ordinaryASpawnsBWithoutMintingAnotherSupervisor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup supervisorS = runtime.createActorGroup();
            AtomicReference<ActorRuntime.ActorRef<String>> childB =
                    new AtomicReference<>();

            // A is an ordinary actor. The spawn ancestry A -> B is retained;
            // B inherits S's supervision and does not promote A.
            ActorRuntime.ActorRef<String> actorA = runtime.spawnPrivateTrusted(
                    factory -> (message, context) -> {
                        if ("spawn-b".equals(message)) {
                            childB.set(context.runtime().<String>spawnPrivate(
                                    child -> (work, receiver) -> { }));
                        }
                    });
            supervisorS.manageWorker(actorA);
            actorA.getReady().get(5, TimeUnit.SECONDS);
            actorA.send("spawn-b");
            actorA.drain().get(5, TimeUnit.SECONDS);

            ActorRuntime.ActorRef<String> actorB = childB.get();
            assertNotNull(actorB);
            assertEquals(supervisorS.supervisorId(),
                    actorA.supervisorId().orElseThrow());
            assertEquals(supervisorS.supervisorId(),
                    actorB.supervisorId().orElseThrow());
            assertEquals(2, supervisorS.workerCount());
            assertTrue(actorA.childIds().contains(actorB.id()),
                    "supervision is independent from spawn ancestry");
            assertEquals(0, supervisorS.memberCount(),
                    "spawn must not imply event-bus membership");

            supervisorS.close();
            assertTrue(actorA.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actorB.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
