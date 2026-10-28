package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A host-only lifecycle handle is not guest authority even if host code
 * accidentally captures it in an actor's behavior closure.
 */
@Timeout(15)
final class ActorControlHandleIsolationTest {
    @Test
    void capturedSupervisorControlHandleCannotBeInspectedOrUsedByActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> child = runtime.spawnPrivateTrusted(
                    factory -> (message, context) -> { });
            ActorRuntime.ActorControlHandle leaked = runtime.controlHandle(child);

            // A trusted host is permitted to inspect its own runtime.
            assertEquals(child.id(), leaked.id());
            assertEquals(child.kind(), leaked.kind());
            assertTrue(leaked.isAlive());
            assertTrue(leaked.failure().isEmpty());
            assertTrue(leaked.parentId().isEmpty());
            assertTrue(leaked.childIds().isEmpty());

            CountDownLatch inspected = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> attacker = runtime.spawnPrivateTrusted(
                    factory -> (message, context) -> {
                        try {
                            assertThrows(SecurityException.class, leaked::id);
                            assertThrows(SecurityException.class, leaked::kind);
                            assertThrows(SecurityException.class, leaked::isAlive);
                            assertThrows(SecurityException.class, leaked::failure);
                            assertThrows(SecurityException.class, leaked::parentId);
                            assertThrows(SecurityException.class, leaked::childIds);
                            assertThrows(SecurityException.class,
                                    () -> leaked.awaitTermination(0, TimeUnit.NANOSECONDS));
                            assertThrows(SecurityException.class, leaked::stop);
                            assertThrows(SecurityException.class, leaked::cancel);
                            assertThrows(SecurityException.class, leaked::kill);
                            assertThrows(SecurityException.class,
                                    () -> runtime.controlHandle(child));
                        } catch (Throwable failure) {
                            unexpected.set(failure);
                        } finally {
                            inspected.countDown();
                        }
                    });
            attacker.send(1);
            assertTrue(inspected.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get(), "captured host handle must not grant actor CONTROL");
            assertTrue(child.isAlive(), "denied calls must not affect sibling lifecycle");

