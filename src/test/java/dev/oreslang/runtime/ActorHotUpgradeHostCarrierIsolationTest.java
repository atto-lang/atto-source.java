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
@Timeout(15)
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
