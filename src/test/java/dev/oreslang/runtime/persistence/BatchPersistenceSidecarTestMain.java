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
            try (var sidecar = new BatchPersistenceSidecar("tenant-1",store,2,512,4,10,100,5000)) {
                sidecar.submit(new Write(new Identity("tenant-1","a","main"),"op1",1,0,1,Kind.EVENT,new byte[0]));
                sidecar.submit(new Write(new Identity("tenant-1","a","main"),"op2",1,0,1,Kind.EVENT,new byte[0]));
                boolean rejected=false;
                try { sidecar.submit(new Write(new Identity("tenant-1","a","main"),"op3",1,0,1,Kind.EVENT,new byte[0])); }
                catch (AdmissionException e) { rejected=e.code.equals("BACKPRESSURE"); }
                check(rejected,"zero-byte flood bypassed quota");
            }
        });
        test("UTF-8 identifiers consume bounded admission credits", () -> {
            MemoryStore store = new MemoryStore();
            String actor = "a".repeat(80), op = "o".repeat(80);
            store.grantEpoch("tenant-1", actor, 1);
            store.grantEpoch("tenant-1", "a", 1);
            try (var sidecar = new BatchPersistenceSidecar("tenant-1",store,3,170,16,3,16,5000)) {
                boolean rejected = false;
                try {
                    sidecar.submit(new Write(new Identity("tenant-1", actor, "main"),
                            op, 1, 0, 1, Kind.SNAPSHOT, new byte[0]));
                } catch (AdmissionException e) { rejected = e.code.equals("BACKPRESSURE"); }
                check(rejected, "long actor/op keys evaded the byte admission budget");
                var small = sidecar.submit(new Write(new Identity("tenant-1", "a", "main"),
                        "o", 1, 0, 1, Kind.SNAPSHOT, new byte[0]));
                sidecar.drain().toCompletableFuture().join();
                check(get(small).status() == Status.COMMITTED, "small identity unexpectedly rejected");
            }
        });
        test("identities reject non-NFC and malformed Unicode but permit valid supplementary scalars", () -> {
            for (String invalid : new String[] {
                    "e" + (char)0x0301,
                    new String(new char[]{(char)0xD800}),
                    new String(new char[]{(char)0xDC00})
            }) {
                boolean rejected = false;
                try { new Identity("tenant-1", invalid, "main"); }
                catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "noncanonical or malformed actor identity admitted");
            }
            String valid = new String(Character.toChars(0x1F642));
            new Identity("tenant-1", valid, "main");
        });
        test("forged durable store receipt cannot publish a commit", () -> {
            Store forged = new Store() {
                public void grantEpoch(String tenant, String actor, long epoch) {}
                public List<Result> commit(List<Write> writes) {
                    Write write = writes.get(0);
                    return List.of(new Result(write.operationId(), Status.COMMITTED,
                            write.expectedVersion() + 1L, true));
                }
                // No durable operation ledger. Default proof must fail closed.
            };
            try (var sidecar = new BatchPersistenceSidecar(
                    "tenant-1", forged, 4, 4096, 64, 4, 512, 5000)) {
                var result = sidecar.submit(write("a", "counterfeit", 1, 0));
                sidecar.drain().toCompletableFuture().join();
                boolean unknown = false;
                try { get(result); }
                catch (CompletionException e) {
                    unknown = e.getCause() instanceof AdmissionException ae
                            && ae.code.equals("OUTCOME_UNKNOWN");
                }
                check(unknown, "unverified durable provider receipt was accepted");
            }
        });
        test("incorrect non-durable committed version cannot bypass CAS contract", () -> {
            Store wrongVersion = new Store() {
                public void grantEpoch(String tenant, String actor, long epoch) {}
                public List<Result> commit(List<Write> writes) {
                    Write write = writes.get(0);
                    return List.of(new Result(write.operationId(), Status.COMMITTED,
                            write.expectedVersion() + 2L, false));
                }
            };
            try (var sidecar = new BatchPersistenceSidecar(
                    "tenant-1", wrongVersion, 4, 4096, 64, 4, 512, 5000)) {
                var result = sidecar.submit(write("a", "wrong-version", 1, 0));
                sidecar.drain().toCompletableFuture().join();
                boolean unknown = false;
                try { get(result); }
                catch (CompletionException e) {
                    unknown = e.getCause() instanceof AdmissionException ae
                            && ae.code.equals("OUTCOME_UNKNOWN");
                }
                check(unknown, "store published commit with mismatched version");
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
        test("stale epoch cannot use old dedupe receipt", () -> {
            MemoryStore store = new MemoryStore();store.grantEpoch("tenant-1","a",1);
            try (var sidecar = newSidecar(store,10,5)) {
                Write old=write("a","shared",1,0);
                check(get(sidecar.submit(old)).status()==Status.COMMITTED,"initial commit failed");
                store.grantEpoch("tenant-1","a",2);
                check(get(sidecar.submit(old)).status()==Status.STALE_EPOCH,"stale replay leaked receipt");
                check(get(sidecar.submit(write("a","shared",2,1))).status()==Status.OP_ID_CONFLICT,
                    "reused operation id under new epoch must be rejected");
            }
        });
        test("control characters rejected in all identity components", () -> {
            for (String invalid : new String[]{"bad\u0000id", "bad\nactor", "bad\tname"}) {
                boolean rejected=false;
                try { new Identity("tenant-1", invalid, "main"); }
                catch (IllegalArgumentException expected) { rejected=true; }
                check(rejected,"control-character identity accepted");
            }
        });
        test("in-flight storage occupies admission quota", () -> {
            CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
            MemoryStore memory = new MemoryStore(); memory.grantEpoch("tenant-1","a",1);
            Store delayed = new Store() {
                public void grantEpoch(String t,String a,long e) { memory.grantEpoch(t,a,e); }
                public java.util.List<Result> commit(java.util.List<Write> writes) throws Exception {
                    inside.countDown();
                    if (!release.await(2,TimeUnit.SECONDS)) throw new RuntimeException("test deadline");
                    return memory.commit(writes);
                }
            };
            try (var sidecar = new BatchPersistenceSidecar("tenant-1",delayed,1,4096,8,1,8,5000)) {
                var first=sidecar.submit(write("a","one",1,0));
                try { check(inside.await(2,TimeUnit.SECONDS),"writer did not start"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                boolean full=false;
                try { sidecar.submit(write("a","two",1,1)); }
                catch (AdmissionException e) { full=e.code.equals("BACKPRESSURE"); }
                check(full,"in-flight message released credits prematurely");
                release.countDown();
                check(get(first).status()==Status.COMMITTED,"in-flight write not committed");
            } finally { release.countDown(); }
        });
        System.out.println("PASS "+tested+"/"+tested+" host adapter tests");
    }
}
