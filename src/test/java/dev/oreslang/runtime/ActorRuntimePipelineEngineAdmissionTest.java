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

    @Test void providerThrowingAfterSpawnCannotOrphanUnreturnedActor() {
        java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<Object>>
                captured = new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            PipelinePlan.ActorProvider crashing = () -> {
                captured.set(runtime.spawn(() -> (message, context) -> { }));
                throw new IllegalStateException("constructor failed after spawning");
            };
            PipelinePlan plan = new PipelinePlanner()
                    .add("source", "R", "M", crashing)
                    .add("sink", "R", "M", crashing).build();
            ActorRuntimePipelineEngine engine = new ActorRuntimePipelineEngine(
                    runtime, Runnable::run, Map.of("R", crashing));
            IllegalStateException thrown =
                    assertThrows(IllegalStateException.class, () -> engine.open(plan));
            assertTrue(thrown.toString().contains("constructor failed after spawning"));
            assertNotNull(captured.get());
            assertTrue(captured.get().done().isDone(),
                    "throw-before-return leaked an untracked provisioned actor");
            assertFalse(captured.get().isAlive());
        }
    }

    @Test void providerThatSpawnsExtraActorIsRejectedAndBothAreRetired() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.List<ActorRuntime.ActorRef<Object>> created =
                    new java.util.ArrayList<>();
            PipelinePlan.ActorProvider greedy = () -> {
                ActorRuntime.ActorRef<Object> one = runtime.spawn(
                        () -> (message, context) -> { });
                ActorRuntime.ActorRef<Object> two = runtime.spawn(
                        () -> (message, context) -> { });
                created.add(one);
                created.add(two);
                return one;
            };
            PipelinePlan plan = new PipelinePlanner()
                    .add("source", "R", "M", greedy)
                    .add("sink", "R", "M", greedy).build();
            ActorRuntimePipelineEngine engine = new ActorRuntimePipelineEngine(
                    runtime, Runnable::run, Map.of("R", greedy));
            SecurityException rejected =
                    assertThrows(SecurityException.class, () -> engine.open(plan));
            assertTrue(rejected.getMessage().contains("exactly its own single stage"));
            assertEquals(2, created.size());
            assertTrue(created.stream().allMatch(ref -> ref.done().isDone()),
                    "extra actors created by a provider bypassed physical rollback");
        }
    }

    @Test void partialProviderFailureWaitsForPhysicalRollback() {
        java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<Object>>
                provisional = new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            PipelinePlan.ActorProvider source = () -> {
                ActorRuntime.ActorRef<Object> actor = runtime.spawn(
                        () -> (message, context) -> { });
                provisional.set(actor);
                return actor;
            };
            PipelinePlan.ActorProvider failing = () -> {
                throw new IllegalStateException("deliberate second provider failure");
            };
            PipelinePlan plan = new PipelinePlanner()
                    .add("source", "S", "M", source)
                    .add("sink", "T", "M", failing).build();
            ActorRuntimePipelineEngine engine = new ActorRuntimePipelineEngine(
                    runtime, Runnable::run, Map.of("S", source, "T", failing));
            IllegalStateException cause =
                    assertThrows(IllegalStateException.class, () -> engine.open(plan));
            assertTrue(cause.getMessage().contains("deliberate second provider failure"));
            ActorRuntime.ActorRef<Object> actor = provisional.get();
            assertNotNull(actor);
            assertTrue(actor.done().isDone(),
                    "startup failed without a physical DONE acknowledgement");
            assertFalse(actor.isAlive(), "startup left a provisional actor alive");
        }
    }

    @Test void registeredProviderCannotRecyclePreviouslyAllocatedActor() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> prior = runtime.spawn(
                    () -> (message, context) -> { });
            PipelinePlan.ActorProvider recycler = () -> prior;
            PipelinePlan plan = new PipelinePlanner()
                    .add("source", "R", "M", recycler)
                    .add("sink", "R", "M", recycler).build();
            ActorRuntimePipelineEngine engine = new ActorRuntimePipelineEngine(
                    runtime, Runnable::run, Map.of("R", recycler));
            SecurityException rejected =
                    assertThrows(SecurityException.class, () -> engine.open(plan));
            assertTrue(rejected.getMessage().contains("fresh actor"));
            assertTrue(prior.isAlive(),
                    "invalid provider caused the engine to STOP a preexisting unrelated actor");
            prior.stop();
            assertTrue(prior.done().isDone());
        }
    }

    @Test void secondStageCannotRecycleNewFirstStageActor() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<Object>>
                    first = new java.util.concurrent.atomic.AtomicReference<>();
            PipelinePlan.ActorProvider duplicated = () -> {
                ActorRuntime.ActorRef<Object> known = first.get();
                if (known != null) return known;
                ActorRuntime.ActorRef<Object> fresh = runtime.spawn(
                        () -> (message, context) -> { });
                first.set(fresh);
                return fresh;
            };
            PipelinePlan plan = new PipelinePlanner()
                    .add("source", "R", "M", duplicated)
                    .add("sink", "R", "M", duplicated).build();
            ActorRuntimePipelineEngine engine = new ActorRuntimePipelineEngine(
                    runtime, Runnable::run, Map.of("R", duplicated));
            assertThrows(SecurityException.class, () -> engine.open(plan));
            assertNotNull(first.get());
            assertTrue(first.get().done().isDone(),
                    "duplicate second actor failed to trigger verified rollback");
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
