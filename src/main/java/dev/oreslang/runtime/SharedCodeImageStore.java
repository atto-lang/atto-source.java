package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local, OresVM-owned read-only source code images.
 *
 * <p>One immutable AST/linked code image is installed per code-unit identity,
 * irrespective of the number or isolation kind of actors evaluating it. Actor
 * state, environment frames, mailbox payloads and capability policy are NOT
 * installed here: they remain independently confined to their owner.</p>
 *
 * <p>This store shares JVM/Truffle AST code data inside one OresVM/OS process.
 * It does not claim sharing compiled native instructions across processes,
 * Truffle Engines or independent hot-reload generations.</p>
 */
public final class SharedCodeImageStore implements AutoCloseable {
    // Bound the VM-level code image registry independently of each code unit's
    // 4096-entry / 1 MiB Symbol snapshot. This is NOT a quota on AST/native
    // instruction bytes or on dynamic Symbol values.
    public static final int MAX_CODE_IMAGES = 4_096;
    public static final long MAX_TOTAL_LITERAL_BYTES = 64L * 1_048_576L;

    /**
     * Opaque capability minted only by this store. Callers may observe the
     * immutable program identity but cannot manufacture a CodeImage that
     * bypasses publication/provenance checks.
     */
    public static final class CodeImage {
        private final String codeUnitId;
        private final Ast.Program program;
        private final List<OresSymbol> staticLiterals;
        private final OresSymbolLiteralImageStore literalStore;
        private final OresSymbolLiteralImageStore.Lease literalLease;

        private CodeImage(String codeUnitId, Ast.Program program, List<OresSymbol> literals) {
            if (Objects.requireNonNull(codeUnitId, "codeUnitId").isBlank()) {
                throw new IllegalArgumentException("code unit id cannot be blank");
            }
            this.codeUnitId = codeUnitId;
            this.program = Objects.requireNonNull(program, "program");
            this.staticLiterals = List.copyOf(Objects.requireNonNull(literals, "literals"));
            // Each linked code unit has its OWN immutable region: importing a
            // ninth source unit must not evict the eight-generation hot-reload
            // quota of a completely different code unit.
            OresSymbolLiteralImageStore image = new OresSymbolLiteralImageStore();
            try {
                image.publish(staticLiterals);
                literalLease = image.pinLatest();
            } catch (RuntimeException | Error failed) {
                image.close();
                throw failed;
            }
            literalStore = image;
        }

        public String codeUnitId() { return codeUnitId; }
        public Ast.Program program() { return program; }

        /**
         * Only compiler-lexed static literals are pooled. Dynamic guest names
         * remain independent non-interned values; correctness is logical
         * kind/name equality, not pointer or slot identity.
         */
        public OresSymbol staticLiteralValue(OresSymbol value) {
            Objects.requireNonNull(value, "value");
            int ordinal = literalLease.indexOf(value);
            return ordinal < 0 ? value : literalLease.get(ordinal);
        }

        /**
         * Compiled #literals are NOT dynamic Symbol.process(...) calls.
         * Every literal AST value must originate in the checked source image.
         * Missing values indicate a source/AST identity mismatch, not a reason
         * to create a new untracked runtime value or bypass image quotas.
         */
        public OresSymbol requireGuestStaticLiteral(
                OresSymbol value, ActorRuntime.ActorKind actorKind) {
            Objects.requireNonNull(value, "value");
            if (literalLease.indexOf(value) < 0) {
                throw new IllegalStateException(
                        "compiled Symbol literal missing from checked code image");
            }
            return literalForActor(value, actorKind);
        }

        /**
         * Sharing an immutable JVM value is permitted only on the trusted
         * SHARED/root domain. PRIVATE/UNTRUSTED actors get separate scalar
         * values, not references into the VM-owned literal pool.
         *
         * This first version has only the sealed public-core untrusted
         * manifest; tenant-scoped authenticated exports require a separate
         * destination-bound authorization proof.
         */
        public OresSymbol literalForActor(
                OresSymbol value, ActorRuntime.ActorKind actorKind) {
            OresSymbol pooled = staticLiteralValue(value);
            if (actorKind == ActorRuntime.ActorKind.UNTRUSTED) {
                if (!pooled.publicToUntrustedCoreV1()) {
                    throw new SecurityException(
                            "Symbol literal is not visible to an untrusted actor");
                }
                return OresSymbol.process(pooled.name());
            }
            if (actorKind == ActorRuntime.ActorKind.PRIVATE) {
                return pooled.kind() == OresSymbol.Kind.PROCESS
                        ? OresSymbol.process(pooled.name())
                        : OresSymbol.stable(pooled.name());
            }
            // Root and SHARED actors use the same deeply immutable scalar.
            return pooled;
        }

        private void close() {
            literalLease.close();
            literalStore.close();
        }

        @Override
        public String toString() {
            return "CodeImage[" + codeUnitId + "]";
        }
    }

    private final Map<String, CodeImage> images = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int maxCodeImages;
    private final long maxLiteralBytes;
    // Guarded by the same images monitor as registry admission and retirement.
    private long activeLiteralBytes;

    public SharedCodeImageStore() {
        this(MAX_CODE_IMAGES, MAX_TOTAL_LITERAL_BYTES);
    }

