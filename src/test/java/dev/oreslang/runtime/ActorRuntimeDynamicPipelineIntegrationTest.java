package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.pipeline.PipelineController;
import dev.oreslang.runtime.pipeline.PipelinePlan;
import dev.oreslang.runtime.pipeline.PipelinePlanner;
import dev.oreslang.runtime.pipeline.PipelineRunner;
import dev.oreslang.types.TaggedMessageContracts;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises real ActorRuntime compiler-code actor turns, MessagePack-v2
 * nominal mailbox binding, the actual forward/backward pipeline scheduler
 * and physical DONE, through the dynamic host adapter across two generations.
 *
 * Receive dispatch is a hand-written host test CodeExecutor, NOT compilation
 * of the proposed Oreslang PipelinePlanner syntax. Guest parser parity is
 * tracked separately.
 */
final class ActorRuntimeDynamicPipelineIntegrationTest {
    private record Owned(ActorRuntime.ActorId ownerActorId)
            implements ActorRuntime.ActorOwnedGuestState { }

    @Test
    void sourceTransformV1SinkThenHotReplaceAndRestartFreshActors() throws Exception {
        var program = Parser.parse("""
                define enum Signal as
                    Data: int = 1
                    Result: int = 2
                end
                define pattern PipeMessages as
                    Signal.Data
                    Signal.Result
                end
                """);
        TaggedMessageCodec codec = new TaggedMessageCodec(
                TaggedMessageContracts.verify(program).requireDomain("PipeMessages"));

        try (ActorRuntime runtime = new ActorRuntime();
             SharedCodeImageStore codeStore = new SharedCodeImageStore()) {
            SharedCodeImageStore.CodeImage code =
                    codeStore.publish("pipeline-hot-swap-code-v1", program);
            ConcurrentLinkedQueue<Long> observed = new ConcurrentLinkedQueue<>();
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override
                public SharedCodeImageStore.CodeImage codeImage() {
                    return code;
                }

                @Override
                public Optional<MessagePackCollectionCodec.MailboxWireCodec> mailboxSchema(
                        String actorTypeName) {
                    return Optional.of(codec);
                }

                @Override
                public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String actorTypeName, ActorRuntime.ActorContext<Object> ctx) {
                    return new Owned(ctx.self().id());
                }

                @Override
                public void receiveActor(
                        String actorTypeName, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> ctx) {
                    if (!state.ownerActorId().equals(ctx.self().id())) {
                        throw new SecurityException("cross-actor state reuse");
                    }
                    TaggedMessageCodec.TaggedValue message =
                            (TaggedMessageCodec.TaggedValue) mail.value();
                    long n = ((Number) message.payload()).longValue();
                    ActorRuntime scheduler = ctx.runtime();
                    switch (actorTypeName) {
                        case "Source" -> {
                            if (mail.sender().isEmpty()) {
                                scheduler.routeCurrentPipeline(
                                        new TaggedMessageCodec.TaggedValue(
                                                "Signal", "Data", 1, n + 1), true);
                            } else {
                                observed.add(n);
                                scheduler.endCurrentActor();
                            }
                        }
                        case "TransformPostPublishFailure" -> {
                            // Simulate a stage throwing after the downstream
                            // mailbox has accepted DATA. Publication may have
                            // occurred, so this flow must never expose success.
                            scheduler.routeCurrentPipeline(
                                    new TaggedMessageCodec.TaggedValue(
                                            "Signal", "Data", 1, n + 2), true);
                            throw new IllegalStateException(
                                    "injected failure after directional DATA publication");
                        }
                        case "TransformV1", "TransformV2" -> {
                            long delta = actorTypeName.equals("TransformV2") ? 10 : 2;
                            if (message.variant().equals("Data")) {
                                scheduler.routeCurrentPipeline(
                                        new TaggedMessageCodec.TaggedValue(
                                                "Signal", "Data", 1, n + delta), true);
                            } else {
                                scheduler.routeCurrentPipeline(
                                        new TaggedMessageCodec.TaggedValue(
                                                "Signal", "Result", 2, n + delta + 1), false);
                                scheduler.endCurrentActor();
                            }
                        }
                        case "Sink" -> {
                            scheduler.routeCurrentPipeline(
                                    new TaggedMessageCodec.TaggedValue(
                                            "Signal", "Result", 2, n + 4), false);
                            scheduler.endCurrentActor();
                        }
                        default -> throw new IllegalStateException(
                                "unexpected actor type " + actorTypeName);
                    }
                }
            };

