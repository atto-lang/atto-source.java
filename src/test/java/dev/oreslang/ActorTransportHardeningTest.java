package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorTransportHardeningTest {
    @Test
    void readonlySharedWrapperFreezesBeforePublication() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ArrayList<String> source = new ArrayList<>(List.of("a"));
            ActorRuntime.Shared<Object> shared = runtime.shareReadonly(source);

            source.add("b");

            assertInstanceOf(List.class, shared.value());
            @SuppressWarnings("unchecked")
            List<String> frozen = (List<String>) shared.value();
            assertEquals(List.of("a"), frozen);
            assertThrows(UnsupportedOperationException.class, () -> frozen.add("c"));
        }
    }

    @Test
    void actorRefsCannotCrossRuntimeCapabilityBoundaries() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var refA = runtimeA.<Object>spawn(() -> (message, context) -> { });
            var targetB = runtimeB.<Object>spawn(() -> (message, context) -> { });

            IllegalArgumentException direct = assertThrows(
                    IllegalArgumentException.class,
                    () -> targetB.send(refA));
            assertTrue(direct.getMessage().contains("owning ActorRuntime"));

            assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(refA));

            ActorRuntime.Shared<Object> wrapped = runtimeA.shareReadonly(refA);
            IllegalArgumentException wrappedFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> targetB.send(wrapped));
            assertTrue(wrappedFailure.getMessage().contains("owning ActorRuntime"));
        }
    }

    @Test
    void cyclicMessageGraphsAreRejectedBeforeStackOverflow() {
        ArrayList<Object> cycle = new ArrayList<>();
        cycle.add(cycle);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(cycle));

        assertTrue(error.getMessage().contains("cyclic actor message"));
    }

    @Test
    void excessivelyDeepMessageGraphsAreRejected() {
        Object nested = "leaf";
        for (int i = 0; i < 300; i++) {
            nested = List.of(nested);
        }
        Object tooDeep = nested;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooDeep));

        assertTrue(error.getMessage().contains("maximum nesting depth"));
    }

    @Test
    void oversizedMessageGraphsAreRejected() {
        List<Integer> tooManyNodes = java.util.Collections.nCopies(100_001, 1);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooManyNodes));

        assertTrue(error.getMessage().contains("maximum graph size"));
    }

    @Test
    void sendableCannotReturnMutableHostObject() {
        ActorRuntime.Sendable malicious = () -> new StringBuilder("mutable");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(malicious));

        assertTrue(error.getMessage().contains("not Sendable"));
    }

    @Test
    void sendableCannotSelfAuthorizeByReturningItself() {
        final class SelfReturning implements ActorRuntime.Sendable {
            @Override
            public Object freezeForSend() {
                return this;
            }
        }

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(new SelfReturning()));

        assertTrue(error.getMessage().contains("distinct frozen representation"));
    }
}
