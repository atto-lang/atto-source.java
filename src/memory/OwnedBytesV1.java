// Internal Java/Graal host primitive for the future sealed Atto OwnedBytes ABI.
// NOT a compiled Atto guest, TenantVM sandbox, or complete quota/ownership bridge.
// Caller Owner must come from the trusted scheduler; do not expose to guest code.
import java.util.Arrays;
import java.util.Objects;

final class OwnedBytesV1 implements AutoCloseable {
    record Owner(long tenant, long actor, long generation) {}
    enum Order { LE, BE }
    enum Fault { OWNER, BOUNDS, VALUE, QUOTA, RETIRED }
    static final class Failure extends IllegalStateException {
        final Fault kind;
        Failure(Fault kind) { super(kind.name()); this.kind = kind; }
    }

    private final Owner owner;
    private final byte[] data;
    private boolean retired;

    OwnedBytesV1(Owner origin, long length, long maxRegionBytes) {
        this.owner = Objects.requireNonNull(origin, "trusted origin");
        if (owner.tenant() <= 0 || owner.actor() <= 0 || owner.generation() <= 0)
            throw new Failure(Fault.OWNER);
        if (length < 0 || maxRegionBytes < 0 || length > maxRegionBytes ||
            length > Integer.MAX_VALUE - 8)
            throw new Failure(Fault.QUOTA);
        data = new byte[(int) length]; // JVM specifies zero initialization
    }

    private void ownerCheck(Owner caller) {
        if (retired) throw new Failure(Fault.RETIRED);
        if (!owner.equals(caller)) throw new Failure(Fault.OWNER);
    }

    synchronized int length(Owner caller) {
        ownerCheck(caller);
        return data.length;
    }

    private int checkedOffset(Owner caller, long offset, int width) {
        ownerCheck(caller);
        if (width != 1 && width != 2 && width != 4) throw new Failure(Fault.VALUE);
        if (offset < 0 || width > data.length || offset > (long)data.length - width)
            throw new Failure(Fault.BOUNDS);
        return (int) offset;
    }

    synchronized long read(Owner caller, long offset, int width, Order order) {
        Objects.requireNonNull(order, "endian");
        int at = checkedOffset(caller, offset, width);
        long value = 0;
        for (int i = 0; i < width; i++) {
            int shift = 8 * (order == Order.LE ? i : width - 1 - i);
            value |= ((long)data[at + i] & 255L) << shift;
        }
        return value;
    }

    synchronized void write(Owner caller, long offset, int width, long value, Order order) {
        Objects.requireNonNull(order, "endian");
        int at = checkedOffset(caller, offset, width);
        long max = (1L << (8 * width)) - 1L;
        if (value < 0 || value > max) throw new Failure(Fault.VALUE);
        // No error after the full admission preflight, so writes are fail-atomic.
        for (int i = 0; i < width; i++) {
            int shift = 8 * (order == Order.LE ? i : width - 1 - i);
            data[at + i] = (byte)((value >>> shift) & 255);
        }
    }

    synchronized void retire(Owner caller) {
        ownerCheck(caller);
        Arrays.fill(data, (byte)0);
        retired = true;
    }

    @Override public synchronized void close() {
        Arrays.fill(data, (byte)0);
        retired = true;
    }
}