            PipelinePlan.ActorProvider src = () ->
                    runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executor, "Source");
            PipelinePlan.ActorProvider v1 = () ->
                    runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executor, "TransformV1");
            PipelinePlan.ActorProvider v2 = () ->
                    runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executor, "TransformV2");
            PipelinePlan.ActorProvider sink = () ->
                    runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executor, "Sink");
            String domain = codec.closedDomainIdentity();

            PipelinePlan original = new PipelinePlanner()
                    .add("source", "Source", domain, src)
                    .add("transform", "TransformV1", domain, v1)
                    .add("sink", "Sink", domain, sink)
                    .build();
            // Runnable::run is suitable for this synchronous HOST test only;
            // production must inject its dedicated trusted control executor.
            ActorRuntimePipelineEngine engine = new ActorRuntimePipelineEngine(
                    runtime, Runnable::run, Map.of("Source", src, "TransformV1", v1,
                            "TransformV2", v2, "Sink", sink));
            PipelineController controller = new PipelineController(original, engine);
            PipelineRunner first = controller.start();
            // A compiler-declared case outside this bound domain must fail
            // before DATA and must not consume the one available root credit.
            assertThrows(Exception.class, () -> first.sendExpected(
                    new TaggedMessageCodec.TaggedValue("Signal", "Data", 1, 5L),
                    "Signal.NotInBoundMailbox").toCompletableFuture()
                    .get(10, TimeUnit.SECONDS));
            assertEquals(PipelineController.State.RUNNING, controller.state());
            assertTrue(observed.isEmpty(), "rejected result published pipeline DATA");

            Object firstReply = first.sendExpected(new TaggedMessageCodec.TaggedValue(
                    "Signal", "Data", 1, 5L), "Signal.Result")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(new TaggedMessageCodec.TaggedValue(
                    "Signal", "Result", 2, 15L), firstReply,
                    "runner must return the actual compiler-checked reverse message");
            assertEquals(15L, observed.remove(), "V1 did not return expected typed result");
            assertEquals(0, observed.size());
            first.getDone().toCompletableFuture().get(10, TimeUnit.SECONDS);

            controller.pause().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(PipelineController.State.PAUSED, controller.state());

            controller.replace("transform", new PipelinePlan.Stage(
                    "transform", "TransformV2", domain, v2));
            PipelineRunner second = controller.resume();
            assertEquals(first.generation() + 1, second.generation());
            assertThrows(Exception.class, () -> first.send(
                    new TaggedMessageCodec.TaggedValue(
                            "Signal", "Data", 1, 5L))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            Object secondReply = second.send(new TaggedMessageCodec.TaggedValue(
                    "Signal", "Data", 1, 5L))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(new TaggedMessageCodec.TaggedValue(
                    "Signal", "Result", 2, 31L), secondReply,
                    "replaced generation must return the new backward typed message");
            assertEquals(new TaggedMessageCodec.TaggedValue(
                    "Signal", "Result", 2, 15L), firstReply,
                    "hot replacement must not mutate a previous generation's result");
            assertEquals(31L, observed.remove(), "V2 hot replacement not in effect");
            second.getDone().toCompletableFuture().get(10, TimeUnit.SECONDS);
            controller.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(PipelineController.State.CLOSED, controller.state());

            // Signal.Data belongs to the *correct bound codec*, so the
            // pre-admission check passes. But the actual authenticated
            // backward mail is Signal.Result: the public host API must
            // reject this mismatch and atomically mark the generation FAILED.
            PipelineController mismatchedReplyController = new PipelineController(
                    original, engine);
            PipelineRunner mismatchedReplyRunner = mismatchedReplyController.start();
            assertThrows(Exception.class, () -> mismatchedReplyRunner.sendExpected(
                    new TaggedMessageCodec.TaggedValue(
                            "Signal", "Data", 1, 5L), "Signal.Data")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS),
                    "host API returned a typed reply of the wrong nominal case");
            assertEquals(PipelineController.State.FAILED,
                    mismatchedReplyController.state(),
                    "wrong typed reply left an invalid retired generation RUNNING");
            mismatchedReplyController.close().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertEquals(PipelineController.State.CLOSED,
                    mismatchedReplyController.state());
            assertEquals(15L, observed.remove(), "wrong-type route did not execute");
            assertTrue(observed.isEmpty());

            // A fatal actor failure after an already committed directional
            // publication must poison the route, even if Sink raced ahead and
            // produced a reply. Neither a successful whole-route DONE nor a
            // fabricated typed result is permissible.
            PipelinePlan.ActorProvider fatalStage = () ->
                    runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                            executor, "TransformPostPublishFailure");
            PipelinePlan failsAfterPublication = new PipelinePlanner()
                    .add("source", "Source", domain, src)
                    .add("transform", "TransformPostPublishFailure",
                            domain, fatalStage)
                    .add("sink", "Sink", domain, sink)
                    .build();
            ActorRuntimePipelineEngine failingEngine = new ActorRuntimePipelineEngine(
                    runtime, Runnable::run, Map.of("Source", src,
                            "TransformPostPublishFailure", fatalStage, "Sink", sink));
            PipelineController failedController = new PipelineController(
                    failsAfterPublication, failingEngine);
            PipelineRunner failureRunner = failedController.start();
            assertThrows(Exception.class, () -> failureRunner.sendExpected(
                    new TaggedMessageCodec.TaggedValue(
                            "Signal", "Data", 1, 5L), "Signal.Result")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS),
                    "failed post-publication actor cannot settle a typed result");
            assertEquals(PipelineController.State.FAILED, failedController.state());
            failedController.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(PipelineController.State.CLOSED, failedController.state());
        }
    }
}
