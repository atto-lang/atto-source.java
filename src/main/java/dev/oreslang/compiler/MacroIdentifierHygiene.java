package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Compiler-only identity metadata for identifiers emitted by macro expansion.
 *
 * <p>Generated identifiers use an internal spelling beginning with '$', which
 * cannot be produced by the source lexer. That spelling is only a local compiler
 * handle; exact hygiene/capture authority lives in the metadata below and never
 * in a hash or textual name. Runtime/native artifacts need no hygiene table.</p>
 */
public final class MacroIdentifierHygiene {
    public enum Kind {
        GENERATED,
        CAPTURED_FUNCTION
    }

    public sealed interface Identity permits Generated, CapturedFunction {
        Kind kind();
    }

    public record Generated(
            MacroSyntaxProvenance.HygieneIdentity hygiene,
            String hint,
            int localOrdinal,
            String internalName) implements Identity {
        public Generated {
            java.util.Objects.requireNonNull(hygiene, "generated identifier hygiene");
            if (hint == null || hint.isBlank() || hint.equals("_") || hint.startsWith("$")
                    || hint.length() > Ast.MacroIdentifierArgument.MAX_NAME_CHARS) {
                throw new IllegalArgumentException("invalid generated identifier hint");
            }
            if (localOrdinal < 0) {
                throw new IllegalArgumentException(
                        "generated identifier ordinal cannot be negative");
            }
            if (internalName == null || !internalName.startsWith("$macro$g$")) {
                throw new IllegalArgumentException(
                        "generated identifier must use compiler-only spelling");
            }
        }

        @Override
        public Kind kind() {
            return Kind.GENERATED;
        }
    }

    public record CapturedFunction(
            MacroSyntaxProvenance.HygieneIdentity hygiene,
            String name,
            Ast.SourceSite identifierSite,
            List<Ast.FunctionDecl> declarations) implements Identity {
        public CapturedFunction {
            java.util.Objects.requireNonNull(hygiene, "checked capture hygiene identity");
            if (name == null || name.isBlank()
                    || name.equals("_") || name.startsWith("$")) {
                throw new IllegalArgumentException("invalid checked function capture");
            }
            java.util.Objects.requireNonNull(
                    identifierSite, "capture identifier call site");
            declarations = List.copyOf(declarations);
            if (declarations.isEmpty()) {
                throw new IllegalArgumentException(
                        "checked function capture needs a declaration");
            }
            if (declarations.size() != 1) {
                throw new IllegalArgumentException(
                        "checked function capture requires one reifiable function");
            }
            Ast.FunctionDecl function = declarations.getFirst();
            if (!function.name().equals(name)
                    || function.visibility() != Ast.Visibility.PUBLIC
                    || function.actorKind() != Ast.ActorKind.NONE
                    || function.kind() != Ast.CallableKind.FNC
                    || !function.genericParameters().isEmpty()
                    || function.suspends()) {
                throw new IllegalArgumentException(
                        "captured function identity/authority mismatch");
            }
        }

        @Override
        public Kind kind() {
            return Kind.CAPTURED_FUNCTION;
        }
    }

    private static final ReferenceQueue<Ast.Expr> CLEARED =
            new ReferenceQueue<>();
    private static final Map<IdentityKey, Identity> IDENTITIES = new HashMap<>();
    private static final Map<IdentityKey, String> INTERNAL_MODULE_REFERENCES = new HashMap<>();

    private MacroIdentifierHygiene() { }

    private static final class IdentityKey extends WeakReference<Ast.Expr> {
        private final int hash;

