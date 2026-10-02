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
