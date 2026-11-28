package dev.oreslang.ast;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiler-checked critical policy metadata. Budgets are elapsed monotonic
 * durations, not wall-clock instants. Absent queue/channel fields impose no
 * local limit. The scheduler separately propagates the earliest active
 * total/execution task deadline; this record does not implement general
 * policy inheritance or replace tenant/supervisor quotas.
 *
 * <p>Queue and channel admission can be revoked atomically before commit.
 * Execution and total timeouts on suspended callables require scheduler-owned
 * physical retirement, never premature public Future cancellation. These
 * are cooperative elapsed deadlines, not arbitrary CPU preemption.</p>
 */
public record CriticalAnnotationPolicy(
        Duration queueTimeout,
        Duration channelWaitTimeout,
        Duration executionTimeout,
        Duration totalTimeout) {
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
        Long queue = null;
        Long channel = null;
        Long execution = null;
        Long total = null;
        Set<String> seen = new HashSet<>();
        for (Ast.TypeRef arg : found.arguments()) {
            if (!arg.isNamedParameter()) {
                throw new IllegalArgumentException(
                        "@Critical requires named integer-millisecond policy values");
            }
            String key = arg.namedParameterName();
            Ast.TypeRef value = arg.namedParameterValue();
            if (!Set.of("queue_timeout_ms", "channel_wait_timeout_ms",
                    "execution_timeout_ms", "total_timeout_ms").contains(key)) {
                throw new IllegalArgumentException(
                        "unsupported @Critical policy '" + key + "'");
            }
            if (!seen.add(key) || !value.isIntLiteral()) {
                throw new IllegalArgumentException(
                        "@Critical " + key + " must occur once with an integer literal");
            }
            long millis;
            try {
                millis = value.intLiteralValue();
            } catch (NumberFormatException tooLarge) {
                throw new IllegalArgumentException(
                        "@Critical " + key + " is outside signed 64-bit range", tooLarge);
            }
            if (millis < 0) {
                throw new IllegalArgumentException("@Critical " + key + " must be >= 0");
            }
            switch (key) {
                case "queue_timeout_ms" -> queue = millis;
                case "channel_wait_timeout_ms" -> channel = millis;
                case "execution_timeout_ms" -> execution = millis;
                case "total_timeout_ms" -> total = millis;
                default -> throw new AssertionError(key);
            }
        }
        if (seen.isEmpty()) {
            throw new IllegalArgumentException("@Critical requires at least one timeout policy");
        }
        if (!suspensionAllowed && (channel != null || execution != null || total != null)) {
            throw new IllegalArgumentException(
                    "@Critical channel/execution/total timeouts require suspends/async "
                            + "critical callable; synchronous CPU work cannot be safely preempted");
        }
        return new CriticalAnnotationPolicy(
                millis(queue), millis(channel), millis(execution), millis(total));
    }

    private static Duration millis(Long value) {
        return value == null ? null : Duration.ofMillis(value);
    }
}
