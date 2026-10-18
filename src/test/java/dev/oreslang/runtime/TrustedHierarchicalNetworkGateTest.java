package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class TrustedHierarchicalNetworkGateTest {
    private static TrustedUntrustedNetworkQuota.Policy policy(int http, int tcp) {
        return new TrustedUntrustedNetworkQuota.Policy(10, 2, http, tcp);
    }

    @Test void supervisorDenialRefundsAllChildReservations() {
        var clock = new AtomicLong();
        var gate = new TrustedHierarchicalNetworkGate();
        var child = gate.createHostScope(policy(10, 10), clock::get);
        var supervisor = gate.createHostScope(policy(1, 10), clock::get);
        var tenant = gate.createHostScope(policy(10, 10), clock::get);
        var first = gate.tryStartHttp(child, supervisor, tenant);
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, first.status());
        assertTrue(first.lease().commitDispatch());
        assertEquals(TrustedUntrustedNetworkQuota.Status.TOO_MANY_IN_FLIGHT,
                gate.tryStartHttp(child, supervisor, tenant).status());
        first.lease().close();
        var next = gate.tryStartHttp(child, supervisor, tenant);
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, next.status());
        next.lease().close();
        assertEquals(TrustedUntrustedNetworkQuota.Status.RATE_LIMITED,
                gate.tryStartHttp(child, supervisor, tenant).status());
    }

    @Test void tenantDenialThenAbortBeforeDispatchNeverMintsTokens() {
        var clock = new AtomicLong();
        var gate = new TrustedHierarchicalNetworkGate();
        var child = gate.createHostScope(policy(10, 10), clock::get);
        var supervisor = gate.createHostScope(policy(10, 10), clock::get);
        var tenant = gate.createHostScope(policy(1, 10), clock::get);
        var first = gate.tryStartHttp(child, supervisor, tenant);
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, first.status());
        assertEquals(TrustedUntrustedNetworkQuota.Status.TOO_MANY_IN_FLIGHT,
                gate.tryStartHttp(child, supervisor, tenant).status());
        assertTrue(first.lease().abortBeforeDispatch());
        assertFalse(first.lease().abortBeforeDispatch());
        first.lease().close();
        var again = gate.tryStartHttp(child, supervisor, tenant);
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, again.status());
        assertTrue(again.lease().commitDispatch());
        assertFalse(again.lease().abortBeforeDispatch());
        again.lease().close();
    }

    @Test void twoChildrenCannotBypassSameSupervisorTcpCapacity() {
        var clock = new AtomicLong();
        var gate = new TrustedHierarchicalNetworkGate();
        var a = gate.createHostScope(policy(10, 10), clock::get);
        var b = gate.createHostScope(policy(10, 10), clock::get);
        var supervisor = gate.createHostScope(policy(10, 1), clock::get);
        var tenant = gate.createHostScope(policy(10, 10), clock::get);
        var first = gate.tryOpenTcp(a, supervisor, tenant);
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, first.status());
        assertEquals(TrustedUntrustedNetworkQuota.Status.TOO_MANY_CONNECTIONS,
                gate.tryOpenTcp(b, supervisor, tenant).status());
        first.lease().close();
        var second = gate.tryOpenTcp(b, supervisor, tenant);
        assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, second.status());
        second.lease().close();
    }

    @Test void stableChildScopeKeepsRateDebtAcrossReincarnations() {
        var clock = new AtomicLong();
        var gate = new TrustedHierarchicalNetworkGate();
        var stableLineage = gate.createHostScope(policy(10, 10), clock::get);
        var supervisor = gate.createHostScope(policy(10, 10), clock::get);
        var tenant = gate.createHostScope(policy(10, 10), clock::get);
        for (int incarnation = 0; incarnation < 2; incarnation++) {
            var admitted = gate.tryStartHttp(stableLineage, supervisor, tenant);
            assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, admitted.status());
            admitted.lease().commitDispatch();
            admitted.lease().close();
        }
        assertEquals(TrustedUntrustedNetworkQuota.Status.RATE_LIMITED,
                gate.tryStartHttp(stableLineage, supervisor, tenant).status());
    }

    @Test void foreignOrClosedScopeIsDenied() {
        var clock = new AtomicLong();
        var gate = new TrustedHierarchicalNetworkGate();
        var foreign = new TrustedHierarchicalNetworkGate();
        var a = gate.createHostScope(policy(10, 10), clock::get);
        var b = gate.createHostScope(policy(10, 10), clock::get);
        var c = gate.createHostScope(policy(10, 10), clock::get);
        assertThrows(IllegalArgumentException.class, () -> gate.tryOpenTcp(a, a, c));
        assertThrows(IllegalArgumentException.class, () -> gate.tryOpenTcp(a, b,
                foreign.createHostScope(policy(10, 10), clock::get)));
        b.close();
        assertEquals(TrustedUntrustedNetworkQuota.Status.CLOSED,
                gate.tryOpenTcp(a, b, c).status());
    }
}
