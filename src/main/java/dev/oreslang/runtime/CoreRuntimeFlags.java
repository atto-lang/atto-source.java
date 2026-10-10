package dev.oreslang.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Typed view of OresVM's flags2env contract in the root .cli-flags.toml.
 *
 * <p>flags2env resolves CLI > environment > TOML defaults and produces an
 * environment-keyed map. The VM accepts that resolved map, rather than letting
 * guest code look up stringly typed process configuration on every turn.
 * Until bootstrap wires in flags2env CLI parsing, default constructors read
 * the process environment directly (the manifest default is false).</p>
 */
public record CoreRuntimeFlags(boolean useCoreMutexes) {
    public static final String ENV_USE_CORE_MUTEXES = "ORES_USE_CORE_MUTEXES";

    public static CoreRuntimeFlags fromFlags2EnvMap(Map<String, String> values) {
        Objects.requireNonNull(values, "flags2env values");
        String value = values.get(ENV_USE_CORE_MUTEXES);
        if (value == null) return new CoreRuntimeFlags(false);
        return new CoreRuntimeFlags(parseBoolean(value));
    }

    public static CoreRuntimeFlags fromProcessEnvironment() {
        return fromFlags2EnvMap(System.getenv());
    }

    private static boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException(
                    "invalid boolean for " + ENV_USE_CORE_MUTEXES + ": " + value);
        };
    }
}
