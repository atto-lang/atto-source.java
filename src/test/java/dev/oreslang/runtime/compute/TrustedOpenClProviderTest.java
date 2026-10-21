package dev.oreslang.runtime.compute;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static dev.oreslang.runtime.compute.PortableKernelIR.*;
import static org.junit.jupiter.api.Assertions.*;

final class TrustedOpenClProviderTest {
    private static final class FakeProgram implements TrustedOpenClProvider.Program {}
    private static final class FakeBuffer implements TrustedOpenClProvider.PhysicalBuffer {
        final byte[] bytes;
        FakeBuffer(int size) { bytes = new byte[size]; }
    }
    private static final class FakeEvent implements TrustedOpenClProvider.NativeEvent {
        TrustedOpenClProvider.EventStatus status;
        FakeEvent(TrustedOpenClProvider.EventStatus status) { this.status = status; }
    }

    private static final class FakeDriver implements TrustedOpenClProvider.Driver {
        boolean failPrepare;
        int failAllocateAt;
        int allocationCount;
        boolean failUpload;
        boolean failKernel;
        boolean failDownload;
        boolean failReleaseBufferOnce;
        boolean failReleaseEventOnce;
        int releasedPrograms;
        int releasedBuffers;
        int releasedEvents;
        final Deque<TrustedOpenClProvider.EventStatus> statuses = new ArrayDeque<>();
        FakeEvent lastEvent;

        @Override
        public TrustedOpenClProvider.Program prepare(OpenClArtifact artifact) {
            if (failPrepare) throw new IllegalStateException("prepare failed");
            return new FakeProgram();
        }

        @Override
        public void releaseProgram(TrustedOpenClProvider.Program program) {
            releasedPrograms++;
        }

        @Override
        public TrustedOpenClProvider.PhysicalBuffer allocate(long bytes) {
            allocationCount++;
            if (failAllocateAt != 0 && allocationCount == failAllocateAt) {
                throw new IllegalStateException("allocate failed");
            }
            return new FakeBuffer(Math.toIntExact(bytes));
        }

        @Override
        public void releaseBuffer(TrustedOpenClProvider.PhysicalBuffer buffer) {
            if (failReleaseBufferOnce) {
                failReleaseBufferOnce = false;
                throw new IllegalStateException("release buffer failed");
            }
            releasedBuffers++;
        }

        @Override
        public TrustedOpenClProvider.NativeEvent enqueueUpload(
                TrustedOpenClProvider.PhysicalBuffer destination,
                byte[] source) {
            if (failUpload) throw new IllegalStateException("upload enqueue failed");
            System.arraycopy(source, 0, ((FakeBuffer) destination).bytes, 0, source.length);
            return nextEvent();
        }

        @Override
        public TrustedOpenClProvider.NativeEvent enqueueKernel(
                TrustedOpenClProvider.Program program,
                TrustedOpenClProvider.PhysicalBuffer input,
                TrustedOpenClProvider.PhysicalBuffer output,
                long globalRows,
                int globalFields) {
            if (failKernel) throw new IllegalStateException("kernel enqueue failed");
            byte[] in = ((FakeBuffer) input).bytes;
            byte[] out = ((FakeBuffer) output).bytes;
            for (int i = 0; i < in.length; i++) out[i] = (byte) (in[i] ^ 0x5a);
            return nextEvent();
        }

        @Override
        public TrustedOpenClProvider.NativeEvent enqueueDownload(
                TrustedOpenClProvider.PhysicalBuffer source,
                byte[] destination) {
            if (failDownload) throw new IllegalStateException("download enqueue failed");
            System.arraycopy(((FakeBuffer) source).bytes, 0, destination, 0, destination.length);
            return nextEvent();
        }

        @Override
        public TrustedOpenClProvider.EventStatus poll(
                TrustedOpenClProvider.NativeEvent event) {
            return ((FakeEvent) event).status;
        }

        @Override
        public void releaseEvent(TrustedOpenClProvider.NativeEvent event) {
            if (failReleaseEventOnce) {
                failReleaseEventOnce = false;
                throw new IllegalStateException("release event failed");
            }
            releasedEvents++;
        }

        FakeEvent nextEvent() {
            TrustedOpenClProvider.EventStatus status =
                    statuses.isEmpty()
                            ? TrustedOpenClProvider.EventStatus.COMPLETE
                            : statuses.removeFirst();
            lastEvent = new FakeEvent(status);
            return lastEvent;
        }
    }

    private record Fixture(
            ComputeDeviceHandle device,
            ComputeBufferHandle<Byte> input,
            ComputeBufferHandle<Byte> output,
            Plan plan,
            Kernel kernel,
            byte[] hostInput) {}

