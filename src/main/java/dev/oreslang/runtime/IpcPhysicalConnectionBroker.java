package dev.oreslang.runtime;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Trusted host broker for one physical authenticated IPC connection.
 *
 * <p>This is not guest-visible authority. A broker mints an inseparable,
 * one-shot tuple containing opaque physical ownership, a strictly increasing
 * generation, private MAC material and a fresh 128-bit connection binding.
 * The socket transport consumes that tuple exactly once.</p>
 */
final class IpcPhysicalConnectionBroker {
    static final int CONNECTION_BINDING_BYTES = 16;

    @FunctionalInterface
    interface BindingSource {
        void fill(byte[] target);
    }

    private final BindingSource bindingSource;
    private long lastGeneration;

    IpcPhysicalConnectionBroker() {
        SecureRandom random = new SecureRandom();
        this.bindingSource = random::nextBytes;
    }

    /**
     * Package-local deterministic source injection is for trusted host tests;
     * production callers use the SecureRandom-backed constructor.
     */
    IpcPhysicalConnectionBroker(BindingSource bindingSource) {
        this.bindingSource = Objects.requireNonNull(bindingSource, "bindingSource");
    }

    synchronized IpcPhysicalConnectionAuthority mint(
            long generation,
            byte[] negotiatedMacKey) {
        if (generation <= 0) {
            throw new IllegalArgumentException(
                    "physical generation must be positive");
        }
        if (generation <= lastGeneration) {
            throw new IllegalArgumentException(
                    "physical generation must increase monotonically");
        }
        if (negotiatedMacKey == null
                || negotiatedMacKey.length < 32
                || negotiatedMacKey.length > 128) {
            throw new IllegalArgumentException(
                    "IPC MAC key must be 32..128 bytes");
        }

        byte[] privateMacKey = negotiatedMacKey.clone();
        byte[] binding = new byte[CONNECTION_BINDING_BYTES];
        try {
            boolean nonzero = false;
            // A trustworthy random source will succeed on the first attempt;
            // the bounded retry also makes all-zero deterministic test sources
            // fail closed without an unbounded loop.
            for (int attempt = 0; attempt < 4 && !nonzero; ++attempt) {
                Arrays.fill(binding, (byte) 0);
                bindingSource.fill(binding);
                if (binding.length != CONNECTION_BINDING_BYTES) {
                    throw new IllegalStateException(
                            "IPC binding source changed binding length");
                }
                for (byte value : binding) nonzero |= value != 0;
            }
            if (!nonzero) {
                throw new IllegalStateException(
                        "IPC connection binding source produced all-zero binding");
            }

            IpcPhysicalConnectionAuthority authority =
                    new IpcPhysicalConnectionAuthority(
                            generation,
                            new Object(),
                            privateMacKey,
                            binding);
            // Advance only after the complete authority has been allocated.
            lastGeneration = generation;
            return authority;
        } catch (RuntimeException | Error mintFailure) {
            Arrays.fill(privateMacKey, (byte) 0);
            Arrays.fill(binding, (byte) 0);
            throw mintFailure;
        }
    }
}

/**
 * One-shot broker-minted physical connection authority.
 *
 * <p>Before consumption, close() destroys private credentials. On successful
 * consumption, ownership of the private arrays transfers to the transport and
 * this object drops every reference to them. A consumed or destroyed
 * authority can never be reused.</p>
 */
final class IpcPhysicalConnectionAuthority implements AutoCloseable {
    static final class Transfer {
        private final long generation;
        private final Object physicalOwner;
        private final byte[] macKey;
        private final byte[] connectionBinding;

        private Transfer(long generation,
                         Object physicalOwner,
                         byte[] macKey,
                         byte[] connectionBinding) {
            this.generation = generation;
            this.physicalOwner = physicalOwner;
            this.macKey = macKey;
            this.connectionBinding = connectionBinding;
        }

        long generation() { return generation; }
        Object physicalOwner() { return physicalOwner; }
        byte[] macKey() { return macKey; }
        byte[] connectionBinding() { return connectionBinding; }

        void destroy() {
            Arrays.fill(macKey, (byte) 0);
            Arrays.fill(connectionBinding, (byte) 0);
        }
    }

    private final long generation;
    private Object physicalOwner;
    private byte[] macKey;
    private byte[] connectionBinding;
    private boolean terminal;

    IpcPhysicalConnectionAuthority(
            long generation,
            Object physicalOwner,
            byte[] macKey,
            byte[] connectionBinding) {
        if (generation <= 0) {
            throw new IllegalArgumentException(
                    "physical generation must be positive");
        }
        this.generation = generation;
        this.physicalOwner = Objects.requireNonNull(
                physicalOwner, "physicalOwner");
        this.macKey = Objects.requireNonNull(macKey, "macKey");
        this.connectionBinding = Objects.requireNonNull(
                connectionBinding, "connectionBinding");
    }

    synchronized Transfer consume() {
        if (terminal) {
            throw new IllegalStateException(
                    "IPC physical connection authority already consumed or destroyed");
        }

        // Allocate the transfer before changing this object's state. If the VM
        // cannot allocate it, the authority remains intact and retryable.
        Transfer transfer = new Transfer(
                generation, physicalOwner, macKey, connectionBinding);
        terminal = true;
        physicalOwner = null;
        macKey = null;
        connectionBinding = null;
        return transfer;
    }

    synchronized boolean consumedOrDestroyed() {
        return terminal;
    }

    @Override
    public synchronized void close() {
        if (terminal) return;
        terminal = true;
        physicalOwner = null;
        if (macKey != null) Arrays.fill(macKey, (byte) 0);
        if (connectionBinding != null) {
            Arrays.fill(connectionBinding, (byte) 0);
        }
        macKey = null;
        connectionBinding = null;
    }
}