        private IdentityKey(
                Ast.Expr expression,
                ReferenceQueue<Ast.Expr> queue) {
            super(expression, queue);
            hash = System.identityHashCode(expression);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityKey key)) return false;
            Ast.Expr value = get();
            return value != null && value == key.get();
        }
    }

    private static void expunge() {
        Reference<? extends Ast.Expr> collected;
        while ((collected = CLEARED.poll()) != null) {
            IDENTITIES.remove(collected);
            INTERNAL_MODULE_REFERENCES.remove(collected);
        }
    }

    private static synchronized <T extends Ast.Expr> T record(
            T expression,
            Identity identity) {
        expunge();
        IDENTITIES.put(new IdentityKey(expression, CLEARED), identity);
        return expression;
    }

    public static synchronized Identity lookup(Ast.Expr expression) {
        java.util.Objects.requireNonNull(expression, "identifier expression");
        expunge();
        return IDENTITIES.get(new IdentityKey(expression, null));
    }

    private static synchronized Ast.NameExpr recordInternalModuleReference(
            Ast.NameExpr expression,
            String module) {
        java.util.Objects.requireNonNull(expression, "internal module reference");
        if (module == null || module.isBlank()) {
            throw new IllegalArgumentException("internal module reference module cannot be blank");
        }
        String encodedTarget = Ast.internalModuleReferenceTarget(expression.name());
        if (!module.equals(encodedTarget)) {
            throw new IllegalArgumentException(
                    "internal module reference spelling does not match checked capture target");
        }
        expunge();
        INTERNAL_MODULE_REFERENCES.put(
                new IdentityKey(expression, CLEARED),
                module);
        return expression;
    }

    /**
     * Semantic-admission gate for compiler-internal module references.
     *
     * <p>An encoded $module$ spelling is not authority by itself. Only the
     * exact NameExpr identity minted by checked capture is accepted. Ordinary
     * names return null; forged internal spellings fail closed.</p>
     */
    public static synchronized String authorizedInternalModuleReferenceTarget(
            Ast.NameExpr expression) {
        java.util.Objects.requireNonNull(expression, "module reference expression");
        String encodedTarget = Ast.internalModuleReferenceTarget(expression.name());
        if (encodedTarget == null) return null;
        expunge();
        String authorized =
                INTERNAL_MODULE_REFERENCES.get(new IdentityKey(expression, null));
        if (!encodedTarget.equals(authorized)) {
            throw new IllegalArgumentException(
                    "unauthorized compiler-internal module reference; "
                            + "only checked capture may mint resolved module authority");
        }
        return encodedTarget;
    }

    public static Ast.NameExpr generated(
            MacroSyntaxProvenance.HygieneIdentity hygiene,
            String hint,
            int localOrdinal) {
        java.util.Objects.requireNonNull(hygiene, "generated identifier hygiene");
        if (hint == null || hint.isBlank() || hint.equals("_") || hint.startsWith("$")
                || hint.length() > Ast.MacroIdentifierArgument.MAX_NAME_CHARS) {
            throw new IllegalArgumentException("invalid generated identifier hint");
        }
        if (localOrdinal < 0) {
            throw new IllegalArgumentException(
                    "generated identifier ordinal cannot be negative");
        }

        // The source-unit id is encoded losslessly, not hashed: collisions in a
        // digest must never become identifier equality. Expansion/local ordinals
        // are deterministic within that exact source unit. Source cannot spell
        // '$', so textual caller capture is impossible.
        String encodedUnit = HexFormat.of().formatHex(
                hygiene.sourceUnitId().getBytes(StandardCharsets.UTF_8));
        String internalName = "$macro$g$" + encodedUnit + "$"
                + hygiene.expansionOrdinal() + "$" + localOrdinal;
        Ast.NameExpr expression = new Ast.NameExpr(internalName);
        return record(
                expression,
                new Generated(hygiene, hint, localOrdinal, internalName));
    }

    public static Ast.MemberExpr capturedFunction(
            MacroSyntaxProvenance.HygieneIdentity hygiene,
            Ast.MacroIdentifierArgument identifier,
            List<Ast.FunctionDecl> declarations) {
        java.util.Objects.requireNonNull(hygiene, "checked capture hygiene identity");
        java.util.Objects.requireNonNull(identifier, "capture identifier");
        Ast.NameExpr receiver = recordInternalModuleReference(
                new Ast.NameExpr(
                        Ast.internalModuleReference(hygiene.callerModule())),
                hygiene.callerModule());
        Ast.MemberExpr expression = new Ast.MemberExpr(
                receiver,
                identifier.name());
        return record(
                expression,
                new CapturedFunction(
                        hygiene,
                        identifier.name(),
                        identifier.site(),
                        declarations));
    }
}