    private static Fixture fixture() {
        var device = ComputeAuthority.issueDevice("opencl", "gpu0", 1, "tenant-a");
        var plan = new Plan(Layout.AOS, 4, 2, 4, 16, 1);
        var kernel = new Kernel(plan, Element.F32, Operation.SCALE_BIAS, 1.5f, -2.0f);
        long bytes = plan.byteLength();
        var input = ComputeAuthority.<Byte>bindBuffer(
                device, 101, bytes, 16, "f32", "actor:1");
        var output = ComputeAuthority.<Byte>bindBuffer(
                device, 102, bytes, 16, "f32", "actor:1");
        byte[] host = new byte[Math.toIntExact(bytes)];
        for (int i = 0; i < host.length; i++) host[i] = (byte) i;
        return new Fixture(device, input, output, plan, kernel, host);
    }

    private static TrustedOpenClProvider provider(FakeDriver driver, Fixture fixture) {
        long bytes = fixture.plan.byteLength();
        return new TrustedOpenClProvider(
                driver,
                new TrustedOpenClProvider.Limits(
                        bytes * 4,
                        bytes * 4,
                        4,
                        4),
                fixture.device);
    }

    private static GpuTarget target(Fixture f) {
        return new GpuTarget(64, f.plan.byteLength() * 2, 100, true);
    }

    @Test
    void successfulProviderTransactionPinsKernelAndReleasesAllQuota() {
        var f = fixture();
        var driver = new FakeDriver();
        var provider = provider(driver, f.plan.byteLength());
        var prepared = provider.prepare(f.kernel, target(f));

        var operation = provider.begin(
                prepared, f.input, f.output, "actor:1", f.hostInput);
        assertEquals(ComputeBufferHandle.State.IN_FLIGHT, f.input.state());
        assertEquals(ComputeBufferHandle.State.IN_FLIGHT, f.output.state());
        assertThrows(IllegalStateException.class, prepared::close);

        assertEquals(
                TrustedOpenClProvider.OperationState.KERNEL_SUBMITTED,
                provider.poll(operation));
        assertEquals(
                TrustedOpenClProvider.OperationState.DOWNLOAD_SUBMITTED,
                provider.poll(operation));
        assertEquals(
                TrustedOpenClProvider.OperationState.SUCCEEDED,
                provider.poll(operation));

        assertEquals(ComputeBufferHandle.State.IDLE, f.input.state());
        assertEquals(ComputeBufferHandle.State.IDLE, f.output.state());
        assertEquals(
                new TrustedOpenClProvider.Usage(0, 0, 0, 0),
                provider.usage());

        byte[] expected = Arrays.copyOf(f.hostInput, f.hostInput.length);
        for (int i = 0; i < expected.length; i++) expected[i] ^= 0x5a;
        assertArrayEquals(expected, operation.outputBytes());

        prepared.close();
        assertEquals(1, driver.releasedPrograms);
        assertEquals(2, driver.releasedBuffers);
        assertEquals(3, driver.releasedEvents);
    }

    @Test
    void quotaFailureHappensBeforeFenceMutation() {
        var f = fixture();
        var driver = new FakeDriver();
        long bytes = f.plan.byteLength();
        var provider = new TrustedOpenClProvider(
                driver,
                new TrustedOpenClProvider.Limits(
                        bytes,
                        bytes * 4,
                        1,
                        1),
                f.device);
        var prepared = provider.prepare(f.kernel, target(f));

        assertThrows(IllegalStateException.class,
                () -> provider.begin(
                        prepared, f.input, f.output, "actor:1", f.hostInput));
        assertEquals(ComputeBufferHandle.State.IDLE, f.input.state());
        assertEquals(ComputeBufferHandle.State.IDLE, f.output.state());
        assertEquals(
                new TrustedOpenClProvider.Usage(0, 0, 0, 0),
                provider.usage());
        prepared.close();
    }

    @Test
    void allocationAndPreEnqueueFailuresRollbackQuotaAndFencePair() {
        var f = fixture();
        long bytes = f.plan.byteLength();

        {
            var driver = new FakeDriver();
            driver.failAllocateAt = 2;
            var provider = provider(driver, bytes);
            var prepared = provider.prepare(f.kernel, target(f));
            assertThrows(IllegalStateException.class,
                    () -> provider.begin(
                            prepared, f.input, f.output, "actor:1", f.hostInput));
            assertEquals(ComputeBufferHandle.State.IDLE, f.input.state());
            assertEquals(ComputeBufferHandle.State.IDLE, f.output.state());
            assertEquals(
                    new TrustedOpenClProvider.Usage(0, 0, 0, 0),
                    provider.usage());
            prepared.close();
        }

        {
            var driver = new FakeDriver();
            driver.failUpload = true;
            var provider = provider(driver, bytes);
            var prepared = provider.prepare(f.kernel, target(f));
            assertThrows(IllegalStateException.class,
                    () -> provider.begin(
                            prepared, f.input, f.output, "actor:1", f.hostInput));
            assertEquals(ComputeBufferHandle.State.IDLE, f.input.state());
            assertEquals(ComputeBufferHandle.State.IDLE, f.output.state());
            assertEquals(
                    new TrustedOpenClProvider.Usage(0, 0, 0, 0),
                    provider.usage());
            prepared.close();
        }
    }

