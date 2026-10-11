package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SerialContinuationAuthorityTest {
    @Test void originStaysBoundAcrossSuspendAndCannotEnterLaterHandler() {
        SerialActorAdmission admission = new SerialActorAdmission(2);
        var first = admission.submit(SerialActorAdmission.Kind.MESSAGE).join();
        SerialContinuationAuthority origin =
                new SerialContinuationAuthority(admission, first);
        assertTrue(origin.mayQueue());
        origin.requireRunnable();
        first.suspend();
        assertTrue(origin.mayQueue(), "suspension must preserve ownership");
        assertThrows(IllegalStateException.class, origin::requireRunnable);
        first.resume();
        origin.requireRunnable();

        first.close();
        var second = admission.submit(SerialActorAdmission.Kind.MESSAGE).join();
        assertFalse(origin.mayQueue(), "completed origin cannot enqueue under next receive");
        assertThrows(IllegalStateException.class, origin::requireRunnable);
        second.close();
        admission.close();
    }

    @Test void unownedCallbackOnlyRunsWhenSerialActorIdle() {
        SerialActorAdmission admission = new SerialActorAdmission(2);
        var unowned = new SerialContinuationAuthority(admission, null);
        unowned.requireRunnable();
        var receive = admission.submit(SerialActorAdmission.Kind.MESSAGE).join();
        assertThrows(IllegalStateException.class, unowned::requireRunnable);
        receive.suspend();
        assertThrows(IllegalStateException.class, unowned::requireRunnable);
        receive.close();
        unowned.requireRunnable();
        admission.close();
    }

    @Test void nullAdmissionIsAllowedOnlyForNonSerialOrigins() {
        var nonSerial = new SerialContinuationAuthority(null, null);
        assertTrue(nonSerial.mayQueue());
        assertDoesNotThrow(nonSerial::requireRunnable);
        SerialActorAdmission admission = new SerialActorAdmission(1);
        var lease = admission.submit(SerialActorAdmission.Kind.MESSAGE).join();
        assertThrows(IllegalArgumentException.class,
                () -> new SerialContinuationAuthority(null, lease));
        lease.close();
        admission.close();
    }
}
