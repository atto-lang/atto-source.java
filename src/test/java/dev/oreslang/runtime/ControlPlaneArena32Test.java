package dev.oreslang.runtime;

public final class ControlPlaneArena32Test {
    private static void check(boolean truth, String message) {
        if (!truth) throw new AssertionError(message);
    }
    private static void denied(Runnable operation) {
        boolean rejected = false;
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("expected fail-closed control ref rejection");
    }
    public static void main(String[] args) {
        check(ControlPlaneArena32.ABI.equals("control_generation12_slot20_v1"), "ABI must be explicit");
        check(ControlPlaneArena32.RECORD_BYTES == 32, "record layout");
        final long huge = 9_000_000_000L;
        try (var a = new ControlPlaneArena32(2); var b = new ControlPlaneArena32(2)) {
            long parent = a.allocate();
            long child = a.allocate();
            check((int) parent == (int) b.allocate(), "numeric collision is legitimate");
            denied(() -> b.readI64(parent, 0)); // 64-bit trusted owner witness blocks collision
            denied(() -> a.writeRef32(parent, b.allocate())); // b quota or foreign witness
            a.writeI64(parent, 0, huge);
            check(a.readI64(parent, 0) == huge, "full i64 behind four-byte Ref32");
            a.writeI64(parent, 8, Long.MIN_VALUE);
            check(a.readI64(parent, 8) == Long.MIN_VALUE, "128-bit payload (two i64 limbs)");
            a.writeRef32(parent, child);
            check(a.readRef32(parent) == child, "checked four-byte child Ref32");
            // The result is host JVM-hotpath evidence ONLY, not Atto compiled guest proof.
            // Warm before reading the allocating-thread counter, keeping diagnostics outside.
            for (int i = 0; i < 16_384; i++) {
                a.writeI64(parent, 0, i);
                if (a.readI64(parent, 0) != i) throw new AssertionError("warmup mismatch");
            }
            var bean = java.lang.management.ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean measured
                    && measured.isThreadAllocatedMemorySupported()) {
                if (!measured.isThreadAllocatedMemoryEnabled())
                    measured.setThreadAllocatedMemoryEnabled(true);
                long thread = Thread.currentThread().threadId();
                long before = measured.getThreadAllocatedBytes(thread);
                for (int i = 0; i < 16_384; i++) {
                    a.writeI64(parent, 0, i);
                    if (a.readI64(parent, 0) != i)
                        throw new AssertionError("hotpath read mismatch");
                }
                long after = measured.getThreadAllocatedBytes(thread);
                if (before < 0 || after < before || after - before != 0)
                    throw new AssertionError("Java control heap managed hotpath allocated "
                            + (after - before) + " bytes");
                System.out.println("PASS java control host warmed i64 read/write managed-byte delta=0");
            } else {
                System.out.println("NOT_EXECUTED Java control allocation counter unsupported");
            }
            denied(() -> a.writeI64(parent, 16, 10L)); // field kind mismatch
            denied(a::allocate); // bounded physical metadata and payload quota
            a.drop(child);
            denied(() -> a.readI64(child, 0)); // stale generation
            denied(() -> a.readRef32(parent)); // embedded stale token
            long reused = a.allocate();
            check(reused != child, "reused slot increments generation");
            a.writeRef32(parent, reused);
            check(a.readRef32(parent) == reused, "new child can be admitted");
            a.drop(parent);
            a.drop(reused);
            denied(() -> a.drop(reused)); // double drop
        }
        try (var limited = new ControlPlaneArena32(1)) {
            long current = limited.allocate();
            // 12-bit generations MUST retire, never wrap to validate oldest witness.
            for (int i = 1; i < 4095; i++) {
                limited.drop(current);
                current = limited.allocate();
            }
            limited.drop(current);
            denied(limited::allocate);
        }
        var stopped = new ControlPlaneArena32(1);
        long old = stopped.allocate();
        stopped.close();
        stopped.close();
        denied(() -> stopped.readI64(old, 0));
        System.out.println("PASS control-plane-ref32 java offheap host 12 guarded scenarios; NO compiled Atto guest or zero-GC proof");
    }
}