    @Test
    void postSubmitEnqueueFailurePoisonsButQuiescesFencePair() {
        var f = fixture();
        var driver = new FakeDriver();
        driver.failKernel = true;
        var provider = provider(driver, f.plan.byteLength());
        var prepared = provider.prepare(f.kernel, target(f));

        var operation = provider.begin(
                prepared, f.input, f.output, "actor:1", f.hostInput);

        assertThrows(IllegalStateException.class, () -> provider.poll(operation));
        assertEquals(ComputeBufferHandle.State.POISONED, f.input.state());
        assertEquals(ComputeBufferHandle.State.POISONED, f.output.state());
        assertEquals(
                new TrustedOpenClProvider.Usage(0, 0, 0, 0),
                provider.usage());

        ComputeAuthority.retire(f.input, f.device, "actor:1");
        ComputeAuthority.retire(f.output, f.device, "actor:1");
        prepared.close();
    }

    @Test
    void timeoutAndCancellationRetainQuotaUntilExactEventQuiesces() {
        var f = fixture();
        var driver = new FakeDriver();
        driver.statuses.add(TrustedOpenClProvider.EventStatus.PENDING);
        var provider = provider(driver, f.plan.byteLength());
        var prepared = provider.prepare(f.kernel, target(f));

        var operation = provider.begin(
                prepared, f.input, f.output, "actor:1", f.hostInput);
        provider.requestTimeoutCancellation(operation);

        var before = provider.usage();
        assertTrue(before.residentBytes() > 0);
        assertEquals(
                TrustedOpenClProvider.OperationState.CANCEL_REQUESTED,
                provider.poll(operation));
        assertEquals(before, provider.usage());
        assertTrue(operation.timeoutRequested());

        driver.lastEvent.status = TrustedOpenClProvider.EventStatus.COMPLETE;
        assertEquals(
                TrustedOpenClProvider.OperationState.CANCELLED,
                provider.poll(operation));
        assertEquals(
                new TrustedOpenClProvider.Usage(0, 0, 0, 0),
                provider.usage());
        assertEquals(ComputeBufferHandle.State.POISONED, f.input.state());
        assertEquals(ComputeBufferHandle.State.POISONED, f.output.state());

        ComputeAuthority.retire(f.input, f.device, "actor:1");
        ComputeAuthority.retire(f.output, f.device, "actor:1");
        prepared.close();
    }

    @Test
    void deviceLossPoisonsOperationAndDoesNotReportSuccess() {
        var f = fixture();
        var driver = new FakeDriver();
        driver.statuses.add(TrustedOpenClProvider.EventStatus.DEVICE_LOST);
        var provider = provider(driver, f.plan.byteLength());
        var prepared = provider.prepare(f.kernel, target(f));

        var operation = provider.begin(
                prepared, f.input, f.output, "actor:1", f.hostInput);
        assertEquals(
                TrustedOpenClProvider.OperationState.DEVICE_LOST,
                provider.poll(operation));
        assertThrows(IllegalStateException.class, operation::outputBytes);
        assertEquals(ComputeBufferHandle.State.POISONED, f.input.state());
        assertEquals(ComputeBufferHandle.State.POISONED, f.output.state());
        prepared.close();
    }

    @Test
    void physicalReleaseFailureRetainsQuotaAndKernelUntilRetry() {
        var f = fixture();
        var driver = new FakeDriver();
        var provider = provider(driver, f.plan.byteLength());
        var prepared = provider.prepare(f.kernel, target(f));

        var operation = provider.begin(
                prepared, f.input, f.output, "actor:1", f.hostInput);
        provider.poll(operation);
        provider.poll(operation);
        driver.failReleaseBufferOnce = true;

        assertThrows(IllegalStateException.class, () -> provider.poll(operation));
        assertEquals(
                TrustedOpenClProvider.OperationState.RELEASE_FAILED,
                operation.state());
        assertTrue(provider.usage().residentBytes() > 0);
        assertThrows(IllegalStateException.class, prepared::close);

        provider.retryRelease(operation);
        assertEquals(
                TrustedOpenClProvider.OperationState.SUCCEEDED,
                operation.state());
        assertEquals(
                new TrustedOpenClProvider.Usage(0, 0, 0, 0),
                provider.usage());
        assertDoesNotThrow(operation::outputBytes);
        prepared.close();
    }

    @Test
    void eventReleaseFailureRetainsTheEventForSafeRetry() {
        var f = fixture();
        var driver = new FakeDriver();
        driver.failReleaseEventOnce = true;
        var provider = provider(driver, f.plan.byteLength());
        var prepared = provider.prepare(f.kernel, target(f));

        var operation = provider.begin(
                prepared, f.input, f.output, "actor:1", f.hostInput);
        assertThrows(IllegalStateException.class, () -> provider.poll(operation));

        // Same exact terminal event can be retried; no fence transition was lost.
        assertEquals(
                TrustedOpenClProvider.OperationState.KERNEL_SUBMITTED,
                provider.poll(operation));
        provider.poll(operation);
        provider.poll(operation);
        prepared.close();
    }
}
