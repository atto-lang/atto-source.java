package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorRoutePublicationTransportAuditTest {
    private static String sha(char c) { return String.valueOf(c).repeat(64); }
    @Test
    void uncertainSendIsQuarantined() {
        var image = new ActorUpgradeEpochGate.Image(sha('a'), sha('1'), sha('2'), sha('3'));
        try (var gate = new ActorUpgradeEpochGate(101, image);
             var mailbox = new LogicalActorProxyMailboxV1(2, 16, 32);
             var router = new ActorRoutePublicationV1<>(
                     gate, mailbox,
                     new ActorRoutePublicationV1.StateSnapshot(sha('1'), new byte[]{1}),
                     "old")) {
            router.admit(new byte[]{1});
            assertThrows(IllegalStateException.class,
                    () -> router.dispatchNext((physical, envelope) -> {
                        throw new IllegalStateException("indeterminate submit");
                    }));
            assertEquals(1, mailbox.snapshot().outstandingMessages());
            assertThrows(IllegalStateException.class, router::current);
        }
    }
}
