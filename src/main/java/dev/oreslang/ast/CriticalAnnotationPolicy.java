package dev.oreslang.ast;

import java.time.Duration;
import java.util.List;

/**
 * Compiler-checked critical policy metadata with no runtime reflection.
 *
 * <p>Queue and per-channel wait timeouts have executable Java/Graal lowering
 * on this draft branch. Execution and overall deadlines remain fail-closed
 * until physical task retirement/cancellation can be proven. A policy must
 * never silently degrade to unbounded waiting.</p>
 */
public record CriticalAnnotationPolicy(Duration queueTimeout, Duration channelWaitTimeout) {
    public static CriticalAnnotationPolicy from(
            List<Ast.Annotation> annotations, boolean isCritical, String name) {
        return from(annotations, isCritical, true, name);
    }

    public static CriticalAnnotationPolicy from(
            List<Ast.Annotation> annotations,
            boolean isCritical,
            boolean suspensionAllowed,
            String name) {
        Ast.Annotation found = null;
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("Critical")) continue;
            if (found != null) {
                throw new IllegalArgumentException(
                        "duplicate @Critical on callable '" + name + "'");
            }
            found = annotation;
        }
        if (found == null) return null;
        if (!isCritical) {
            throw new IllegalArgumentException(
                    "@Critical requires the critical callable modifier on '" + name + "'");
        }
        Long queueMillis = null;
        Long channelMillis = null;
        for (Ast.TypeRef arg : found.arguments()) {
            if (!arg.isNamedParameter()) {
                throw new IllegalArgumentException(
                        "@Critical requires named integer-millisecond policy values");
            }
            String key = arg.namedParameterName();
            Ast.TypeRef value = arg.namedParameterValue();
            if (!key.equals("queue_timeout_ms") && !key.equals("channel_wait_timeout_ms")) {
                throw new IllegalArgumentException(
                        "unsupported @Critical policy '" + key
                                + "': execution and total deadlines require task retirement support");
            }
            if (!value.isIntLiteral()
                    || key.equals("queue_timeout_ms") && queueMillis != null
                    || key.equals("channel_wait_timeout_ms") && channelMillis != null) {
                throw new IllegalArgumentException(
                        "@Critical " + key + " must be one integer literal");
            }
            long millis;
            try {
                millis = value.intLiteralValue();
            } catch (NumberFormatException tooLarge) {
                throw new IllegalArgumentException(
                        "@Critical " + key + " is outside signed 64-bit range", tooLarge);
            }
            if (millis < 0) {
                throw new IllegalArgumentException(
                        "@Critical " + key + " must be >= 0");
            }
            if (key.equals("queue_timeout_ms")) queueMillis = millis;
            else channelMillis = millis;
        }
        if (queueMillis == null && channelMillis == null) {
            throw new IllegalArgumentException(
                    "@Critical requires queue_timeout_ms or channel_wait_timeout_ms");
        }
        if (channelMillis != null && !suspensionAllowed) {
            throw new IllegalArgumentException(
                    "@Critical channel_wait_timeout_ms requires a suspends/async critical callable");
        }
        return new CriticalAnnotationPolicy(
                queueMillis == null ? null : Duration.ofMillis(queueMillis),
                channelMillis == null ? null : Duration.ofMillis(channelMillis));
    }
}
