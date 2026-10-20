package dev.oreslang.compiler;

import java.util.Locale;

/**
 * Backend-neutral Oreslang build profile selector.
 *
 * Runtime reflection is forbidden by the v1 contract for every profile.
 * Stronger profiles inherit the semantic requirements of weaker profiles.
 */
public enum BuildProfile {
    DEV("dev"),
    RELEASE("release"),
    STRICT_AOT("strict-aot"),
    HARD_RT("hard-rt");

    private final String cliName;

    BuildProfile(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    public boolean requiresTreeShaking() {
        return this != DEV;
    }

    public boolean requiresFullAot() {
        return this == STRICT_AOT || this == HARD_RT;
    }

    public boolean requiresHardRealtime() {
        return this == HARD_RT;
    }

    public static BuildProfile fromFlag(String flag) {
        return switch (flag.toLowerCase(Locale.ROOT)) {
            case "--release" -> RELEASE;
            case "--strict-aot" -> STRICT_AOT;
            case "--hard-rt" -> HARD_RT;
            default -> throw new IllegalArgumentException("unknown build profile flag: " + flag);
        };
    }

    public static BuildProfile select(BuildProfile current, BuildProfile requested) {
        if (current != DEV) {
            throw new IllegalArgumentException(
                    "--release, --strict-aot and --hard-rt are mutually exclusive");
        }
        return requested;
    }
}
