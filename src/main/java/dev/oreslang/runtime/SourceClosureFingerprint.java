package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical SHA-256 over the exact import-closure snapshots consumed by the
 * source loader, including raw source text, resolved imports and project roots.
 *
 * A length-framed, sorted encoding avoids concatenation and traversal-order
 * collisions. This fingerprint describes sources, NOT guest runtime schedules.
 */
public final class SourceClosureFingerprint {
    private static final byte[] FORMAT = "oreslang-source-closure-v2\0"
            .getBytes(StandardCharsets.UTF_8);
    private static final int MAX_UNIT_COUNT = 4096;
    private static final int MAX_UNIT_BYTES = 16 * 1024 * 1024;
    private static final long MAX_SOURCE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_NAME_BYTES = 16 * 1024;

    private SourceClosureFingerprint() { }

    public static String sha256(Map<String, String> rawSources,
                                Map<String, ? extends Map<String, String>> importResolutions,
                                String manifestIdentity, byte[] manifestBytes,
                                List<String> searchRoots) {
        if (rawSources == null || importResolutions == null
                || manifestIdentity == null || manifestBytes == null
                || searchRoots == null) {
            throw new IllegalArgumentException("source closure inputs cannot be null");
        }
        if (rawSources.isEmpty() || rawSources.size() > MAX_UNIT_COUNT
                || importResolutions.size() > MAX_UNIT_COUNT
                || searchRoots.size() > MAX_UNIT_COUNT
                || manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("source closure exceeds verification limits");
        }
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(FORMAT);
            updateString(hash, manifestIdentity);
            updateBytes(hash, manifestBytes);
            updateInt(hash, searchRoots.size());
            for (String root : searchRoots) updateString(hash, root);

            TreeMap<String, String> units = new TreeMap<>(rawSources);
            updateInt(hash, units.size());
            long accumulated = 0;
            for (var unit : units.entrySet()) {
                updateString(hash, unit.getKey());
                if (unit.getValue() == null) {
                    throw new IllegalArgumentException("source unit has null text");
                }
                byte[] bytes = unit.getValue().getBytes(StandardCharsets.UTF_8);
                accumulated += bytes.length;
                if (bytes.length > MAX_UNIT_BYTES || accumulated > MAX_SOURCE_BYTES) {
                    throw new IllegalArgumentException("source bytes exceed closure limits");
                }
                updateBytes(hash, bytes);
            }

            TreeMap<String, ? extends Map<String, String>> imports =
                    new TreeMap<>(importResolutions);
            updateInt(hash, imports.size());
            long edgeCount = 0;
            for (var unit : imports.entrySet()) {
                if (!units.containsKey(unit.getKey()) || unit.getValue() == null) {
                    throw new IllegalArgumentException("imports reference unknown source unit");
                }
                updateString(hash, unit.getKey());
                TreeMap<String, String> resolutions = new TreeMap<>(unit.getValue());
                edgeCount += resolutions.size();
                if (edgeCount > 100_000) {
                    throw new IllegalArgumentException("too many resolved import edges");
                }
                updateInt(hash, resolutions.size());
                for (var resolution : resolutions.entrySet()) {
                    updateString(hash, resolution.getKey());
                    if (!units.containsKey(resolution.getValue())) {
                        throw new IllegalArgumentException("resolved target missing from closure");
                    }
                    updateString(hash, resolution.getValue());
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }

    private static void updateString(MessageDigest hash, String value) {
        if (value == null) throw new IllegalArgumentException("null closure identifier");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("source closure identifier too long");
        }
        updateBytes(hash, bytes);
    }

    private static void updateBytes(MessageDigest hash, byte[] bytes) {
        updateInt(hash, bytes.length);
        hash.update(bytes);
    }

    private static void updateInt(MessageDigest hash, int value) {
        hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}
