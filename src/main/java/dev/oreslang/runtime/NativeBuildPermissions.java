package dev.oreslang.runtime;

import org.graalvm.nativeimage.ImageInfo;

import java.nio.file.Path;
import java.util.Map;

/**
 * Permission ceiling captured into Native Image build-time initialized state.
 *
 * <p>Native executables deny every privileged host-I/O category unless the
 * image build explicitly supplies {@code -Dores.native.permission.<kind>=...}.
 * A JVM is not a sealed artifact, so its build ceiling is intentionally broad;
 * the standalone CLI remains default-deny through its independent runtime
 * grant.</p>
 */
public final class NativeBuildPermissions {
    private static final String PREFIX = "ores.native.permission.";
    private static final RuntimePermissions CEILING = captureAtInitialization();

    private NativeBuildPermissions() { }

    public static RuntimePermissions ceiling() {
        return CEILING;
    }

    private static RuntimePermissions captureAtInitialization() {
        if (!ImageInfo.inImageBuildtimeCode()) {
            RuntimePermissions.Builder builder = new RuntimePermissions.Builder();
            for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
                builder.allowAll(permission);
            }
            return builder.build();
        }
        return fromProperties(System.getProperties().entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> String.valueOf(entry.getKey()),
                        entry -> String.valueOf(entry.getValue()))), true);
    }

    static RuntimePermissions fromProperties(
            Map<String, String> properties,
            boolean sealedNativeArtifact) {
        RuntimePermissions.Builder builder = new RuntimePermissions.Builder();
        if (!sealedNativeArtifact) {
            for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
                builder.allowAll(permission);
            }
            return builder.build();
        }

        for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
            String value = properties.get(PREFIX + permission.cliName());
            if (value == null || value.isBlank()) continue;
            requirePortableBuildScopes(permission, value);
            builder.allowCsv(permission, value);
        }
        return builder.build();
    }

    private static void requirePortableBuildScopes(
            RuntimePermissions.Permission permission,
            String csv) {
        if (permission != RuntimePermissions.Permission.READ
                && permission != RuntimePermissions.Permission.WRITE
                && permission != RuntimePermissions.Permission.FFI
                && permission != RuntimePermissions.Permission.IMPORT) {
            return;
        }
        for (String raw : csv.split(",", -1)) {
            String scope = raw.trim();
            if ("*".equals(scope)) continue;
            if (scope.isEmpty() || !Path.of(scope).isAbsolute()) {
                throw new IllegalArgumentException(
                        "native build permission " + permission.cliName()
                                + " requires absolute paths or '*': " + raw);
            }
        }
    }
}
