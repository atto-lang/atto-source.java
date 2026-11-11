package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ActorRuntime.PrivateMemoryBlock;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import dev.oreslang.runtime.persistence.SqliteCommandPersistence.*;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.Identity;

/**
 * Real Java ActorRuntime + v2 SQLite host bridge; NOT compiler-lowered .ores.
 * Each asynchronous completion must run with the original actor execution lease.
 */
public final class ActorRuntimeCommandPersistenceIntegrationTestMain {
    private ActorRuntimeCommandPersistenceIntegrationTestMain() {}
    private static void check(boolean yes, String message) {
        if (!yes) throw new AssertionError(message);
    }
    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static byte[] encode(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
    private static long decode(byte[] payload) {
        if(payload == null || payload.length != Long.BYTES)
            throw new AssertionError("invalid durable i64 result");
        return ByteBuffer.wrap(payload).getLong();
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("ores-actor-command-v2-");
        Path path=dir.resolve("command.sqlite");
        Identity trusted=new Identity("tenant-a", "account-42", "balance");
        SqliteCommandPersistence.Identity key=new SqliteCommandPersistence.Identity(
            trusted.tenant(),trusted.actor(),trusted.state());
        AtomicReference<PrivateMemoryBlock> oldBlock=new AtomicReference<>();
        AtomicReference<ActorPersistenceBridge.Activation> oldCapability=new AtomicReference<>();
        try {
            try (SqliteCommandPersistence db=new SqliteCommandPersistence(path,"tenant-a");
                 ActorRuntime runtime=new ActorRuntime()) {
                db.grantEpoch(trusted.actor(),1);
                CompletableFuture<Long> committed=new CompletableFuture<>();
                ActorRuntime.ActorRef<Long> actor=runtime.spawnPrivateTrusted(context -> {
                    PrivateMemoryBlock block=context.privateMemory().orElseThrow()
                        .allocatePrivateBytes(Long.BYTES);
                    block.writeLong(0,0);oldBlock.set(block);
                    ActorPersistenceBridge.Activation activation=
                        ActorPersistenceBridge.bindTrustedCurrentActor(context,trusted,1);
                    oldCapability.set(activation);
                    return (amount,turn) -> {
                        long candidate=Math.addExact(block.readLong(0),amount);
                        ActorPersistenceBridge.onCommandCompletionInCurrentActor(
                            activation,db,"deposit-001",0,1,bytes("deposit:100"),
                            List.of(bytes("+100")),encode(candidate),(receipt,error)->{
                                try {
                                    if(error!=null)throw new AssertionError(error);
                                    check(ActorRuntime.currentActorId().orElseThrow()
                                        .equals(turn.self().id()),"storage callback escaped actor lane");
                                    check(!receipt.duplicate() && receipt.firstVersion()==1 &&
                                        receipt.lastVersion()==1,"command did not durably commit");
                                    check(block.readLong(0)==0L,"published before durability");
                                    block.writeLong(0,candidate);
                                    committed.complete(block.readLong(0));
                                }catch(Throwable problem){committed.completeExceptionally(problem);}
                                finally{turn.runtime().endCurrentActor();}
                            });
                    };
                });
                actor.ready().get(15,TimeUnit.SECONDS);
                actor.send(100L);
                check(committed.get(15,TimeUnit.SECONDS)==100L,"first commit not published");
                actor.done().get(15,TimeUnit.SECONDS);
                check(oldBlock.get().closed(),"old actor arena leaked");
                // A stale/host-held capability cannot issue an authorized
                // persistence read outside the owning actor's current turn.
                boolean denied=false;
                try {
                    ActorPersistenceBridge.onCommandProofInCurrentActor(
                        oldCapability.get(),db,"deposit-001",bytes("deposit:100"),
                        (proof,error)->{ throw new AssertionError("forged callback"); });
                }catch(IllegalStateException | SecurityException expected){denied=true;}
                check(denied,"old activation token escaped actor execution");
            }
            // New epoch + new ActorRuntime + new private arena: replay then retry the
            // SAME logical operation ID with a different expected stream version.
            try (SqliteCommandPersistence db=new SqliteCommandPersistence(path,"tenant-a");
                 ActorRuntime runtime=new ActorRuntime()) {
                db.grantEpoch(trusted.actor(),2);
                CompletableFuture<Long> deduplicated=new CompletableFuture<>();
                ActorRuntime.ActorRef<Long> actor=runtime.spawnPrivateTrusted(context -> {
                    PrivateMemoryBlock block=context.privateMemory().orElseThrow()
                        .allocatePrivateBytes(Long.BYTES);
                    block.writeLong(0,0);
                    ActorPersistenceBridge.Activation activation=
                        ActorPersistenceBridge.bindTrustedCurrentActor(context,trusted,2);
                    return (request,turn) -> ActorPersistenceBridge.onCommandRecoveryInCurrentActor(
                        activation,db,(recovery,recoveryError)->{
                            if(recoveryError!=null){deduplicated.completeExceptionally(recoveryError);
                                turn.runtime().endCurrentActor();return;}
                            check(recovery.version()==1 && recovery.events().size()==1,
                                "durable recovery lost committed event");
                            long restored=0;
                            for(SqliteCommandPersistence.Event event:recovery.events()){
                                check(event.schemaVersion()==1 &&
                                    "deposit-001".equals(event.operationId()),
                                    "unexpected recovery event identity/schema");
                                restored=Math.addExact(restored,Long.parseLong(
                                    new String(event.payload(),
                                        java.nio.charset.StandardCharsets.UTF_8)));
                            }
                            check(restored==100L,"replayed reducer balance mismatch");
                            block.writeLong(0,restored);
                            ActorPersistenceBridge.onCommandProofInCurrentActor(
                                activation,db,"deposit-001",bytes("deposit:100"),
                                (proof,proofError)->{
                                    if(proofError!=null){deduplicated.completeExceptionally(proofError);
                                        turn.runtime().endCurrentActor();return;}
                                    check(proof!=null && proof.lastVersion()==1 &&
                                        decode(proof.resultBytes())==block.readLong(0),
                                        "durable proof result does not match recovered state");
                                    ActorPersistenceBridge.onCommandCompletionInCurrentActor(
                                        activation,db,"deposit-001",1,1,
                                        bytes("deposit:100"),List.of(bytes("+100")),encode(200L),
                                        (receipt,error)->{
                                            try{
                                                if(error!=null)throw new AssertionError(error);
                                                check(receipt.duplicate(),"same command re-applied");
                                                check(decode(receipt.resultBytes())==100L,
                                                    "duplicate receipt returned tentative result");
                                                check(block.readLong(0)==100L,
                                                    "duplicate changed recovered state");
                                                check(block!=oldBlock.get(),"old actor heap reused");
                                                deduplicated.complete(block.readLong(0));
                                            }catch(Throwable issue){deduplicated.completeExceptionally(issue);}
                                            finally{turn.runtime().endCurrentActor();}
                                        });
                                });
                        });
                });
                actor.ready().get(15,TimeUnit.SECONDS);
                actor.send(100L);
                check(deduplicated.get(15,TimeUnit.SECONDS)==100L,"retry applied twice");
                actor.done().get(15,TimeUnit.SECONDS);
                check(db.loadFenced(key,2).events().size()==1,
                    "retry created duplicate event row");
            }
            System.out.println("PASS Java ActorRuntime v2 async command commit/replay/proof/epoch retry");
        }finally{
            try(var paths=Files.walk(dir)){
                for(Path item:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(item);
            }
        }
    }
}
