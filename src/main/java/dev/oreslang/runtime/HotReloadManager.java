package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Versioned source hot loader.
 *
 * Loading is side-effect free with respect to guest execution: source is
 * parsed/type/capability checked, then assigned a fresh context. Legacy load()
 * publishes before start; reloadAndStart() initializes before publication.
 *
 * No JNI/FFI or OS dynamic-library loading is required.
 */
public final class HotReloadManager implements AutoCloseable {
    private static final int MAX_ADVERSARIAL_SOURCE_CHARS = 1_048_576;
    private static final int MAX_ADVERSARIAL_CODE_UNIT_ID_CHARS = 512;
    private static final int MAX_ADVERSARIAL_LIVE_GENERATIONS = 8;

    private final IsolatePolicy supervisorPolicy;
    private final IsolatePolicy guestPolicy;
    private final ExecutionProfile executionProfile;
    private final AtomicLong sequence = new AtomicLong();
    private boolean closed;
    // Host monitor protected: routing changes invalidate speculative upgrade candidates.
    // A single monotonic epoch also prevents A -> B -> A slot ABA.
    private long routingEpoch;
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final Map<String, Generation> activeByCodeUnit = new LinkedHashMap<>();
    private final Map<Long, Generation> generations = new LinkedHashMap<>();

    public HotReloadManager(
            IsolatePolicy supervisorPolicy,
            ExecutionProfile executionProfile) {
        this(supervisorPolicy, supervisorPolicy, executionProfile);
    }

    public HotReloadManager(
            IsolatePolicy supervisorPolicy,
            IsolatePolicy guestPolicy,
            ExecutionProfile executionProfile) {
        this.supervisorPolicy =
                java.util.Objects.requireNonNull(supervisorPolicy);
        this.guestPolicy =
                java.util.Objects.requireNonNull(guestPolicy);
        this.executionProfile =
                java.util.Objects.requireNonNull(executionProfile);
        this.supervisorPolicy.require(
                IsolatePolicy.Capability.HOT_CODE_LOAD,
                "HotReloadManager");
        // Trusted parents may narrow authority, but must never grant a
        // hot-loaded guest more permission or resource budget than they own.
        if (!this.supervisorPolicy.capabilities().containsAll(this.guestPolicy.capabilities())
                || this.guestPolicy.maxHeapBytes() > this.supervisorPolicy.maxHeapBytes()
                || this.guestPolicy.maxMailboxMessages() > this.supervisorPolicy.maxMailboxMessages()
                || this.guestPolicy.maxWallTime().compareTo(this.supervisorPolicy.maxWallTime()) > 0
                || (this.supervisorPolicy.adversarial() && !this.guestPolicy.adversarial())) {
            throw new SecurityException(
                    "hot-loaded guest policy must be no more permissive than its supervisor");
        }
    }

    public static HotReloadManager forUntrustedActors(
            IsolatePolicy supervisorPolicy,
            ExecutionProfile executionProfile) {
        return new HotReloadManager(
                supervisorPolicy,
                IsolatePolicy.untrustedActor(),
                executionProfile);
    }

    public IsolatePolicy supervisorPolicy() { return supervisorPolicy; }
    public IsolatePolicy guestPolicy() { return guestPolicy; }

    /**
     * Validates and stages a new generation without executing its entrypoint.
     */
    public synchronized Generation load(String name, String sourceText) {
        validateAdmissionInputs(name, sourceText);
        OresCompiler.validateForIsolate(sourceText, guestPolicy);
        return stage(name, digest(sourceText), sourceText, true);
    }

