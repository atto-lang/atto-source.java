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
    private boolean retired;

    synchronized boolean retired(){return retired;}
    synchronized void retire(){retired=true;}

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
            if (retired || charge>maxBytes-usedBytes || usedFrames>=maxFrames)
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
        private long charge;
        private boolean released;
        private Lease(long charge){this.charge=charge;}
        /** Add decoded graph staging credit to this already-held frame lease.
         * No additional frame slot is consumed; no waiting for other leases. */
        void reserveMaterialization(long bytes) {
            RuntimeBlockingGuard.requireBlockingHostThread("reserve IPC decoder materialization");
            if (bytes <= 0 || bytes > 8L*1024*1024)
                throw new IllegalArgumentException("invalid decoded logical credit");
            synchronized(IpcIngressBudgetV1.this) {
                if(released || retired)throw new IllegalStateException("released or retired ingress lease");
                if(bytes>maxBytes-usedBytes)
                    throw new IllegalStateException("tenant IPC decoded staging exhausted");
                usedBytes+=bytes; charge+=bytes;
            }
        }
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
