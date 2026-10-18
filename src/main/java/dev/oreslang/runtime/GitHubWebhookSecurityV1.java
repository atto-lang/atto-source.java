package dev.oreslang.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** GitHub webhook authentication helpers that operate on the exact raw body. */
public final class GitHubWebhookSecurityV1 {

    public record Headers(
            String signature256,
            String deliveryId,
            String eventName) {
        public Headers {
            signature256 = requireText(
                    signature256, "signature256");
            deliveryId = requireText(deliveryId, "deliveryId");
            eventName = requireText(eventName, "eventName")
                    .toLowerCase(Locale.ROOT);
        }
    }

    public record VerifiedWebhook(
            String deliveryId,
            String eventName,
            String payloadSha256) { }

    private GitHubWebhookSecurityV1() { }

    public static VerifiedWebhook verify(
            byte[] secret,
            byte[] rawBody,
            Headers headers) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(rawBody, "rawBody");
        Objects.requireNonNull(headers, "headers");
        if (secret.length < 16) {
            throw new IllegalArgumentException(
                    "webhook secret must contain at least 16 bytes");
        }

        byte[] supplied = decodeSignature(headers.signature256());
        byte[] expected = hmacSha256(secret, rawBody);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new SecurityException(
                    "GitHub webhook signature verification failed");
        }

        return new VerifiedWebhook(
                headers.deliveryId(),
                headers.eventName(),
                sha256Hex(rawBody));
    }

    private static byte[] decodeSignature(String header) {
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("sha256=")) {
            throw new SecurityException(
                    "GitHub webhook signature must use sha256=");
        }
        String hex = normalized.substring("sha256=".length());
        if (hex.length() != 64
                || !hex.matches("[0-9a-f]{64}")) {
            throw new SecurityException(
                    "GitHub webhook signature has invalid SHA-256 form");
        }
        return HexFormat.of().parseHex(hex);
    }

    private static byte[] hmacSha256(byte[] secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (java.security.InvalidKeyException failure) {
            throw new IllegalArgumentException(
                    "invalid webhook secret", failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "HmacSHA256 is unavailable", impossible);
        }
    }

    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
        return normalized;
    }
}
