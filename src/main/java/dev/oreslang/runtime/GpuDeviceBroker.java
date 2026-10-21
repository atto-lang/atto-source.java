package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Trusted-host reservation ledger for future ActorGroup GPU placement.
 *
 * <p>This broker reserves only bounded logical device capacity. It is not a
 * driver, queue, stream, fence or device capability. A reservation cannot be
 * exposed to guest code and does not prove that a kernel was enqueued. Provider
 * integration must bind a live reservation to the authenticated buffer/fence
 * submission state machine before GPU placement can be admitted.</p>
 */
final class GpuDeviceBroker implements AutoCloseable {
    record DeviceSpec(String deviceId, int maxInFlight, long memoryBudgetBytes) {
        DeviceSpec {
            if (deviceId == null || !deviceId.matches("[A-Za-z0-9_.:-]{1,96}")) {
                throw new IllegalArgumentException("invalid GPU device identifier");
            }
            if (maxInFlight <= 0) {
                throw new IllegalArgumentException("device maxInFlight must be positive");
            }
            if (memoryBudgetBytes <= 0) {
                throw new IllegalArgumentException("device memory budget must be positive");
            }
        }
    }

    record DeviceSnapshot(
            String deviceId,
            int maxInFlight,
            int inFlight,
            long memoryBudgetBytes,
            long reservedBytes,
            boolean accepting) { }

    private static final class DeviceState {
        final DeviceSpec spec;
        int inFlight;
        long reservedBytes;
        boolean accepting = true;

        DeviceState(DeviceSpec spec) { this.spec = spec; }
    }

    /**
     * Opaque reservation owner intended to be held by one lifecycle-owning
     * ActorGroup. Cooperation membership alone must never mint one.
     */
    static final class Owner {
        private final GpuDeviceBroker broker;
        private final Object proof = new Object();
        private final String label;
        private final int maxInFlight;
        private int inFlight;
        private boolean closing;

        private Owner(GpuDeviceBroker broker, String label, int maxInFlight) {
            this.broker = broker;
            this.label = label;
            this.maxInFlight = maxInFlight;
        }

        String label() { return label; }
        int maxInFlight() { return maxInFlight; }
    }

    /**
     * One exact capacity reservation. close() is idempotent and means only
     * that the provider has already made the associated submission physically
     * quiescent; callers must not release it on mere cancellation request.
     */
    static final class Lease implements AutoCloseable {
        private final GpuDeviceBroker broker;
        private final DeviceState device;
        private final Owner owner;
        private final long bytes;
        private final long sequence;
        private boolean closed;

        private Lease(
                GpuDeviceBroker broker,
                DeviceState device,
                Owner owner,
                long bytes,
                long sequence) {
            this.broker = broker;
            this.device = device;
            this.owner = owner;
            this.bytes = bytes;
            this.sequence = sequence;
        }

        String deviceId() { return device.spec.deviceId(); }
        long bytes() { return bytes; }
        long sequence() { return sequence; }
        Owner owner() { return owner; }

        @Override public void close() {
            broker.release(this);
        }
    }

    private final Object lock = new Object();
    private final Map<String, DeviceState> devices;
    private long nextLeaseSequence = 1L;
    private boolean closed;

    GpuDeviceBroker(List<DeviceSpec> specs) {
        Objects.requireNonNull(specs, "specs");
        if (specs.isEmpty() || specs.size() > 1024) {
            throw new IllegalArgumentException("GPU broker requires 1..1024 devices");
        }
        LinkedHashMap<String, DeviceState> byId = new LinkedHashMap<>();
        for (DeviceSpec spec : specs) {
            Objects.requireNonNull(spec, "device spec");
            if (byId.putIfAbsent(spec.deviceId(), new DeviceState(spec)) != null) {
                throw new IllegalArgumentException(
                        "duplicate GPU device identifier: " + spec.deviceId());
            }
        }
        devices = Map.copyOf(byId);
    }

