package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.OresKeyedMap;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Backend-neutral contract for source-level typed open-map key declarations.
 *
 * <p>This class intentionally contains no parser or evaluator state.  The
 * parser/typechecker port can call it without reintroducing the stale #636
 * object-field storage model.
 */
public final class TypedMapKeyContract {
    private TypedMapKeyContract() { }

    public enum VisibilitySyntax { NONE, PUBLIC, PRIVATE }

    public record KeyDecl(String name, Ast.TypeRef declaredType, VisibilitySyntax visibility) {
        public KeyDecl {
            Objects.requireNonNull(name, "map key name");
            Objects.requireNonNull(declaredType, "map key type");
            Objects.requireNonNull(visibility, "map key visibility");
        }
    }

    public record Schema(List<KeyDecl> keys) {
        public Schema {
            keys = List.copyOf(keys);
            validate(keys);
        }
    }

    public static final Set<String> SEALED_HOOKS =
            Set.of("on_get", "on_put", "on_remove");

    public static void validate(List<KeyDecl> keys) {
        Objects.requireNonNull(keys, "map keys");
        if (keys.size() > OresKeyedMap.MAX_ENTRIES) {
            throw new IllegalArgumentException("typed Map key schema exceeds maximum key count");
        }

        Set<String> names = new HashSet<>();
        long aggregateBytes = 0L;
        for (KeyDecl key : keys) {
            if (key.visibility() != VisibilitySyntax.NONE) {
                throw new IllegalArgumentException(
                        "map key declarations are storage schema metadata; pub/private visibility is not supported");
            }
            validateName(key.name());
            if (!names.add(key.name())) {
                throw new IllegalArgumentException("duplicate map key schema entry '" + key.name() + "'");
            }
            aggregateBytes += encodedKeyBytes(key.name());
            if (aggregateBytes > OresKeyedMap.MAX_KEY_SET_WIRE_BYTES) {
                throw new IllegalArgumentException("typed Map key schema exceeds aggregate key byte budget");
            }
            storedType(key.declaredType());
        }
    }

    public static Ast.TypeRef storedType(Ast.TypeRef declaration) {
        Objects.requireNonNull(declaration, "map key declaration type");
        if (declaration.inferArguments()
                || declaration.isUnion()
                || declaration.isBorrow()
                || !declaration.name().equals("Option")
                || declaration.arguments().size() != 1) {
            throw new IllegalArgumentException(
                    "typed map key declarations must use exactly key name: Option<T>");
        }
        Ast.TypeRef stored = declaration.arguments().getFirst();
        if (stored.name().equals("void") && stored.arguments().isEmpty()) {
            throw new IllegalArgumentException("typed map key cannot store void");
        }
        return stored;
    }

    public static Ast.TypeRef lookupType(Ast.TypeRef declaration) {
        // Peel exactly the schema presence marker, then add the lookup presence
        // marker back.  Option<Option<T>> therefore remains nested.
        return new Ast.TypeRef(
                "Option",
                List.of(storedType(declaration)),
                false);
    }

    public static void rejectDirectHookCall(String member) {
        if (SEALED_HOOKS.contains(member)) {
            throw new IllegalArgumentException(
                    member + " is a sealed typed-map interception hook and cannot be called directly");
        }
    }

    private static void validateName(String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("typed Map key name cannot be blank");
        }
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(name));
            if (bytes.remaining() > OresKeyedMap.MAX_TEXT_KEY_BYTES) {
                throw new IllegalArgumentException("typed Map key name exceeds byte limit");
            }
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("typed Map key name must be valid Unicode", invalid);
        }
    }

    private static long encodedKeyBytes(String name) {
        // Same six-byte tagged-key envelope used by OresKeyedMap's runtime
        // quota.  The String payload is valid UTF-8 after validateName().
        return 6L + name.getBytes(StandardCharsets.UTF_8).length;
    }
}
