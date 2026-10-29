package dev.oreslang.runtime.persistence;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;

/** Zero-dependency executable host-contract tests; run via javac/java or CI. */
public final class BatchPersistenceSidecarTestMain {
    private static int tested;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void test(String name, Runnable test) { test.run(); tested++; System.out.println("PASS " + name); }
    static Result get(CompletionStage<Result> f) { return f.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join(); }
    static Write write(String actor, String op, long epoch, long version) {
        return new Write(new Identity("tenant-1",actor,"main"),op,epoch,version,1,Kind.SNAPSHOT,new byte[] {42});
    }
    static BatchPersistenceSidecar newSidecar(MemoryStore store, int batchSize, long waitMs) {
        return new BatchPersistenceSidecar("tenant-1",store,4096,1048576,1024,batchSize,65536,waitMs);
    }
    public static void main(String[] args) {
        test("1000 actors batched and committed", () -> {
            MemoryStore store = new MemoryStore();
            try (var sidecar = newSidecar(store, 256, 5000)) {
                List<CompletionStage<Result>> futures = new ArrayList<>();
                for (int i=0; i<1000; i++) {
                    store.grantEpoch("tenant-1", "a"+i, 1);
                    futures.add(sidecar.submit(write("a"+i,"op"+i,1,0)));
                }
                sidecar.drain().toCompletableFuture().join();
                for (CompletionStage<Result> f : futures) check(get(f).status()==Status.COMMITTED && !get(f).durable(), "not committed or memory reported durable");
                check(store.version(new Identity("tenant-1","a999","main"))==1,"missing actor");
                check(store.commitCalls()<=8,"too many batches: "+store.commitCalls());
            }
        });
        test("old epoch rejected at commit, not just enqueue", () -> {
            MemoryStore store = new MemoryStore(); store.grantEpoch("tenant-1","a",1);
            try (var sidecar = newSidecar(store,100,5000)) {
                var old = sidecar.submit(write("a","old",1,0));
                store.grantEpoch("tenant-1","a",2);
                sidecar.drain().toCompletableFuture().join();
                check(get(old).status()==Status.STALE_EPOCH,"stale write accepted");
                var fresh = sidecar.submit(write("a","new",2,0));
                sidecar.drain().toCompletableFuture().join();
                check(get(fresh).status()==Status.COMMITTED,"new epoch rejected");
            }
        });
        test("idempotent replay and conflict", () -> {
            MemoryStore store = new MemoryStore(); store.grantEpoch("tenant-1","a",1);
            try (var sidecar = newSidecar(store,128,3)) {
                Write w=write("a","op",1,0);
                check(get(sidecar.submit(w)).status()==Status.COMMITTED,"first failed");
                check(get(sidecar.submit(w)).status()==Status.DUPLICATE,"dedup failed");
                Write mutation = new Write(w.id(),w.operationId(),1,0,1,Kind.SNAPSHOT,new byte[]{43});
                check(get(sidecar.submit(mutation)).status()==Status.OP_ID_CONFLICT,"reused op id accepted");
            }
        });
        test("per actor CAS conflict isolated from other actor", () -> {
            MemoryStore store = new MemoryStore(); store.grantEpoch("tenant-1","a",1);store.grantEpoch("tenant-1","b",1);
            try (var sidecar = newSidecar(store,10,5000)) {
                var x=sidecar.submit(write("a","one",1,0));
                var y=sidecar.submit(write("a","two",1,0));
                var z=sidecar.submit(write("b","three",1,0));
                sidecar.drain().toCompletableFuture().join();
                check(get(x).status()==Status.COMMITTED,"first failed");
                check(get(y).status()==Status.VERSION_CONFLICT,"CAS failed");
                check(get(z).status()==Status.COMMITTED,"other actor poisoned");
            }
        });
        test("defensive payload copy and tenant rejection", () -> {
            MemoryStore store = new MemoryStore();store.grantEpoch("tenant-1","a",1);
            try (var sidecar = newSidecar(store,10,5000)) {
                byte[] source=new byte[]{42};
                Write w=new Write(new Identity("tenant-1","a","main"),"op",1,0,1,Kind.SNAPSHOT,source);
                source[0]=9;w.payload()[0]=8;
                var result=sidecar.submit(w);
                sidecar.drain().toCompletableFuture().join();
                check(get(result).status()==Status.COMMITTED && !get(result).durable(),"memory provider claimed durability");
                check(store.snapshot(w.id())[0]==42,"pointer/alias escape");
                boolean rejected=false;
                try { sidecar.submit(new Write(new Identity("other","a","main"),"x",1,0,1,Kind.EVENT,new byte[0])); }
                catch (AdmissionException e) { rejected=e.code.equals("WRONG_TENANT"); }
                check(rejected,"tenant isolation missing");
            }
        });
        test("sidecar callbacks only through actor mailbox", () -> {
            MemoryStore store = new MemoryStore();store.grantEpoch("tenant-1","a",1);
            try (var sidecar = newSidecar(store,1,5000)) {
                ArrayBlockingQueue<Runnable> mailbox=new ArrayBlockingQueue<>(2);
                AtomicBoolean ran=new AtomicBoolean();
                sidecar.submitToActor(write("a","op",1,0),mailbox::add,(value,error)->{
                    check(value!=null && value.status()==Status.COMMITTED && !value.durable() && error==null,"bad outcome"); ran.set(true);
                });
                Runnable resume;
                try { resume=mailbox.poll(5,TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                check(resume!=null,"continuation not posted");
                check(!ran.get(),"guest callback executed on storage thread");
                resume.run();check(ran.get(),"guest callback never executed");
            }
        });
        test("empty write admission consumes quota", () -> {
            MemoryStore store = new MemoryStore();store.grantEpoch("tenant-1","a",1);
            try (var sidecar = new BatchPersistenceSidecar("tenant-1",store,2,256,4,10,100,5000)) {
                sidecar.submit(new Write(new Identity("tenant-1","a","main"),"op1",1,0,1,Kind.EVENT,new byte[0]));
                sidecar.submit(new Write(new Identity("tenant-1","a","main"),"op2",1,0,1,Kind.EVENT,new byte[0]));
                boolean rejected=false;
                try { sidecar.submit(new Write(new Identity("tenant-1","a","main"),"op3",1,0,1,Kind.EVENT,new byte[0])); }
                catch (AdmissionException e) { rejected=e.code.equals("BACKPRESSURE"); }
                check(rejected,"zero-byte flood bypassed quota");
            }
        });
        test("provider failure reports unknown not guaranteed abort", () -> {
            Store faulty=new Store() {
                public void grantEpoch(String t,String a,long e) {}
                public List<Result> commit(List<Write> b) { throw new RuntimeException("ambiguous commit"); }
            };
            try (var sidecar = new BatchPersistenceSidecar("tenant-1",faulty,2,4096,100,2,100,5)) {
                var pending=sidecar.submit(write("a","op",1,0));
                try { get(pending); throw new AssertionError("no failure"); }
                catch (CompletionException e) {
                    check(e.getCause() instanceof AdmissionException ae && ae.code.equals("OUTCOME_UNKNOWN"),"wrong failure");
                }
            }
        });
        System.out.println("PASS "+tested+"/"+tested+" host adapter tests");
    }
}
