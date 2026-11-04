package dev.oreslang.types;

import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Not;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.SelfType;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Union;
import dev.oreslang.types.Types.Unknown;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Compiler-internal, direction-aware pipe endpoint contract.
 *
 * <p>This is NOT a public guest HasInputChannel/HasOutputChannel interface yet.
 * Type resolution must prove the endpoint role: guest code cannot acquire an
 * authenticated actor route by declaring an ordinary class with these names.
 * Only the AUTHENTICATED_ACTOR transport is executable in the current source
 * pipeline. All other link kinds require separately implemented, bounded,
 * cancellation-aware transport adapters and must fail closed at lowering.</p>
 */
final class PipeEndpointContracts {
    private PipeEndpointContracts() { }

    enum Role { SOURCE, SINK, TRANSFORM, AUTHENTICATED_ACTOR }
    enum Transport { AUTHENTICATED_ACTOR, BRIDGE_REQUIRED }

    record Endpoint(Type input, Type output, Role role) {
        Endpoint {
            Objects.requireNonNull(role, "pipe endpoint role");
            if (input == null && output == null) {
                throw new IllegalArgumentException("pipe endpoint needs an input or output");
            }
            if (role == Role.SOURCE && input != null
                    || role == Role.SINK && output != null
                    || role == Role.TRANSFORM && (input == null || output == null)
                    || role == Role.AUTHENTICATED_ACTOR
                        && (input == null || output == null || !input.equals(output))) {
                throw new IllegalArgumentException("invalid directional pipe endpoint role");
            }
            if (input != null) requireConcretePayload(input);
            if (output != null) requireConcretePayload(output);
        }
    }

    record Link(Type payload, Transport transport) {
        Link {
            Objects.requireNonNull(payload);
            Objects.requireNonNull(transport);
        }
    }

    static Endpoint source(Type output) {
        return new Endpoint(null, output, Role.SOURCE);
    }

    static Endpoint sink(Type input) {
        return new Endpoint(input, null, Role.SINK);
    }

    static Endpoint transform(Type input, Type output) {
        return new Endpoint(input, output, Role.TRANSFORM);
    }

    /** Existing homogeneous ActorStream v1: one exact, closed mail domain. */
    static Endpoint authenticatedActor(Type mailboxDomain) {
        return new Endpoint(mailboxDomain, mailboxDomain, Role.AUTHENTICATED_ACTOR);
    }

    static Link connect(Endpoint upstream, Endpoint downstream) {
        Objects.requireNonNull(upstream);
        Objects.requireNonNull(downstream);
        if (upstream.output() == null) {
            throw new IllegalArgumentException("pipe left endpoint has no output channel");
        }
        if (downstream.input() == null) {
            throw new IllegalArgumentException("pipe right endpoint has no input channel");
        }
        if (!upstream.output().equals(downstream.input())) {
            // No structural coercion or implicit byte framing: a transport
            // must prove the precise wire domain before exposing the link.
            if (upstream.role() == Role.AUTHENTICATED_ACTOR
                    && downstream.role() == Role.AUTHENTICATED_ACTOR) {
                throw new IllegalArgumentException(
                        "ActorStream pipe stage ActorMail domains must match exactly: "
                                + upstream.output() + " versus " + downstream.input());
            }
            throw new IllegalArgumentException(
                    "pipe endpoint domains must match exactly: "
                            + upstream.output() + " versus " + downstream.input());
        }
        Transport transport = upstream.role() == Role.AUTHENTICATED_ACTOR
                && downstream.role() == Role.AUTHENTICATED_ACTOR
                ? Transport.AUTHENTICATED_ACTOR : Transport.BRIDGE_REQUIRED;
        return new Link(upstream.output(), transport);
    }

    static void requireExecutable(Link link) {
        Objects.requireNonNull(link);
        if (link.transport() != Transport.AUTHENTICATED_ACTOR) {
            throw new IllegalArgumentException(
                    "non-actor pipe transport requires bounded stream/channel bridge lowering");
        }
    }

    /** Called by the existing TypeChecker actor-graph route to preserve its ABI. */
    static void requireAuthenticatedActorConnection(Type from, Type to) {
        requireExecutable(connect(authenticatedActor(from), authenticatedActor(to)));
    }

    /**
     * Fail closed even for deeply nested capability types: an otherwise
     * structurally valid packet may not smuggle a live Topic/Subscription,
     * channel direction view, or pipe routing handle into actor DATA.
     * ActorRef is deliberately not listed: independently granted actor
     * references have their own Sendable/Serializable rules.
     */
    private static boolean isNonTransportableCapability(String name) {
        return switch (name) {
            case "ActorGroup", "ActorStream",
                    "Topic", "Subscription", "TopicPublisher", "TopicSubscriber",
                    "Channel", "ReadChannel", "WriteChannel",
                    "HasInputChannel", "HasOutputChannel",
                    "SelectCase", "SelectSet", "SelectResult",
                    "MutexGuard", "Future", "Iterator", "AsyncIterator" -> true;
            default -> false;
        };
    }

    private static void requireConcretePayload(Type root) {
        Objects.requireNonNull(root, "pipe payload type");
        ArrayDeque<Type> pending = new ArrayDeque<>();
        IdentityHashMap<Type, Boolean> seen = new IdentityHashMap<>();
        pending.push(root);
        int examined = 0;
        int scheduled = 1;
        while (!pending.isEmpty()) {
            Type type = pending.pop();
            if (type == null) {
                throw new IllegalArgumentException("pipe payload contains a null nested type");
            }
            if (seen.put(type, Boolean.TRUE) != null) continue;
            if (++examined > 1024) {
                throw new IllegalArgumentException("pipe payload type exceeds complexity budget");
            }
            // A capability handle is not Sendable pipeline DATA. In particular,
            // a Topic or Subscription must never be implicitly lowered into
            // the authenticated ActorStream mail domain or mistaken for a
            // Channel/stream bridge. Capability transport requires a separate,
            // compiler-owned grant and may not be inferred from a nominal name.
            if (type instanceof Named named
                    && isNonTransportableCapability(named.name())) {
                throw new IllegalArgumentException(
                        "pipe payload cannot carry an endpoint or pub-sub capability: "
                                + named.name());
            }
            if (type == Unknown.INSTANCE || type instanceof Generic
                    || type instanceof Borrow || type instanceof Function
                    || type instanceof SelfType || type instanceof ClassNamespace
                    || type instanceof Not || type == Primitive.VOID || type == Primitive.NULL) {
                throw new IllegalArgumentException(
                        "pipe payload requires a resolved, owned, non-void type: " + type);
            }
            java.util.Collection<? extends Type> children = java.util.List.of();
            if (type instanceof Named named) {
                children = named.arguments();
            } else if (type instanceof ListType list) {
                children = java.util.Collections.singletonList(list.element());
            } else if (type instanceof Tuple tuple) {
                children = tuple.elements();
            } else if (type instanceof Union union) {
                children = union.options();
            } else if (type instanceof Record record) {
                children = record.members().values();
            }
            // Bound total scheduled work, including repeated references to the
            // same node, before allocating traversal stack entries.
            if (children.size() > 1024 - scheduled) {
                throw new IllegalArgumentException("pipe payload type exceeds complexity budget");
            }
            for (Type child : children) {
                if (child == null) {
                    throw new IllegalArgumentException("pipe payload contains a null nested type");
                }
                pending.push(child);
                scheduled++;
            }
        }
    }
}
