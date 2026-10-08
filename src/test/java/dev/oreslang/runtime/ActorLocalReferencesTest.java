package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorLocalReferencesTest {
    @Test
    void allocationOutsideActorFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ActorLocalReferences.create(7));
        assertThrows(IllegalStateException.class, ActorLocalReferences::empty);
    }

    @Test
    void strongAndWeakHandlesRemainActorLocalAndTransportIsRejected() throws Exception {
        AtomicReference<ActorLocalReferences.Ref<Object>> escaped = new AtomicReference<>();
        AtomicReference<ActorLocalReferences.WeakRef<Object>> weak = new AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();

        try (ActorRuntime actors = new ActorRuntime()) {
            var actor = actors.<String>spawn(() -> (message, context) -> {
                try {
                    ActorLocalReferences.Ref<Object> first = ActorLocalReferences.empty();
                    assertFalse(first.isSet());
                    assertEquals(Optional.empty(), first.take());
                    first.set("hello");
                    assertTrue(first.isSet());
                    assertEquals("hello", first.getCopy());

                    // The stored value is identity-bearing and refers back to
                    // the very same cell; no recursive construction is needed.
                    first.set(List.of(first));
                    assertThrows(IllegalArgumentException.class, first::getCopy);
                    assertSame(first, ((List<?>) first.take().orElseThrow()).getFirst());
                    assertFalse(first.isSet());

                    first.set("again");
                    ActorLocalReferences.WeakRef<Object> downgraded = first.downgrade();
                    ActorLocalReferences.Ref<Object> upgraded = downgraded.upgrade().orElseThrow();
                    assertTrue(first.sameIdentity(upgraded));
                    assertEquals("again", upgraded.getCopy());
                    escaped.set(first);
                    weak.set(downgraded);
                    done.complete(null);
                } catch (Throwable problem) {
                    done.completeExceptionally(problem);
                }
            });

            actor.send("test");
            done.get(10, TimeUnit.SECONDS);

            assertThrows(IllegalStateException.class, () -> escaped.get().take());
            assertThrows(IllegalStateException.class, () -> escaped.get().set("outside"));
            assertThrows(IllegalStateException.class, () -> weak.get().upgrade());
            assertThrows(SecurityException.class,
                    () -> ActorRuntime.freeze(List.of(escaped.get())));
            assertThrows(SecurityException.class,
                    () -> ActorRuntime.freeze(List.of(weak.get())));

            CompletableFuture<Throwable> crossActor = new CompletableFuture<>();
            var second = actors.<String>spawn(() -> (message, context) -> {
                try {
                    escaped.get().getCopy();
                    crossActor.complete(new AssertionError("cross-actor access was accepted"));
                } catch (Throwable failure) {
                    crossActor.complete(failure);
                }
            });
            second.send("cross");
            assertInstanceOf(SecurityException.class, crossActor.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void actorCannotPublishReferencesThroughMailbox() throws Exception {
        CompletableFuture<Boolean> rejected = new CompletableFuture<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<Object>spawn(() -> (message, context) -> { });
            var source = runtime.<Object>spawn(() -> (message, context) -> {
                try {
                    var local = ActorLocalReferences.create(42);
                    assertThrows(SecurityException.class, () -> target.send(List.of(local)));
                    rejected.complete(true);
                } catch (Throwable problem) {
                    rejected.completeExceptionally(problem);
                }
            });
            source.send("go");
            assertTrue(rejected.get(10, TimeUnit.SECONDS));
        }
    }
}
