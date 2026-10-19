package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host/supervisor blocking methods must reject actor, scheduler and async
 * carrier callbacks even after those callbacks have cleared their CURRENT turn.
 * The private ThreadLocal manipulation is test-only fault injection.
 */
@Timeout(45)
final class ActorHotUpgradeHostCarrierIsolationTest {
    @SuppressWarnings("unchecked")
    private static ThreadLocal<Boolean> marker(
            Class<?> owner, String fieldName) throws Exception {
        var field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ThreadLocal<Boolean>) field.get(null);
    }

    private static void rejectsBlockingSurfaces(
            Method proxyHost, Method routerHost) {
        var proxyFailure = assertThrows(InvocationTargetException.class,
                () -> proxyHost.invoke(null));
        assertInstanceOf(SecurityException.class, proxyFailure.getCause());

        var routeFailure = assertThrows(InvocationTargetException.class,
                () -> routerHost.invoke(null, "test hot-upgrade route control"));
        assertInstanceOf(SecurityException.class, routeFailure.getCause());
    }

    private static void checkCarrier(
            ThreadLocal<Boolean> marker,
            Method proxyHost,
            Method routerHost) {
        Boolean prior = marker.get();
        try {
            marker.set(Boolean.TRUE);
            rejectsBlockingSurfaces(proxyHost, routerHost);
        } finally {
            if (prior == null) marker.remove();
            else marker.set(prior);
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> mailmanMarker() throws Exception {
        var field = ActorRuntime.class.getDeclaredField(
                "CURRENT_ACTOR_GROUP_MAILMAN");
        field.setAccessible(true);
        return (ThreadLocal<Object>) field.get(null);
    }

    @Test
    void actorGroupControlExecutionNeverInheritsHostUpgradeAuthority()
            throws Exception {
        Method proxyHost = LogicalSourceActorProxyBridgeV1.class
                .getDeclaredMethod("requireHost");
        Method routerHost = LogicalSourceActorRouterV1.class
                .getDeclaredMethod("requireBlockingHostSurface", String.class);
        proxyHost.setAccessible(true);
        routerHost.setAccessible(true);

        // The runtime's CONTROL flag checks only the existence of its
        // ThreadLocal. Inject an opaque sentinel rather than constructing
        // a valid Mailman session or granting guest CONTROL capabilities.
        ThreadLocal<Object> context = mailmanMarker();
        Object prior = context.get();
        try {
            context.set(new Object());
            assertFalse(ActorRuntime.inActorExecution());
            assertTrue(ActorRuntime.inActorGroupMailmanExecution());
            rejectsBlockingSurfaces(proxyHost, routerHost);
        } finally {
            if (prior == null) context.remove();
            else context.set(prior);
        }
    }

    @Test
    void taskScopeOwnerCannotJoinChildrenFromActorOrMailmanCarrier()
            throws Exception {
        Method taskHost = ActorHotUpgradeTaskOwnerV1.class
                .getDeclaredMethod("requireHost");
        taskHost.setAccessible(true);
        assertDoesNotThrow(() -> taskHost.invoke(null),
                "ordinary supervisor remains authorized");

        for (var marker : java.util.List.of(
                marker(ActorRuntime.class, "ACTOR_CARRIER"),
                marker(OresScheduler.class, "SCHEDULER_CARRIER"),
                marker(AsyncRuntime.class, "ASYNC_CARRIER"))) {
            Boolean prior = marker.get();
            try {
                marker.set(Boolean.TRUE);
                var error = assertThrows(InvocationTargetException.class,
                        () -> taskHost.invoke(null));
                assertInstanceOf(SecurityException.class, error.getCause());
            } finally {
                if (prior == null) marker.remove();
                else marker.set(prior);
            }
        }

        ThreadLocal<Object> mailman = mailmanMarker();
        Object prior = mailman.get();
        try {
            mailman.set(new Object());
            assertTrue(ActorRuntime.inActorGroupMailmanExecution());
            assertFalse(ActorRuntime.inActorExecution());
            var error = assertThrows(InvocationTargetException.class,
                    () -> taskHost.invoke(null));
            assertInstanceOf(SecurityException.class, error.getCause());
        } finally {
            if (prior == null) mailman.remove();
            else mailman.set(prior);
        }
    }

    @Test
    void actorCarrierCannotReadOrReapLogicalRouteGeneration()
            throws Exception {
        String source = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 0; return; }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    return;
                  }
                end
                pub routine main(): void { return; }
                """;
        try (var manager = new HotReloadManager(
                     IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration(
                    "carrier-read-denial.ores", source);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         generation, ActorRuntime.ActorKind.SHARED,
                         "Counter", java.time.Duration.ofSeconds(5));
                 var mailbox = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                router.bindProxyMailboxForSupervisor(mailbox);
                var ref = router.ref();
                var expectedId = ref.id();

                ThreadLocal<Boolean> actorCarrier = marker(
                        ActorRuntime.class, "ACTOR_CARRIER");
                Boolean previous = actorCarrier.get();
                try {
                    actorCarrier.set(Boolean.TRUE);
                    assertFalse(ActorRuntime.inActorExecution(),
                            "carrier-only context has no active actor turn");
                    assertThrows(SecurityException.class, ref::address);
                    assertThrows(SecurityException.class, ref::incarnation);
                    assertThrows(SecurityException.class, ref::routeEpoch);
                    assertThrows(SecurityException.class, router::generation);
                    assertThrows(SecurityException.class, router::reapRetired);
                    assertThrows(SecurityException.class,
                            router::physicalAddressForTesting);
                    assertThrows(SecurityException.class,
                            router::physicalRefForTesting);
                    assertThrows(SecurityException.class,
                            () -> router.proxyRouteEpochAlignedForTesting(mailbox));
                } finally {
                    if (previous == null) actorCarrier.remove();
                    else actorCarrier.set(previous);
                }
                // ActorGroup Mailman CONTROL has its own execution marker
                // and is NOT a privileged host thread even without an actor
                // or scheduler turn. It must not inspect or retire code routes.
                ThreadLocal<Object> mailman = mailmanMarker();
                Object previousMailman = mailman.get();
                try {
                    mailman.set(new Object());
                    assertThrows(SecurityException.class, ref::address);
                    assertThrows(SecurityException.class, router::generation);
                    assertThrows(SecurityException.class, router::reapRetired);
                    assertThrows(SecurityException.class,
                            router::physicalRefForTesting);
                } finally {
                    if (previousMailman == null) mailman.remove();
                    else mailman.set(previousMailman);
                }
                assertEquals(expectedId, ref.address().actorId());
                assertSame(generation, router.generation());
                assertTrue(router.proxyRouteEpochAlignedForTesting(mailbox));
                assertEquals(0, router.reapRetired());
            }
        }
    }

    @Test
    void carrierCallbacksWithoutActiveTurnCannotEnterBlockingUpgradeControls()
            throws Exception {
        Method proxyHost = LogicalSourceActorProxyBridgeV1.class
                .getDeclaredMethod("requireHost");
        Method routerHost = LogicalSourceActorRouterV1.class
                .getDeclaredMethod("requireBlockingHostSurface", String.class);
        proxyHost.setAccessible(true);
        routerHost.setAccessible(true);

        assertDoesNotThrow(() -> { proxyHost.invoke(null); },
                "ordinary host entry must remain available");
        assertDoesNotThrow(() -> { routerHost.invoke(null, "host test"); },
                "ordinary host route control must remain available");

        // Test *carrier-only* markers; no CURRENT actor/task is necessary.
        // An actor carrier may still execute a lifecycle/terminal callback
        // after CURRENT_ACTOR_EXECUTION has been cleared.
        checkCarrier(marker(ActorRuntime.class, "ACTOR_CARRIER"),
                proxyHost, routerHost);
        checkCarrier(marker(OresScheduler.class, "SCHEDULER_CARRIER"),
                proxyHost, routerHost);
        checkCarrier(marker(AsyncRuntime.class, "ASYNC_CARRIER"),
                proxyHost, routerHost);
    }
}
