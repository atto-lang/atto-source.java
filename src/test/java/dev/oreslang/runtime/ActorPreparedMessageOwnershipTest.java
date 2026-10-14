package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for mutable host Map input that changes after initial validation.
 * The detached payload, not a previous view of a caller-owned Map, is the
 * authoritative message admitted to the destination actor.
 */
final class ActorPreparedMessageOwnershipTest {

    /**
     * Return the foreign reference precisely while ActorRuntime is copying
     * the sender-owned Map into the mailbox snapshot. Earlier preflight
     * traversals remain safe. Stack inspection is restricted to this
     * adversarial *test fixture*, never used by the runtime admission policy.
     */
    private static Map<String, Object> injectAtSnapshotCopy(
            Object foreignRef, AtomicBoolean copyObserved) {
        return new AbstractMap<>() {
            @Override public int size() { return 1; }

            @Override public Set<Entry<String, Object>> entrySet() {
                boolean snapshotCopy = StackWalker.getInstance().walk(frames ->
                        frames.anyMatch(frame ->
                                frame.getClassName().equals(ActorRuntime.class.getName())
                                && (frame.getMethodName().equals("freeze")
                                    || frame.getMethodName().equals("isolateCopy"))));
                if (snapshotCopy) copyObserved.set(true);
                return Set.of(Map.entry("ref",
                        snapshotCopy ? foreignRef : "safe"));
            }
        };
    }

    @Test
    void dataOnlyFreezeRejectsCapabilityInsertedDuringSnapshot() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (value, context) -> {});
            AtomicBoolean copyObserved = new AtomicBoolean();

            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(injectAtSnapshotCopy(ref, copyObserved)));
            assertTrue(copyObserved.get(),
                    "the hostile map must inject during the actual frozen snapshot");
            assertTrue(denied.getMessage().contains("data values only"),
                    denied.getMessage());
        }
    }

    @Test
    void sharedMailboxRejectsForeignActorRefInsertedDuringSnapshot() {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime receiver = new ActorRuntime()) {
            var foreign = owner.<Integer>spawnShared(() -> (value, context) -> {});
            var sink = receiver.<Object>spawnShared(() -> (value, context) -> {});
            AtomicBoolean copyObserved = new AtomicBoolean();

            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> sink.send(injectAtSnapshotCopy(foreign, copyObserved)));
            assertTrue(denied.getMessage().contains("different ActorRuntime"),
                    denied.getMessage());
            assertTrue(copyObserved.get(),
                    "the hostile source Map must inject during frozen snapshot construction");
        }
    }

    @Test
    void privateMailboxRejectsForeignActorRefInsertedDuringSnapshot() {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime receiver = new ActorRuntime()) {
            var foreign = owner.<Integer>spawnShared(() -> (value, context) -> {});
            var sink = receiver.<Object>spawnPrivate(
                    ignored -> (value, context) -> {});
            AtomicBoolean copyObserved = new AtomicBoolean();

            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> sink.send(injectAtSnapshotCopy(foreign, copyObserved)));
            assertTrue(denied.getMessage().contains("different ActorRuntime"),
                    denied.getMessage());
            assertTrue(copyObserved.get(),
                    "the hostile source Map must inject during private snapshot construction");
        }
    }
}
