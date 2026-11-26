package dev.oreslang.compiler;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Structured identities and canonical digests for incremental metadata.
 * These are not bytecode artifacts or a persisted cache authorization. */
public final class NamespaceArtifactIdentity {
    private NamespaceArtifactIdentity() { }

    public record Key(String packageId, String namespace) implements Comparable<Key> {
        public Key {
            Objects.requireNonNull(packageId, "package identity");
            if (packageId.isEmpty()) throw new IllegalArgumentException("empty package identity");
            if (namespace != null && namespace.isEmpty()) throw new IllegalArgumentException("empty namespace");
            utf8(packageId);
            if (namespace != null) utf8(namespace);
        }
        @Override public int compareTo(Key other) {
            int p = packageId.compareTo(other.packageId);
            if (p != 0) return p;
            if (namespace == null) return other.namespace == null ? 0 : -1;
            return other.namespace == null ? 1 : namespace.compareTo(other.namespace);
        }
        /** Preserve ordinary existing labels, escape separators in unusual paths. */
        public String displayKey() {
            return escape(packageId) + "#" + (namespace == null ? "" : escape(namespace));
        }
        private static String escape(String s) { return s.replace("%", "%25").replace("#", "%23"); }
    }

    private static byte[] utf8(String value) {
        Objects.requireNonNull(value, "text");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("invalid UTF-16 source or identity", malformed);
        }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String sourceDigest(String source) {
        return HexFormat.of().formatHex(sha256().digest(utf8(source)));
    }
    private static void field(MessageDigest md, String value) {
        byte[] bytes = utf8(value);
        md.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        md.update(bytes);
    }
    private static void digestField(MessageDigest md, String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid SHA-256 digest");
        md.update(HexFormat.of().parseHex(digest));
    }
    private static void key(MessageDigest md, Key key) {
        field(md, key.packageId());
        md.update((byte) (key.namespace() == null ? 0 : 1));
        if (key.namespace() != null) field(md, key.namespace());
    }
    public static String namespaceInterface(Key key, Map<String, String> members) {
        if (members.isEmpty()) throw new IllegalArgumentException("empty namespace members");
        MessageDigest md = sha256();
        field(md, "atto.java.namespace-interface.v2");
        key(md, key);
        md.update(ByteBuffer.allocate(4).putInt(members.size()).array());
        new TreeMap<>(members).forEach((id, digest) -> { field(md, id); digestField(md, digest); });
        return HexFormat.of().formatHex(md.digest());
    }
    public static String packageInterface(String packageId, Map<Key, String> namespaces) {
        if (namespaces.isEmpty()) throw new IllegalArgumentException("empty package namespaces");
        MessageDigest md = sha256();
        field(md, "atto.java.package-interface.v2");
        field(md, packageId);
        md.update(ByteBuffer.allocate(4).putInt(namespaces.size()).array());
        new TreeMap<>(namespaces).forEach((key, digest) -> {
            if (!key.packageId().equals(packageId)) throw new IllegalArgumentException("mixed package identity");
            key(md, key);
            digestField(md, digest);
        });
        return HexFormat.of().formatHex(md.digest());
    }
}
