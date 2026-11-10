package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.Identity;
import java.util.*;
import java.util.concurrent.*;

/** Bounded trusted host worker. Not callable from untrusted AttoLang guest code. */
public final class DurableCommandSidecar implements AutoCloseable {
    private final SqlitePersistenceStore store;
    private final String tenant;
    private final ExecutorService writer;
    private final int maxPending;
    private final long maxBytes;
    private final Set<CompletableFuture<?>> unresolved=ConcurrentHashMap.newKeySet();
    private int outstanding;
    private long reserved;
    private boolean closed;

    public DurableCommandSidecar(String tenant,SqlitePersistenceStore store,
                                 int maxPending,long maxBytes) {
        this.tenant=Objects.requireNonNull(tenant);
        this.store=Objects.requireNonNull(store);
        if(tenant.isBlank()||maxPending<1||maxBytes<131072)
            throw new IllegalArgumentException("invalid sidecar bounds");
        this.maxPending=maxPending;this.maxBytes=maxBytes;
        writer=Executors.newSingleThreadExecutor(task->{
            Thread t=new Thread(task,"ores-durable-command-worker");
            t.setDaemon(true);return t;
        });
    }
    private <T> CompletionStage<T> admit(long charge,Callable<T> job) {
        CompletableFuture<T> completion=new CompletableFuture<>();
        synchronized(this) {
            if(closed)throw new IllegalStateException("sidecar closed");
            if(outstanding>=maxPending||charge>maxBytes-reserved)
                throw new BatchPersistenceSidecar.AdmissionException("BACKPRESSURE");
            outstanding++;reserved+=charge;unresolved.add(completion);
            try {
                writer.execute(()->{
                    try{completion.complete(job.call());}
                    catch(Throwable problem){completion.completeExceptionally(problem);}
                    finally {
                        unresolved.remove(completion);
                        synchronized(DurableCommandSidecar.this){
                            outstanding--;reserved-=charge;
                        }
                    }
                });
            }catch(RejectedExecutionException problem){
                outstanding--;reserved-=charge;unresolved.remove(completion);
                throw new BatchPersistenceSidecar.AdmissionException("BACKPRESSURE");
            }
        }
        return completion;
    }
    /** Copy all bytes before queue admission; SQLite runs only on writer. */
    public CompletionStage<SqlitePersistenceStore.CommandReceipt> commit(
            Identity id,long epoch,String opId,long expected,
            byte[] request,List<SqlitePersistenceStore.CommandEvent> events,byte[] reply) {
        Objects.requireNonNull(id);Objects.requireNonNull(opId);
        Objects.requireNonNull(request);Objects.requireNonNull(events);
        Objects.requireNonNull(reply);
        if(!tenant.equals(id.tenant()))throw new SecurityException("WRONG_TENANT");
        byte[] ownedRequest=request.clone(),ownedReply=reply.clone();
        List<SqlitePersistenceStore.CommandEvent> ownedEvents=List.copyOf(events).stream()
                .map(e->new SqlitePersistenceStore.CommandEvent(
                        e.schemaVersion(),e.payload())).toList();
        long charge=128L+ownedRequest.length+ownedReply.length;
        for(var event:ownedEvents)charge+=8L+event.payload().length;
        return admit(charge,()->store.commitCommand(id,epoch,opId,expected,
                ownedRequest,ownedEvents,ownedReply));
    }
    /** Resolve the original reply BEFORE running user decide(). */
    public CompletionStage<Optional<SqlitePersistenceStore.CommandReceipt>> resolve(
            Identity id,long epoch,String opId,byte[] originalRequest) {
        Objects.requireNonNull(id);Objects.requireNonNull(opId);
        Objects.requireNonNull(originalRequest);
        if(!tenant.equals(id.tenant()))throw new SecurityException("WRONG_TENANT");
        byte[] copy=originalRequest.clone();
        return admit(128L+copy.length,()->
                store.resolveCommandFenced(id,epoch,opId,copy));
    }
    @Override public void close() {
        synchronized(this){if(closed)return;closed=true;writer.shutdown();}
        try{
            if(!writer.awaitTermination(15,TimeUnit.SECONDS)) {
                writer.shutdownNow();
                unresolved.forEach(f->f.completeExceptionally(
                    new IllegalStateException("OUTCOME_UNKNOWN")));
            }
        }catch(InterruptedException interrupted){
            writer.shutdownNow();
            unresolved.forEach(f->f.completeExceptionally(
                new IllegalStateException("OUTCOME_UNKNOWN")));
            Thread.currentThread().interrupt();
        }
    }
}
