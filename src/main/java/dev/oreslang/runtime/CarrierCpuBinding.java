package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Per-physical-carrier hard CPU binding cache. An actor is never an OS thread:
 * independent actor turns on the same dedicated carrier share its CPU mask.
 *
 * <p>Binding is reasserted periodically to detect affinity changes caused by
 * cpuset updates, hotplug or native code. The last successful binding is only
 * cached after the OS operation completes without throwing. A failure leaves
 * the actor turn's existing fail-closed error path in charge.</p>
 */
final class CarrierCpuBinding {
    static final long REBIND_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private record Binding(int cpu, long checkedAtNanos) { }

    private CarrierCpuBinding() { }

    static void ensureBound(int cpu, IntConsumer nativeBind, LongSupplier clock) {
        if (cpu < 0) throw new IllegalArgumentException("CPU must be nonnegative");
        Objects.requireNonNull(nativeBind, "nativeBind");
        Objects.requireNonNull(clock, "clock");
        long now = clock.getAsLong();
        Binding previous = CURRENT.get();
        if (previous != null && previous.cpu() == cpu) {
            long age = now - previous.checkedAtNanos();
            if (age >= 0 && age < REBIND_INTERVAL_NANOS) return;
        }
        // An OS failure must never mark a CPU as successfully pinned.
        nativeBind.accept(cpu);
        CURRENT.set(new Binding(cpu, now));
    }

    static void clearCurrentCarrier() {
        CURRENT.remove();
    }
}
