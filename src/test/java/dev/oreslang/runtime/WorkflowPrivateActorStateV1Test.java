package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowPrivateActorStateV1Test {

    @Test
    void utf8CodecRoundTripsSupplementaryCharacters() {
        String value = "workflow-🚀-λ";
        byte[] encoded = WorkflowPrivateActorStateV1.encodeUtf8(value);
        try {
            assertEquals(
                    value,
                    WorkflowPrivateActorStateV1.decodeUtf8(encoded));
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
        }
    }

    @Test
    void utf8CodecRejectsMalformedBytesAndUnpairedSurrogates() {
        assertThrows(
                IllegalStateException.class,
                () -> WorkflowPrivateActorStateV1.decodeUtf8(
                        new byte[] {(byte) 0xC3, (byte) 0x28}));

        String unpaired = new String(
                new char[] {'b', 'a', 'd', '-', (char) 0xD800});
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowPrivateActorStateV1.encodeUtf8(unpaired));
    }


    @Test
    void envelopeRoundTripsZeroFillsAndRejectsCorruptLength()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var checked = new CountDownLatch(1);
            var problem = new AtomicReference<Throwable>();

            var actor = runtime.<String>spawnPrivateTrusted(factory -> {
                var block = factory.privateMemory()
                        .orElseThrow()
                        .allocatePrivateBytes(64);

                return (message, context) -> {
                    try {
                        assertTrue(WorkflowPrivateActorStateV1.empty(block));

                        byte[] payload = new byte[] {1, 2, 3, 4, 5};
                        WorkflowPrivateActorStateV1.write(block, payload);
                        assertArrayEquals(
                                payload,
                                WorkflowPrivateActorStateV1.read(block));

                        byte[] raw = block.copyOut();
                        for (int i = Integer.BYTES + payload.length;
                             i < raw.length;
                             i++) {
                            assertEquals(
                                    0,
                                    raw[i],
                                    "unused envelope bytes must be zero-filled");
                        }

                        block.writeInt(0, Integer.MAX_VALUE);
                        var failure = assertThrows(
                                IllegalStateException.class,
                                () -> WorkflowPrivateActorStateV1.read(block));
                        assertTrue(
                                failure.getMessage().contains("corrupt"));
                    } catch (Throwable failure) {
                        problem.set(failure);
                    } finally {
                        checked.countDown();
                        context.self().stop();
                    }
                };
            });

            actor.send("check");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            actor.done().get(5, TimeUnit.SECONDS);
            assertNull(problem.get(), () -> String.valueOf(problem.get()));
            assertEquals(
                    0L,
                    runtime.privateMemoryBytes(),
                    "actor teardown must retire private state memory");
        }
    }

    @Test
    void oversizedWriteFailsBeforeReplacingPreviousPayload()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var checked = new CountDownLatch(1);
            var problem = new AtomicReference<Throwable>();

            var actor = runtime.<String>spawnPrivateTrusted(factory -> {
                var block = factory.privateMemory()
                        .orElseThrow()
                        .allocatePrivateBytes(16);

                return (message, context) -> {
                    try {
                        byte[] original = new byte[] {9, 8, 7};
                        WorkflowPrivateActorStateV1.write(block, original);

                        var failure = assertThrows(
                                IllegalStateException.class,
                                () -> WorkflowPrivateActorStateV1.write(
                                        block,
                                        new byte[13]));
                        assertTrue(
                                failure.getMessage().contains("exceeds"));
                        assertArrayEquals(
                                original,
                                WorkflowPrivateActorStateV1.read(block),
                                "rejected oversize write must not mutate state");
                    } catch (Throwable failure) {
                        problem.set(failure);
                    } finally {
                        checked.countDown();
                        context.self().stop();
                    }
                };
            });

            actor.send("check");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            actor.done().get(5, TimeUnit.SECONDS);
            assertNull(problem.get(), () -> String.valueOf(problem.get()));
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }
}
