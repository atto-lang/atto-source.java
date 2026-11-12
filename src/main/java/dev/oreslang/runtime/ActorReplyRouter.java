package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded request/reply correlation for a future multi-client actor transport.
 *
 * <p>This is runtime-only infrastructure, NOT a guest-visible RPC API or a
 * host-owned KV store. A connection owns an opaque Client capability, and an
 * admitted request has exactly one Future. Actor output must carry both IDs
 * and be authenticated by the caller before deliver/fail; IDs alone are not
 * authority to forge actor outputs. No callbacks or Future publication occur
 * while holding the accounting lock. The transport is responsible for
 * propagating cancellation and preserving per-connection wire order.</p>
 */
public final class ActorReplyRouter<T> implements AutoCloseable {
    private final Object lock = new Object();
    private final int maxClients;
    private final int maxPerClient;
    private final int maxInFlight;
    private final Map<Long, Client> clients = new HashMap<>();
    private final Map<Long, Pending> pending = new HashMap<>();
    private long nextClientId = 1;
    private long nextRequestId = 1;
    private boolean closed;

    private final class Pending {
        private final Client client;
        private final OresFuture<T> reply;
        private Pending(Client client, OresFuture<T> reply) {
            this.client = client;
            this.reply = reply;
        }
    }

    public ActorReplyRouter(int maxClients, int maxPerClient, int maxInFlight) {
        if (maxClients < 1 || maxPerClient < 1 || maxInFlight < 1) {
            throw new IllegalArgumentException("reply-router quotas must be positive");
        }
        this.maxClients = maxClients;
        this.maxPerClient = maxPerClient;
        this.maxInFlight = maxInFlight;
    }

    /** Opaque, never reused connection identity. Closing aborts its outstanding requests. */
    public final class Client implements AutoCloseable {
        private final long id;
        private final Set<Long> requests = new HashSet<>();
        private boolean retired;

        private Client(long id) {
            this.id = id;
        }

        public long id() {
            return id;
        }

        @Override public void close() {
            retire(this);
        }
    }

    /** One admission and one scheduler-aware Future, without a second wrapper Future. */
    public static final class Ticket<R> {
        private final long clientId;
        private final long requestId;
        private final OresFuture<R> reply;

        private Ticket(long clientId, long requestId, OresFuture<R> reply) {
            this.clientId = clientId;
            this.requestId = requestId;
            this.reply = reply;
        }

        public long clientId() { return clientId; }
        public long requestId() { return requestId; }
        public OresFuture<R> reply() { return reply; }
    }

    public Optional<Client> tryOpenClient() {
        synchronized (lock) {
            if (closed || clients.size() >= maxClients) return Optional.empty();
            if (nextClientId == Long.MAX_VALUE) {
                throw new IllegalStateException("reply-router client IDs exhausted");
            }
            Client client = new Client(nextClientId++);
            clients.put(client.id, client);
            return Optional.of(client);
        }
    }

    /**
     * Strict nonblocking admission: returns empty on either quota exhaustion.
     * The caller must not enqueue actor mail unless this ticket was granted.
     * If mailbox try_send subsequently fails, cancel the ticket's Future.
     */
    public Optional<Ticket<T>> tryAdmit(Client client) {
        Objects.requireNonNull(client, "client");
        synchronized (lock) {
            if (closed || client.retired || clients.get(client.id) != client) {
                return Optional.empty();
            }
            if (pending.size() >= maxInFlight || client.requests.size() >= maxPerClient) {
                return Optional.empty();
            }
            if (nextRequestId == Long.MAX_VALUE) {
                throw new IllegalStateException("reply-router request IDs exhausted");
            }
            long requestId = nextRequestId++;
            OresFuture<T> future = new OresFuture<>(
                    () -> discard(client, requestId));
            pending.put(requestId, new Pending(client, future));
            client.requests.add(requestId);
            return Optional.of(new Ticket<>(client.id, requestId, future));
        }
    }

    /**
     * Deliver only after validating the authentic actor output's target client.
     * Out-of-order replies are safe: the request ID, not the stream position,
     * chooses the waiter. A duplicate, late, or cross-client reply is rejected.
     */
    public boolean deliver(Client client, long requestId, T value) {
        Objects.requireNonNull(value, "reply value");
        Pending entry = remove(client, requestId);
        return entry != null && entry.reply.completeFromRuntime(value);
    }

    public boolean fail(Client client, long requestId, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Pending entry = remove(client, requestId);
        return entry != null && entry.reply.failFromRuntime(failure);
    }

    private Pending remove(Client client, long requestId) {
        Objects.requireNonNull(client, "client");
        synchronized (lock) {
            Pending entry = pending.get(requestId);
            if (entry == null || entry.client != client
                    || clients.get(client.id) != client || client.retired) {
                return null;
            }
            pending.remove(requestId);
            client.requests.remove(requestId);
            return entry;
        }
    }

    private void discard(Client client, long requestId) {
        // Cancel may race delivery. Only a still-pending, matching request is
        // removed; no other client's quota or response can be affected.
        remove(client, requestId);
    }

    public int inFlight() {
        synchronized (lock) {
            return pending.size();
        }
    }

    public int openClients() {
        synchronized (lock) {
            return clients.size();
        }
    }

    private void retire(Client client) {
        ArrayList<OresFuture<T>> abandoned = new ArrayList<>();
        synchronized (lock) {
            if (clients.get(client.id) != client || client.retired) return;
            client.retired = true;
            clients.remove(client.id);
            for (long requestId : client.requests) {
                Pending entry = pending.remove(requestId);
                if (entry != null) abandoned.add(entry.reply);
            }
            client.requests.clear();
        }
        // Never invoke Future cancellation callbacks under the router lock.
        for (OresFuture<T> future : abandoned) future.cancel(false);
    }

    @Override public void close() {
        ArrayList<OresFuture<T>> abandoned = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            for (Client client : clients.values()) {
                client.retired = true;
                client.requests.clear();
            }
            clients.clear();
            for (Pending entry : pending.values()) abandoned.add(entry.reply);
            pending.clear();
        }
        for (OresFuture<T> future : abandoned) future.cancel(false);
    }
}
