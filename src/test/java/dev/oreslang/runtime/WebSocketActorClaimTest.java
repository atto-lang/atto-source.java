package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class WebSocketActorClaimTest {

    @Test
    void onlyExactOwnerActorTurnMayExerciseClaim() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<WebSocketActorClaim> capability = new AtomicReference<>();
            AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
            AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
            CountDownLatch ownerTurn = new CountDownLatch(1);
            CountDownLatch foreignTurn = new CountDownLatch(1);
            var owner = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try { capability.get().requireOwnerTurn(); }
                catch (Throwable failure) { ownerFailure.set(failure); }
                finally { ownerTurn.countDown(); }
            });
            var other = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try { capability.get().requireOwnerTurn(); }
                catch (Throwable failure) { foreignFailure.set(failure); }
                finally { foreignTurn.countDown(); }
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            other.ready().get(5, TimeUnit.SECONDS);
            WebSocketActorClaim claim = WebSocketActorClaim.issue(runtime, owner);
            capability.set(claim);
            assertEquals(owner.id(), claim.address().actorId());
            assertEquals(owner.generation(), claim.address().generation());
            assertThrows(SecurityException.class, claim::requireOwnerTurn,
                    "host thread is not a mailbox turn and cannot impersonate owner");
            owner.send("owner");
            other.send("foreign");
            assertTrue(ownerTurn.await(5, TimeUnit.SECONDS));
            assertTrue(foreignTurn.await(5, TimeUnit.SECONDS));
            assertNull(ownerFailure.get(), "owner turn must be authorized");
            assertInstanceOf(SecurityException.class, foreignFailure.get());
        }
    }

    @Test
    void stoppedOwnerRevokesClaimAndCannotBeRebound() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var owner = runtime.<String>spawnPrivate(() -> (message, context) -> { });
            owner.ready().get(5, TimeUnit.SECONDS);
            WebSocketActorClaim claim = WebSocketActorClaim.issue(runtime, owner);
            assertFalse(claim.revoked());
            owner.stop();
            owner.done().get(5, TimeUnit.SECONDS);
            assertTrue(claim.revoked(), "actor finalization must revoke claim");
            assertFalse(claim.revoke(), "duplicate revocation is inert");
            assertThrows(SecurityException.class, claim::requireOwnerTurn);
            assertThrows(SecurityException.class,
                    () -> WebSocketActorClaim.issue(runtime, owner));
        }
    }

    @Test
    void otherRuntimeCannotForgeOwnerAndStoppedActorCannotClaim() throws Exception {
        try (ActorRuntime source = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            var owner = source.<String>spawnPrivate(() -> (message, context) -> { });
            owner.ready().get(5, TimeUnit.SECONDS);
            assertThrows(SecurityException.class,
                    () -> WebSocketActorClaim.issue(foreign, owner));
            assertThrows(NullPointerException.class,
                    () -> WebSocketActorClaim.issue(source, null));
            owner.stop();
            owner.done().get(5, TimeUnit.SECONDS);
            assertFalse(source.isLiveIncarnation(owner));
        }
    }

    @Test
    void revocationRejectsEveryLaterOwnerTurnAdmission() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<WebSocketActorClaim> claim = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger admitted =
                    new java.util.concurrent.atomic.AtomicInteger();
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch second = new CountDownLatch(1);
            var owner = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try {
                    claim.get().admitOwnerTurn(admitted::incrementAndGet);
                } catch (Throwable caught) {
                    failure.set(caught);
                } finally {
                    if (message.equals("before")) first.countDown();
                    else second.countDown();
                }
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            claim.set(WebSocketActorClaim.issue(runtime, owner));
            owner.send("before");
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertEquals(1, admitted.get());
            assertTrue(claim.get().revoke());
            owner.send("after");
            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, failure.get());
            assertEquals(1, admitted.get(),
                    "no USER admission may execute after claim revocation");
        }
    }

    @Test
    void actorCannotIssueItsOwnClaimWithoutSupervisor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.ActorRef<String>> ownerRef = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch attempted = new CountDownLatch(1);
            var owner = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try { WebSocketActorClaim.issue(runtime, ownerRef.get()); }
                catch (Throwable caught) { failure.set(caught); }
                finally { attempted.countDown(); }
            });
            ownerRef.set(owner);
            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("claim");
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, failure.get());
        }
    }
}
