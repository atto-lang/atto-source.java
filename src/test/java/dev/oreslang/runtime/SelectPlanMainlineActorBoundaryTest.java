package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Java host values cannot bypass Oreslang's actor-local select capability rules. */
final class SelectPlanMainlineActorBoundaryTest {
    @Test
    void directAndNestedSelectPlansAreRejectedBeforeMailboxAdmission() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var fixed = ChannelRuntime.SelectPlan.of(ChannelRuntime.read(channel));
        var dynamic = new ChannelRuntime.DynamicSelectPlan<String>();
        dynamic.put("read", ChannelRuntime.read(channel));

        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<Object>spawnShared(() -> (message, context) -> {
                throw new AssertionError("rejected runtime coordination handle was delivered");
            });
            RuntimeException fixedRejected = assertThrows(RuntimeException.class,
                    () -> target.send(fixed));
            assertTrue(fixedRejected.getMessage().contains("actor mailboxes"),
                    fixedRejected::getMessage);
            RuntimeException dynamicRejected = assertThrows(RuntimeException.class,
                    () -> target.send(dynamic));
            assertTrue(dynamicRejected.getMessage().contains("actor mailboxes"),
                    dynamicRejected::getMessage);
            assertThrows(RuntimeException.class, () -> target.send(List.of(fixed)));
            assertThrows(RuntimeException.class, () -> target.send(Map.of("plan", dynamic)));
            target.stop();
        }
    }
}
