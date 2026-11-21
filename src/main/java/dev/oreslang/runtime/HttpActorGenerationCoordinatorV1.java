package dev.oreslang.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trusted-host composition of the single ActorRoutePublicationV1 authority
 * with bounded HTTP exchange lifetimes and code-image retention.
 *
 * <p>This class deliberately owns NO second active route pointer. Every new
 * HTTP admission reads the authoritative route and acquires its generation
 * lease while ActorRoutePublicationV1 holds its publication lock. Old HTTP
 * requests may continue after a compatible route publication; they pin only
 * their old generation's drain/image lifetime, not the new admission route.</p>
 *
 * <p>Host-only v1: the physical route must already be warmed/ready. Actual
 * image loading/unloading, signatures, source compiler pins, and NativeHttp
 * transport attachment are separate integration gates.</p>
 */
final class HttpActorGenerationCoordinatorV1<R> implements AutoCloseable {
    record Admission<R>(
            long routeEpoch,
            ActorUpgradeEpochGate.Image image,
            R physicalRoute,
            HttpGenerationDrainV1.Exchange lifetime) { }

    record GenerationStatus(
            long routeEpoch,
            long imageGeneration,
            HotCodeGenerationRetentionV1.Phase phase,
            HttpGenerationDrainV1.DrainStatus http) { }

    record Status(
            long activeRouteEpoch,
            HotCodeGenerationRetentionV1.Status retention,
            Map<Long, GenerationStatus> generations,
            boolean poisoned,
            boolean closed) { }

    private static final class Generation<R> {
        final long routeEpoch;
        final long imageGeneration;
        final ActorUpgradeEpochGate.Image image;
        final R physicalRoute;
        final HotCodeGenerationRetentionV1.Ticket imageTicket;
        final HttpGenerationDrainV1 http;

        Generation(long routeEpoch, long imageGeneration,
                   ActorUpgradeEpochGate.Image image, R physicalRoute,
                   HotCodeGenerationRetentionV1.Ticket imageTicket,
                   HttpGenerationDrainV1 http) {
            this.routeEpoch = routeEpoch;
            this.imageGeneration = imageGeneration;
            this.image = image;
            this.physicalRoute = physicalRoute;
            this.imageTicket = imageTicket;
            this.http = http;
        }
    }

    final class Stage implements AutoCloseable {
        private final ActorRoutePublicationV1<R>.Stage routeStage;
        private final Generation<R> prior;
        private final Generation<R> candidate;
        private final ActorRoutePublicationV1.StateSnapshot candidateState;
        private boolean published;
        private boolean closed;
        private boolean candidateCleanupAcknowledged;

        private Stage(ActorRoutePublicationV1<R>.Stage routeStage,
                      Generation<R> prior, Generation<R> candidate,
                      ActorRoutePublicationV1.StateSnapshot candidateState) {
            this.routeStage = routeStage;
            this.prior = prior;
            this.candidate = candidate;
            this.candidateState = candidateState;
        }

        /**
         * Compatible rolling publication. Existing HTTP requests do NOT block
         * this cutover; actor mailbox/Task pins owned by the logical actor gate
         * still must satisfy ActorRoutePublicationV1's own quiescence contract.
         */
        synchronized boolean publish() {
            requireOpen();
            if (!routeStage.publish(candidateState, candidate.physicalRoute))
                return false;

            published = true;
            closed = true;
            try {
                // These tickets were prevalidated before the authoritative
                // route swap. If this bookkeeping ever diverges, quarantine
                // future admissions instead of rolling the route backward.
                retention.activated(candidate.imageTicket);
                retention.draining(prior.imageTicket);
                prior.http.beginDrain();
            } catch (RuntimeException | Error failure) {
                poisoned = true;
                throw new IllegalStateException(
                        "route published but HTTP/image retention bookkeeping diverged",
                        failure);
            }
            return true;
        }

        /**
         * Abort before publication. Candidate capacity stays charged and enters
         * quarantine until the trusted loader separately confirms cleanup.
         */
        @Override public synchronized void close() {
            if (closed) return;
            try {
                routeStage.close();
            } finally {
                closed = true;
                generations.remove(candidate.routeEpoch, candidate);
                candidate.http.beginDrain();
                retention.quarantine(candidate.imageTicket);
            }
        }

