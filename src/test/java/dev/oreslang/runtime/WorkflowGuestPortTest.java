package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class WorkflowGuestPortTest {
    private static List<Object> request() {
        return List.of("probe-1","INSPECT","tenant-a","run-1",
                "a".repeat(40),1L,0L,0L,"","","","","","","scoped-cap");
    }

    @Test void rejectsIncorrectShapeAndNonScalarFields() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGuestPort.decode(List.of("not-a-request")));
        var wrong = new java.util.ArrayList<Object>(request());
        wrong.set(5, 1.0);
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGuestPort.decode(wrong));
        wrong.set(5, new Object());
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGuestPort.decode(wrong));
        wrong.set(5, 1L);
        wrong.set(1, "OPEN_DATABASE");
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGuestPort.decode(wrong));
    }

    @Test void decodesExactScopedRequestAndRejectsHostHandles() {
        var decoded=WorkflowGuestPort.decode(request());
        assertEquals(WorkflowHostServiceRegistry.Operation.INSPECT, decoded.operation());
        assertEquals("tenant-a",decoded.tenant());
        assertEquals(1,decoded.generation());
        assertEquals("scoped-cap",decoded.capability());
        var withHandle=new java.util.ArrayList<Object>(request());
        withHandle.set(14,new Object());
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGuestPort.decode(withHandle));
    }

    @Test void mapsHostResponseToDetachedScalars() throws Exception {
        WorkflowHostServiceRegistry.Service host = request ->
                CompletableFuture.completedFuture(
                    new WorkflowHostServiceRegistry.Reply(
                        request.requestId(),WorkflowHostServiceRegistry.Status.OK,
                        3,4,1,0,2));
        try(var scope=WorkflowHostServiceRegistry.provisionForHost(host);
            var registry=WorkflowHostServiceRegistry.forNewContext()) {
            var result=WorkflowGuestPort.submit(registry,request())
                    .get(3,TimeUnit.SECONDS);
            assertEquals(List.of("probe-1","ok",3L,4L,1L,0L,2L),result);
            assertThrows(UnsupportedOperationException.class,() -> result.add(new Object()));
        }
    }

    @Test void unprovisionedRegistryFailsClosed() throws Exception {
        try(var registry=new WorkflowHostServiceRegistry()) {
            var result=WorkflowGuestPort.submit(registry,request()).get(3,TimeUnit.SECONDS);
            assertEquals("unavailable",result.get(1));
            assertEquals(-1L,result.get(2));
        }
    }
}
