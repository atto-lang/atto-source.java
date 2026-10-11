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
        assertFalse(unowned.mayQueue(), "busy serial actor rejects unbound callback");
        assertThrows(IllegalStateException.class, unowned::requireRunnable);
        receive.suspend();
        assertThrows(IllegalStateException.class, unowned::requireRunnable);
        receive.close();
        unowned.requireRunnable();
        admission.close();
    }

    @Test void foreignActorLeaseCannotMintContinuationAuthority() {
        SerialActorAdmission firstActor = new SerialActorAdmission(2);
        SerialActorAdmission secondActor = new SerialActorAdmission(2);
        var first = firstActor.submit(SerialActorAdmission.Kind.MESSAGE).join();
        var second = secondActor.submit(SerialActorAdmission.Kind.MESSAGE).join();
        assertEquals(first.generation(), second.generation(),
                "same generation must not imply same actor authority");
        assertThrows(IllegalArgumentException.class,
                () -> new SerialContinuationAuthority(secondActor, first));
        assertThrows(IllegalArgumentException.class,
                () -> new SerialContinuationAuthority(firstActor, second));
        var legitimate = new SerialContinuationAuthority(firstActor, first);
        legitimate.requireRunnable();
        first.close();
        second.close();
        firstActor.close();
        secondActor.close();
    }

    @Test void nullAdmissionIsAllowedOnlyForNonSerialOrigins() {
        var nonSerial = new SerialContinuationAuthority(null, null);
        assertTrue(nonSerial.mayQueue());
        assertDoesNotThrow(nonSerial::requireRunnable);
        SerialActorAdmission admission = new SerialActorAdmission(1);
        var lease = admission.submit(SerialActorAdmission.Kind.MESSAGE).join();
        assertThrows(IllegalArgumentException.class,
                () -> new SerialContinuationAuthority(null, lease));
        SerialActorAdmission foreign = new SerialActorAdmission(1);
        assertThrows(IllegalArgumentException.class,
                () -> new SerialContinuationAuthority(foreign, lease));
        foreign.close();
        lease.close();
        admission.close();
    }
}