    // Trusted test-only constructor: lets quota edge cases run without
    // allocating 64 MiB of symbols or thousands of linked AST objects.
    SharedCodeImageStore(int maxCodeImages, long maxLiteralBytes) {
        if (maxCodeImages < 1 || maxCodeImages > MAX_CODE_IMAGES
                || maxLiteralBytes < 0 || maxLiteralBytes > MAX_TOTAL_LITERAL_BYTES) {
            throw new IllegalArgumentException("invalid VM Symbol image quotas");
        }
        this.maxCodeImages = maxCodeImages;
        this.maxLiteralBytes = maxLiteralBytes;
    }

    // A caller-provided List may execute arbitrary iterator callbacks during
    // snapshotting. Do not permit recursive publication on the same thread:
    // it could insert an unexpected code unit before the outer transaction
    // checks its name and immutable source identity. Other threads can still
    // stage independently and trusted readers remain lock-free.
    private final ThreadLocal<Boolean> staging = new ThreadLocal<>();

    public CodeImage publish(String codeUnitId, Ast.Program immutableProgram) {
        return publish(codeUnitId, immutableProgram, List.of());
    }

    public CodeImage publish(
            String codeUnitId, Ast.Program immutableProgram, List<OresSymbol> literalCatalog) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        Objects.requireNonNull(immutableProgram, "immutableProgram");
        Objects.requireNonNull(literalCatalog, "literalCatalog");
        // Reject invalid code-unit authority before iterating a potentially
        // malicious catalog. Normal source paths are OS-bounded in practice;
        // this protects host/integration APIs with arbitrary logical IDs.
        if (codeUnitId.isBlank() || codeUnitId.length() > 8_192) {
            throw new IllegalArgumentException("invalid or oversized linked code unit id");
        }
        if (closed.get()) throw new IllegalStateException("shared code store is closed");
        if (Boolean.TRUE.equals(staging.get())) {
            throw new IllegalStateException("recursive linked code-image publication");
        }
        staging.set(Boolean.TRUE);
        try {
            // Even a host-supplied List can lie about size(), mutate, or block
            // inside iterator(); never invoke it with the VM code registry locked.
            List<OresSymbol> sealedCatalog = prepareLiteralCatalog(literalCatalog);
            synchronized (images) {
                if (closed.get()) throw new IllegalStateException("shared code store is closed");
                CodeImage prior = images.get(codeUnitId);
                if (prior != null) {
                    if (prior.program() != immutableProgram
                            || !prior.staticLiterals.equals(sealedCatalog)) {
                        throw new IllegalStateException(
                                "attempted to replace already-linked code unit '" + codeUnitId
                                        + "' without starting a new versioned code generation");
                    }
                    return prior;
                }
                if (images.size() >= maxCodeImages) {
                    throw new IllegalStateException("VM linked code-image count quota exceeded");
                }
                long candidateBytes = 0L;
                for (OresSymbol literal : sealedCatalog) {
                    candidateBytes += literal.name().getBytes(StandardCharsets.UTF_8).length;
                }
                if (candidateBytes > maxLiteralBytes - activeLiteralBytes) {
                    throw new IllegalStateException("VM aggregate static Symbol payload quota exceeded");
                }
                CodeImage image = new CodeImage(codeUnitId, immutableProgram, sealedCatalog);
                try {
                    images.put(codeUnitId, image);
                    activeLiteralBytes += candidateBytes;
                    return image;
                } catch (RuntimeException | Error failed) {
                    image.close();
                    throw failed;
                }
            }
        } finally {
            staging.remove();
        }
    }

    private static List<OresSymbol> prepareLiteralCatalog(List<OresSymbol> input) {
        List<OresSymbol> values = new ArrayList<>();
        Set<OresSymbol> keys = new HashSet<>();
        long bytes = 0L;
        for (OresSymbol value : input) {
            Objects.requireNonNull(value, "null Symbol literal");
            if (values.size() == OresSymbolLiteralImageStore.MAX_LITERALS) {
                throw new IllegalArgumentException("linked Symbol image entry quota exceeded");
            }
            if (!keys.add(value)) {
                throw new IllegalArgumentException("duplicate logical Symbol image entry");
            }
            bytes += value.name().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > OresSymbolLiteralImageStore.MAX_PAYLOAD_UTF8_BYTES) {
                throw new IllegalArgumentException("linked Symbol image UTF-8 quota exceeded");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    /** VM/internal lookup only. Never expose this reference to untrusted source code. */
    public CodeImage get(String codeUnitId) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        if (closed.get()) throw new IllegalStateException("shared code store is closed");
        return images.get(codeUnitId);
    }

    public int imageCount() {
        if (closed.get()) return 0;
        return images.size();
    }

    /** Only the canonical Symbol payload portion, not complete VM memory. */
    long activeLiteralPayloadBytes() {
        synchronized (images) {
            return activeLiteralBytes;
        }
    }

    @Override
    public void close() {
        synchronized (images) {
            if (!closed.compareAndSet(false, true)) return;
            for (CodeImage image : images.values()) image.close();
            images.clear();
            activeLiteralBytes = 0L;
        }
    }
}
