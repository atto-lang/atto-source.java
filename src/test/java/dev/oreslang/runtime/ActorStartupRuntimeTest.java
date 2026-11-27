package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorStartupRuntimeTest {
    private static final IsolatePolicy POLICY = new IsolatePolicy(
        Set.of(IsolatePolicy.Capability.SHARED_MEMORY, IsolatePolicy.Capability.ACTOR_SPAWN),
        16L*1024*1024, 8, Duration.ofSeconds(10));
    private static final IsolatePolicy CEILING = new IsolatePolicy(
        POLICY.capabilities(), 32L*1024*1024, POLICY.maxMailboxMessages(), POLICY.maxWallTime());
    private static final String SOURCE = "define actor Worker as state { let int count = 0; } end";
    private static ActorStartupPayload.Schema schema() {
        return new ActorStartupPayload.Schema(List.of(new ActorStartupPayload.Field("seed", ActorStartupPayload.Kind.INT)));
    }
    private static ActorStartupPayload payload(ActorStartupPayload.Schema schema) {
        return schema.bind(List.of(new ActorStartupPayload.Argument("seed",42L)),8);
    }
    private static Ast.ClassDecl actor(Ast.Program program) {
        return (Ast.ClassDecl)program.modules().getFirst().declarations().getFirst();
    }
    @FunctionalInterface interface Init {
        void run(ActorRuntime.ActorContext<Object> context) throws Exception;
    }
    private static ActorRuntime.ActorCodeExecutor executor(SharedCodeImageStore.CodeImage code,
            Init init, Init receive) {
        return new ActorRuntime.ActorCodeExecutor() {
            public SharedCodeImageStore.CodeImage codeImage() { return code; }
            public ActorRuntime.ActorOwnedGuestState initializeActor(String type, ActorRuntime.ActorContext<Object> ctx) throws Exception {
                init.run(ctx); var id=ctx.self().id(); return ()->id;
            }
            public void receiveActor(String type, ActorRuntime.ActorOwnedGuestState state,
                    ActorRuntime.ActorInboxMail<Object> mail, ActorRuntime.ActorContext<Object> ctx) throws Exception {
                receive.run(ctx);
            }
        };
    }
    @Test void exactImageAndOneShotInitThenReceiveDenial() throws Exception {
        try(var store=new SharedCodeImageStore(); var runtime=new ActorRuntime(CEILING)) {
            var program=Parser.parse(SOURCE);
            var code=store.publish("startup-exact",program);
            var s=schema(); var p=payload(s);
            var seen=new AtomicReference<Object>(); var received=new CountDownLatch(1);
            var failure=new AtomicReference<Throwable>();
            var e=executor(code,ctx->{
                seen.set(ctx.runtime().takeCurrentActorStartup().get(0));
                assertThrows(SecurityException.class,ctx.runtime()::takeCurrentActorStartup);
            },ctx->{
                try { assertThrows(SecurityException.class,ctx.runtime()::takeCurrentActorStartup); }
                catch(Throwable t) { failure.set(t); }
                finally { received.countDown(); }
            });
            assertThrows(SecurityException.class,runtime::takeCurrentActorStartup);
            assertThrows(SecurityException.class,()->runtime.spawnCodeActorWithStartup(
                ActorRuntime.ActorKind.SHARED,POLICY,e,"Worker",actor(Parser.parse(SOURCE)),s,p));
            assertThrows(SecurityException.class,()->runtime.spawnCodeActorWithStartup(
                ActorRuntime.ActorKind.SHARED,POLICY,e,"Worker",actor(program),schema(),p));
            assertEquals(0,runtime.actorCount());
            var ref=runtime.spawnCodeActorWithStartup(ActorRuntime.ActorKind.SHARED,POLICY,e,"Worker",actor(program),s,p);
            ref.ready().get(5,TimeUnit.SECONDS); assertEquals(42L,seen.get());
            ref.send(1L); assertTrue(received.await(5,TimeUnit.SECONDS)); assertNull(failure.get());
            ref.stop(); assertTrue(ref.awaitTermination(5,TimeUnit.SECONDS));
            assertEquals(0,runtime.sharedMemoryBytes());
        }
    }
    @Test void cancelledInitCannotConsumeAndRefundsAfterExit() throws Exception {
        var entered=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        var checked=new CountDownLatch(1); var failure=new AtomicReference<Throwable>();
        try(var store=new SharedCodeImageStore(); var runtime=new ActorRuntime(CEILING)) {
            var program=Parser.parse(SOURCE); var code=store.publish("startup-cancel",program);
            var s=schema(); var e=executor(code,ctx->{
                entered.countDown();
                if(!proceed.await(5,TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                try { assertThrows(SecurityException.class,ctx.runtime()::takeCurrentActorStartup); }
                catch(Throwable t) { failure.set(t); } finally { checked.countDown(); }
            },ctx->{ throw new AssertionError("unexpected DATA"); });
            var ref=runtime.spawnCodeActorWithStartup(ActorRuntime.ActorKind.SHARED,POLICY,e,"Worker",actor(program),s,payload(s));
            var ready=ref.ready();
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS)); assertTrue(ref.cancel());
                assertEquals(POLICY.maxHeapBytes(),runtime.sharedMemoryBytes());
            } finally { proceed.countDown(); }
            assertTrue(checked.await(5,TimeUnit.SECONDS)); assertNull(failure.get());
            assertThrows(ExecutionException.class,()->ready.get(5,TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5,TimeUnit.SECONDS)); assertEquals(0,runtime.sharedMemoryBytes());
        } finally { proceed.countDown(); }
    }
    @Test void unusedPayloadCannotEscapeInitialization() throws Exception {
        try(var store=new SharedCodeImageStore(); var runtime=new ActorRuntime(CEILING)) {
            var program=Parser.parse(SOURCE); var code=store.publish("startup-unused",program);
            var s=schema(); var received=new CountDownLatch(1); var failure=new AtomicReference<Throwable>();
            var e=executor(code,ctx->{},ctx->{
                try { assertThrows(SecurityException.class,ctx.runtime()::takeCurrentActorStartup); }
                catch(Throwable t) { failure.set(t); } finally { received.countDown(); }
            });
            var ref=runtime.spawnCodeActorWithStartup(ActorRuntime.ActorKind.SHARED,POLICY,e,"Worker",actor(program),s,payload(s));
            ref.ready().get(5,TimeUnit.SECONDS); ref.send(1L);
            assertTrue(received.await(5,TimeUnit.SECONDS)); assertNull(failure.get());
            ref.stop(); assertTrue(ref.awaitTermination(5,TimeUnit.SECONDS)); assertEquals(0,runtime.sharedMemoryBytes());
        }
    }
}
