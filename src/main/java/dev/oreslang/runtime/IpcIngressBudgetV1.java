package dev.oreslang.runtime;

/**
 * Shared tenant logical staging budget for authenticated DATA ingress.
 * Not a measurement of physical JVM heap, provider internals or decoded objects.
 * The trusted tenant registry must supply the same instance to all endpoints.
 */
final class IpcIngressBudgetV1 {
    private final long maxBytes;
    private final int maxFrames;
    private long usedBytes;
    private int usedFrames;

    IpcIngressBudgetV1(long maxBytes, int maxFrames) {
        RuntimeBlockingGuard.requireBlockingHostThread("create IPC ingress budget");
        if (maxBytes <= 0 || maxBytes > 256L*1024*1024 || maxFrames <= 0 || maxFrames > 4096) {
            throw new IllegalArgumentException("invalid ingress budget limits");
        }
        this.maxBytes=maxBytes;
        this.maxFrames=maxFrames;
    }

    record Usage(long bytes,int frames) { }

    static long chargeFor(int wireBytes) {
        if (wireBytes < 32 || wireBytes > IpcSchemaBoundDataV1.MAX_AUTHENTICATED_FRAME) {
            throw new IllegalArgumentException("invalid schema DATA wire length");
        }
        // Covers logical staging copies, snapshot, parser slices and fixed overhead.
        // Does not claim an exact physical heap bound or charge decoded graphs.
        return 4096L + 8L*wireBytes;
    }

    Lease reserve(int wireBytes) {
        RuntimeBlockingGuard.requireBlockingHostThread("reserve IPC ingress staging");
        long charge=chargeFor(wireBytes);
        synchronized(this) {
            if (charge>maxBytes-usedBytes || usedFrames>=maxFrames)
                throw new IllegalStateException("tenant IPC ingress staging exhausted");
            Lease lease=new Lease(charge);
            usedBytes+=charge; ++usedFrames;
            return lease;
        }
    }

    Usage usage() {
        RuntimeBlockingGuard.requireBlockingHostThread("inspect IPC ingress staging");
        synchronized(this) { return new Usage(usedBytes,usedFrames); }
    }

    final class Lease implements AutoCloseable {
        private final long charge;
        private boolean released;
        private Lease(long charge){this.charge=charge;}
        @Override public void close() {
            RuntimeBlockingGuard.requireBlockingHostThread("release IPC ingress staging");
            synchronized(IpcIngressBudgetV1.this) {
                if(released)return;
                released=true;
                usedBytes-=charge; --usedFrames;
            }
        }
    }
}
