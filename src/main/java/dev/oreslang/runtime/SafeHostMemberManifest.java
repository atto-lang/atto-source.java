package dev.oreslang.runtime;

import java.lang.reflect.Method;

/**
 * Closed, reviewable v0 static Java method grant for Native Image host imports.
 *
 * Class-wide host access is legacy trusted interop. A method selector admits
 * exactly the named method and overload, never public constructors, fields,
 * other overloads, reflection, native access, or implicit actor capabilities.
 * Future methods require a pinned build-time capability/effect manifest, not
 * dynamic name-based guest reflection.
 */
public final class SafeHostMemberManifest {
    public static final String LONG_SUM_I64 =
            "java.lang.Long#sum(long,long)";

    private SafeHostMemberManifest() { }

    public static boolean isMemberSelector(String grant) {
        return grant != null && grant.indexOf('#') >= 0;
    }

    public static String className(String grant) {
        if (!LONG_SUM_I64.equals(grant)) {
            throw new IllegalArgumentException(
                    "unapproved Java host method grant (manifest v0 only admits "
                    + LONG_SUM_I64 + "): " + grant);
        }
        return "java.lang.Long";
    }

    public static Method resolve(String grant) {
        className(grant);
        try {
            // The class and signature are compile-time known, not obtained
            // from an AttoLang expression, runtime classpath search or plugin.
            return Long.class.getDeclaredMethod("sum", long.class, long.class);
        } catch (NoSuchMethodException impossibleWithPinnedJdk) {
            throw new IllegalStateException(
                    "pinned JDK is missing the manifest-approved Long.sum overload",
                    impossibleWithPinnedJdk);
        }
    }
}
