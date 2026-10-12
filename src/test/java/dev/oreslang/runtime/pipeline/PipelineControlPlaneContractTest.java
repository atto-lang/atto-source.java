package dev.oreslang.runtime.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Standalone executable host contract suite; intentionally makes NO claim
 * of source-compiled Oreslang pipeline execution.
 */
public final class PipelineControlPlaneContractTest {
    private static int checks;
    private static void check(boolean yes, String why) {
        ++checks;
        if (!yes) throw new AssertionError(why);
    }
    private static void rejected(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) {
            ++checks;
            return;
        }
        throw new AssertionError("invalid lifecycle action was admitted");
    }
    private static void failed(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            throw new AssertionError("rejected operation completed successfully");
        } catch (ExecutionException expected) {
            ++checks;
        } catch (Exception problem) {
            throw new AssertionError(problem);
        }
    }
    private static <T> T settled(CompletionStage<T> stage) {
        try { return stage.toCompletableFuture().get(5, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static final class FakeEngine implements PipelineController.Engine {
        private final List<Session> opened = new ArrayList<>();
        private int actorsConstructed;
        private final class Session implements PipelineController.Session {
            private final List<Object> actors = new ArrayList<>();
            private final CompletableFuture<Object> admission = new CompletableFuture<>();
            private final CompletableFuture<Void> physicallyDone = new CompletableFuture<>();
            private boolean used;
            private boolean stopping;

            Session(PipelinePlan plan) {
                for (PipelinePlan.Stage stage : plan.stages()) {
                    Object actor = stage.provider().spawn();
                    if (actor == null) throw new IllegalArgumentException("null actor provider");
                    actors.add(actor);
                    ++actorsConstructed;
                }
            }
            @Override public CompletionStage<Object> send(Object value) {
                if (used || stopping) throw new IllegalStateException("one root DATA flow per generation");
                used = true;
                return admission;
            }
            @Override public CompletionStage<Void> stopAfterDrain() {
                stopping = true;
                return physicallyDone; // simulates physical actor shutdown, not just a Boolean
            }
            @Override public CompletionStage<Void> getDone() { return physicallyDone; }
            void reply(Object answer) { admission.complete(answer); }
            void failSend(Throwable why) { admission.completeExceptionally(why); }
            void finalizeActors() {
                check(stopping, "fake physical DONE without authoritative stop");
                physicallyDone.complete(null);
            }
        }
        @Override public PipelineController.Session open(PipelinePlan plan) {
            Session result = new Session(plan);
            opened.add(result);
            return result;
        }
        Session latest() { return opened.getLast(); }
        @Override public void scheduleControl(Runnable task) {
            // This deterministic fake runs on the host test thread.
            task.run();
        }
    }

    private static PipelinePlan.Stage stage(String name, String providerId, String domain,
                                           PipelinePlan.ActorProvider factory) {
        return new PipelinePlan.Stage(name, providerId, domain, factory);
    }

    private static void happyPathAndReplacement() {
        FakeEngine engine = new FakeEngine();
        PipelinePlanner planner = new PipelinePlanner()
                .add("source", "SourceV1", "Signal", Object::new)
                .add("transform", "TransformV1", "Signal", Object::new)
                .add("sink", "SinkV1", "Signal", Object::new);
        PipelinePlan original = planner.build();
        check(original.size() == 3 && original.revision() == 1, "planner topology");
        PipelineController controller = new PipelineController(original, engine);
        PipelineRunner first = controller.start();
        check(first.generation() == 1 && engine.actorsConstructed == 3, "first generation");
        CompletionStage<Object> result = first.send(5);
        result.toCompletableFuture().complete("forged delivery");
        failed(first.send(6));
        rejected(() -> controller.replace("transform",
                stage("transform", "TransformV2", "Signal", Object::new)));
        CompletionStage<Void> paused = controller.pause();
        check(controller.state() == PipelineController.State.PAUSING, "pause must fence ingress");
        // Observers must not carry settlement authority over physical pause.
        paused.toCompletableFuture().complete(null);
        check(controller.state() == PipelineController.State.PAUSING,
                "forged pause result reopened controller");
        check(!paused.toCompletableFuture().isDone(),
                "caller forged the production physical-DONE observer");
        failed(first.send(7));
        check(!paused.toCompletableFuture().isDone(), "inflight DATA must finish before pause");
        engine.latest().reply(19);
        check((Integer) settled(result) == 19, "typed roundtrip outcome");
        check(!paused.toCompletableFuture().isDone(), "reply is not a physical DONE receipt");
        engine.latest().finalizeActors();
        settled(paused);
        check(controller.state() == PipelineController.State.PAUSED, "physical quiescence barrier");
        controller.replace("transform", stage("transform", "TransformV2", "Signal", Object::new));
        check(controller.plan().revision() == 2, "swap must create new plan revision");
        controller.insert(2, stage("tap", "AuditTap", "Signal", Object::new));
        check(controller.plan().size() == 4, "hot insertion");
        controller.remove("tap");
        check(controller.plan().size() == 3, "hot removal");
        PipelineRunner second = controller.resume();
        check(second.generation() == 2 && engine.actorsConstructed == 6, "fresh actors on resume");
        failed(first.send(8));
        CompletionStage<Object> next = second.send(8);
        engine.latest().reply(24);
        check((Integer) settled(next) == 24, "new pipeline roundtrip");
        CompletionStage<Void> closed = controller.close();
        check(!closed.toCompletableFuture().isDone(), "close must await physical actors");
        closed.toCompletableFuture().complete(null);
        check(controller.state() != PipelineController.State.CLOSED,
                "forged close observation retired live actors");
        check(!closed.toCompletableFuture().isDone(),
                "caller could complete the production close barrier");
        engine.latest().finalizeActors();
        settled(closed);
        check(controller.state() == PipelineController.State.CLOSED, "closed state");
        failed(second.send(9));
    }

    private static void restartRequiresRealStopAndNewActors() {
        FakeEngine engine = new FakeEngine();
        PipelineController controller = new PipelineController(new PipelinePlanner()
                .add("a", "A", "M", Object::new)
                .add("b", "B", "M", Object::new).build(), engine);
        PipelineRunner first = controller.start();
        CompletionStage<Object> pending = first.send(1);
        CompletionStage<PipelineRunner> restart = controller.restart();
        check(!restart.toCompletableFuture().isDone(), "restart skipped in-flight barrier");
        engine.latest().reply(1);
        settled(pending);
        check(!restart.toCompletableFuture().isDone(), "restart skipped physical DONE");
        engine.latest().finalizeActors();
        PipelineRunner second = settled(restart);
        check(second.generation() == first.generation() + 1,
                "restart reused stale pipeline generation");
        check(engine.actorsConstructed == 4, "restart reused old actors");
        failed(first.send(2));
        CompletionStage<Void> stopped = controller.close();
        engine.latest().finalizeActors();
        settled(stopped);
    }

    private static void failureDoesNotRestartAmbiguousPublication() {
        FakeEngine engine = new FakeEngine();
        PipelineController controller = new PipelineController(new PipelinePlanner()
                .add("a", "A", "M", Object::new)
                .add("b", "B", "M", Object::new).build(), engine);
        PipelineRunner active = controller.start();
        CompletionStage<Object> result = active.send(42);
        engine.latest().failSend(new IllegalStateException("ambiguous transport result"));
        failed(result);
        check(controller.state() == PipelineController.State.FAILED, "failed ingress accepted");
        failed(controller.restart());
        rejected(controller::start);
        CompletionStage<Void> cleanup = controller.close();
        check(!cleanup.toCompletableFuture().isDone(), "failure cleanup skipped DONE");
        engine.latest().finalizeActors();
        settled(cleanup);
        check(controller.state() == PipelineController.State.CLOSED, "failed controller cleanup");
    }

    private static void closeWhileDeliveryFailsStillDrainsEveryActor() {
        FakeEngine engine = new FakeEngine();
        PipelineController controller = new PipelineController(new PipelinePlanner()
                .add("a", "A", "M", Object::new)
                .add("b", "B", "M", Object::new).build(), engine);
        PipelineRunner runner = controller.start();
        CompletionStage<Object> delivery = runner.send(99);
        CompletionStage<Void> closed = controller.close();
        check(!closed.toCompletableFuture().isDone(),
                "close bypassed in-flight DATA");
        engine.latest().failSend(new IllegalStateException("failed after root DATA admission"));
        failed(delivery);
        check(!closed.toCompletableFuture().isDone(),
                "close falsely succeeded before failed-flow physical retirement");
        check(engine.latest().stopping,
                "failed send did not propagate shutdown to owned actors");
        engine.latest().finalizeActors();
        settled(closed);
        check(controller.state() == PipelineController.State.CLOSED,
                "failed delivery leaked controller after confirmed physical cleanup");
        failed(runner.send(101));
    }

    private static void rejectStalePlanInstall() {
        FakeEngine engine = new FakeEngine();
        PipelinePlan initial = new PipelinePlanner()
                .add("a", "A", "M", Object::new)
                .add("b", "B", "M", Object::new).build();
        PipelineController controller = new PipelineController(initial, engine);
        controller.start();
        CompletionStage<Void> paused = controller.pause();
        engine.latest().finalizeActors();
        settled(paused);
        rejected(() -> controller.install(initial));
        rejected(() -> controller.install(initial.replace("a",
            stage("a", "A2", "different-domain", Object::new))));
        PipelinePlan next = initial.replace("a", stage("a", "A2", "M", Object::new));
        controller.install(next);
        check(controller.plan().revision() == 2,
                "valid revised plan not installed");
        CompletionStage<Void> closed = controller.close();
        settled(closed);
    }

    private static void planValidationBeforeActorCreation() {
        FakeEngine engine = new FakeEngine();
        rejected(() -> new PipelinePlanner()
                .add("A", "p", "A", Object::new)
                .add("B", "q", "B", Object::new));
        rejected(() -> new PipelinePlanner()
                .add("A", "p", "A", Object::new)
                .add("A", "q", "A", Object::new));
        rejected(() -> new PipelinePlanner().add("only", "p", "A", Object::new).build());
        PipelinePlanner full = new PipelinePlanner();
        for (int i = 0; i < 16; i++)
            full.add("a" + i, "p", "A", Object::new);
        rejected(() -> full.add("overflow", "p", "A", Object::new));
        PipelinePlan plan = full.build();
        rejected(() -> plan.insert(8, stage("extra", "p", "A", Object::new)));
        rejected(() -> plan.remove("not-present"));
        rejected(() -> plan.replace("a0", stage("other", "p", "A", Object::new)));
        check(engine.actorsConstructed == 0, "validation incorrectly spawned actors");
    }

    public static void main(String[] args) {
        happyPathAndReplacement();
        restartRequiresRealStopAndNewActors();
        failureDoesNotRestartAmbiguousPublication();
        closeWhileDeliveryFailsStillDrainsEveryActor();
        rejectStalePlanInstall();
        planValidationBeforeActorCreation();
        System.out.println("DYNAMIC_PIPELINE_CONTROL_CONTRACT_PASS assertions=" + checks);
    }
}
