package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Host-owned process/session boundary for one PRIVATE IPC actor route.
 *
 * <p>Launch is fail-closed: the exact admitted executable is started by the
 * native sealed-object transport, bootstrap material is single-use, and the
 * constructor does not return until the first authenticated worker frame is the
 * exact CONTROL/READY commitment accepted by {@link ActorIpcSessionV1}.</p>
 *
 * <p>UNTRUSTED IPC actor process launch remains disabled until a parent-enforced
 * OS sandbox provider is present. This class never weakens that boundary merely
 * to make process launch available.</p>
 */
public final class ActorIpcProcessV1 implements AutoCloseable {
    public static final int DEFAULT_STARTUP_TIMEOUT_MILLIS = 5_000;

    private final ActorIpcSessionV1 session;
    private final NativeActorIpcTransportV1 transport;
    private boolean closed;

    private ActorIpcProcessV1(
            ActorIpcSessionV1 session,
            NativeActorIpcTransportV1 transport) {
        this.session = Objects.requireNonNull(session, "session");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public static ActorIpcProcessV1 launch(
            ActorRuntime runtime,
            ActorArtifact<?> artifact,
            UUID logicalActorId,
            long incarnation,
            long routeEpoch) {
        return launch(
                runtime,
                artifact,
                logicalActorId,
                incarnation,
                routeEpoch,
                DEFAULT_STARTUP_TIMEOUT_MILLIS);
    }

    public static ActorIpcProcessV1 launch(
            ActorRuntime runtime,
            ActorArtifact<?> artifact,
            UUID logicalActorId,
            long incarnation,
            long routeEpoch,
            int startupTimeoutMillis) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(logicalActorId, "logicalActorId");
        if (artifact.actorKind() == Ast.ActorKind.UNTRUSTED) {
            throw new SecurityException(
                    "UNTRUSTED IPC process launch requires a parent-enforced OS sandbox provider");
        }
        if (artifact.actorKind() != Ast.ActorKind.PRIVATE) {
            throw new SecurityException(
                    "IPC process v1 only launches PRIVATE actor artifacts");
        }
        if (startupTimeoutMillis <= 0 || startupTimeoutMillis > 60_000) {
            throw new IllegalArgumentException(
                    "startupTimeoutMillis must be in [1,60000]");
        }

        ActorIpcSessionV1 session =
                runtime.openIpcSession(
                        artifact,
                        logicalActorId,
                        incarnation,
                        routeEpoch);
        NativeActorIpcTransportV1 transport = null;
        byte[] artifactBytes = null;
        byte[] bootstrapWire = null;
        boolean success = false;
        try (ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            artifactBytes = artifact.copyExactArtifactBytesForTrustedLauncher();
            bootstrapWire = ActorIpcBootstrapV1.encode(bootstrap);
            transport =
                    NativeActorIpcTransportV1.launch(
                            artifactBytes,
                            bootstrapWire);

            ActorIpcSessionV1.Frame ready =
                    session.acceptWorkerFrame(
                            transport.readControlFrame(startupTimeoutMillis));
            if (ready.lane() != ActorIpcSessionV1.Lane.CONTROL
                    || ready.kind() != ActorIpcSessionV1.FrameKind.READY
                    || !session.ready()) {
                throw new SecurityException(
                        "IPC actor worker did not establish exact CONTROL/READY state");
            }

            ActorIpcProcessV1 result =
                    new ActorIpcProcessV1(session, transport);
            success = true;
            transport = null;
            return result;
        } finally {
            if (artifactBytes != null) {
                Arrays.fill(artifactBytes, (byte) 0);
            }
            if (bootstrapWire != null) {
                Arrays.fill(bootstrapWire, (byte) 0);
            }
            if (!success) {
                if (transport != null) transport.close();
                session.close();
            }
        }
    }

    public synchronized UUID logicalActorId() {
        requireOpen();
        return session.logicalActorId();
    }

    public synchronized long incarnation() {
        requireOpen();
        return session.incarnation();
    }

    public synchronized long routeEpoch() {
        requireOpen();
        return session.routeEpoch();
    }

    public synchronized long pid() {
        requireOpen();
        return transport.pid();
    }

    public synchronized boolean ready() {
        return !closed && session.ready() && transport.isAlive();
    }

    public void sendData(long messageSequence, byte[] payload) {
        byte[] frame;
        synchronized (this) {
            requireOpen();
            frame = session.encodeSupervisorData(messageSequence, payload);
        }
        transport.writeDataFrame(frame);
    }

    public void sendControl(
            ActorIpcSessionV1.FrameKind kind,
            long messageSequence,
            byte[] payload) {
        byte[] frame;
        synchronized (this) {
            requireOpen();
            frame = session.encodeSupervisorControl(
                    kind,
                    messageSequence,
                    payload);
        }
        transport.writeControlFrame(frame);
    }

    public ActorIpcSessionV1.Frame receiveData(int timeoutMillis) {
        byte[] wire = transport.readDataFrame(timeoutMillis);
        synchronized (this) {
            requireOpen();
            ActorIpcSessionV1.Frame frame =
                    session.acceptWorkerFrame(wire);
            if (frame.lane() != ActorIpcSessionV1.Lane.DATA) {
                closeOnProtocolMismatch();
                throw new SecurityException(
                        "CONTROL frame arrived on physical DATA transport");
            }
            return frame;
        }
    }

    public ActorIpcSessionV1.Frame receiveControl(int timeoutMillis) {
        byte[] wire = transport.readControlFrame(timeoutMillis);
        synchronized (this) {
            requireOpen();
            ActorIpcSessionV1.Frame frame =
                    session.acceptWorkerFrame(wire);
            if (frame.lane() != ActorIpcSessionV1.Lane.CONTROL) {
                closeOnProtocolMismatch();
                throw new SecurityException(
                        "DATA frame arrived on physical CONTROL transport");
            }
            return frame;
        }
    }

    private void closeOnProtocolMismatch() {
        closed = true;
        try {
            session.close();
        } finally {
            transport.close();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "IPC actor process is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            session.close();
        } finally {
            transport.close();
        }
    }
}