            leaked.stop(); // Still usable from the trusted host.
            assertTrue(leaked.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(leaked.isAlive());
        }
    }
    @Test
    void removingLogicalContextDoesNotGrantHostOrForeignRuntimeAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime other = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> protectedChild = runtime.spawnPrivateTrusted(
                    factory -> (message, context) -> { });
            ActorRuntime.ActorRef<Integer> foreignChild = other.spawnPrivateTrusted(
                    factory -> (message, context) -> { });
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> attacker = runtime.spawnPrivateTrusted(
                    factory -> (message, context) -> {
                        try {
                            java.lang.reflect.Field contextField =
                                    ActorRuntime.class.getDeclaredField("CURRENT_ACTOR_EXECUTION");
                            contextField.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            ThreadLocal<Object> currentExecution =
                                    (ThreadLocal<Object>) contextField.get(null);
                            Object original = currentExecution.get();
                            assertNotNull(original, "test requires a real actor execution");
                            assertTrue(ActorRuntime.isActorCarrierThread());
                            currentExecution.remove();
                            try {
                                assertThrows(SecurityException.class, runtime::rootSupervisor);
                                assertThrows(SecurityException.class,
                                        () -> runtime.controlHandle(protectedChild));
                                assertThrows(SecurityException.class, runtime::createSupervisor);
                                assertThrows(SecurityException.class, other::rootSupervisor);
                                assertThrows(SecurityException.class,
                                        () -> other.controlHandle(foreignChild));
                                assertThrows(SecurityException.class, foreignChild::stop,
                                        "an actor cannot become a foreign runtime host by erasing context");
                                assertThrows(SecurityException.class,
                                        () -> protectedChild.awaitTermination(0, TimeUnit.NANOSECONDS),
                                        "a carrier cannot become a blocking host by erasing context");
                            } finally {
                                currentExecution.set(original);
                            }
                        } catch (Throwable failure) {
                            unexpected.set(failure);
                        } finally {
                            checked.countDown();
                        }
                    });

            attacker.send(1);
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get(), "logical-context spoof must not promote actor authority");
            assertTrue(protectedChild.isAlive());
            assertTrue(foreignChild.isAlive());
            // The same APIs remain available to an actual trusted host caller.
            assertNotNull(runtime.rootSupervisor());
            assertNotNull(other.rootSupervisor());
            protectedChild.stop();
            foreignChild.stop();
        }
    }


    @Test
    void forgedSameIdentityActorRefCannotControlLiveActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> victim = runtime.spawnPrivateTrusted(
                    factory -> (message, context) -> { });
            victim.getReady().get(5, TimeUnit.SECONDS);
            // Reconstruct an object with the same logical UUID, kind and
            // generation. It must remain a different, unauthorized ActorRef.
            var constructor = ActorRuntime.ActorRef.class.getDeclaredConstructor(
                    ActorRuntime.class,
                    ActorRuntime.ActorId.class,
                    ActorRuntime.ActorKind.class,
                    long.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<Integer> forgery =
                    (ActorRuntime.ActorRef<Integer>) constructor.newInstance(
                            runtime, victim.id(), victim.kind(), victim.generation());
            assertNotSame(victim, forgery);
            assertEquals(victim.id(), forgery.id());
            assertEquals(victim.generation(), forgery.generation());

            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> runtime.stop(forgery));
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> runtime.cancel(forgery));
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> runtime.gracefulStop(forgery));
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> runtime.send(forgery, 1));
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> runtime.drain(forgery));
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> runtime.controlHandle(forgery));
            // Observer APIs must not bypass exact-ref authorization, especially
            // ready(), which otherwise schedules the victim by its forged ID.
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    forgery::getReady);
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    forgery::readyRef);
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> forgery.awaitTermination(0, TimeUnit.NANOSECONDS));
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    forgery::parentId);
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    forgery::childIds);
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    forgery::isAlive);
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> runtime.isAlive(forgery));
            assertTrue(victim.isAlive(), "forged reference must not change target lifecycle");

            // Exact runtime-minted reference retains its legitimate authority.
            victim.stop();
            assertTrue(victim.awaitTermination(5, TimeUnit.SECONDS));
        }
    }


    @Test
    void forgedStructuredChildHandleCannotCancelItsLiveLookalike() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> parent = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            ActorRuntime.ActorHandle<Integer> real =
                                    context.runtime().spawnChildShared(
                                            childFactory -> (value, childContext) -> { });
                            ActorRuntime.ActorRef<Integer> child = real.ref();
                            var refConstructor = ActorRuntime.ActorRef.class.getDeclaredConstructor(
                                    ActorRuntime.class,
                                    ActorRuntime.ActorId.class,
                                    ActorRuntime.ActorKind.class,
                                    long.class);
                            refConstructor.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            ActorRuntime.ActorRef<Integer> forgedRef =
                                    (ActorRuntime.ActorRef<Integer>) refConstructor.newInstance(
                                            runtime, child.id(), child.kind(), child.generation());

                            var ownerField = ActorRuntime.ActorHandle.class.getDeclaredField("ownerCell");
                            ownerField.setAccessible(true);
                            Object actualOwnerCell = ownerField.get(real);
                            var handleConstructor = ActorRuntime.ActorHandle.class.getDeclaredConstructors()[0];
                            handleConstructor.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            ActorRuntime.ActorHandle<Integer> forgedHandle =
                                    (ActorRuntime.ActorHandle<Integer>) handleConstructor.newInstance(
                                            runtime, forgedRef, actualOwnerCell);

                            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                                    forgedHandle::cancel);
                            assertTrue(child.isAlive(),
                                    "forged child lifecycle authority must not cancel real child");
                            assertTrue(real.cancel(),
                                    "original handle must retain parent-owned cancellation rights");
                        } catch (Throwable failure) {
                            unexpected.set(failure);
                        } finally {
                            checked.countDown();
                        }
                    });

            parent.send(1);
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get(), "structured child authority must be exact-incarnation");
            parent.stop();
        }
    }

}
