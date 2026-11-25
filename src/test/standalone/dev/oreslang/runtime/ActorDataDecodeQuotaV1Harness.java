package dev.oreslang.runtime;

public final class ActorDataDecodeQuotaV1Harness {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("quota invariant");
    }
    public static void main(String[] args) throws Exception {
        var q = new ActorDataDecodeQuotaV1(8,2);
        var a = q.tryReserve(8);
        check(a != null && q.usedBytes() == 8 && q.active() == 1);
        check(q.tryReserve(1) == null);
        var other = new java.util.concurrent.FutureTask<>(() -> q.tryReserve(1));
        var t = new Thread(other);
        t.start();
        check(other.get(5,java.util.concurrent.TimeUnit.SECONDS) == null);
        t.join();
        a.close(); a.close();
        check(q.usedBytes() == 0 && q.active() == 0);
        try (var b=q.tryReserve(1); var c=q.tryReserve(1)) {
            check(b != null && c != null);
            check(q.tryReserve(1) == null);
            check(q.tryReserve(-1) == null);
            check(q.tryReserve(Long.MAX_VALUE) == null);
            check(q.usedBytes() == 2 && q.active() == 2);
        }
        check(q.usedBytes() == 0 && q.active() == 0);
        System.out.println("ACTOR_DATA_DECODE_QUOTA_V1_HOST_PASS");
    }
}
