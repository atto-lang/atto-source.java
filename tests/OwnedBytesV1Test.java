// HOST runtime test, not compiled .atto guest nor authenticated TenantVM bridge.
// javac src/memory/OwnedBytesV1.java tests/OwnedBytesV1Test.java
// java -cp src/memory:tests OwnedBytesV1Test
public final class OwnedBytesV1Test {
    private static final OwnedBytesV1.Owner A = new OwnedBytesV1.Owner(1, 2, 3);
    private static final OwnedBytesV1.Owner B = new OwnedBytesV1.Owner(9, 2, 3);
    private static int checks;

    private static void yes(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void deny(OwnedBytesV1.Fault fault, Runnable call) {
        checks++;
        try { call.run(); }
        catch (OwnedBytesV1.Failure err) {
            if (err.kind == fault) return;
            throw new AssertionError("wrong failure " + err.kind);
        }
        throw new AssertionError("missing failure " + fault);
    }

    public static void main(String[] unused) throws InterruptedException {
        for (var order : OwnedBytesV1.Order.values()) {
            try (var bytes = new OwnedBytesV1(A, 8, 8)) {
                bytes.write(A, 1, 4, 0x12345678L, order);
                yes(bytes.read(A, 1, 4, order) == 0x12345678L, "roundtrip");
                yes(bytes.read(A, 5, 1, order) == 0, "zero initialized");
                for (long offset : new long[] {-1L, 5L, Long.MAX_VALUE, Long.MIN_VALUE}) {
                    deny(OwnedBytesV1.Fault.BOUNDS,
                         () -> bytes.write(A, offset, 4, 42, order));
                }
                for (long value : new long[] {-1, 4294967296L, Long.MAX_VALUE}) {
                    deny(OwnedBytesV1.Fault.VALUE,
                         () -> bytes.write(A, 1, 4, value, order));
                    yes(bytes.read(A, 1, 4, order) == 0x12345678L,
                        "rejected write must preserve bytes");
                }
                deny(OwnedBytesV1.Fault.OWNER, () -> bytes.read(B, 1, 4, order));
                deny(OwnedBytesV1.Fault.OWNER, () -> bytes.write(B, 1, 4, 42, order));
                bytes.retire(A);
                deny(OwnedBytesV1.Fault.RETIRED, () -> bytes.read(A, 1, 4, order));
            }
        }
        deny(OwnedBytesV1.Fault.QUOTA, () -> new OwnedBytesV1(A, -1, 8));
        deny(OwnedBytesV1.Fault.QUOTA, () -> new OwnedBytesV1(A, 9, 8));
        try (var zero = new OwnedBytesV1(A, 0, 0)) {
            yes(zero.length(A) == 0, "zero length admitted");
            deny(OwnedBytesV1.Fault.BOUNDS, () -> zero.read(A, 0, 1, OwnedBytesV1.Order.LE));
        }
        try (var bytes = new OwnedBytesV1(A, 8, 8)) {
            final java.util.concurrent.atomic.AtomicBoolean safe =
                new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread[] workers = new Thread[4];
            for (int i = 0; i < workers.length; ++i) {
                final int worker = i;
                workers[i] = new Thread(() -> {
                    for (int k = 0; k < 2_000; k++) {
                        if (worker < 2) {
                            bytes.write(A, 0, 4, (k & 1) == 0 ? 0x12345678L : 0x01020304L,
                                        OwnedBytesV1.Order.LE);
                        } else {
                            long observed = bytes.read(A, 0, 4, OwnedBytesV1.Order.LE);
                            if (observed != 0 && observed != 0x12345678L &&
                                observed != 0x01020304L) safe.set(false);
                        }
                    }
                });
                workers[i].start();
            }
            for (Thread thread : workers) thread.join();
            yes(safe.get(), "no torn concurrent word reads");
        }
        System.out.println("ATTO_BYTES_JAVA_HOST_PASS checks=" + checks
            + " note=not_compiled_atto_guest");
    }
}
