package dev.oreslang.types;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
final class ThreadSafetyTraitInitializationTest {
    @Test void atomicCapabilitiesRemainDeniedEvenWithOptimisticNominalCallback() {
        var calls = new AtomicInteger();
        for (String name : List.of("Atomic", "AtomicRef", "AtomicBool", "AtomicInt", "AtomicLong")) {
            for (var trait : ThreadSafetyTraits.Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(new Types.Named(name, List.of(Types.Primitive.INT)), trait,
                        (type, t) -> { calls.incrementAndGet(); return true; }), name + ":" + trait);
            }
        }
        assertEquals(0, calls.get(), "reserved capability cannot consult nominal proof");
    }
}
