package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.SingletonSharedDomain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * FIRST compiler -> runtime bridge for immutable, closed singleton literals.
 *
 * <p>This runs the actual production lexer/parser in its existing deliberately
 * restricted check-only mode, applies SingletonCheckOnly's semantic gates,
 * then lowers fields into a separately owned domain registry. There is no
 * AST interpretation of actor bodies, no guest singleton executable lowering,
 * no aliases/imports, no mutable state access, and no cross-isolate claim.</p>
 */
public final class SingletonSnapshotCompilerV1 {
    private SingletonSnapshotCompilerV1() { }

    private record Declaration(Map<String, Object> fields, String fingerprint) {
        private Declaration {
            fields = Map.copyOf(fields);
            Objects.requireNonNull(fingerprint, "compiler schema fingerprint");
        }
    }

    /**
     * Code-unit IDs must come from the trusted resolver; untrusted guest code
     * must never choose an identity with which to impersonate another module.
     */
    public static Compiled compile(String source, String checkedCodeUnitId) {
        Objects.requireNonNull(source, "source");
        if (checkedCodeUnitId == null || checkedCodeUnitId.isBlank()
                || checkedCodeUnitId.length() > 120
                || checkedCodeUnitId.startsWith("/")
                || checkedCodeUnitId.contains("\\")
                || checkedCodeUnitId.indexOf((char) 0) >= 0) {
            throw new IllegalArgumentException("invalid checked singleton code-unit identity");
        }
        for (String segment : checkedCodeUnitId.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("noncanonical checked singleton code-unit identity");
            }
        }

        Ast.Program parsed = Parser.parseSingletonsCheckOnly(source);
        SingletonCheckOnly.check(parsed);
        String namespace = checkedCodeUnitId
                + (parsed.namespace() == null ? "" : "|" + parsed.namespace());
        if (namespace.length() > 256) {
            throw new IllegalArgumentException("singleton namespace too long");
        }

        Map<SingletonSharedDomain.Key, Declaration> lowered = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : parsed.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.SingletonDecl singleton)) {
                    // No executable declarations may sneak into the checked path.
                    throw new IllegalArgumentException("singleton compiler input contains executable declaration");
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                StringBuilder signature = new StringBuilder();
                append(signature, module.name());
                append(signature, singleton.name());
                for (Ast.SingletonField positioned : singleton.fields()) {
                    Ast.FieldDecl field = positioned.declaration();
                    if (!(field.initializer() instanceof Ast.LiteralExpr literal)) {
                        throw new IllegalArgumentException("nonliteral singleton initializer");
                    }
                    Object value = literal.value();
                    if (!isClosedLiteral(value)) {
                        throw new IllegalArgumentException("nonprimitive singleton value");
                    }
                    if (fields.putIfAbsent(field.name(), value) != null) {
                        throw new IllegalArgumentException("duplicate singleton field");
                    }
                    append(signature, field.name());
                    append(signature, field.type().name());
                    append(signature, literalKey(value));
                }
                SingletonSharedDomain.Key key =
                        new SingletonSharedDomain.Key(namespace, module.name(), singleton.name());
                if (lowered.putIfAbsent(key,
                        new Declaration(fields, digest(signature.toString()))) != null) {
                    throw new IllegalArgumentException("duplicate compiled singleton identity");
                }
            }
        }
        if (lowered.isEmpty()) {
            throw new IllegalArgumentException("no singleton declarations in checked source unit");
        }
        return new Compiled(lowered);
    }

    private static boolean isClosedLiteral(Object value) {
        return value instanceof String || value instanceof Boolean
                || value instanceof Long || value instanceof Integer
                || value instanceof Double d && Double.isFinite(d)
                || value instanceof Float f && Float.isFinite(f);
    }

    private static String literalKey(Object value) {
        if (value instanceof Double d) {
            return "double:" + Long.toUnsignedString(Double.doubleToRawLongBits(d), 16);
        }
        if (value instanceof Float f) {
            return "float:" + Integer.toUnsignedString(Float.floatToRawIntBits(f), 16);
        }
        return value.getClass().getName() + ":" + value;
    }

    private static void append(StringBuilder out, String item) {
        out.append(item.length()).append(':').append(item);
    }

    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError("Java runtime lacks SHA-256", error);
        }
    }

    /**
     * All registrations use non-parking future receipts, not blocking joins.
     * Returned futures are separate from the registry's publication authority.
     */
    public static final class Compiled {
        private final Map<SingletonSharedDomain.Key, Declaration> declarations;

        private Compiled(Map<SingletonSharedDomain.Key, Declaration> declarations) {
            this.declarations = Map.copyOf(declarations);
        }

        public int declarationCount() { return declarations.size(); }

        public Map<SingletonSharedDomain.Key, CompletableFuture<Object>> publishAsync(
                SingletonSharedDomain domain) {
            Objects.requireNonNull(domain, "singleton application domain");
            Map<SingletonSharedDomain.Key, CompletableFuture<Object>> published =
                    new LinkedHashMap<>();
            for (Map.Entry<SingletonSharedDomain.Key, Declaration> entry
                    : declarations.entrySet()) {
                Declaration decl = entry.getValue();
                // Domain performs atomic schema check with once-only publication.
                published.put(entry.getKey(), domain.snapshotOnceChecked(
                        entry.getKey(), decl.fingerprint(), () -> decl.fields()));
            }
            return Map.copyOf(published);
        }
    }
}
