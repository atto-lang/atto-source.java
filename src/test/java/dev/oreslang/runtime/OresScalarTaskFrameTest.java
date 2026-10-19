package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exact-source host-side execution tests; this does not open guest nb syntax. */
final class OresScalarTaskFrameTest {
    @Test
    void scalarCaptureIsIndependentOfParentBindingAndAwaitsOnRealScheduler() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(2);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            ArrayList<Object> parentBindings = new ArrayList<>(List.of(41L));
            OresScalarTaskFrame frame = OresScalarTaskFrame.returnCapture(parentBindings, 0);
            parentBindings.set(0, 1000L);
            AtomicInteger physicalExits = new AtomicInteger();
            OresTask<Object> child = scope.startCompilerFrame(
                    scheduler, frame, physicalExits::incrementAndGet);

            assertEquals(41L, AsyncRuntime.await(child.getAwaited()));
            assertNull(AsyncRuntime.await(child.getDone()));
            assertEquals(1, physicalExits.get());
        }
    }

    @Test
    void primitiveAdditionAndConstantUseRealTaskSchedulingAndPhysicalExit() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(2);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Object> sum = scope.startCompilerFrame(
                    scheduler, OresScalarTaskFrame.addInts(List.of(20L, 22L), 0, 1));
            OresTask<Object> constant = scope.startCompilerFrame(
                    scheduler, OresScalarTaskFrame.returnConstant(true));
            assertEquals(42L, AsyncRuntime.await(sum.getAwaited()));
            assertEquals(true, AsyncRuntime.await(constant.getAwaited()));
            assertNull(AsyncRuntime.await(sum.getDone()));
            assertNull(AsyncRuntime.await(constant.getDone()));
        }
    }

    @Test
    void hostPointersBorrowLikeObjectsAndNestedHandlesFailClosed() {
        for (Object forbidden : List.of(new Object(), new int[] {1},
                new AtomicInteger(7), ByteBuffer.allocateDirect(8),
                Map.of("a", 1), new ArrayList<>(List.of(1L)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresScalarTaskFrame.returnCapture(List.of(forbidden), 0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> OresScalarTaskFrame.returnCapture(Arrays.asList((Object) null), 0));
        assertThrows(IllegalArgumentException.class,
                () -> OresScalarTaskFrame.returnConstant(new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> OresScalarTaskFrame.returnCapture(List.of(1L), 1));
        assertThrows(IllegalArgumentException.class,
                () -> OresScalarTaskFrame.addInts(List.of(1L, true), 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> OresScalarTaskFrame.returnCapture(
                        java.util.Collections.nCopies(17, 1L), 0));
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Object> inner = scope.startCompilerFrame(
                    scheduler, OresScalarTaskFrame.returnConstant(1L));
            assertThrows(IllegalArgumentException.class,
                    () -> OresScalarTaskFrame.returnCapture(List.of(inner), 0));
            assertThrows(IllegalArgumentException.class,
                    () -> OresScalarTaskFrame.returnCapture(List.of(inner.getAwaited()), 0));
            assertNull(AsyncRuntime.await(inner.getDone()));
        }
    }

    @Test
    void overflowAndReplayCannotSilentlySucceed() throws Exception {
        OresScalarTaskFrame overflow = OresScalarTaskFrame.addInts(
                List.of(Long.MAX_VALUE, 1L), 0, 1);
        assertThrows(ArithmeticException.class, () -> overflow.resume(null));
        OresScalarTaskFrame once = OresScalarTaskFrame.returnConstant(1L);
        assertInstanceOf(OresScheduler.Done.class, once.resume(null));
        assertThrows(IllegalStateException.class, () -> once.resume(null));
    }

    @Test
    void workerFailureStillRunsExactlyOnePhysicalExitCallback() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            AtomicInteger exits = new AtomicInteger();
            OresTask<Object> child = scope.startCompilerFrame(scheduler,
                    OresScalarTaskFrame.addInts(List.of(Long.MAX_VALUE, 1L), 0, 1),
                    exits::incrementAndGet);
            assertThrows(ArithmeticException.class,
                    () -> AsyncRuntime.await(child.getAwaited()));
            assertNull(AsyncRuntime.await(child.getDone()));
            assertEquals(1, exits.get());
        }
    }
}
