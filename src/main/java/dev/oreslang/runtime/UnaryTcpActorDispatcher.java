package dev.oreslang.runtime;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Host bridge from authenticated unary TCP requests into real ActorRuntime
 * mailbox execution.
 *
 * <p>This is deliberately a compiler/runtime integration primitive, not a guest
 * networking API. TCP request metadata crosses the actor boundary only as a
 * frozen data map. Socket/session capabilities, reply slots and dispatch
 * handles remain supervisor-owned.</p>
 *
 * <p>Completion is not published to the transport until the one-shot actor has
 * crossed its finalization/carrier-leave barrier. Cancellation from TCP CANCEL,
 * timeout or session close propagates to the actor ref. UNTRUSTED actors remain
 * fail-closed here until compiler-owned source-code executors can bind this
 * transport without using a trusted captured host factory.</p>
 */
final class UnaryTcpActorDispatcher implements UnaryTcpSessionAdapter.Dispatcher {
    @FunctionalInterface
    interface ResultCodec {
        OresBytes encode(Object value) throws Exception;
    }

    private final ActorRuntime runtime;
    private final ActorRuntime.ActorKind kind;
    private final ActorRuntime.Invocation<Map<String, Object>, Object> invocation;
    private final ResultCodec codec;

    UnaryTcpActorDispatcher(
            ActorRuntime runtime,
            ActorRuntime.ActorKind kind,
            ActorRuntime.Invocation<Map<String, Object>, Object> invocation,
            ResultCodec codec) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.invocation = Objects.requireNonNull(invocation, "invocation");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (kind == ActorRuntime.ActorKind.UNTRUSTED) {
            throw new SecurityException(
                    "untrusted unary TCP dispatch requires compiler-owned source executor binding");
        }
    }

    /**
     * Strict Bytes-style result codec. Compact OresBytes is the canonical
     * runtime representation; integer lists remain accepted for compatibility
     * with the bootstrap interpreter until source-level Bytes lowering lands.
     */
    static ResultCodec bytesCodec(int maxBytes) {
        if (maxBytes < 0 || maxBytes > UnaryTransportFrame.MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("invalid unary TCP result byte limit");
        return value -> {
            if (value instanceof OresBytes bytesValue) {
                if (bytesValue.size() > maxBytes)
                    throw new IllegalArgumentException(
                            "unary TCP result exceeds selected byte limit");
                return bytesValue;
            }
            if (!(value instanceof List<?> values))
                throw new IllegalArgumentException(
                        "unary TCP result must be OresBytes or a byte list under the selected codec");
            if (values.size() > maxBytes)
                throw new IllegalArgumentException("unary TCP result exceeds selected byte limit");
            byte[] bytes = new byte[values.size()];
            for (int i = 0; i < values.size(); i++) {
                Object item = values.get(i);
                final long octet;
                if (item instanceof Byte byteValue) {
                    octet = Byte.toUnsignedInt(byteValue);
                } else if (item instanceof Short shortValue) {
                    octet = shortValue.longValue();
                } else if (item instanceof Integer intValue) {
                    octet = intValue.longValue();
                } else if (item instanceof Long longValue) {
                    octet = longValue;
                } else if (item instanceof BigInteger bigValue
                        && bigValue.signum() >= 0
                        && bigValue.bitLength() <= 8) {
                    octet = bigValue.longValue();
                } else {
                    throw new IllegalArgumentException(
                            "unary TCP result byte must be an integer in 0..255");
                }
                if (octet < 0 || octet > 255) {
                    throw new IllegalArgumentException(
                            "unary TCP result byte must be an integer in 0..255");
                }
                bytes[i] = (byte) octet;
            }
            return OresBytes.wrapTrusted(bytes);
        };
    }

    static ResultCodec byteListCodec(int maxBytes) {
        return bytesCodec(maxBytes);
    }

    private OresBytes encodeResultForSupervisor(Object value) throws Exception {
        OresBytes encoded = Objects.requireNonNull(
                codec.encode(value),
                "unary TCP result codec returned null");
        if (encoded.size() > UnaryTransportFrame.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "unary TCP result exceeds protocol byte limit");
        }
        return kind == ActorRuntime.ActorKind.PRIVATE
                ? encoded.isolatedCopy()
                : encoded;
    }

    @Override
    public UnaryTcpSessionAdapter.DispatchHandle dispatch(
            UnaryTcpSessionAdapter.Request request) {
        Objects.requireNonNull(request, "request");
        OresFuture<OresBytes> staged = new OresFuture<>();

        ActorRuntime.BehaviorFactory<Map<String, Object>> factory =
                ignoredFactoryContext -> (delivered, turnContext) -> {
                    try {
                        Object result = invocation.run(delivered, turnContext);
                        if (result instanceof OresFuture<?> future) {
                            @SuppressWarnings("unchecked")
                            OresFuture<Object> owned =
                                    (OresFuture<Object>) runtime.ownCurrentActorFuture(future);
                            ActorRuntime.ContinuationTarget target =
                                    runtime.captureCurrentContinuationTarget();
                            runtime.enqueueOnCompletion(
                                    owned,
                                    target,
                                    (value, failure) -> {
                                        try {
                                            if (failure == null) {
                                                staged.completeFromRuntime(
                                                        encodeResultForSupervisor(value));
                                            } else {
                                                staged.failFromRuntime(
                                                        OresFuture.unwrap(failure));
                                            }
                                        } catch (Error fatal) {
                                            staged.failFromRuntime(fatal);
                                            throw fatal;
                                        } catch (Throwable callbackFailure) {
                                            staged.failFromRuntime(callbackFailure);
                                        } finally {
                                            turnContext.self().stop();
                                        }
                                    });
                            return;
                        }

                        staged.completeFromRuntime(
                                encodeResultForSupervisor(result));
                        turnContext.self().stop();
                    } catch (Error fatal) {
                        staged.failFromRuntime(fatal);
                        throw fatal;
                    } catch (Throwable failure) {
                        staged.failFromRuntime(failure);
                        turnContext.self().stop();
                    }
                };

        ActorRuntime.ActorRef<Map<String, Object>> ref = switch (kind) {
            case PRIVATE -> runtime.spawnPrivateTrusted(factory);
            case SHARED -> runtime.spawnSharedTrusted(factory);
            case UNTRUSTED -> throw new SecurityException(
                    "untrusted unary TCP dispatch is not enabled");
        };

        Dispatch dispatch = new Dispatch(ref, staged);
        ref.done().whenCompleteRuntime(dispatch::actorFinalized);

        try {
            ref.send(request.actorMessage());
        } catch (Error fatal) {
            staged.failFromRuntime(fatal);
            dispatch.cancel();
            throw fatal;
        } catch (Throwable failure) {
            staged.failFromRuntime(failure);
            dispatch.cancel();
        }
        return dispatch;
    }

    private final class Dispatch implements UnaryTcpSessionAdapter.DispatchHandle {
        private final ActorRuntime.ActorRef<Map<String, Object>> ref;
        private final OresFuture<OresBytes> staged;
        private final OresFuture<UnaryTcpSessionAdapter.Reply> completion =
                new OresFuture<>();

        private Dispatch(
                ActorRuntime.ActorRef<Map<String, Object>> ref,
                OresFuture<OresBytes> staged) {
            this.ref = Objects.requireNonNull(ref, "ref");
            this.staged = Objects.requireNonNull(staged, "staged");
        }

        private void actorFinalized(Void ignored, Throwable barrierFailure) {
            if (completion.isDone()) return;

            Object terminal = staged.runtimeTerminalStateOrNull();
            if (terminal == null) {
                Throwable failure = barrierFailure == null
                        ? new IllegalStateException(
                                "unary TCP actor finalized before producing a result")
                        : OresFuture.unwrap(barrierFailure);
                completion.failFromRuntime(failure);
                return;
            }

            Throwable resultFailure = OresFuture.runtimeTerminalFailure(terminal);
            if (resultFailure != null) {
                if (resultFailure instanceof CancellationException) {
                    completion.cancel(false);
                } else {
                    completion.failFromRuntime(resultFailure);
                }
                return;
            }

            Object value = OresFuture.runtimeTerminalValue(terminal);
            if (!(value instanceof OresBytes bytes)) {
                completion.failFromRuntime(new IllegalStateException(
                        "unary TCP staged result lost immutable Bytes representation"));
                return;
            }
            completion.completeFromRuntime(
                    new UnaryTcpSessionAdapter.Reply(
                            UnaryTransportFrame.Kind.REPLY, bytes));
        }

        @Override
        public void whenComplete(
                java.util.function.BiConsumer<
                        ? super UnaryTcpSessionAdapter.Reply,
                        ? super Throwable> callback) {
            completion.whenCompleteRuntime(
                    Objects.requireNonNull(callback, "callback"));
        }

        @Override
        public boolean cancel() {
            boolean actorCancellationWon = ref.cancel();
            completion.cancel(false);
            return actorCancellationWon;
        }
    }
}
