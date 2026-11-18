package dev.oreslang.imports;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Compile-time interpolation for static source import specifiers.
 *
 * <p>${NAME} reads exactly the supplied compiler environment snapshot, once.
 * A bare $repo is not an alias, and +/ remains a separate project-root
 * operator. No shell execution, recursive expansion, defaults or filesystem
 * access occurs here. The caller must still enforce source identity,
 * permissions, package boundaries and lockfile provenance.</p>
 */
public final class ImportPathInterpolation {
    private static final int MAX_UTF8_BYTES = 4096;

    private ImportPathInterpolation() { }

    public static String expand(String raw, Map<String, String> environment) {
        Objects.requireNonNull(raw, "import specifier");
        Objects.requireNonNull(environment, "compiler environment snapshot");
        if (raw.isEmpty() || raw.length() > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("invalid import specifier length");
        }
        StringBuilder expanded = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) != '$' || i + 1 >= raw.length() || raw.charAt(i + 1) != '{') {
                expanded.append(raw.charAt(i++));
            } else {
                int close = raw.indexOf('}', i + 2);
                if (close < 0) {
                    throw new IllegalArgumentException("unterminated ${NAME} import variable");
                }
                String name = raw.substring(i + 2, close);
                if (!validName(name)) {
                    throw new IllegalArgumentException("invalid ${NAME} import variable name");
                }
                String value = environment.get(name);
                if (value == null || value.isEmpty()) {
                    throw new IllegalArgumentException("missing or empty import environment variable: " + name);
                }
                if (value.length() > MAX_UTF8_BYTES || value.contains("${")) {
                    throw new IllegalArgumentException("invalid import environment variable value: " + name);
                }
                expanded.append(value);
                i = close + 1;
            }
            if (expanded.length() > MAX_UTF8_BYTES) {
                throw new IllegalArgumentException("expanded import specifier exceeds 4096 bytes");
            }
        }
        String result = expanded.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("expanded import specifier exceeds 4096 bytes");
        }
        if (result.codePoints().anyMatch(cp ->
                Character.isISOControl(cp)
                        || cp == 0x061C
                        || cp == 0x200E || cp == 0x200F
                        || cp == 0x2028 || cp == 0x2029
                        || (cp >= 0x202A && cp <= 0x202E)
                        || (cp >= 0x2066 && cp <= 0x2069))) {
            throw new IllegalArgumentException(
                    "expanded import specifier contains control or display-spoofing characters");
        }
        // A malformed surrogate would be replaced during UTF-8 encoding and
        // could invalidate the path fingerprint used for source identities.
        for (int i = 0; i < result.length(); ++i) {
            char ch = result.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= result.length()
                        || !Character.isLowSurrogate(result.charAt(i + 1))) {
                    throw new IllegalArgumentException("invalid Unicode in import specifier");
                }
                ++i;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("invalid Unicode in import specifier");
            }
        }
        return result;
    }

    private static boolean validName(String name) {
        if (name.isEmpty() || name.length() > 128) return false;
        char first = name.charAt(0);
        if (!((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z') || first == '_')) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }
}
