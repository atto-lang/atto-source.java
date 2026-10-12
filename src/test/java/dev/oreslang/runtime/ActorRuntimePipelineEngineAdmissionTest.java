package dev.oreslang.runtime;

import dev.oreslang.runtime.pipeline.PipelinePlan;
import dev.oreslang.runtime.pipeline.PipelinePlanner;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Trusted host adapter rejects forged providers before any actor allocation. */
final class ActorRuntimePipelineEngineAdmissionTest {
    @Test void providerRegistrationCheckedBeforeSpawning() {
        AtomicInteger calls = new AtomicInteger();
        PipelinePlan.ActorProvider registered = () -> {
            calls.incrementAndGet();
            return new Object();
        };
        PipelinePlan.ActorProvider forged = () -> {
            calls.incrementAndGet();
            return new Object();
        };
        PipelinePlan plan = new PipelinePlanner()
                .add("source", "source-provider", "closed-domain", registered)
                .add("sink", "sink-provider", "closed-domain", forged)
                .build();
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntimePipelineEngine engine = new ActorRuntimePipelineEngine(
                    runtime, Runnable::run, Map.of(
                            "source-provider", registered,
                            "sink-provider", registered));
            assertThrows(SecurityException.class, () -> engine.open(plan));
            assertEquals(0, calls.get(),
                    "forged provider must be rejected before first actor spawn");
        }
    }

    @Test void foreignRuntimeActorCannotBeStoppedByFailedPipelineOpen() {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> foreignRef = foreign.spawn(
                    () -> (message, context) -> { });
            PipelinePlan.ActorProvider provider = () -> foreignRef;
            PipelinePlan plan = new PipelinePlanner()
                    .add("source", "registered", "domain", provider)
                    .add("sink", "registered", "domain", provider).build();
            ActorRuntimePipelineEngine engine = new ActorRuntimePipelineEngine(
                    owner, Runnable::run, Map.of("registered", provider));
            assertThrows(SecurityException.class, () -> engine.open(plan));
            assertTrue(foreignRef.isAlive(),
                    "failed pipeline open revoked an unrelated runtime actor");
            foreignRef.stop();
        }
    }

    @Test void providerMustProduceActorCapabilityNotHostObject() {
        AtomicInteger calls = new AtomicInteger();
        PipelinePlan.ActorProvider bogus = () -> {
            calls.incrementAndGet();
            return new Object();
        };
        PipelinePlan plan = new PipelinePlanner()
                .add("source", "registered", "closed-domain", bogus)
                .add("sink", "registered", "closed-domain", bogus)
                .build();
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntimePipelineEngine engine = new ActorRuntimePipelineEngine(
                    runtime, Runnable::run, Map.of("registered", bogus));
            assertThrows(SecurityException.class, () -> engine.open(plan));
            assertEquals(1, calls.get(),
                    "bogus first provider must abort before later stages spawn");
        }
    }
}
