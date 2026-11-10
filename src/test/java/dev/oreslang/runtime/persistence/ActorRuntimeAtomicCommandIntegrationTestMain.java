package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ActorRuntime.PrivateMemoryBlock;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.Identity;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.Status;
import dev.oreslang.runtime.persistence.SqliteCommandTransactionStore.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Actual Java ActorRuntime/private-heap + SQLite command transaction.
 * This is HOST Java, NOT compiled AttoLang .ores guest or Native Image.
 */
public final class ActorRuntimeAtomicCommandIntegrationTestMain {
    private ActorRuntimeAtomicCommandIntegrationTestMain() {}
    private static void check(boolean good,String why) {
        if(!good)throw new AssertionError(why);
    }
    private static byte[] encode(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
    private static long decode(byte[] data) {
        check(data.length==Long.BYTES,"BAD_EVENT");
        return ByteBuffer.wrap(data).getLong();
    }
    private static long replay(SqlitePersistenceStore.Recovery recovery) {
        check(recovery.snapshotVersion()==0,"unexpected actor snapshot");
        long value=0,version=1;
        for (SqlitePersistenceStore.Event event : recovery.events()) {
            check(event.version()==version++,"journal gap");
            check(event.schemaVersion()==1,"schema mismatch");
            value=Math.addExact(value,decode(event.payload()));
        }
        check(recovery.version()==recovery.events().size(),"head mismatch");
        return value;
    }
    private static Receipt deposit(ActorRuntime runtime,AtomicCommandSidecar sidecar,
            Identity id,long epoch,long expectedVersion,long balance,
            String opId,long amount,AtomicReference<PrivateMemoryBlock> memory)
            throws Exception {
        CompletableFuture<Receipt> done=new CompletableFuture<>();
        ActorRuntime.ActorRef<Long> bank=runtime.spawnPrivateTrusted(context->{
            PrivateMemoryBlock local=context.privateMemory().orElseThrow()
                    .allocatePrivateBytes(Long.BYTES);
            local.writeLong(0,balance);
            memory.set(local);
            ActorPersistenceBridge.Activation activation=
                    ActorPersistenceBridge.bindTrustedCurrentActor(context,id,epoch);
            return (cents,turn)->{
                long before=local.readLong(0);
                check(cents>1,"invalid positive deposit");
                long first=cents/2,second=cents-first;
                long proposed=Math.addExact(before,cents);
                ActorPersistenceBridge.onCommandCompletionInCurrentActor(
                    activation,sidecar,opId,expectedVersion,1,
                    ("deposit:"+cents).getBytes(StandardCharsets.UTF_8),
                    List.of(encode(first),encode(second)),encode(proposed),
                    (receipt,error)->{
                        try {
                            check(ActorRuntime.currentActorId().orElseThrow().equals(turn.self().id()),
                                  "storage callback not in actor's execution lease");
                            if(error!=null)throw new AssertionError(error);
                            check(receipt.status()==Status.COMMITTED &&
                                  receipt.durable() &&
                                  receipt.firstVersion()==expectedVersion+1 &&
                                  receipt.version()==expectedVersion+2 &&
                                  receipt.eventCount()==2,"unproven command commit");
                            check(local.readLong(0)==before,
                                  "candidate state visible before durable command proof");
                            local.writeLong(0,proposed);
                            done.complete(receipt);
                        } catch(Throwable failure) {
                            done.completeExceptionally(failure);
                        } finally {turn.runtime().endCurrentActor();}
                    });
            };
        });
        bank.ready().get(15,TimeUnit.SECONDS);
        bank.send(amount);
        Receipt receipt=done.get(15,TimeUnit.SECONDS);
        bank.done().get(15,TimeUnit.SECONDS);
        check(memory.get().closed(),"private actor arena was not revoked");
        return receipt;
    }

    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory("ores-actor-command-");
        Path database=directory.resolve("actor.sqlite");
        Identity id=new Identity("tenant-command","bank-42","ledger");
        AtomicReference<PrivateMemoryBlock> first=new AtomicReference<>();
        AtomicReference<PrivateMemoryBlock> second=new AtomicReference<>();
        try {
            try(SqlitePersistenceStore legacy=new SqlitePersistenceStore(database);
                SqliteCommandTransactionStore storage=new SqliteCommandTransactionStore(database);
                AtomicCommandSidecar sidecar=new AtomicCommandSidecar(id.tenant(),storage,32,65536);
                ActorRuntime runtime=new ActorRuntime()) {
                legacy.grantEpoch(id.tenant(),id.actor(),1);
                Receipt committed=deposit(runtime,sidecar,id,1,0,0,
                         "logical-deposit-100",100,first);
                check(committed.version()==2,"initial command head");
                check(replay(storage.loadVerified(id,1))==100,"committed events failed replay");
            }
            try(SqlitePersistenceStore legacy=new SqlitePersistenceStore(database);
                SqliteCommandTransactionStore storage=new SqliteCommandTransactionStore(database);
                AtomicCommandSidecar sidecar=new AtomicCommandSidecar(id.tenant(),storage,32,65536);
                ActorRuntime runtime=new ActorRuntime()) {
                legacy.grantEpoch(id.tenant(),id.actor(),2);
                var loaded=storage.loadVerified(id,2);
                check(replay(loaded)==100 && loaded.version()==2,"recovery disagrees");
                Command retry=new Command(id,"logical-deposit-100",2,0,1,
                    "deposit:100".getBytes(StandardCharsets.UTF_8),
                    List.of(encode(50),encode(50)),encode(100));
                Receipt duplicate=sidecar.submit(retry).toCompletableFuture()
                    .get(15,TimeUnit.SECONDS);
                check(duplicate.durable() && duplicate.status()==Status.DUPLICATE &&
                      duplicate.version()==2 && decode(duplicate.resultBytes())==100,
                      "lost ACK retried as a second deposit");
                check(storage.loadVerified(id,2).events().size()==2,"duplicate wrote events");
                Receipt committed=deposit(runtime,sidecar,id,2,2,100,
                          "logical-deposit-50",50,second);
                check(committed.version()==4,"second logical command version");
                check(first.get()!=second.get(),"new actor allocation reused old heap");
                check(replay(storage.loadVerified(id,2))==150,
                      "actor replay after second command did not match 150");
                boolean denied=false;
                try {storage.verify(new Command(id,"logical-deposit-100",1,0,1,
                          "deposit:100".getBytes(StandardCharsets.UTF_8),
                          List.of(encode(50),encode(50)),encode(100)));}
                catch(SecurityException expected){denied=true;}
                check(denied,"stale incarnation retrieved durable receipt");
            }
            System.out.println("PASS real Java ActorRuntime atomic command journal -> SQLite -> epoch2 replay");
        } finally {
            try(var files=Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path->{
                    try{Files.deleteIfExists(path);}
                    catch(Exception error){throw new IllegalStateException(error);}
                });
            }
        }
    }
}
