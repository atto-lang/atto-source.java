package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.DataOrientedLayout;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Protects the allocation-root liveness/retention boundary without GC timing assumptions. */
final class DopRegionTokenWeakProvenanceTest {
    private static DataOrientedLayout.Plan shape() {
        return new DataOrientedLayout.Plan(DataOrientedLayout.Kind.SOA, 4, 2, 4, 16, 1);
    }

    @Test
    void provenanceIsWeakAndOwnerTransferPreservesLiveRoot() throws Exception {
        var owner = new DopKernelIR.HostBuffer(shape());
        var token = owner.regionToken();
        var root = owner.regionRoot();
        Field stateField = DopKernelIR.HostBuffer.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object state = stateField.get(owner);
        Field weakField = DopKernelIR.HostBuffer.RegionToken.class.getDeclaredField("ownerState");
        weakField.setAccessible(true);
        assertInstanceOf(WeakReference.class, weakField.get(token));
        var weak = (WeakReference<?>) weakField.get(token);
        assertSame(state, weak.get(), "live allocation must be the weak-reference referent");
        Field ownerField = DopKernelIR.HostBuffer.RegionToken.class.getDeclaredField("currentOwner");
        ownerField.setAccessible(true);
        assertInstanceOf(WeakReference.class, ownerField.get(token));
        var weakOwner = (WeakReference<?>) ownerField.get(token);
        assertSame(owner, weakOwner.get(), "fresh token must weakly reference initial owner");
        for (Field field : DopKernelIR.HostBuffer.RegionToken.class.getDeclaredFields()) {
            assertNotEquals(state.getClass(), field.getType(),
                    "region token must not strongly retain the owner State");
            assertFalse(ByteBuffer.class.isAssignableFrom(field.getType()),
                    "region token must not retain native backing bytes");
        }
        assertTrue(root.isLiveOwned());
        var successor = owner.take();
        assertSame(token, successor.regionToken());
        assertSame(successor, ((WeakReference<?>) ownerField.get(token)).get(),
                "ownership transfer must rebind token away from moved-from wrapper");
        assertTrue(root.sameRoot(successor.regionRoot()));
        assertTrue(root.isLiveOwned());
        assertThrows(IllegalStateException.class, owner::regionRoot);
        owner.close(); // moved-from close cannot retire successor storage
        assertTrue(root.isLiveOwned());
        successor.close();
        assertFalse(root.isLiveOwned());
        assertFalse(token.isLive());
    }

    @Test
    void activeLoanRetainsBackingUntilQuiescenceAndRetirement() {
        var owner = new DopKernelIR.HostBuffer(shape());
        var token = owner.regionToken();
        var root = owner.regionRoot();
        try (var loan = owner.borrowRead()) {
            assertTrue(root.isLiveOwned());
            assertThrows(IllegalStateException.class, owner::close);
            assertThrows(IllegalStateException.class, owner::take);
            assertEquals(0.0f, loan.read(0, 0));
        }
        assertTrue(token.isLive());
        owner.close();
        assertFalse(root.isLiveOwned());
        assertThrows(IllegalStateException.class, owner::borrowRead);
    }
}