        synchronized HotCodeGenerationRetentionV1.CleanupReceipt
                abortedPhysicalCleanupCompleted(String loaderResourceId) {
            if (!closed || published)
                throw new IllegalStateException("candidate is not an aborted stage");
            return retention.physicalCleanupCompleted(
                    candidate.imageTicket, loaderResourceId);
        }

        synchronized void acknowledgeAbortedPhysicalCleanup(
                HotCodeGenerationRetentionV1.CleanupReceipt receipt) {
            if (!closed || published)
                throw new IllegalStateException("candidate is not an aborted stage");
            if (candidateCleanupAcknowledged)
                throw new IllegalStateException("candidate cleanup already acknowledged");
            retention.acknowledgePhysicalCleanup(
                    candidate.imageTicket, candidate.routeEpoch,
                    candidate.http, receipt);
            candidateCleanupAcknowledged = true;
        }

        long candidateRouteEpoch() { return candidate.routeEpoch; }
        long candidateImageGeneration() { return candidate.imageGeneration; }
        String candidateImageSha256() { return candidate.image.codeSha256(); }

        private void requireOpen() {
            requireCoordinatorOperational();
            if (closed)
                throw new IllegalStateException("HTTP rolling stage already closed");
        }
    }

    private final ActorRoutePublicationV1<R> routes;
    private final HotCodeGenerationRetentionV1 retention;
    private final int maxExchangesPerGeneration;
    private final int maxResourcePinsPerGeneration;
    private final ConcurrentHashMap<Long, Generation<R>> generations =
            new ConcurrentHashMap<>();
    private long highestImageGeneration;
    private volatile boolean poisoned;
    private volatile boolean closed;

    HttpActorGenerationCoordinatorV1(
            ActorRoutePublicationV1<R> routes,
            int maxImages,
            long maxReservedImageBytes,
            long initialReservedImageBytes,
            int maxExchangesPerGeneration,
            int maxResourcePinsPerGeneration) {
        this.routes = Objects.requireNonNull(routes, "actor route authority");
        if (maxExchangesPerGeneration < 1 || maxResourcePinsPerGeneration < 1)
            throw new IllegalArgumentException("HTTP generation limits must be positive");
        this.maxExchangesPerGeneration = maxExchangesPerGeneration;
        this.maxResourcePinsPerGeneration = maxResourcePinsPerGeneration;
        this.retention = new HotCodeGenerationRetentionV1(
                maxImages, maxReservedImageBytes);

        var current = routes.current();
        long initialImageGeneration = 1L;
        var ticket = retention.reserve(
                initialImageGeneration, current.image().codeSha256(),
                initialReservedImageBytes);
        retention.activated(ticket);
        highestImageGeneration = initialImageGeneration;
        var initial = new Generation<>(
                current.routeEpoch(), initialImageGeneration,
                current.image(), current.physicalRoute(),
                ticket, new HttpGenerationDrainV1(
                        current.routeEpoch(), maxExchangesPerGeneration,
                        maxResourcePinsPerGeneration));
        if (generations.putIfAbsent(current.routeEpoch(), initial) != null)
            throw new IllegalStateException("duplicate initial HTTP generation");
    }

    /**
     * Atomic route + lifetime admission. Caller may dispatch only the returned
     * physical route; it must not re-read current() afterwards.
     */
    Admission<R> admit() {
        requireCoordinatorOperational();
        return routes.withCurrentAdmission(published -> {
            requireCoordinatorOperational();
            Generation<R> generation = generations.get(published.routeEpoch());
            if (generation == null
                    || generation.image != published.image()
                    || !Objects.equals(generation.physicalRoute, published.physicalRoute())) {
                poisoned = true;
                throw new IllegalStateException(
                        "HTTP generation registry diverged from authoritative route");
            }
            var lifetime = generation.http.admit();
            return new Admission<>(
                    published.routeEpoch(), published.image(),
                    published.physicalRoute(), lifetime);
        });
    }