    /**
     * Accepts a compiled-unit descriptor only after independently validating its
     * source text. CompiledUnit is a public, forgeable record: trusting its AST,
     * source digest or dependency set as a security receipt would let a benign
     * cached program authorize unrelated malicious source. A future incremental
     * fast path needs compiler-private, digest-bound attestation.
     */
    public synchronized Generation load(IncrementalCompiler.CompiledUnit unit) {
        java.util.Objects.requireNonNull(unit, "unit");
        validateAdmissionInputs(unit.unitId(), unit.sourceText());
        if (!digest(unit.sourceText()).equals(unit.sourceDigest())) {
            throw new SecurityException("compiled hot-load source digest mismatch");
        }
        if (!unit.dependencies().isEmpty()) {
            throw new SecurityException(
                    "incremental hot loading of imported dependencies requires verified linker provenance");
        }
        // Parse the executable source independently of the forgeable CompiledUnit
        // AST and dependency list. Reject imports *before* type/link resolution:
        // an unverified imported module must never become a trusted dependency.
        // Syntax/type/ownership/capability admission still follows for the
        // accepted import-free source.
        var parsedSource = dev.oreslang.parser.Parser.parse(unit.sourceText());
        if (!parsedSource.imports().isEmpty()) {
            throw new SecurityException(
                    "compiled hot-load imports require verified dependency closure");
        }
        OresCompiler.validateForIsolate(unit.sourceText(), guestPolicy);
        return stage(unit.unitId(), unit.sourceDigest(), unit.sourceText(), true);
    }

    private void validateAdmissionInputs(
            String codeUnitId,
            String sourceText) {
        requireOpen();
        java.util.Objects.requireNonNull(codeUnitId, "codeUnitId");
        java.util.Objects.requireNonNull(sourceText, "sourceText");
        if (codeUnitId.isBlank()) {
            throw new IllegalArgumentException("codeUnitId cannot be blank");
        }
        if (!guestPolicy.adversarial()) return;
        if (codeUnitId.length() > MAX_ADVERSARIAL_CODE_UNIT_ID_CHARS) {
            throw new IllegalArgumentException(
                    "adversarial hot-load codeUnitId exceeds maximum length "
                            + MAX_ADVERSARIAL_CODE_UNIT_ID_CHARS);
        }
        if (sourceText.length() > MAX_ADVERSARIAL_SOURCE_CHARS) {
            throw new IllegalArgumentException(
                    "adversarial hot-load source exceeds maximum character count "
                            + MAX_ADVERSARIAL_SOURCE_CHARS);
        }
    }

