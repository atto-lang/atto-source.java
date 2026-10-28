package dev.oreslang.ast;

import java.time.Duration;
import java.util.List;

/**
 * Fail-closed compiler metadata for critical callable admission.
 *
 * <p>Only queue_timeout_ms has executable lowering in this increment.
 * Total, execution and channel-wait deadlines must be rejected until the
 * owning Task/VM and channel scheduler can enforce them safely. Silently
 * ignoring a timeout annotation is a correctness and tenant-safety bug.</p>
 */
public record CriticalAnnotationPolicy(Duration queueTimeout) {
    public static CriticalAnnotationPolicy from(
            List<Ast.Annotation> annotations, boolean isCritical, String name) {
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
        for (Ast.TypeRef arg : found.arguments()) {
            if (!arg.isNamedParameter()) {
                throw new IllegalArgumentException(
                        "@Critical requires named integer-millisecond policy values");
            }
            String key = arg.namedParameterName();
            Ast.TypeRef value = arg.namedParameterValue();
            if (!key.equals("queue_timeout_ms")) {
                throw new IllegalArgumentException(
                        "unsupported @Critical policy '" + key
                                + "': execution/total/channel deadlines require runtime enforcement");
            }
            if (queueMillis != null || !value.isIntLiteral()) {
                throw new IllegalArgumentException(
                        "@Critical queue_timeout_ms must be one integer literal");
            }
            try {
                queueMillis = value.intLiteralValue();
            } catch (NumberFormatException tooLarge) {
                throw new IllegalArgumentException(
                        "@Critical queue_timeout_ms is outside signed 64-bit range", tooLarge);
            }
            if (queueMillis < 0) {
                throw new IllegalArgumentException("@Critical queue_timeout_ms must be >= 0");
            }
        }
        if (queueMillis == null) {
            throw new IllegalArgumentException(
                    "@Critical currently requires queue_timeout_ms=...");
        }
        return new CriticalAnnotationPolicy(Duration.ofMillis(queueMillis));
    }
}