    /**
     * Stage a warmed compatible physical route. Route staging happens BEFORE
     * image-budget reservation so quota failure can safely roll back the actor
     * freeze without exposing the candidate.
     */
    synchronized Stage stage(
            ActorUpgradeEpochGate.Image candidateImage,
            ActorRoutePublicationV1.StateSnapshot candidateState,
            R readyPhysicalRoute,
            long reservedImageBytes) {
        requireCoordinatorOperational();
        Objects.requireNonNull(candidateImage, "candidate image");
        Objects.requireNonNull(candidateState, "candidate state");
        Objects.requireNonNull(readyPhysicalRoute, "ready physical route");

        var priorPublished = routes.current();
        Generation<R> prior = generations.get(priorPublished.routeEpoch());
        if (prior == null) {
            poisoned = true;
            throw new IllegalStateException("active HTTP generation missing");
        }
        long nextEpoch = Math.addExact(priorPublished.routeEpoch(), 1L);
        ActorRoutePublicationV1<R>.Stage routeStage = routes.stage(candidateImage);
        final long imageGeneration = Math.addExact(highestImageGeneration, 1L);
        final HotCodeGenerationRetentionV1.Ticket ticket;
        try {
            ticket = retention.reserve(
                    imageGeneration, candidateImage.codeSha256(), reservedImageBytes);
        } catch (RuntimeException | Error failure) {
            try {
                routeStage.close();
            } catch (RuntimeException | Error rollback) {
                poisoned = true;
                if (failure != rollback) failure.addSuppressed(rollback);
            }
            throw failure;
        }

        highestImageGeneration = imageGeneration;
        Generation<R> candidate = new Generation<>(
                nextEpoch, imageGeneration, candidateImage, readyPhysicalRoute, ticket,
                new HttpGenerationDrainV1(
                        nextEpoch, maxExchangesPerGeneration,
                        maxResourcePinsPerGeneration));
        if (generations.putIfAbsent(nextEpoch, candidate) != null) {
            retention.quarantine(ticket);
            try {
                routeStage.close();
            } catch (RuntimeException | Error rollback) {
                poisoned = true;
            }
            throw new IllegalStateException("HTTP generation epoch already registered");
        }
        return new Stage(routeStage, prior, candidate, candidateState);
    }

    /**
     * Trusted loader reports physical cleanup of the exact retained image.
     * The returned receipt is bound to image generation/digest and one-shot.
     */
    synchronized HotCodeGenerationRetentionV1.CleanupReceipt
            physicalCleanupCompleted(long routeEpoch, String loaderResourceId) {
        requireCoordinatorOperational();
        Generation<R> generation = generations.get(routeEpoch);
        if (generation == null)
            throw new IllegalStateException("unknown or already collected HTTP generation");
        return retention.physicalCleanupCompleted(
                generation.imageTicket, loaderResourceId);
    }

    /** Physically collected old image after typed loader receipt + HTTP drain. */
    synchronized void collectRetired(
            long routeEpoch,
            HotCodeGenerationRetentionV1.CleanupReceipt receipt) {
        requireCoordinatorOperational();
        var current = routes.current();
        if (routeEpoch == current.routeEpoch())
            throw new IllegalStateException("cannot collect active HTTP route generation");
        Generation<R> generation = generations.get(routeEpoch);
        if (generation == null)
            throw new IllegalStateException("unknown or already collected HTTP generation");
        retention.acknowledgePhysicalCleanup(
                generation.imageTicket, generation.routeEpoch,
                generation.http, receipt);
        if (!generations.remove(routeEpoch, generation)) {
            poisoned = true;
            throw new IllegalStateException("HTTP generation collection raced registry mutation");
        }
    }

    Status status() {
        var current = routes.current();
        Map<Long, GenerationStatus> snapshots = generations.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> {
                            var g = entry.getValue();
                            var blocker = retention.status().retained().stream()
                                    .filter(item -> item.generation() == g.imageGeneration)
                                    .findFirst()
                                    .orElseThrow();
                            return new GenerationStatus(
                                    g.routeEpoch, g.imageGeneration,
                                    blocker.phase(), g.http.status());
                        }));
        return new Status(current.routeEpoch(), retention.status(),
                snapshots, poisoned, closed);
    }

    private void requireCoordinatorOperational() {
        if (closed || poisoned)
            throw new IllegalStateException(
                    "HTTP actor-generation coordinator closed or quarantined");
    }

    @Override public void close() {
        closed = true;
        // Ownership of routes/code images remains with the supervisor. Closing
        // this facade never purges or unloads a still-retained generation.
    }
}
