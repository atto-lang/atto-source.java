package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.text.Normalizer;

/**
 * Canonical, versioned, transport-neutral address for one logical virtual actor.
 * An address is a locator, not authorization. Base64URL is encoding, not secrecy.
 */
public final class VirtualActorAddressV1 {
    private static final String PREFIX = "atto-va1";
    private static final int MAX_ADDRESS_CHARS = 2_048;

    private VirtualActorAddressV1() { }

    public static String encode(VirtualActorActivationRegistry.Identity identity) {
        Objects.requireNonNull(identity, "identity");
        validate(identity.domain(), "domain", 128);
        validate(identity.declaration(), "declaration", 128);
        validate(identity.key(), "key", 256);
        if (!Normalizer.isNormalized(identity.domain(), Normalizer.Form.NFC)
                || !Normalizer.isNormalized(identity.declaration(), Normalizer.Form.NFC)
                || !Normalizer.isNormalized(identity.key(), Normalizer.Form.NFC))
            throw new IllegalArgumentException("virtual actor key is not NFC-normalized");
        String result = PREFIX + "." + part(identity.domain()) + "." + part(identity.declaration())
                + "." + part(identity.key());
        if (result.length() > MAX_ADDRESS_CHARS)
            throw new IllegalArgumentException("virtual actor address exceeds size limit");
        return result;
    }

    public static VirtualActorActivationRegistry.Identity decode(String address) {
        Objects.requireNonNull(address, "address");
        if (address.length() > MAX_ADDRESS_CHARS)
            throw new IllegalArgumentException("virtual actor address exceeds size limit");
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0]))
            throw new IllegalArgumentException("unsupported virtual actor address version");
        String domain = decodePart(parts[1]);
        String declaration = decodePart(parts[2]);
        String key = decodePart(parts[3]);
        validate(domain, "domain", 128);
        validate(declaration, "declaration", 128);
        validate(key, "key", 256);
        if (!Normalizer.isNormalized(domain, Normalizer.Form.NFC)
                || !Normalizer.isNormalized(declaration, Normalizer.Form.NFC)
                || !Normalizer.isNormalized(key, Normalizer.Form.NFC))
            throw new IllegalArgumentException("virtual actor key is not NFC-normalized");
        if (!encode(new VirtualActorActivationRegistry.Identity(domain, declaration, key)).equals(address))
            throw new IllegalArgumentException("non-canonical virtual actor address");
        return new VirtualActorActivationRegistry.Identity(domain, declaration, key);
    }

    private static void validate(String value, String kind, int maxUtf8Bytes) {
        Objects.requireNonNull(value, "address " + kind);
        // Bound UTF-16 length before allocating the UTF-8 representation.
        if (value.length() > maxUtf8Bytes || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes)
            throw new IllegalArgumentException("invalid virtual actor address " + kind);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch))
                throw new IllegalArgumentException("control character in virtual actor address " + kind);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new IllegalArgumentException("unpaired surrogate in virtual actor address " + kind);
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("unpaired surrogate in virtual actor address " + kind);
            }
        }
    }

    private static String part(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Objects.requireNonNull(value, "address component")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String decodePart(String value) {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("invalid virtual actor address component");
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException failure) {
            throw new IllegalArgumentException("invalid virtual actor address encoding", failure);
        }
    }
}
