package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorRoutePublicationPlacementAuditTest {
    @Test
    void sharedActorInNonHostPlacementIsNotALocalRouter() {
        var sha = "a".repeat(64);
        var image = new ActorUpgradeEpochGate.Image(
                sha, "1".repeat(64), "2".repeat(64), "3".repeat(64));
        var placement = new ActorUpgradeEpochGate.Placement(
                ActorUpgradeEpochGate.ActorKind.SHARED,
                ActorUpgradeEpochGate.ProtectionDomain.TRUSTED_PROCESS,
                "0".repeat(64));
        try (var gate = new ActorUpgradeEpochGate(104, placement, image);
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32)) {
            assertThrows(SecurityException.class, () -> new ActorRoutePublicationV1<>(
                    gate, mailbox,
                    new ActorRoutePublicationV1.StateSnapshot("1".repeat(64), new byte[]{1}),
                    "physical"));
        }
    }
}
