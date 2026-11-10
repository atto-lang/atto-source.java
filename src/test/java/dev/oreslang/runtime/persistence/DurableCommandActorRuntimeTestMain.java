package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ActorRuntime.PrivateMemoryBlock;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

/**
 * Real Java HOST ActorRuntime + bounded async command sidecar + SQLite.
 * No claim of source-compiled AttoLang guest or automatic supervisor replay.
 */
public final class DurableCommandActorRuntimeTestMain {
    private DurableCommandActorRuntimeTestMain() {}
    private static byte[] b(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("atto-host-actor-command-");
        Path db=root.resolve("state.sqlite");
        Identity id=new Identity("tenant-a","bank-42","main");
        try {
            try(SqlitePersistenceStore store=new SqlitePersistenceStore(db);
                DurableCommandSidecar io=new DurableCommandSidecar("tenant-a",store,16,1048576);
                ActorRuntime runtime=new ActorRuntime()){
                store.grantEpoch(id.tenant(),id.actor(),1);
                CompletableFuture<Long> completed=new CompletableFuture<>();
                ActorRuntime.ActorRef<Long> actor=runtime.spawnPrivateTrusted(context->{
                    PrivateMemoryBlock memory=context.privateMemory().orElseThrow()
                        .allocatePrivateBytes(Long.BYTES);
                    memory.writeLong(0,0L);
                    var activation=ActorPersistenceBridge.bindTrustedCurrentActor(context,id,1);
                    return (amount,turn)->{
                        ActorPersistenceBridge.onDurableCommandCompletionInCurrentActor(
                            activation,io,"op-42",0,b("Deposit(100)"),
                            List.of(new SqlitePersistenceStore.CommandEvent(1,b("Deposited(60)")),
                                    new SqlitePersistenceStore.CommandEvent(1,b("Deposited(40)"))),
                            b("Ok(100)"),(result,failure)->{
                                try {
                                    if(failure!=null)throw new AssertionError(failure);
                                    check(ActorRuntime.currentActorId().orElseThrow()
                                        .equals(turn.self().id()),"callback escaped owning actor");
                                    check(memory.readLong(0)==0,"state published before commit");
                                    check(result.lastVersion()==2&&!result.duplicate(),
                                        "wrong durable command result");
                                    memory.writeLong(0,100L);
                                    completed.complete(memory.readLong(0));
                                }catch(Throwable problem){completed.completeExceptionally(problem);}
                                finally{turn.runtime().endCurrentActor();}
                            });
                    };
                });
                actor.ready().get(15,TimeUnit.SECONDS);
                actor.send(100L);
                check(completed.get(15,TimeUnit.SECONDS)==100L,
                    "host actor failed to publish after durable event append");
                actor.done().get(15,TimeUnit.SECONDS);
            }
            // Completely fresh runtime and actor incarnation. No re-decide:
            // fetch original reply and verify the event journal did not grow.
            try(SqlitePersistenceStore store=new SqlitePersistenceStore(db);
                DurableCommandSidecar io=new DurableCommandSidecar("tenant-a",store,16,1048576);
                ActorRuntime runtime=new ActorRuntime()){
                store.grantEpoch(id.tenant(),id.actor(),2);
                check(store.loadFenced(id,2).events().size()==2,
                    "fenced restart lost committed journal");
                CompletableFuture<byte[]> answered=new CompletableFuture<>();
                ActorRuntime.ActorRef<String> actor=runtime.spawnPrivateTrusted(context->{
                    var activation=ActorPersistenceBridge.bindTrustedCurrentActor(context,id,2);
                    return (op,turn)->{
                        ActorPersistenceBridge.onPriorCommandResolutionInCurrentActor(
                            activation,io,op,b("Deposit(100)"),(found,failure)->{
                                try {
                                    if(failure!=null)throw new AssertionError(failure);
                                    check(ActorRuntime.currentActorId().orElseThrow()
                                        .equals(turn.self().id()),"lookup callback escaped actor");
                                    var resolved=found.orElseThrow();
                                    check(resolved.duplicate()&&resolved.lastVersion()==2,
                                        "did not retrieve original durable command");
                                    answered.complete(resolved.reply());
                                }catch(Throwable problem){answered.completeExceptionally(problem);}
                                finally{turn.runtime().endCurrentActor();}
                            });
                    };
                });
                actor.ready().get(15,TimeUnit.SECONDS);
                actor.send("op-42");
                check(Arrays.equals(answered.get(15,TimeUnit.SECONDS),b("Ok(100)")),
                    "retried command did not receive original durable reply");
                actor.done().get(15,TimeUnit.SECONDS);
                check(store.loadFenced(id,2).version()==2,
                    "retried command appended a duplicate event");
            }
            System.out.println("PASS host actor turn: async atomic events, fenced original reply");
        }finally {
            try(var files=Files.walk(root)){
                files.sorted(Comparator.reverseOrder()).forEach(path->{
                    try{Files.deleteIfExists(path);}
                    catch(Exception failure){throw new IllegalStateException(failure);}
                });
            }
        }
    }
}
