package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
        private final CodeImageIdentity identity;

        private CodeImage(
                String codeUnitId,
                Ast.Program program,
                CodeImageIdentity identity) {
            if (Objects.requireNonNull(codeUnitId, "codeUnitId").isBlank()) {
                throw new IllegalArgumentException("code unit id cannot be blank");
            }
            this.codeUnitId = codeUnitId;
            this.program = Objects.requireNonNull(program, "program");
            this.identity = Objects.requireNonNull(identity, "identity");
        }

        public String codeUnitId() { return codeUnitId; }
        public Ast.Program program() { return program; }
        CodeImageIdentity identity() { return identity; }

        @Override
        public String toString() {
            return "CodeImage[" + codeUnitId + "]";
        }
    }

    private final Map<String, CodeImage> images = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong codeImageSequence = new AtomicLong();

    public CodeImage publish(String codeUnitId, Ast.Program immutableProgram) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        Objects.requireNonNull(immutableProgram, "immutableProgram");
        synchronized (images) {
            if (closed.get()) throw new IllegalStateException("shared code store is closed");
            CodeImage prior = images.get(codeUnitId);
            if (prior != null) {
                if (prior.program() != immutableProgram) {
                    throw new IllegalStateException(
                            "attempted to replace already-linked code unit '" + codeUnitId
                            + "' without starting a new versioned code generation");
                }
                return prior;
            }
            CodeImageIdentity identity =
                    CodeImageIdentity.issue(codeUnitId, codeImageSequence.incrementAndGet());
            try {
                CodeImage image = new CodeImage(codeUnitId, immutableProgram, identity);
                images.put(codeUnitId, image);
                return image;
            } catch (RuntimeException | Error publicationFailure) {
                identity.revoke();
                throw publicationFailure;
            }
        }
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
            for (CodeImage image : images.values()) {
                image.identity().revoke();
            }
            images.clear();
        }
    }
}
