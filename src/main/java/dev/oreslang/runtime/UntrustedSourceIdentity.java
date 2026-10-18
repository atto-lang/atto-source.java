package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Pure, bounded supervisor input validation; never parses attacker source. */
final class UntrustedSourceIdentity {
    static final int MAX_CODE_UNIT_CHARS = 512;
    static final int MAX_SOURCE_BYTES = 1_048_576;

    record Validated(String codeUnitId, String sha256, int byteLength) { }

    private UntrustedSourceIdentity() { }

    static Validated inspect(String codeUnitId, String sourceText) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        Objects.requireNonNull(sourceText, "sourceText");
        if (codeUnitId.isBlank() || codeUnitId.length() > MAX_CODE_UNIT_CHARS
                || codeUnitId.codePoints().anyMatch(cp ->
                        Character.isISOControl(cp)
                        || Character.getType(cp) == Character.FORMAT)) {
            // Unicode direction/zero-width formatting controls can make
            // different immutable modules appear identical in diagnostics.
            // Such names are never authority; reject ambiguous spelling here.
            throw new IllegalArgumentException("invalid bounded untrusted code-unit identity");
        }
        for (int i = 0; i < codeUnitId.length(); i++) {
            char c = codeUnitId.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= codeUnitId.length()
                        || !Character.isLowSurrogate(codeUnitId.charAt(i))) {
                    throw new IllegalArgumentException("malformed code-unit identity");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("malformed code-unit identity");
            }
        }
        // A valid UTF-8 string cannot occupy fewer bytes than its number of
        // UTF-16 code units; avoid allocating an encoder buffer for oversized
        // attacker input before the native Engine has even been constructed.
        if (sourceText.isEmpty() || sourceText.length() > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("untrusted source byte budget exceeded");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(sourceText));
            int byteLength = encoded.remaining();
            if (byteLength == 0 || byteLength > MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("untrusted source byte budget exceeded");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(encoded.asReadOnlyBuffer());
            return new Validated(codeUnitId, HexFormat.of().formatHex(digest.digest()), byteLength);
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("malformed UTF-8 source input");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
