package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorRoutePublicationAuthorityAuditTest {
    private static String sha(char c) { return String.valueOf(c).repeat(64); }

    private static ActorUpgradeEpochGate.Image image(char c) {
        return new ActorUpgradeEpochGate.Image(sha(c), sha('1'), sha('2'), sha('3'));
    }

    private static ActorRoutePublicationV1<String> route(
            ActorUpgradeEpochGate gate, LogicalActorProxyMailboxV1 mailbox) {
        return new ActorRoutePublicationV1<>(gate, mailbox,
                new ActorRoutePublicationV1.StateSnapshot(sha('1'), new byte[]{1}), "old");
    }

    @Test
    void unrelatedMailboxFreezeFencesData() {
        try (var gate = new ActorUpgradeEpochGate(102, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32);
             var owner = route(gate, mailbox)) {
            mailbox.freezeDispatchForUpgrade();
            assertThrows(IllegalStateException.class, owner::current);
            assertThrows(IllegalStateException.class,
                    () -> owner.admit(new byte[]{1}));
            assertEquals(1L, mailbox.snapshot().routeEpoch());
        }
    }

    @Test
    void unrelatedGenerationPrepareFencesData() {
        try (var gate = new ActorUpgradeEpochGate(103, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32);
             var owner = route(gate, mailbox)) {
            gate.prepare(image('b'));
            assertThrows(IllegalStateException.class, owner::current);
            assertThrows(IllegalStateException.class,
                    () -> owner.dispatchNext((r, e) -> fail("stale")));
        }
    }
}
