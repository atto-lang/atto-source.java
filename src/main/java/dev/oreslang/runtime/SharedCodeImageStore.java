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

    public CodeImage publish(String codeUnitId, Ast.Program immutableProgram) {
        return publish(codeUnitId, immutableProgram, List.of());
    }

    public CodeImage publish(
            String codeUnitId, Ast.Program immutableProgram, List<OresSymbol> literalCatalog) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        Objects.requireNonNull(immutableProgram, "immutableProgram");
        Objects.requireNonNull(literalCatalog, "literalCatalog");
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
            CodeImage image = new CodeImage(codeUnitId, immutableProgram, sealedCatalog);
            images.put(codeUnitId, image);
            return image;
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

    @Override
    public void close() {
        synchronized (images) {
            if (!closed.compareAndSet(false, true)) return;
            for (CodeImage image : images.values()) image.close();
            images.clear();
        }
    }
}
