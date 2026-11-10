package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Objects;

/**
 * Host-owned immutable TCP payload slab; pin handles never expose raw pointers.
 * Data is copied once at publication. Only metadata (pin/release) is
 * synchronized; a read does not serialize through any actor mailbox.
 * This is a Java soft-RT implementation, not a hard-RT zero-allocation claim.
 */
final class FrozenTcpSlab {
    static final int MAX_BYTES = 1024 * 1024;

    static final class Pin {
        private final FrozenTcpSlab owner;
        private boolean active = true;

        private Pin(FrozenTcpSlab owner) {
            this.owner = owner;
        }
    }

    private byte[] bytes;
    private final int size;
    private int pins;
    private boolean retiring;

    FrozenTcpSlab(byte[] input) {
        Objects.requireNonNull(input, "input");
        if (input.length > MAX_BYTES)
            throw new IllegalArgumentException("TCP slab exceeds one MiB");
        this.bytes = Arrays.copyOf(input, input.length);
        this.size = input.length;
    }

    synchronized int size() { return size; }

    synchronized Pin pin() {
        if (retiring) throw new IllegalStateException("slab retired");
        if (pins == Integer.MAX_VALUE)
            throw new IllegalStateException("pin count exhausted");
        pins++;
        return new Pin(this);
    }

    synchronized int readByte(Pin pin, int index) {
        if (pin == null || pin.owner != this || !pin.active)
            throw new IllegalStateException("invalid TCP slab pin");
        if (bytes == null) throw new IllegalStateException("TCP slab storage retired");
        if (index < 0 || index >= bytes.length)
            throw new IndexOutOfBoundsException("TCP slab byte index");
        return Byte.toUnsignedInt(bytes[index]);
    }

    synchronized void releasePin(Pin pin) {
        if (pin == null || pin.owner != this || !pin.active)
            throw new IllegalStateException("invalid TCP slab pin release");
        pin.active = false;
        pins--;
        finalizeRetirement();
    }

    synchronized void retire() { retiring = true; finalizeRetirement(); }

    synchronized boolean freed() { return bytes == null; }

    synchronized int pinCount() { return pins; }

    private void finalizeRetirement() {
        // Drop strong backing reference after the final trusted physical IO pin.
        // This is GC reclamation eligibility, NOT a secret-memory zeroization.
        if (retiring && pins == 0) bytes = null;
    }
}
