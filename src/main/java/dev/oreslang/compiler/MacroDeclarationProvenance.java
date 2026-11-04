package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Compiler-only exact provenance for declarations emitted by macros. */
public final class MacroDeclarationProvenance {
    public record ExposedName(
            String name,
            Ast.SourceSite identifierSite,
            String builder) {
        public ExposedName {
            if (name == null || name.isBlank() || name.equals("_") || name.startsWith("$")) {
                throw new IllegalArgumentException("invalid generated declaration name provenance");
            }
            java.util.Objects.requireNonNull(
                    identifierSite, "generated declaration name source site");
            if (builder == null || builder.isBlank()) {
                throw new IllegalArgumentException("generated declaration builder cannot be blank");
            }
        }
    }

    public record Entry(
            MacroSyntaxProvenance.Origin origin,
            ExposedName exposedName) {
        public Entry {
            java.util.Objects.requireNonNull(origin, "generated declaration origin");
            java.util.Objects.requireNonNull(
                    exposedName, "generated declaration exposed-name provenance");
        }
    }

    private static final ReferenceQueue<Ast.Decl> CLEARED = new ReferenceQueue<>();
    private static final Map<IdentityKey, Entry> ENTRIES = new HashMap<>();

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
            ENTRIES.remove(collected);
        }
    }

    public static synchronized void record(
            Ast.Decl declaration,
            MacroSyntaxProvenance.Origin origin,
            ExposedName exposedName) {
        java.util.Objects.requireNonNull(declaration, "generated declaration");
        Entry entry = new Entry(origin, exposedName);
        expunge();
        IdentityKey key = new IdentityKey(declaration, CLEARED);
        Entry previous = ENTRIES.putIfAbsent(key, entry);
        if (previous != null && !previous.equals(entry)) {
            throw new IllegalArgumentException(
                    "generated declaration already has different macro provenance");
        }
    }

    public static synchronized Entry lookupEntry(Ast.Decl declaration) {
        java.util.Objects.requireNonNull(declaration, "generated declaration");
        expunge();
        return ENTRIES.get(new IdentityKey(declaration, null));
    }

    public static MacroSyntaxProvenance.Origin lookup(Ast.Decl declaration) {
        Entry entry = lookupEntry(declaration);
        return entry == null ? null : entry.origin();
    }
}
