package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class TaskScopeInvariantTest {
    @Test
    void joiningCannotBeCancelledWhileChildMayStillBeExecuting() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        OresFuture<Void> joined = scope.close();
        assertFalse(joined.cancel(true));
        assertFalse(joined.isDone());
        child.attach(OresFuture.completed(null));
        assertFalse(joined.isDone());
        child.quiesced();
        assertNull(joined.get(2, TimeUnit.SECONDS));
    }

    @Test
    void duplicateAttachmentDoesNotCountTwice() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        child.attach(OresFuture.completed(null));
        assertThrows(IllegalStateException.class, () -> child.attach(OresFuture.completed(null)));
        OresFuture<Void> joined = scope.close();
        assertFalse(joined.isDone());
        child.quiesced();
        assertNull(joined.get(2, TimeUnit.SECONDS));
    }

    @Test
    void attachedChildCannotBeReportedAsFailedAdmission() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration child = scope.reserve();
        child.attach(OresFuture.completed(null));
        OresFuture<Void> joined = scope.close();
        assertThrows(IllegalStateException.class,
                () -> child.failedAdmission(new IllegalArgumentException("invalid")));
        assertFalse(joined.isDone());
        child.quiesced();
        assertNull(joined.get(2, TimeUnit.SECONDS));
    }
}
