package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Compiler-only exact provenance for declarations emitted by macros. */
public final class MacroDeclarationProvenance {
    private static final ReferenceQueue<Ast.Decl> CLEARED = new ReferenceQueue<>();
    private static final Map<IdentityKey, MacroSyntaxProvenance.Origin> ORIGINS =
            new HashMap<>();

    private MacroDeclarationProvenance() { }

    private static final class IdentityKey extends WeakReference<Ast.Decl> {
        private final int hash;

        private IdentityKey(
                Ast.Decl declaration,
                ReferenceQueue<Ast.Decl> queue) {
            super(declaration, queue);
            hash = System.identityHashCode(declaration);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityKey key)) return false;
            Ast.Decl value = get();
            return value != null && value == key.get();
        }
    }

    private static void expunge() {
        Reference<? extends Ast.Decl> collected;
        while ((collected = CLEARED.poll()) != null) {
            ORIGINS.remove(collected);
        }
    }

    public static synchronized void record(
            Ast.Decl declaration,
            MacroSyntaxProvenance.Origin origin) {
        java.util.Objects.requireNonNull(declaration, "generated declaration");
        java.util.Objects.requireNonNull(origin, "generated declaration origin");
        expunge();
        IdentityKey key = new IdentityKey(declaration, CLEARED);
        MacroSyntaxProvenance.Origin previous = ORIGINS.putIfAbsent(key, origin);
        if (previous != null && !previous.equals(origin)) {
            throw new IllegalArgumentException(
                    "generated declaration already has a different macro origin");
        }
    }

    public static synchronized MacroSyntaxProvenance.Origin lookup(
            Ast.Decl declaration) {
        java.util.Objects.requireNonNull(declaration, "generated declaration");
        expunge();
        return ORIGINS.get(new IdentityKey(declaration, null));
    }
}