    Owner issueOwner(String label, int maxInFlight) {
        Objects.requireNonNull(label, "label");
        if (label.isBlank() || label.length() > 128) {
            throw new IllegalArgumentException("invalid GPU reservation owner label");
        }
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("owner maxInFlight must be positive");
        }
        synchronized (lock) {
            requireOpen();
            return new Owner(this, label, maxInFlight);
        }
    }

    Optional<Lease> tryReserve(Owner owner, String deviceId, long bytes) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(deviceId, "deviceId");
        if (bytes <= 0) throw new IllegalArgumentException("GPU reservation bytes must be positive");
        synchronized (lock) {
            requireOpen();
            requireOwner(owner);
            if (owner.closing) return Optional.empty();
            DeviceState state = devices.get(deviceId);
            if (state == null || !state.accepting) return Optional.empty();
            if (owner.inFlight >= owner.maxInFlight
                    || state.inFlight >= state.spec.maxInFlight()) {
                return Optional.empty();
            }
            long budget = state.spec.memoryBudgetBytes();
            if (bytes > budget - state.reservedBytes) return Optional.empty();
            if (nextLeaseSequence == Long.MAX_VALUE) {
                throw new IllegalStateException("GPU reservation sequence exhausted");
            }
            long sequence = nextLeaseSequence++;
            owner.inFlight++;
            state.inFlight++;
            state.reservedBytes += bytes;
            return Optional.of(new Lease(this, state, owner, bytes, sequence));
        }
    }

    void beginOwnerClose(Owner owner) {
        Objects.requireNonNull(owner, "owner");
        synchronized (lock) {
            requireOwner(owner);
            owner.closing = true;
        }
    }

    boolean ownerDrained(Owner owner) {
        Objects.requireNonNull(owner, "owner");
        synchronized (lock) {
            requireOwner(owner);
            return owner.inFlight == 0;
        }
    }

    void disableDevice(String deviceId) {
        Objects.requireNonNull(deviceId, "deviceId");
        synchronized (lock) {
            requireOpen();
            DeviceState state = devices.get(deviceId);
            if (state == null) throw new IllegalArgumentException("unknown GPU device");
            // Device loss or administrative drain stops new work immediately.
            // Existing leases stay charged until the provider proves quiescence.
            state.accepting = false;
        }
    }

    List<DeviceSnapshot> snapshot() {
        synchronized (lock) {
            ArrayList<DeviceSnapshot> result = new ArrayList<>(devices.size());
            for (DeviceState state : devices.values()) {
                result.add(new DeviceSnapshot(
                        state.spec.deviceId(),
                        state.spec.maxInFlight(),
                        state.inFlight,
                        state.spec.memoryBudgetBytes(),
                        state.reservedBytes,
                        state.accepting && !closed));
            }
            return List.copyOf(result);
        }
    }

    private void release(Lease lease) {
        Objects.requireNonNull(lease, "lease");
        synchronized (lock) {
            if (lease.broker != this || lease.owner.broker != this) {
                throw new SecurityException("foreign GPU broker lease");
            }
            if (lease.closed) return;
            if (lease.device.inFlight <= 0 || lease.owner.inFlight <= 0
                    || lease.bytes > lease.device.reservedBytes) {
                throw new IllegalStateException("GPU broker reservation accounting corrupt");
            }
            lease.closed = true;
            lease.device.inFlight--;
            lease.device.reservedBytes -= lease.bytes;
            lease.owner.inFlight--;
        }
    }

    private void requireOwner(Owner owner) {
        if (owner.broker != this || owner.proof == null) {
            throw new SecurityException("foreign GPU reservation owner");
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("GPU broker closed");
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            for (DeviceState state : devices.values()) {
                if (state.inFlight != 0 || state.reservedBytes != 0) {
                    throw new IllegalStateException(
                            "cannot close GPU broker with live reservations");
                }
            }
            closed = true;
            for (DeviceState state : devices.values()) state.accepting = false;
        }
    }
}