    private Generation stage(String codeUnitId, String sourceDigest, String sourceText, boolean publish) {
        requireOpen();
        if (guestPolicy.adversarial()
                && generations.size() >= MAX_ADVERSARIAL_LIVE_GENERATIONS) {
            throw new IllegalStateException(
                    "adversarial hot-load live generation limit exceeded: "
                            + MAX_ADVERSARIAL_LIVE_GENERATIONS);
        }
        long id = sequence.updateAndGet(current -> {
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("hot-reload generation IDs exhausted");
            }
            return current + 1;
        });
        Context context =
                guestPolicy.restrictedContextBuilder(executionProfile).build();
        try {
            Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnitId)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();
            Generation generation = new Generation(this, id, codeUnitId, sourceDigest, context, source, executionProfile);
            if (publish) advanceRoutingEpoch();
            generations.put(id, generation);
            if (publish) {
                activeByCodeUnit.put(codeUnitId, generation);
                active.set(generation);
            }
            return generation;
        } catch (RuntimeException | Error failure) {
            try {
                context.close(true);
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Stage and validate one actor-only generation without making it the
     * manager's generic active source route and without executing init/main.
     * The actor migration coordinator links declarations later through
     * Generation.linkActorGeneration().
     */
    public synchronized Generation stageActorGeneration(
            String name,
            String sourceText) {
        validateAdmissionInputs(name, sourceText);
        OresCompiler.validateForIsolate(sourceText, guestPolicy);
        return stage(name, digest(sourceText), sourceText, false);
    }

    /**
     * Transactional replacement for the generic source-loader path. The candidate
     * is NOT routable until its entrypoint returns successfully. An optimistic
     * supervisor routing epoch rejects stale publications (including A/B/A).
     * Existing generation-scoped users remain pinned to their old context.
     *
     * This is NOT actor state migration, actor mailbox fencing, or protection
     * against irreversible external side effects during guest initialization.
     */
    public Generation reloadAndStart(String name, String sourceText) {
        final Generation candidate;
        final long expectedRoutingEpoch;
        synchronized (this) {
            validateAdmissionInputs(name, sourceText);
            OresCompiler.validateForIsolate(sourceText, guestPolicy);
            candidate = stage(name, digest(sourceText), sourceText, false);
            expectedRoutingEpoch = routingEpoch;
        }
        try {
            candidate.start();
            synchronized (this) {
                requireOpen();
                if (routingEpoch != expectedRoutingEpoch
                        || generations.get(candidate.id()) != candidate
                        || candidate.closed()) {
                    throw new IllegalStateException(
                            "stale hot-reload candidate: routing changed during initialization");
                }
                advanceRoutingEpoch();
                activeByCodeUnit.put(name, candidate);
                active.set(candidate);
                return candidate;
            }
        } catch (RuntimeException | Error failure) {
            try {
                retire(candidate.id());
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Compatibility alias; successful initialization precedes routing publication. */
    public Generation loadAndStart(String name, String sourceText) {
        return reloadAndStart(name, sourceText);
    }

    private void advanceRoutingEpoch() {
        if (routingEpoch == Long.MAX_VALUE) {
            throw new IllegalStateException("hot-reload routing epoch exhausted");
        }
        ++routingEpoch;
    }

    /** Last generation staged, retained for compatibility with the single-unit API. */
    public Generation active() { return active.get(); }

    /** Active generation for one independently compiled code unit. */
    public synchronized Generation active(String codeUnitId) {
        return activeByCodeUnit.get(codeUnitId);
    }

    public synchronized Map<String, Generation> activeGenerations() {
        return Map.copyOf(activeByCodeUnit);
    }

    /** Explicit retirement permits old actors/requests to drain before teardown. */
    public void retire(long generationId) {
        final Generation generation;
        synchronized (this) {
            generation = generations.get(generationId);
            if (generation != null && generation.actorRoutePins() != 0) {
                throw new IllegalStateException(
                        "cannot retire hot-reload generation " + generationId
                                + " while a logical actor route pins it");
            }
            if (generation != null) {
                // Unpublish routing immediately, but retain the generation in
                // the durable registry until physical Context teardown and
                // manager deregistration both succeed. A failed close must be
                // addressable by generationId for explicit retry.
                boolean removed = active.compareAndSet(generation, null);
                removed |= activeByCodeUnit.remove(
                        generation.codeUnitId(), generation);
                if (removed) advanceRoutingEpoch();
            }
        }
        // Never close a potentially executing guest context under the manager
        // routing monitor: callbacks and unwind handlers may re-enter the host.
        if (generation != null) generation.close();
    }

    public synchronized int liveGenerations() { return generations.size(); }

    /**
     * A generation that has entered physical close must stop serving routing
     * immediately, even if Context teardown later fails. Keep it in
     * generations as the durable cleanup owner until onGenerationClosed().
     */
    private synchronized void onGenerationSealed(Generation generation) {
        boolean removed =
                activeByCodeUnit.remove(generation.codeUnitId(), generation);
        removed |= active.compareAndSet(generation, null);
        if (removed && !closed) advanceRoutingEpoch();
    }

    /** Closed guest contexts must not remain in the manager's cleanup registry. */
    private synchronized void onGenerationClosed(Generation generation) {
        generations.remove(generation.id(), generation);
        boolean removed = activeByCodeUnit.remove(generation.codeUnitId(), generation);
        removed |= active.compareAndSet(generation, null);
        if (removed && !closed) advanceRoutingEpoch();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("hot reload manager is closed");
        }
    }

    @Override
    public void close() {
        final java.util.List<Generation> toClose;
        synchronized (this) {
            if (!closed) {
                for (Generation generation : generations.values()) {
                    if (generation.actorRoutePins() != 0) {
                        throw new IllegalStateException(
                                "cannot close HotReloadManager while generation "
                                        + generation.id()
                                        + " is pinned by a logical actor route");
                    }
                }
                // Seal all new load/pin admission and unpublish routing. Do not
                // clear generations: that map is the durable retry registry.
                closed = true;
                activeByCodeUnit.clear();
                active.set(null);
            } else if (generations.isEmpty()) {
                return;
            }
            // Allocation failure here is safe: the sealed manager retains the
            // exact generation registry and a later close() reconstructs it.
            toClose = java.util.List.copyOf(generations.values());
        }

        Throwable failure = null;
        for (Generation generation : toClose) {
            try {
                generation.forceCloseFromManager();
            } catch (RuntimeException | Error closingFailure) {
                if (failure == null) {
                    failure = closingFailure;
                } else if (failure != closingFailure) {
                    failure.addSuppressed(closingFailure);
                }
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Trusted host view over one linked actor source generation. This object is
     * never returned to guest Oreslang code and does not itself authorize a
     * route publication or state migration.
     */
    public record ActorGenerationView(
            Generation generation,
            OresContext context,
            ActorRuntime runtime,
            ActorRuntime.ActorCodeExecutor executor,
            IsolatePolicy policy,
            String actorTypeName) {
        public ActorGenerationView {
            java.util.Objects.requireNonNull(generation);
            java.util.Objects.requireNonNull(context);
            java.util.Objects.requireNonNull(runtime);
            java.util.Objects.requireNonNull(executor);
            java.util.Objects.requireNonNull(policy);
            if (actorTypeName == null || actorTypeName.isBlank())
                throw new IllegalArgumentException("actor type name cannot be blank");
        }
    }

    public static final class Generation implements AutoCloseable {
        // A retained retired Generation must not pin the manager (and all its other contexts).
        private final java.lang.ref.WeakReference<HotReloadManager> owner;
        private final long id;
        private final String codeUnitId;
        private final String sha256;
        private final Context context;
        private final Source source;
        private final ExecutionProfile executionProfile;
        private final AtomicBoolean started = new AtomicBoolean();
        /** Admission/routing seal; not a physical Context-close witness. */
        private final AtomicBoolean closed = new AtomicBoolean();
        /** Guarded by this Generation. */
        private boolean contextCloseComplete;
        /** Guarded by this Generation. */
        private boolean closeComplete;
        /** Guarded by this Generation. */
        private boolean closeActive;
        // Package/test fault boundaries around physical Context teardown.
        private Runnable closeAfterSealFaultForTesting;
        private Runnable closeAfterContextFaultForTesting;
        private int actorRoutePins;
        private boolean actorLinked;
        private OresContext linkedActorContext;

        private Generation(HotReloadManager owner, long id, String codeUnitId, String sha256, Context context, Source source, ExecutionProfile executionProfile) {
            this.owner = new java.lang.ref.WeakReference<>(owner);
            this.id = id;
            this.codeUnitId = codeUnitId;
            this.sha256 = sha256;
            this.context = context;
            this.source = source;
            this.executionProfile = executionProfile;
        }

        /**
         * Generation lifetime pin owned by one logical actor route. Ordinary
         * retire()/close() cannot invalidate a live route behind its holder.
         */
        public final class ActorRouteLease implements AutoCloseable {
            private final AtomicBoolean released = new AtomicBoolean();

            private ActorRouteLease() { }

            public Generation generation() { return Generation.this; }

            @Override public void close() {
                synchronized (Generation.this) {
                    if (released.get()) return;
                    // Do not consume the lease token before validating its
                    // accounting debit. A failed release must be retryable;
                    // otherwise code retirement could be certified falsely.
                    if (actorRoutePins <= 0)
                        throw new IllegalStateException(
                                "hot-reload generation route-pin underflow");
                    actorRoutePins--;
                    released.set(true);
                }
            }
        }

        public ActorRouteLease pinActorRoute() {
            HotReloadManager manager = owner.get();
            if (manager == null)
                throw new IllegalStateException(
                        "generation owner is no longer available");
            // Lock order is manager -> generation, identical to retire().
            // Registry membership and pin acquisition therefore form one
            // linearizable admission: retire cannot remove at pin-count zero
            // and race a late pin before context close.
            synchronized (manager) {
                if (manager.closed
                        || manager.generations.get(id) != this) {
                    throw new IllegalStateException(
                            "generation is no longer registered for actor routing");
                }
                synchronized (this) {
                    if (closed.get())
                        throw new IllegalStateException("generation is closed");
                    if (actorRoutePins == Integer.MAX_VALUE)
                        throw new IllegalStateException(
                                "generation route-pin budget exhausted");
                    // Construct the lease before charging its count: if an
                    // allocation fails, no unowned generation pin remains.
                    ActorRouteLease lease = new ActorRouteLease();
                    actorRoutePins++;
                    return lease;
                }
            }
        }

        private synchronized int actorRoutePins() {
            return actorRoutePins;
        }

        HotReloadManager ownerForActorRouting() {
            HotReloadManager manager = owner.get();
            if (manager == null)
                throw new IllegalStateException(
                        "generation owner is no longer available");
            return manager;
        }

        public long id() { return id; }
        public String codeUnitId() { return codeUnitId; }
        public String sha256() { return sha256; }
        public Context context() { return context; }
        public Source source() { return source; }
        public ExecutionProfile executionProfile() { return executionProfile; }
        public boolean started() { return started.get(); }
        public boolean closed() { return closed.get(); }

        /**
         * Links declarations without running module initialization or main and
         * returns the compiler-private executor for one source actor type.
         *
         * <p>This is the staging primitive used by cross-context actor upgrade:
         * candidate on_start/main code does not execute merely because a
         * generation is being prepared.</p>
         */
        public synchronized ActorGenerationView linkActorGeneration(
                String actorTypeName) {
            if (actorTypeName == null || actorTypeName.isBlank())
                throw new IllegalArgumentException("actor type name cannot be blank");
            if (closed.get()) throw new IllegalStateException("generation is closed");
            if (started.get()) {
                throw new IllegalStateException(
                        "logical actor routing v1 requires an actor-only generation "
                                + "whose init/main has not executed");
            }

            if (!actorLinked) {
                Value parsed = context.parse(source);
                parsed.execute(OresEvalRootNode.LINK_ONLY_COMMAND);
                context.enter();
                try {
                    linkedActorContext = OresLanguage.currentEnteredContextForHost();
                } finally {
                    context.leave();
                }
                actorLinked = true;
            }
            OresContext ores = java.util.Objects.requireNonNull(
                    linkedActorContext, "linked actor context");
            Object linked = ores.linkedCodeUnit(codeUnitId);
            if (!(linked instanceof ActorRuntime.ActorCodeExecutor executor)) {
                throw new IllegalStateException(
                        "linked code unit does not expose an actor executor: "
                                + codeUnitId);
            }
            // Force the actor type to be resolved now, before any route is
            // frozen. These compiler receipts fail closed for unknown types.
            executor.actorStateSchemaSha256(actorTypeName);
            executor.actorMessageAbiSha256(actorTypeName);
            executor.actorCodeSha256(actorTypeName);
            return new ActorGenerationView(
                    this,
                    ores,
                    ores.actors(),
                    executor,
                    ores.isolatePolicy(),
                    actorTypeName);
        }

        /** Starts the staged generation exactly once. */
        public Value start() {
            synchronized (this) {
                if (closed.get())
                    throw new IllegalStateException("generation is closed");
                if (actorRoutePins != 0) {
                    throw new IllegalStateException(
                            "cannot execute generation init/main while "
                                    + actorRoutePins
                                    + " logical actor route(s) pin it");
                }
                if (!started.compareAndSet(false, true))
                    throw new IllegalStateException("generation already started");
            }
            try {
                return context.eval(source);
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }

        @Override
        public void close() {
            closeInternal(false);
        }

        private void forceCloseFromManager() {
            closeInternal(true);
        }

        private void closeInternal(boolean force) {
            final boolean closeContext;
            synchronized (this) {
                if (closeComplete) return;
                if (closeActive) {
                    throw new IllegalStateException(
                            "generation close is already in progress");
                }
                if (!force && actorRoutePins != 0) {
                    throw new IllegalStateException(
                            "cannot close generation while "
                                    + actorRoutePins
                                    + " logical actor route(s) pin it");
                }
                // Revoke execution/pin admission before touching Context, but
                // retain separate completion receipts for retry.
                closed.set(true);
                closeActive = true;
                closeContext = !contextCloseComplete;
            }

            try {
                HotReloadManager manager = owner.get();
                if (manager != null) manager.onGenerationSealed(this);

                Runnable afterSeal = closeAfterSealFaultForTesting;
                if (afterSeal != null) afterSeal.run();

                if (closeContext) {
                    context.close(true);
                    synchronized (this) {
                        contextCloseComplete = true;
                    }
                }

                Runnable afterContext = closeAfterContextFaultForTesting;
                if (afterContext != null) afterContext.run();

                // Registry eviction is part of physical retirement. If it ever
                // fails, contextCloseComplete lets a later retry skip Context
                // teardown while retaining this exact Generation owner.
                if (manager != null) manager.onGenerationClosed(this);

                synchronized (this) {
                    closeComplete = true;
                }
            } finally {
                synchronized (this) {
                    // Failed teardown remains sealed but explicitly retryable.
                    closeActive = false;
                }
            }
        }
    }
}
